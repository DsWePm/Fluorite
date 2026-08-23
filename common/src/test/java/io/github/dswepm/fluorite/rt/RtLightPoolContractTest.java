package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        assertTrue(build.contains("out.pdf = pdf;"));

        String pooled = between(sampling, "public bool sampleVolumeEmitterPooled(",
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
     * An empty slot falls back to the walk rather than being retried, and the reason is the estimator.
     *
     * <p>The build records an empty slot whenever it drew a zero-power or out-of-neighbourhood light,
     * which is exactly what emitterProposalPdf refuses to paper over with a global density. Redrawing
     * such a slot against the grid would resample a distribution that had already been sampled.
     */
    @Test
    void anEmptySlotIsRecordedRatherThanRedrawn() throws IOException {
        String build = shader("light_pool.comp.slang");
        String sampling = shader("light_sampling.slang");

        assertTrue(build.contains("if (!(pdf > 0.0))"));
        assertTrue(build.contains("pool[slotIndex] = empty;"));
        assertTrue(sampling.contains("if (!(pooled.pdf > 0.0))"));
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
        assertTrue(record.contains("uint   reserved;"));
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
