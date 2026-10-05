package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M28 path reservoir layout and the seed contract for random replay. D229 suspended the invalid
 * reconnected shift; the D231 rebuild replays a recorded path from its stored seed through per-vertex
 * streams and shared vertex functions (R1b), and so far uses that replay only for the prefix identity
 * self-check (R1b-3) -- the hybrid shift that needs it arrives with R3.
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
        String world = source("shaders/world/path_reuse_trace.slang");
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
        assertTrue(worldCommon.contains("PATH_RESERVOIR_BYTES = 80u"));
        assertTrue(worldCommon.contains("pathReservoirSlot"));
        // The float3 MUST lead: std430 gives float3 a 16-byte alignment, and a uint before it pads
        // the stride to 64 against the 48-byte allocation (the D220 GPU fault, verbatim).
        String struct = worldCommon.substring(worldCommon.indexOf("public struct PackedPathReservoir"));
        assertTrue(struct.indexOf("public float3 reconPos;") < struct.indexOf("public uint   pathSeed;"),
                "PackedPathReservoir field order regressed: reconPos must precede pathSeed or the "
                        + "stride grows to 64 and every slot access walks off the allocation");
        String restirPt = source("shaders/world/restir_pt.slang");
        // The estimator side stays in restir_pt: the packer and the merge constants.
        assertFalse(restirPt.contains("evalPathSuffixCandidate("));
        assertFalse(restirPt.contains("PATH_RESERVOIR_M_CAP"));
    }

    @Test
    void theBounceLoopSnapshotsTheStreamBaseTheReservoirStores() throws IOException {
        String world = source("shaders/world/path_reuse_trace.slang");
        // The recorded pathSeed must be the stream's BASE, snapshotted before the first draw mutates
        // the working seed -- storing a mid-stream state would replay only the tail of the path.
        assertTrue(world.contains("uint pathSeedBase = seed;"));
        // The write is gated on the store's address, not a separate flag: no buffer means no record
        // and no branch left behind (the switch's whole shader-side off state).
        assertTrue(world.contains("worldPush.pathReservoirAddr != 0"));
        assertTrue(world.contains("= emptyPathReservoir();"));
    }

    @Test
    void theInvalidInPlaceEstimatorIsRetired() throws IOException {
        String world = source("shaders/world/path_reuse_trace.slang");
        String module = source("shaders/world/restir_pt.slang");
        for (String old : new String[] {"evalPathSuffixCandidate(", "temporalWins", "spatialWins",
                "temporalValue", "spatialTargetSum", "reconLpre", "PATH_RESERVOIR_APPLY_MIN_M"}) {
            assertFalse(world.contains(old) || module.contains(old), "retired estimator returned: " + old);
        }
        assertFalse(module.contains("cosHere / cosStored"));
    }

    @Test
    void theRecordOnlySliceReturnsTheWholeOwnPath() throws IOException {
        String world = source("shaders/world/path_reuse_trace.slang");
        assertFalse(world.contains("L = reconLpre + applied"));
        assertFalse(world.contains("prevParity"));
        assertTrue(world.contains("return L;"));
    }

    @Test
    void theInvalidReconnectedShiftStaysGone() throws IOException {
        String world = source("shaders/world/path_reuse_trace.slang");
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
        String world = source("shaders/world/path_reuse_trace.slang");
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
    void theRecordOnlySliceDoesNotReadHistory() throws IOException {
        String world = source("shaders/world/path_reuse_trace.slang");
        assertFalse(world.contains("restirPreviousPixel("));
        assertFalse(world.contains("stored.reconIndex"));
        assertFalse(world.contains("neighbourSlot"));
    }

    @Test
    void theShippedPassBCompilesThePathReuseOut() throws IOException {
        // D231 (G15): the path reuse runs in its own pass B pipeline so a switch-off frame binds exactly
        // what shipped. Four spellings have to agree, and none of them fails loudly on its own.
        String gradle = source("build.gradle");
        assertTrue(gradle.contains("def reuse = [\"-DFLUORITE_PATH_REUSE\"]"));
        assertTrue(gradle.contains("new File(scratchDir, \"world_reuse.rgen.spv\"), reuse)"));
        assertTrue(gradle.contains("new File(scratchDir, \"world_ser_reuse.rgen.spv\"), ser + reuse)"),
                "the SER device gets a reuse variant too, or it silently falls back to the plain tracer");

        String bringup = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtDeviceBringup.java");
        assertTrue(bringup.contains("\"world.rgen.spv\", \"world_reuse.rgen.spv\")"));
        assertTrue(bringup.contains("\"world_ser.rgen.spv\", \"world_ser_reuse.rgen.spv\")"));

        // The shipped tracer carries no path reservoir at all; the reuse tracer is included, and
        // called, only under the define.
        String shipped = source("shaders/world/world.rgen.slang");
        assertFalse(shipped.contains("pathReservoirAddr"),
                "world.rgen's own tracePath is the tracer that shipped before S2; the reuse lives elsewhere");
        int include = shipped.indexOf("#ifdef FLUORITE_PATH_REUSE\n"
                + "// M28 D231 (G15): the path reuse's pass B tracer, in the variant pipeline only. See the file banner.\n"
                + "#include \"path_reuse_trace.slang\"\n#endif");
        assertTrue(include >= 0, "the reuse tracer is included only into the variant");
        assertTrue(shipped.contains("#ifdef FLUORITE_PATH_REUSE\n                    tracePathReuse(segment,")
                && shipped.contains("#else\n                    tracePath(segment,"),
                "main() calls the reuse tracer only in the variant");
        String reuse = source("shaders/world/path_reuse_trace.slang");
        assertTrue(reuse.contains("public float3 tracePathReuse(__ref PathSegment seg,"));
        assertTrue(reuse.contains("bool recordPath = worldPush.pathReservoirAddr != 0 && sampleIndex == 0u;"));

        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertTrue(composite.contains("active.trace(cmd, renderW, renderH, pushConstants, 0, passB);"));
        assertTrue(composite.contains("active.trace(cmd, renderW, renderH, pushConstants, 1);"),
                "the switch-off frame keeps launching the shipped raygen record");
        String variant = composite.substring(composite.indexOf("private RtPipeline.Variant pathReuseVariant(RtPipeline active) {"));
        assertTrue(variant.indexOf("if (pathReservoirStore == null) {") < variant.indexOf("createVariant("),
                "the variant is bound only while the path reservoir's store exists");

        String pipeline = source("common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtPipeline.java");
        assertTrue(pipeline.contains("buildPipeline(ctx, stack, pipelineLayout, rgen, missShaders, closestHitShader,"),
                "a variant is built over the SAME layout, so the base pipeline's descriptor sets bind to it");
    }

    @Test
    void aRecordedPathDrawsItsVertexDecisionsFromAddressableStreams() throws IOException {
        // D231 R1b, the REPLAY CONTRACT: a recorded path's vertex decisions must not share a stream with
        // anything whose draw count depends on geometry or acceptance (NEE, RIS, M24's reuse, the walk),
        // or a replay on another pixel cannot land on the same decisions (D227, audit B07).
        String segment = source("shaders/world/segment.slang");
        assertTrue(segment.contains("uint s = pathSeed ^ ((vertex + 1u) * PATH_VERTEX_SALT);"));
        assertTrue(segment.contains("uint s = pathSeed ^ PATH_REUSE_SALT;"));
        String reuse = source("shaders/world/path_reuse_trace.slang");
        String rule = "uint vertexSeed = recordPath ? vertexStream(pathSeedBase, uint(hitDepth)) : seed;";
        assertEquals(2, reuse.split(java.util.regex.Pattern.quote(rule), -1).length - 1,
                "both vertex kinds -- dielectric and opaque -- draw their decisions from the vertex stream");
        assertEquals(2, reuse.split("seed = vertexSeed;", -1).length - 1,
                "an unrecorded path hands the stream back: it must draw exactly as the shipped tracer does");
        assertEquals(2, reuse.split("if \\(!recordPath\\) \\{\\s*seed = vertexSeed;", -1).length - 1,
                "and only an unrecorded one");
        // The reuse's own picks never borrow the vertex stream or the path's sequential one.
        assertTrue(reuse.contains("uint reuseSeed = reuseStream(pathSeedBase);"));
        assertFalse(reuse.contains("spatialNeighbourOffset(seed)"));
        // The shared vertex functions draw only from the stream they are handed.
        for (String fn : new String[] {"bool reuseResolveDielectric(", "bool reuseSampleContinuation("}) {
            String body = reuse.substring(reuse.indexOf(fn), reuse.indexOf("\n}\n", reuse.indexOf(fn)));
            assertTrue(body.contains("inout uint stream"), fn + " takes its stream as a parameter");
            assertFalse(body.matches("(?s).*\\bseed\\b.*"), fn + " must not reach for the path's seed");
        }
    }

    @Test
    void thePrefixReplayReDrivesARecordedPathAndSaysSo() throws IOException {
        // D231 R1b-3: the replay every later shift stands on, and the diagnostic that proves it lands.
        String reuse = source("shaders/world/path_reuse_trace.slang");
        java.util.List<String> replay = body(reuse, "int reuseReplayPrefix(");
        String joined = String.join("\n", replay);
        // Same rays as the loop: the trace call and the cone update are the loop's own lines.
        java.util.List<String> loop = body(reuse, "public float3 tracePathReuse(");
        for (String line : new String[] {
                "traceRadiance(bounce == 0 ? CULL_PRIMARY : secondaryCull, ro, 0.0, rd, RAY_FAR,",
                "rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);",
                "int hitDepth = indirectDepth++;"}) {
            assertTrue(replay.contains(line), "the replay traces as the loop does: " + line);
            assertTrue(loop.contains(line), "the loop still traces this way: " + line);
        }
        // Same decisions: the shared vertex functions, each vertex drawing from its own stream.
        assertTrue(joined.contains("uint vertexSeed = vertexStream(pathSeed, uint(hitDepth));"));
        assertTrue(joined.contains("reuseResolveDielectric("));
        assertTrue(joined.contains("reuseOpaqueVertex("));
        assertTrue(joined.contains("reuseSampleContinuation("));
        // Nothing else: no draw from the path's sequential stream -- which is where roulette, NEE and the
        // particle's continuation live -- and no roulette at all (Enhanced section 6.2.4).
        assertFalse(joined.matches("(?s).*\\bseed\\b.*"), "the replay must not touch the sequential stream");
        assertFalse(joined.contains("rrStart") || joined.contains("throughput /= q"), "no roulette in a replay");
        assertTrue(joined.contains("if (material == MATERIAL_PARTICLE) {\nbreak;"),
                "a billboard continues from the sequential stream: the replay stops there");
        String loopJoined = String.join("\n", loop);
        assertTrue(loopJoined.contains("identityParticle = true;"),
                "and the check skips a path that crossed one");
        // It restarts from what the loop started from, with the seed the record stores.
        assertTrue(loopJoined.contains("uint entryMediumFlags = activeMediumFlags;"));
        assertTrue(loopJoined.contains("reuseReplayPrefix(seg, entryMediumFlags, pathSeedBase, identityVertices,"));
        assertTrue(loopJoined.contains("identityVertices >= 2"), "a one-vertex replay decides nothing");

        // Diagnostics only: switch word bit 0, the stats buffer, off by default; the Java packer and the
        // Slang reader spell the same bit, clear of the neighbour count.
        assertTrue(loopJoined.contains("(worldPush.pathReplayEnabled & PATH_REUSE_IDENTITY_BIT) != 0u"));
        assertTrue(loopJoined.contains("&& worldPush.pathReservoirStatsAddr != 0u"));
        assertTrue(source("shaders/world/restir_pt.slang").contains(
                "PATH_REUSE_IDENTITY_BIT = " + RtComposite.PATH_REUSE_IDENTITY_BIT + "u;"));
        assertEquals(0, RtComposite.PATH_REUSE_IDENTITY_BIT
                & (RtComposite.PATH_REUSE_NEIGHBOURS_MASK << RtComposite.PATH_REUSE_NEIGHBOURS_SHIFT));
        assertTrue(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java")
                .contains("word |= PATH_REUSE_IDENTITY_BIT;"));
        assertFalse(FluoriteConfig.Rt.Diagnostics.PATH_REUSE_IDENTITY_CHECK.defaultValue());

        // R2a removed the unused merge lanes: identity now occupies lanes 0 and 1.
        assertTrue(loopJoined.contains("DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[0], 1u)"));
        assertTrue(loopJoined.contains("DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[1], 1u)"));
        assertEquals(9, RtPathReservoirStats.LANES);
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtPathReservoirStats.java");
        assertTrue(stats.contains("memGetInt(src.mapped)") && stats.contains("src.mapped + 4L"));
        assertTrue(stats.contains("identityPass, identityAttempt, percentage(identityPass, identityAttempt)"));
        assertTrue(stats.contains("%.3f%%"));
    }

    @Test
    void theReuseTracerKeepsTheShippedTracerLineForLine() throws IOException {
        // The reuse tracer must be the shipped tracePath with the path reservoir ADDED: the switch has to
        // compare reuse against no reuse, not two different tracers. R1b moved the vertex decisions into
        // shared functions (so the replay decides exactly as the loop did), which is why two checks
        // replace the old in-order one:
        java.util.List<String> shipped = body(source("shaders/world/world.rgen.slang"),
                "public float3 tracePath(__ref PathSegment seg, uint activeMediumFlags,");
        String reuseFile = source("shaders/world/path_reuse_trace.slang");

        // 1. Every shared vertex function is a verbatim, in-order excerpt of the shipped loop, with
        //    only its random stream renamed and its exits turned into returns.
        String[][] functions = {
            {"bool reuseResolveDielectric(", "return true;"},
            {"ReuseOpaqueVertex reuseOpaqueVertex(", "ReuseOpaqueVertex o;|o\\.\\w+ = \\w+;|return o;"},
            {"bool reuseSampleContinuation(", "return true;|walked = (false|true);.*"
                    + "|float3 (n|v|p|F0|diffAlb) = vtx\\.\\w+;|(RainSurface rain|BsdfContext bc) = vtx\\.\\w+;"
                    + "|float (pf|ps) = vtx\\.\\w+;|bool exactSpecular = vtx\\.exactSpecular;"},
        };
        for (String[] f : functions) {
            java.util.List<String> lines = new java.util.ArrayList<>();
            for (String line : body(reuseFile, f[0])) {
                String l = line.replace("stream", "seed").replace("return false;", "break;")
                        .replace("vtx.sssRadius", "sssRadius").replace("vtx.ext.specular.w", "ext.specular.w")
                        .replace("vtx.walkSssShare", "walkSssShare");
                if (!l.startsWith("//") && !l.matches(f[1])) {
                    lines.add(l);
                }
            }
            int at = 0;
            for (String line : lines) {
                while (at < shipped.size() && !shipped.get(at).equals(line)) {
                    at++;
                }
                assertTrue(at < shipped.size(), f[0] + " departs from the shipped loop at: " + line);
                at++;
            }
        }

        // 2. No line of the shipped body is lost: each one is in the reuse file, as written or with the
        //    same stream/exit rename the shared functions apply.
        for (String line : shipped) {
            if (line.startsWith("//")) {
                continue; // prose may be reworded; the code may not
            }
            String renamed = line.replace("seed", "stream").replace("break;", "return false;");
            // ...and the three reads the continuation takes from the decoded vertex rather than a local.
            String fromVertex = renamed.replace("walkSssShare", "vtx.walkSssShare")
                    .replace("sssRadius,", "vtx.sssRadius,").replace("ext.specular.w", "vtx.ext.specular.w");
            assertTrue(reuseFile.contains(line) || reuseFile.contains(renamed) || reuseFile.contains(fromVertex),
                    "shipped line missing from the reuse tracer: " + line);
        }
    }

    /** The trimmed, non-blank lines of a function's body, from its signature to its closing brace. */
    private static java.util.List<String> body(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "missing: " + signature);
        int end = source.indexOf("\n}\n", start);
        if (end < 0 && source.endsWith("\n}")) {
            end = source.length() - 2; // the function is the file's last declaration
        }
        assertTrue(end > start, "unterminated: " + signature);
        java.util.List<String> lines = new java.util.ArrayList<>();
        // From the line after the signature's opening brace: signatures span several lines here.
        int open = source.indexOf('{', start);
        for (String line : source.substring(source.indexOf('\n', open) + 1, end).split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.equals("return L;")) {
                lines.add(t);
            }
        }
        return lines;
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

        String world = source("shaders/world/path_reuse_trace.slang");
        assertFalse(world.contains("i < pathReuseSpatialNeighbours()"),
                "R2 is record-only; the spatial loop arrives with R4");
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
    void theHybridRecordReservesPrimaryAndSourceDensityState() throws IOException {
        String common = source("shaders/world/world_common.slang");
        for (String field : new String[] {"primaryPos", "primaryNrm", "jacDenom", "confPdf", "bits"}) {
            assertTrue(common.contains(field));
        }
        assertTrue(common.contains("PATH_OBJECT_UNAVAILABLE = 0xffffffffu"));
    }

    @Test
    void theUnprovenPerceptualRoughnessConversionStaysGone() throws IOException {
        String module = source("shaders/world/restir_pt.slang");
        assertFalse(module.contains("PATH_RECONNECT_MIN_ALPHA = 0.04"));
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
