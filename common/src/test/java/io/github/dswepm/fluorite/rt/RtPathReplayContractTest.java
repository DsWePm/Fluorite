package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M28 path reservoir layout and the seed contract for future random replay. The current tracer does
 * not replay the seed; D229 suspends the invalid reconnected shift until the full path density can be
 * evaluated. A future replay implementation must preserve this stream contract.
 *
 * <p>So the contract is pinned at the source level, in three parts: the seed travels with the record
 * (segment.slang), the stream is derived deterministically from it (world.rgen's loop idiom), and the
 * reservoir module that stores it exists with the layout the Java allocation assumes (restir_pt.slang).
 * Any of these moving means replay semantics moved; change them and this test in the same commit.
 */
final class RtPathReplayContractTest {

    @Test
    void theSeedTravelsWithThePackedRecord() throws IOException {
        String segment = source("shaders/world/segment.slang");
        // The packed record carries the path's RNG state -- replay reads it from the queue-side record.
        assertTrue(segment.contains("p.seed = seg.seed;"));
        // The banner is the only warning future editors get; its absence is how silent replay breakage
        // ships.
        assertTrue(segment.contains("REPLAY CONTRACT"));
    }

    @Test
    void theBounceLoopStreamIsDerivedDeterministicallyFromTheSeed() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // The loop's first draw derives from the segment seed XOR the sample index. A future replay
        // must re-derive the same stream from the stored seed: changing the
        // constant, the xor, or the pcg placement changes every stored seed's meaning.
        assertTrue(world.contains("uint seed = seg.seed ^ sampleIndex * 2246822519u;"));
        assertTrue(world.contains("seed = pcg(seed);"));
    }

    @Test
    void thePathReservoirModuleExistsWithThePinnedLayout() throws IOException {
        String worldCommon = source("shaders/world/world_common.slang");
        // The record lives beside PackedPathSegment so the layout probe can reflect it (restir_pt's
        // import chain collides with the probe's bindings). Its stride is pinned by
        // RtPathReservoirLayoutTest against the generated record, and RtComposite takes its
        // allocation stride from that same generated constant -- the D220 hand-copy lesson.
        assertTrue(worldCommon.contains("public struct PackedPathReservoir"));
        assertTrue(worldCommon.contains("PATH_RESERVOIR_BYTES = 48u"));
        assertTrue(worldCommon.contains("pathReservoirSlot"));
        // The float3 MUST lead: std430 gives float3 a 16-byte alignment, and a uint before it pads
        // the stride to 64 against the 48-byte allocation (the D220 GPU fault, verbatim).
        String struct = worldCommon.substring(worldCommon.indexOf("public struct PackedPathReservoir"));
        assertTrue(struct.indexOf("public float3 reconPos;") < struct.indexOf("public uint   pathSeed;"),
                "PackedPathReservoir field order regressed: reconPos must precede pathSeed or the "
                        + "stride grows to 64 and every slot access walks off the allocation");
        String restirPt = source("shaders/world/restir_pt.slang");
        // The estimator side stays in restir_pt: the packer and the merge constants.
        assertTrue(restirPt.contains("packPathReservoir("));
        assertTrue(restirPt.contains("PATH_RESERVOIR_M_CAP"));
    }

    @Test
    void theBounceLoopSnapshotsTheStreamBaseTheReservoirStores() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // The recorded pathSeed must be the stream's BASE, snapshotted before the first draw mutates
        // the working seed -- storing a mid-stream state would replay only the tail of the path.
        assertTrue(world.contains("uint pathSeedBase = seed;"));
        // The write is gated on the store's address, not a separate flag: no buffer means no record
        // and no branch left behind (the switch's whole shader-side off state).
        assertTrue(world.contains("worldPush.pathReservoirAddr != 0"));
        assertTrue(world.contains("packPathReservoir("));
    }

    @Test
    void theTemporalMergeReprojectsAndCarriesAMergeWeight() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // The temporal read goes through M24's d0 reprojection at the primary hit -- without it a
        // moving camera reads a history that was never this point's.
        assertTrue(world.contains("restirPreviousPixel(hitPos, renderSize, readPixel)"));
        // The merged pixel value and the survivor write are the estimator; losing either silently
        // turns the switch into "record and never reuse" or "reuse and never re-record". S3a: the
        // survivor is a three-way draw (own / temporal representative / spatial representative).
        assertTrue(world.contains("temporalValue"));
        assertTrue(world.contains("temporalWins"));
        assertTrue(world.contains("spatialWins"));
        assertTrue(world.contains("evalPathSuffixCandidate("));
        String restirPt = source("shaders/world/restir_pt.slang");
        // The RIS W normalization alone does not prove the cross-pixel estimator unbiased; the m cap
        // bounds how long the past outvotes the present.
        assertTrue(restirPt.contains("PATH_RESERVOIR_M_CAP"));
        // W is a lane of the record, which lives in world_common beside PackedPathSegment (D220).
        String worldCommon = source("shaders/world/world_common.slang");
        assertTrue(worldCommon.contains("bitcast float merge weight"));
    }

    @Test
    void theAppliedEstimateAccumulatesInRgbAndSelectsByScalar() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // Decision 6's RGB vector weights, pinned in its shipped form (A-②a): the APPLIED estimate
        // accumulates candidates as RGB (vector weights reach the picture -- chroma noise averages),
        // while the survivor SELECTION is driven by the scalar luminance target. Decoupling those two
        // is the whole of Enhanced §6.3; either half regressing to the other's domain is what this
        // pin catches.
        assertTrue(world.contains("temporalCount * temporalValue + spatialValue"));
        assertTrue(world.contains("rndf(seed) * totalTarget"));
        // The per-candidate selection weight stays a scalar multiplication (A-02b moved it to the
        // receiver domain; scalar-vs-vector is the invariant this test guards, not the domain).
        assertTrue(world.contains("candCount * luminance(candValue)"));
    }

    @Test
    void theInvalidReconnectedShiftStaysGone() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // D229 removed D227's reconnected estimator (an absolute geometry term where Enhanced Eq. 2
        // wants a density ratio); D231 retired the composite.path-replay switch that rejected the deep
        // candidates it used to serve. Neither may come back except as the rebuild's derived shift.
        assertFalse(world.contains("evalReconnectedSuffixCandidate("));
        assertFalse(world.contains("worldPush.pathReplayEnabled != 0u"),
                "the switch word is a bitfield now; testing it as a whole reads every future field");
        String restirPt = source("shaders/world/restir_pt.slang");
        assertFalse(restirPt.contains("public struct ReplayedPrefix"));
        assertFalse(restirPt.contains("float geometry = cosStored * cosHere"));
    }

    @Test
    void spatialPixelCoordinatesRoundTripAtNonSquareResolution() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        assertTrue(world.contains("int(readPixel / renderSize.x)"));
        assertFalse(world.contains("int(readPixel / renderSize.y)"));
        int width = 1920;
        int height = 1080;
        for (int y : new int[] {0, 500, 800, height - 1}) {
            for (int x : new int[] {0, 100, width - 1}) {
                int readPixel = y * width + x;
                assertEquals(x, readPixel % width);
                assertEquals(y, readPixel / width);
            }
        }
    }

    @Test
    void historyIsEvaluatedOnlyAtTheFirstReconnectionBounce() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        int snapshot = world.indexOf("bool firstReconThisBounce = reconQualifies;");
        int reset = world.indexOf("reconQualifies = false;", snapshot);
        int history = world.indexOf("if (recordPath && firstReconThisBounce) {", reset);
        int temporal = world.indexOf("evalPathSuffixCandidate(", history);
        int roulette = world.indexOf("// Russian roulette on deeper bounces", temporal);
        assertTrue(snapshot >= 0 && snapshot < reset && reset < history
                && history < temporal && temporal < roulette);
        assertFalse(world.substring(history, roulette).contains("if (recordPath && reconFound)"));
    }

    @Test
    void thePathReuseCarriesItsOwnNeighbourCountInItsSwitchWord() throws IOException {
        // D231 (G14): composite.path-replay is retired and its WorldPush lane is the path reuse's
        // switch word. Its first field is the path reuse's own spatial neighbour count, split from
        // M24's dial so the two stores can be tuned and measured apart.
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        assertFalse(config.contains("PATH_REPLAY ="), "composite.path-replay is retired");
        assertEquals(0, FluoriteConfig.Rt.Composite.PATH_REUSE_SPATIAL_NEIGHBOURS.defaultValue().intValue(),
                "the path reservoir switch alone gathers no spatial candidates");

        // The Java packer and the Slang reader spell the same field: pinned by value, not by prose.
        String restirPt = source("shaders/world/restir_pt.slang");
        assertTrue(restirPt.contains("PATH_REUSE_NEIGHBOURS_SHIFT = "
                + RtComposite.PATH_REUSE_NEIGHBOURS_SHIFT + "u;"));
        assertTrue(restirPt.contains("PATH_REUSE_NEIGHBOURS_MASK = "
                + RtComposite.PATH_REUSE_NEIGHBOURS_MASK + "u;"));
        assertTrue(restirPt.contains(
                "(worldPush.pathReplayEnabled >> PATH_REUSE_NEIGHBOURS_SHIFT) & PATH_REUSE_NEIGHBOURS_MASK"));
        assertTrue(RtComposite.PATH_REUSE_NEIGHBOURS_MASK >= 8,
                "the field must hold the dial's whole range");

        String world = source("shaders/world/world.rgen.slang");
        assertTrue(world.contains("i < pathReuseSpatialNeighbours()"),
                "the path reuse's spatial loop reads its own count");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertFalse(composite.contains("reservoirStore != null || pathReservoirStore != null"),
                "M24's restirSpatialNeighbours lane serves the light reservoirs only");
        assertTrue(composite.contains("pathReuseSwitchWord()\n            ).write(push);")
                        || composite.contains("pathReuseSwitchWord()\r\n            ).write(push);"),
                "the switch word fills the WorldPush lane");
        // D211: the capture reads the very function that fills the lane.
        assertTrue(composite.contains("RtFrameStats.FRAME.count(\"pathReuseSwitches\", pathReuseSwitchWord());"));
    }

    @Test
    void theSelectionTargetStaysInOneDomain() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // A-02b: selection moved to the RECEIVER domain (Enhanced §6.3 survivor representativeness).
        // Unbiasedness does not care which positive function selects, but it DOES care that the
        // draw, the sums and t_chosen stay in one domain -- so all three spellings are pinned.
        assertTrue(world.contains("candCount * luminance(candValue)"));
        assertTrue(world.contains("spatialWinTarget = luminance(candValue)"));
        assertTrue(world.contains("temporalSum = candCount * luminance(candValue)"));
        // The applied estimate stays RGB accumulation, untouched by the domain switch.
        assertTrue(world.contains("temporalCount * temporalValue + spatialValue"));
    }

    @Test
    void theReconnectionGateLooksAtThePredecessorRoughness() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // A-01a: Enhanced §4.2's single-vertex roughness gate. The qualification reads the PREVIOUS
        // opaque vertex's alpha (a mirror behind the reconnection kills the suffix transfer; this
        // vertex's own rough continuation is what makes it reconnectable), and the state rides the
        // tracePath locals. Dielectric interfaces must clear this eligibility before continuing.
        assertTrue(world.contains("float prevVertexRough = 1.0;"));
        assertTrue(world.contains("prevVertexRough >= PATH_RECONNECT_MIN_ALPHA"));
        assertTrue(world.contains("prevVertexRough = 0.0;"));
        String restirPt = source("shaders/world/restir_pt.slang");
        // 0.04 is the paper's rho_min = 0.2 converted from Falcor perceptual roughness to this
        // repository's GGX-alpha storage (iron law 2): a one-time unit conversion, not a square.
        assertTrue(restirPt.contains("PATH_RECONNECT_MIN_ALPHA = 0.04"));
    }

    @Test
    void thePathReservoirSwitchExistsAndDefaultsToOff() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        assertTrue(config.contains("PATH_RESERVOIR ="));
        assertTrue(config.contains("\"composite.path-reservoir\", false"));
    }

    private static String source(String relativePath) throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return String.join("\n", java.nio.file.Files.readAllLines(root.resolve(relativePath)));
    }
}
