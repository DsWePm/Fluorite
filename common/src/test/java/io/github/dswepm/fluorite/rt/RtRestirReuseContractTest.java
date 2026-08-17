package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contracts for M24 S1 — temporal reservoir reuse.
 *
 * <p>Every one of these guards something that fails SILENTLY and PLAUSIBLY. A reuse that addresses the
 * wrong slot still produces a lit image; a light check that never runs still produces a lit image; a store
 * that reallocates every frame still produces a lit image. What they have in common is that the picture
 * looks like reuse working, which is why they are worth a test apiece rather than a look.
 */
final class RtRestirReuseContractTest {

    /**
     * The slot must be keyed by PATH, not only by pixel and bounce.
     *
     * <p>main() calls tracePath {@code spp * MAX_PATH_SEGMENTS} times per pixel and each call restarts its
     * own bounce numbering. Without the path plane every one of them lands on the same slot: the last write
     * wins and each read returns whatever a different path just wrote. The failure is not a crash and not a
     * black screen — it is temporal reuse that appears to work while combining unrelated histories.
     */
    @Test
    void everyPathOfAPixelGetsItsOwnReservoirPlane() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String rgen = code(source("shaders/world/world.rgen.slang"));
        String core = source("shaders/world/world_core.slang");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(restir.contains("uint reservoirSlot(uint pixelIndex, uint pixelCount, uint pathIndex, uint pathCount,"),
                "reservoirSlot must take the path plane");
        assertTrue(restir.contains("(parity * pathCount + pathIndex) * bounceCount"),
                "the path plane must sit between parity and bounce so pixels stay innermost");
        // sampleIndex IS the path index; main() builds it as s + leaf * spp and hands the same number to
        // both uses. If they ever diverge, the store is indexed by something the decorrelation does not
        // follow and two paths share a plane again.
        assertTrue(rgen.contains("s + leaf * spp, pixelIndex,"),
                "main() must pass sampleIndex as the path index");
        assertTrue(rgen.contains("restirReuse(bc, r, uint(hitDepth), sampleIndex, pixelIndex, pixelCount,"),
                "both RIS sites must key the reservoir by hitDepth and sampleIndex");
        assertEquals(2, rgen.split("restirReuse\\(bc, r, uint\\(hitDepth\\)", -1).length - 1,
                "both RIS call sites take part, or the particle vertex quietly never reuses");

