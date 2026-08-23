package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M26's presampled emitter pool, pinned at the seams where its two halves have to agree.
 *
 * <p>The pool is a cache of a sampling decision, and the way a cache like this goes wrong is not that it
 * returns the wrong light -- it is that it returns a light with a density nobody re-derived correctly, or
 * that the build and the read disagree about which cell a receiver belongs to. Neither shows up as a
 * crash; both show up as light in the wrong place, which is indistinguishable from art direction.
 */
final class RtLightPoolContractTest {

    /**
     * Off has to be the published renderer, or the A/B this milestone exists to run means nothing.
     *
     * <p>Spelled as an absent buffer rather than as a branch: with the switch down no pool is allocated,
     * the address published is zero, and the sampler's first guard sends every sample back down the walk.
     */
    @Test
    void theOffStateIsAnAbsentBufferRatherThanABranch() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String sampling = shader("light_sampling.slang");

        assertTrue(config.contains("\"fluorite.rt.lightPool\", \"composite.light-pool\", false"));
        assertTrue(composite.contains("if (FluoriteConfig.Rt.Composite.LIGHT_POOL.value() && terrain != null)"));
        assertTrue(composite.contains("lightPool != null ? lightPool.deviceAddress : 0L"));
        assertTrue(sampling.contains("if (wp.lightPoolAddr == 0 || wp.lightPoolDepth == 0u"));
    }

    /**
     * THE SLOT CHOOSES THE LIGHT, THE SAMPLE CHOOSES THE POINT.
     *
     * <p>Drawing s and t at build time would hand every pixel that reads a slot the same point on the
     * emitter. That is correlation across the screen rather than noise, and no temporal accumulator
     * removes it -- averaging frames that all carry the same error leaves the error. So the build must
     * copy the half-extents and the sampler must draw the point.
     */
    @Test
    void theRectanglePointIsDrawnPerSampleAndNotBakedIntoTheSlot() throws IOException {
        String build = shader("light_pool.comp.slang");
        String sampling = shader("light_sampling.slang");

        assertTrue(build.contains("out.halfUxy = light.halfUxy;"));
        assertTrue(build.contains("out.halfVyz = light.halfVyz;"));
        assertFalse(build.contains("emitterHalfU("));
        assertFalse(build.contains("emitterHalfV("));

        assertTrue(sampling.contains("float s = rndf(seed) * 2.0 - 1.0;"));
        assertTrue(sampling.contains("+ s * emitterHalfU(light) + t * emitterHalfV(light)"));
    }

    /**
     * The pooled path must be the SAME estimator, which means it never re-derives a density.
     *
     * <p>The slot carries the probability the build's walk drew it with, and the sampler hands that
     * through untouched. A pooled read that recomputed emitterProposalPdf against the receiver's own cell
     * would be a different estimator wearing the same switch.
     */
    @Test
    void thePooledPathCarriesTheBuildsDensityRatherThanRederivingOne() throws IOException {
        String build = shader("light_pool.comp.slang");
        String sampling = shader("light_sampling.slang");

        assertTrue(build.contains("float pdf = emitterProposalPdf("));
        assertTrue(build.contains("out.pdf = max(0.0, pdf);"));

        String pooled = between(sampling, "public uint sampleVolumeEmitterPooled(",
                "/**\n * One nearby-emitter proposal for a volume event.");
        assertTrue(pooled.contains("sample.sourcePdf = pooled.pdf;"));
        assertFalse(pooled.contains("emitterProposalPdf("));
        assertFalse(pooled.contains("emitterSelectGrid("));
        assertFalse(pooled.contains("emitterSelectSection("));
    }

    /**
     * Both halves must agree about which cell a receiver is in, jitter included.
     *
     * <p>The walk jitters the lookup by a cell before flooring it, which softens the boundary between
     * neighbouring cells into noise. A pooled read that skipped the jitter would put a hard seam along
     * every cell face -- and it would be a seam the switch introduces, which is precisely what an
     * isolation switch must not do.
     */
    @Test
    void bothPathsLocateTheReceiverCellTheSameWay() throws IOException {
        String sampling = shader("light_sampling.slang");
        String walkJitter = "gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), "
                + "rndf(proposalSeed)) - 0.5)";
        assertTrue(sampling.contains(walkJitter));
        assertTrue(sampling.contains("+ (float3(rndf(proposalSeed), rndf(proposalSeed), "
                + "rndf(proposalSeed)) - 0.5)\n              * wp.lightGridOrigin.w"));
    }

    /**
     * The slot's density has THREE states, and the sign is what separates the two that look alike.
     *
     * <p>"Nothing was drawn here" and "a light was drawn and its local density is zero" are different
     * facts. The first leaves the grid as the authority, so the reader must walk it. The second is an
     * answer -- emitterProposalPdf's refusal to substitute a global density for a rejected local draw --
     * and walking again would hand that rejection a second chance at contributing.
     *
     * <p>Negative is reserved for the first, and the build clamps so that the second can never produce
     * it by accident. An encoding whose middle state can be forged is worth nothing.
     */
    @Test
    void theSlotDensityHasThreeStatesAndTheSignSeparatesThem() throws IOException {
        String build = shader("light_pool.comp.slang");
        String sampling = shader("light_sampling.slang");

        assertTrue(build.contains("empty.pdf = -1.0;"));
        assertTrue(build.contains("out.pdf = max(0.0, pdf);"));
        // And a rejected draw is no longer erased: the record is written whatever the density came to.
        assertFalse(build.contains("if (!(pdf > 0.0))"));

        assertTrue(sampling.contains("public static const uint EMITTER_POOL_MISS = 0u;"));
        assertTrue(sampling.contains("public static const uint EMITTER_POOL_HIT = 1u;"));
        assertTrue(sampling.contains("public static const uint EMITTER_POOL_REJECT = 2u;"));
        assertTrue(sampling.contains("if (pooled.pdf < 0.0) {\n        return EMITTER_POOL_MISS;"));
        assertTrue(sampling.contains("if (!(pooled.pdf > 0.0)) {\n        return EMITTER_POOL_REJECT;"));
    }

    /**
     * A rejected pooled draw contributes zero. It is NOT redrawn, and the difference is measurable.
     *
     * <p>sampleVolumeEmitter answers a rejected draw with false, and volumeNee turns that into black.
     * The pool stands in for that walk, so it owes the same zero. Falling through to the walk instead --
     * which is what a two-state result forced -- gives every rejection a second draw, leaving the pooled
     * fog brighter than the walk it replaces by exactly the rejection rate. That is the switch changing
     * the picture, which is the one thing an isolation switch may not do.
     */
    @Test
    void aRejectedPooledDrawContributesZeroRatherThanBeingRedrawn() throws IOException {
        String lighting = shader("lighting.slang");

        assertTrue(lighting.contains("if (poolStatus == EMITTER_POOL_REJECT) {\n"
                + "        return float3(0.0, 0.0, 0.0);\n    }"));
        // And the walk is reached ONLY from a miss, never from a rejection.
        assertTrue(lighting.contains("if (poolStatus == EMITTER_POOL_MISS\n"
                + "            && !sampleVolumeEmitter("));
    }

    /**
     * The SURFACE reader keeps a zero-density light instead of replacing it, because its walk does.
     *
     * <p>proposalPdf never rejects: an out-of-neighbourhood light gets (1-alpha)*globalPdf and counts as
     * a candidate like any other. So the surface half of the same bug ran the other way -- treating a
     * zero density as "empty" and redrawing DELETED a candidate the walk would have kept. It needs no
     * special case to fix, because the mixture already does the right thing with a zero local half; it
     * needs only to stop calling zero empty.
     */
    @Test
    void theSurfaceCandidateKeepsAZeroDensityLightRatherThanReplacingIt() throws IOException {
        String lighting = shader("lighting.slang");
        assertTrue(lighting.contains("if (pooled.pdf >= 0.0) {"));
        assertFalse(lighting.contains("if (pooled.pdf > 0.0) {"));
    }

    /**
     * The pool's footprint reaches the log, because a default depth cannot be chosen without it.
     *
     * <p>Nothing else reports it: the frame profile has no VRAM column and the buffer's debug name never
     * reaches the log. At DEBUG rather than info, and the reason is measured rather than assumed -- the
     * first run printed 530 lines in four minutes, because the populated cell count only ever grows
     * while chunks stream in, so "only on a new peak" turned out to be every single resize.
     */
    @Test
    void thePoolReportsItsPeakFootprintWithoutFloodingTheLog() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        assertTrue(composite.contains("if (bytes > lightPoolPeakBytes) {"));
        assertTrue(composite.contains("FluoriteMod.LOGGER.debug(\n"
                + "                        \"Light presample pool peak:"));
    }

    /**
     * ONE reader of the depth knob, and what the pool publishes is the depth it was SIZED with.
     *
     * <p>The shader indexes the pool by `rank * depth + slot`, so a depth larger than the one the
     * allocation was made for runs off the end of the buffer. Reading the live config at the record site
     * makes that reachable by moving a slider between the resize and the record; reading the field the
     * resize wrote does not.
     *
     * <p>The per-site Math.clamp calls that used to stand in for this were worse than redundant. They
     * were a second, independent copy of the config's own bound -- four places to update when the
     * ceiling moves, and no failure at all on the day one is missed, just a depth quietly pinned lower
     * than the setting says. IntSetting sanitises on every write, so value() cannot be out of range.
     */
    @Test
    void theDepthKnobIsReadInExactlyOnePlace() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertEquals(1, countOf(composite, "Composite.LIGHT_POOL_DEPTH"),
                "the depth knob belongs to ensureLightPool alone");
        assertTrue(composite.contains("depth = FluoriteConfig.Rt.Composite.LIGHT_POOL_DEPTH.value();"));
        assertFalse(composite.contains("Math.clamp(FluoriteConfig.Rt.Composite.LIGHT_POOL_DEPTH"));
        // Published and dispatched from the field the resize wrote, never from the knob.
        assertTrue(composite.contains("lightPool != null ? lightPoolDepth : 0,"));
        assertTrue(composite.contains("lightPoolSlots = want;\n        lightPoolDepth = depth;"));
        // And the early-out watches the depth too: the product hides a change that halves the cells
        // while doubling the depth, which wants the same bytes and a different stride.
        assertTrue(composite.contains("if (lightPoolSlots == want && lightPoolDepth == depth) {"));
    }

    /**
     * The shipped default is the depth that was measured not to flicker, and the slider can reach the top.
     *
     * <p>Eight was the default and eight visibly flickered in a many-light room under heavy fog -- a
     * whole sixteen-block cell pulsing together, because every pixel in a cell shares its slots and the
     * slots are redrawn each frame. Thirty-two stopped it for 0.009 ms of build and no measurable trace
     * time. A default that ships a known artefact is not a default, it is a trap the user has to find.
     *
     * <p>The slider's range is pinned against the setting's own, because a slider that stops short of
     * the config's ceiling silently pins whatever a hand-edited toml already holds.
     */
    @Test
    void theDefaultDepthIsTheOneMeasuredNotToFlicker() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(config.contains(
                "\"fluorite.rt.lightPoolDepth\", \"composite.light-pool-depth\", 32, 1, 64)"));
        assertTrue(options.contains("new OptionInstance.IntRange(1, 64),"));
    }

    /**
     * The pool's record size is spelled twice and has to match: 48 bytes.
     *
     * <p>Java sizes the allocation and Slang indexes it. Nothing checks this at runtime -- a mismatch
     * simply reads a light out of the middle of two others.
     */
    @Test
    void theRecordSizeAgreesBetweenTheAllocationAndTheShader() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String common = shader("world_common.slang");

        assertTrue(composite.contains("POOLED_LIGHT_BYTES = 48L"));
        String record = between(common, "public struct PooledLight {", "};");
        // float3 + float + six uints: 12 + 4 + 24 = 40, padded by std430's float3 alignment to 48.
        assertTrue(record.contains("float3 pos;"));
        assertTrue(record.contains("float  pdf;"));
        assertTrue(record.contains("uint   lightIndex;"));
    }

    /**
     * A pooled surface sample has to name its light, and this is not spare bookkeeping.
     *
     * <p>Surface samples go into ReSTIR reservoirs, and storedLightStillPresent re-loads Light by the
     * stored index a frame later to decide whether the sample may be reused. A slot that could not name
     * its light would validate reuse against whatever now sits at some other index -- not a crash, but
     * last frame's light wearing this frame's geometry.
     */
    @Test
    void aPooledSurfaceSampleCarriesTheLightIndexTheReservoirWillRevalidate() throws IOException {
        String build = shader("light_pool.comp.slang");
        String lighting = shader("lighting.slang");

        assertTrue(build.contains("out.lightIndex = lightIndex;"));
        assertTrue(lighting.contains("li = pooled.lightIndex;"));
    }

    /**
     * The pool stores HALF the surface mixture, and the other half is reconstructed rather than looked up.
     *
     * <p>The volume path samples locally or globally and its stored density is the whole answer. The
     * surface path samples alpha*local + (1-alpha)*global, so a pooled candidate that used the slot's
     * density raw would be weighted by the wrong probability -- unbiased sampling with the wrong pdf is
     * just bias. Power is area times luminance and both are already in hand, so the missing half costs no
     * load.
     */
    @Test
    void thePooledSurfaceCandidateRebuildsTheFullMixtureDensity() throws IOException {
        String lighting = shader("lighting.slang");
        assertTrue(lighting.contains(
                "mixedPdf = localProbability * pooledLocalPdf + (1.0 - localProbability) * globalPdf;"));
        // And the walk's own density is still what a non-pooled candidate uses.
        assertTrue(lighting.contains(
                "mixedPdf = proposalPdf(lg, le, gridCell, gridCellCoord, localProbability);"));
    }

    /**
     * Global candidates keep the walk, because the pool has nothing to say about a distant light.
     *
     * <p>The pool is built per cell over its own neighbourhood; the global stratum exists precisely to
     * give lights outside that neighbourhood support. Serving a global candidate from the pool would
     * quietly delete that support and leave distant emitters unsampleable.
     */
    @Test
    void onlyLocalCandidatesComeFromThePool() throws IOException {
        String lighting = shader("lighting.slang");
        assertTrue(lighting.contains("if (useLocal && (worldPush.environmentFlags "
                + "& ENVIRONMENT_LIGHT_POOL_SURFACE) != 0u"));
        assertTrue(lighting.contains("selectLightGridLight(gridCell, useLocal, proposalSeed, li);"));
    }

    /** The surface switch is its own, because slice one's measurement depends on it being separate. */
    @Test
    void theSurfaceSwitchIsSeparateFromTheVolumeOne() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");

        assertTrue(config.contains(
                "\"fluorite.rt.lightPoolSurface\", \"composite.light-pool-surface\", false"));
        // Nested under the pool's own switch: a surface read with no pool built is an address of zero.
        assertTrue(composite.contains("if (FluoriteConfig.Rt.Composite.LIGHT_POOL.value()\n"
                + "                && FluoriteConfig.Rt.Composite.LIGHT_POOL_SURFACE.value())"));
    }

    /** Push constants are scalars, because a vector's alignment already cost this project a session. */
    @Test
    void thePoolBuildPushBlockIsScalarThroughout() throws IOException {
        String build = shader("light_pool.comp.slang");
        // Comments stripped first, because the struct's own banner NAMES the vector types it refuses.
        // Matching raw text would make the explanation of the rule break the rule.
        String push = stripLineComments(between(build, "struct LightPoolPush {", "};"));
        assertFalse(push.contains("int2"));
        assertFalse(push.contains("int3"));
        assertFalse(push.contains("uint2"));
        assertFalse(push.contains("uint3"));
        assertFalse(push.contains("float3"));
        assertTrue(push.contains("uint poolDepth;"));
        assertTrue(push.contains("uint populatedCells;"));
    }

    /** Counts occurrences outside comments, so an explanation of a rule cannot break the rule. */
    private static int countOf(String source, String needle) {
        String bare = stripLineComments(source);
        int n = 0;
        for (int i = bare.indexOf(needle); i >= 0; i = bare.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    private static String stripLineComments(String source) {
        StringBuilder out = new StringBuilder();
        for (String line : source.split("\n", -1)) {
            int comment = line.indexOf("//");
            out.append(comment >= 0 ? line.substring(0, comment) : line).append('\n');
        }
        return out.toString();
    }

    private static String between(String haystack, String start, String end) {
        int a = haystack.indexOf(start);
        assertTrue(a >= 0, () -> "missing: " + start);
        int b = haystack.indexOf(end, a + start.length());
        assertTrue(b > a, () -> "missing terminator after: " + start);
        return haystack.substring(a, b);
    }

    private static String shader(String name) throws IOException {
        return source("shaders/world/" + name);
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from "
                    + System.getProperty("user.dir"));
        }
        // Normalised, because the working tree carries CRLF on this platform while every pattern in
        // this file is written with LF. A contract test that passes or fails on a checkout setting is
        // not testing the contract. Rejoining the lines does it without spelling either terminator.
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}
