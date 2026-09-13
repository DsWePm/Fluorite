package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Random replay (M28 S2) works ONLY while the path tracer's random stream is deterministic from the
 * path seed: the reservoir stores the seed a path was traced with, and a neighbour pixel re-drives the
 * same decision stream on shifted geometry. Nothing at runtime checks that -- a replay that produces
 * different decisions produces wrong reuse WEIGHTS, which look like ordinary noise or a slight brightness
 * shift, never like an error.
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
        // The loop's first draw derives from the segment seed XOR the sample index. Replay re-derives the
        // same stream from the stored seed, so this exact derivation idiom is load-bearing: changing the
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
        // W * target(y) is the unbiased-estimate invariant; the m cap bounds how long the past
        // outvotes the present.
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
        // The per-candidate selection weight is a scalar (count * home-domain luminance target).
        assertTrue(world.contains("candCount * candMeanTarget"));
    }

    @Test
    void theReconnectionGateLooksAtThePredecessorRoughness() throws IOException {
        String world = source("shaders/world/world.rgen.slang");
        // A-01a: Enhanced §4.2's single-vertex roughness gate. The qualification reads the PREVIOUS
        // opaque vertex's alpha (a mirror behind the reconnection kills the suffix transfer; this
        // vertex's own rough continuation is what makes it reconnectable), and the state rides the
        // tracePath locals so dielectric-only chains keep their camera-prefix default of 1.0.
        assertTrue(world.contains("float prevVertexRough = 1.0;"));
        assertTrue(world.contains("prevVertexRough >= PATH_RECONNECT_MIN_ALPHA"));
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