        // The Java allocation has to agree with MAX_PATH_SEGMENTS, and there is no shared constant across
        // the language boundary — only this pair of declarations.
        assertTrue(core.contains("static const uint MAX_PATH_SEGMENTS = 2u"));
        assertTrue(composite.contains("PATH_SEGMENTS_PER_PIXEL = 2L"));
        assertTrue(composite.contains("2L * reservoirDepth * reservoirPaths"),
                "the store must be sized by paths as well as depth");
    }

    /**
     * hitDepth, not bounce.
     *
     * <p>bounce starts wherever pass A left the queued record, so on the transmission branch it can start
     * above zero — leaving slot 0 unused and running off the end of a store sized by depth. hitDepth counts
     * shading vertices from zero on both branches, which is the thing a reservoir is one of.
     */
    @Test
    void theReservoirIsKeyedByShadingVertexNotByLoopIteration() throws IOException {
        String rgen = code(source("shaders/world/world.rgen.slang"));
        assertFalse(rgen.contains("restirReuse(bc, r, uint(bounce)"),
                "keying by bounce misaddresses the split branch");
        assertTrue(rgen.contains("int hitDepth = indirectDepth++"));
    }

    /**
     * The light-side check has to actually re-read the light.
     *
     * <p>A stored reservoir carries a position, a radiance and an area, so it goes on lighting the room
     * after its torch is mined: the target function re-evaluates the same, the shadow ray finds nothing to
     * occlude it, and the sample is written back every frame so it never decays. The first version of
     * reservoirReusable documented this check and did not perform it — it compared the stored sample
     * against itself. Nothing observable distinguishes the two until a light is destroyed.
     */
    @Test
    void aStoredSampleIsRejectedWhenItsEmitterIsNoLongerThere() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String lighting = code(source("shaders/world/lighting.slang"));

        assertTrue(lighting.contains("public uint lightIndex;"),
                "the survivor must remember which Light it came from");
        assertTrue(lighting.contains("r.lightIndex = li;"),
                "risInitial must record the index on a reservoir update");
        assertTrue(lighting.contains("r.lightIndex = RESERVOIR_NO_LIGHT;"),
                "an empty reservoir must not name light 0");
        assertTrue(restir.contains("asfloat(r.lightIndex)") && restir.contains("asuint(p.misc.z)"),
                "the index must survive the round trip through storage");

        String check = between(restir, "public bool storedLightStillPresent(", "\n}");
        assertTrue(check.contains("index >= worldPush.lightCount"), "an index past the buffer is not a light");
        assertTrue(check.contains("ConstPtr<Light>(pc.lightBufAddr)[index]"), "the record must be re-read");
        assertTrue(check.contains("lightRadiance(lg)"), "radiance must be compared");
        assertTrue(check.contains("lightArea(lg)"), "area must be compared");
        // Containment is the only one of the three a light that MOVED cannot pass, and moving is exactly
        // what an entity light does between frames.
        assertTrue(check.contains("p.lightPosArea.xyz - (lg.pos + worldPush.lightRebase.xyz)"),
                "the stored point must be tested against the rectangle it claims to be on");
        assertTrue(check.contains("uu * LIGHT_EXTENT_SLACK") && check.contains("vv * LIGHT_EXTENT_SLACK"));

        assertTrue(restir.contains("return storedLightStillPresent(stored);"),
                "reservoirReusable must run the light check, not merely define it");
    }

    /**
     * p-hat is re-evaluated at the vertex doing the reusing.
     *
     * <p>Carrying it forward from the vertex that produced it is the classic way to make temporal reuse
     * wrong, and it produces a picture that looks fine in a static scene. unpackReservoir returns zero for
     * it rather than a stale value so a caller that forgets gets an obviously wrong answer.
     */
    @Test
    void reuseRecomputesTheTargetFunctionAndNeverInheritsIt() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String combine = between(restir, "public Reservoir combineStoredReservoir(", "\n}");

        assertTrue(combine.contains("evalSampleContrib(bc, prev.pos, prev.lnrm, prev.le, prev.area, phatPrev)"),
                "the stored sample must be re-evaluated against this vertex");
        assertTrue(combine.contains("float wPrev = phatPrev * prev.W * mPrev;"));
        assertTrue(combine.contains("min(prev.M, REUSE_M_CAP)"), "history must be capped or it stops responding");
        assertTrue(combine.contains("r.wSum + wPrev"),
                "this frame's weight is the wSum risInitial left; a second formula is a second thing to drift");
        assertTrue(combine.contains("wSum / (m * r.phat)"));

        String unpack = between(restir, "public Reservoir unpackReservoir(", "\n}");
        assertTrue(unpack.contains("r.wSum = 0.0;") && unpack.contains("r.phat = 0.0;"),
                "storage must not hand back a plausible stale target");
    }

    /**
     * Reads come from the other frame half, writes go to this one.
     *
     * <p>Reading the half being written would let a sample combine with something written moments ago in
     * this same frame — including by the other spp samples of this pixel, which is what turns independent
     * estimates into one shared history and quietly eats what spp was bought for.
     */
    @Test
    void historyIsReadFromTheOppositeParityAndWrittenToThisOne() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String reuse = between(restir, "public Reservoir restirReuse(", "\n}");

        assertTrue(reuse.contains("uint parity = worldPush.frameIndex & 1u;"));
        assertTrue(reuse.contains("bounce, depth, parity ^ 1u)"), "history comes from the other half");
        assertTrue(reuse.contains("bounce, depth, parity)"), "this frame writes its own half");
        assertTrue(reuse.contains("PackedReservoir stored = store[slotPrev];"));
        assertTrue(reuse.contains("store[slotCur] = packReservoir(r, bc.hitPos, bc.n);"),
                "the combined reservoir must be written, or history never accumulates");
    }

    /**
     * Off is off (iron law 8).
     *
     * <p>Depth 0 must reach single-frame RIS without touching the store, and the renderer must publish a
     * zero address rather than a stale one — the switch exists to A/B a gigabyte, and an off state that
     * differs from the published behaviour makes the comparison meaningless.
     */
    @Test
    void depthZeroIsTheBehaviourThatPredatesTheStore() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(restir.contains("if (pc.reservoirAddr == 0 || depth == 0u || bounce >= depth) {"),
                "no store, no depth, or past the depth: return the reservoir untouched");
        assertTrue(composite.contains("reservoirStore != null ? reservoirStore.deviceAddress : 0L"));
        assertTrue(composite.contains("reservoirStore != null ? reservoirDepth : 0"));
        assertTrue(composite.contains("reservoirStore != null ? reservoirPaths : 0"));
        assertEquals(0, FluoriteConfig.Rt.Composite.RESTIR_REUSE_DEPTH.defaultValue().intValue(),
                "the dial ships off");
    }

    /** Uninitialised device memory must not be able to present itself as history. */
    @Test
    void theStoreIsZeroedOnceWhenItIsAllocated() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertTrue(composite.contains("reservoirStoreNeedsClear = true;"));
        assertTrue(composite.contains("vkCmdFillBuffer(cmd, reservoirStore.handle, 0L, reservoirStore.size, 0)"));
        assertTrue(composite.contains("VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT"),
                "a buffer that gets filled needs the transfer-destination usage");
    }

    /**
     * The store's size is what the shader's layout says it is.
     *
     * <p>Four float4s, not fourteen floats: std430 gives a scalar-member struct a stride of 56, which is
     * not a multiple of 16, so every other element of a buffer read once per bounce vertex per pixel would
     * straddle a cache line. Adding a field is the way this breaks, and it breaks by changing a stride the
     * Java allocation states independently.
     */
    @Test
    void theStoredLayoutStaysSixtyFourBytesOnBothSides() throws IOException {
        String restir = source("shaders/world/restir.slang");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        String struct = between(code(restir), "public struct PackedReservoir {", "};");
        assertEquals(4, struct.split("float4", -1).length - 1,
                "PackedReservoir must be exactly four float4s");
        assertFalse(struct.contains("float3") || struct.contains("float2"),
                "a narrower member would change the stride away from 64");
        assertTrue(restir.contains("RESERVOIR_BYTES = 64u"));
        assertTrue(composite.contains("RESERVOIR_BYTES = 64L"));
    }

    /**
     * The reallocation test must compare against the depth that will actually be allocated.
     *
     * <p>The size clamp can cut the request. A condition that tested the raw request would then never be
     * satisfied, and the store — up to four gigabytes of it — would be freed and rebuilt every frame, with
     * a waitIdle each time. It would present as a framerate collapse with no wrong pixels anywhere.
     */
    @Test
    void aClampedDepthDoesNotReallocateTheStoreEveryFrame() {
        long hd = 1920L * 1080L;
        long qhd = 2560L * 1440L;
        // 1440p, depth 8, two path planes: 7.0 GiB, past the 32-bit slot ceiling.
        int fitted = RtComposite.reservoirDepthThatFits(8, 2, qhd);
        assertTrue(fitted < 8, "depth 8 at 1440p with two paths does not fit a 32-bit slot index");
        assertEquals(fitted, RtComposite.reservoirDepthThatFits(8, 2, qhd),
                "the clamp must be a pure function of what it is given, or ensureOutput oscillates");
        // 1080p at the default one sample per pixel fits to the top of the dial, clamp or no clamp; it is
        // raising spp that puts it over. Both are configurations a user can reach from the settings screen.
        assertEquals(8, RtComposite.reservoirDepthThatFits(8, 2, hd));
        assertTrue(RtComposite.reservoirDepthThatFits(8, 4, hd) < 8);
        assertEquals(4, RtComposite.reservoirDepthThatFits(4, 2, hd));
        // Before the first allocation there is no render size and nothing can be too large yet.
        assertEquals(4, RtComposite.reservoirDepthThatFits(4, 2, 0L));
        assertEquals(0, RtComposite.reservoirDepthThatFits(0, 2, hd));
    }

    /**
     * The acceptance rate is counted, not flagged.
     *
     * <p>Every path of a pixel asks independently at each depth and they routinely disagree. A bitmask
     * OR-ed across them reports the pixel as accepting whenever any one path did, and the published rate
     * comes out flattering — which is the exact number the 265 MB per depth is being judged on.
     */
    @Test
    void reuseStatisticsCountEveryPathRatherThanFlaggingThePixel() throws IOException {
        String restir = code(source("shaders/world/restir.slang"));
        String rgen = code(source("shaders/world/world.rgen.slang"));

        String bump = between(restir, "public void restirCountBump(", "\n}");
        assertTrue(bump.contains("packed += 1u << shift;"), "counts, not flags");
        assertTrue(bump.contains("< 0xFu"), "saturate rather than carry into the next depth's nibble");
        assertFalse(restir.contains("reuseCounts.x |= "), "an OR would lose the disagreements between paths");

        assertTrue(restir.contains("restirCountBump(reuseCounts.x, bounce);"));
        assertTrue(restir.contains("restirCountBump(reuseCounts.y, bounce);"));
        // A depth no path reached is not a rejection, and counting it as one would report the deep
        // reservoirs as useless for the entirely separate reason that nothing got that far.
        assertTrue(restir.contains("if (attempts == 0u) {"));
        assertTrue(rgen.contains("restirStatsAccumulate(reuseCounts, pixelIndex);"));
        assertEquals(RtRestirStats.MAX_DEPTH, valueAfter(restir, "RESTIR_MAX_STAT_DEPTH = ", "u;"),
                "the shader and the reader must agree on how many depths exist");
        assertEquals(RtRestirStats.LANES, valueAfter(restir, "RESTIR_STAT_LANES = ", "u;"));
    }

    /** The integer literal a declaration is given, so the shader's own number is what gets compared. */
    private static int valueAfter(String haystack, String prefix, String end) {
        String span = between(haystack, prefix, end);
        return Integer.parseInt(span.substring(prefix.length()).trim());
    }

    /** The text of the {@code needle} declaration up to {@code end}, so a check cannot drift into its neighbours. */
    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "missing: " + needle);
        int stop = haystack.indexOf(end, start + needle.length());
        assertTrue(stop >= 0, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    /** Source with line comments removed, so an assertion cannot be satisfied by prose describing the bug. */
    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash < 0 ? line : line.substring(0, slash)).append('\n');
        }
        return out.toString();
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(relativePath));
    }
}
