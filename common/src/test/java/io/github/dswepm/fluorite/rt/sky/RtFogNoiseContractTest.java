package io.github.dswepm.fluorite.rt.sky;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Structural guardrails for the approved D62-D67 heterogeneous-fog contract. */
final class RtFogNoiseContractTest {
    @Test
    void densityFieldIsPerHeightMeanOneAndKeepsThePublishedBindings() throws IOException {
        String bake = shader("fog_noise.comp.slang");
        String volume = shader("volume.slang");
        String world = shader("world_common.slang");

        // The xz half-period pair is zero-mean independently on every y slice. Shifting y would only
        // conserve the unweighted 3D tile and let the exponential height profile bias the result.
        assertTrue(bake.contains("float3(0.5, 0.0, 0.5)"));
        assertFalse(bake.contains("float3(0.5, 0.5, 0.5)"));
        assertTrue(bake.contains("FOG_NOISE_DIM = 128"));

        // Water's long-lived binding remains 18; M13 appends its raygen-only field at 19. M14 then
        // appends environment radiance/transfer/disk at 20/21/22 without reinterpreting prior slots.
        assertTrue(volume.contains("[[vk::binding(19, 0)]] Sampler3D fogNoise"));
        String environment = shader("environment.slang");
        assertTrue(environment.contains("[[vk::binding(20, 0)]] public Sampler2DArray environmentRadiance"));
        assertTrue(environment.contains("[[vk::binding(21, 0)]] public Sampler2DArray environmentTransfer"));
        assertTrue(environment.contains("[[vk::binding(22, 0)]] public Sampler2DArray environmentDisk"));
        assertTrue(world.contains("public float4   fogNoiseOrigin"));
    }

    @Test
    void disabledStructureOrZeroAuthoredDensityExitsBeforeEveryFogTextureFetch() throws IOException {
        String source = shader("volume_source.slang");
        String volume = shader("volume.slang");
        String froxel = shader("sky_froxel.comp.slang");

        assertTrue(source.contains("return fogParams.x > 0.0 && fogAmbient.x > 0.0"));
        assertTrue(source.contains("VOLUME_FOG_MARCH_LIMIT = 31"));
        assertTrue(volume.contains("layout.steps = min(layout.steps, int3(cap, cap, cap))"));

        int volumeGuard = volume.indexOf("if (!ambientNoiseOn())");
        int volumeFetch = volume.indexOf("fogNoise.SampleLevel", volumeGuard);
        assertTrue(volumeGuard >= 0 && volumeFetch > volumeGuard);

        int froxelGuard = froxel.indexOf("if (!volumeFogNoiseOn(wp.fogParams, wp.fogAmbient))");
        int froxelFetch = froxel.indexOf("fogNoise.SampleLevel", froxelGuard);
        assertTrue(froxelGuard >= 0 && froxelFetch > froxelGuard);
    }

    /**
     * The contrast remap after the two-times ceiling was given up on purpose.
     *
     * <p>D68A's remap was odd and bounded, so the multiplier lived in 0..2 with the field mean pinned at
     * one exactly. That pinning was structural rather than incidental: the baked field is PAIRED, every
     * sample having a partner with exactly the opposite variation, so holding the mean at one for every
     * v forces f(v) + f(-v) = 2 and therefore f &lt;= 2. Two was the ceiling, not a tuning limit the
     * slider had failed to reach -- raising contrast from 1 to 4 only pushed the distribution toward a
     * binary 0-or-2 field, which is why a fog bank that reads as a wall was unreachable at any setting.
     *
     * <p>What this now pins is the replacement's three load-bearing properties. The old assertions are
     * deliberately absent: asserting a bound this change exists to remove would be pinning the defect.
     */
    @Test
    void contrastRemapIsExponentialWithItsOwnMeanDividedOut() throws IOException {
        String source = shader("volume_source.slang");
        String config = repositoryFile(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(config.contains("FOG_NOISE_ENABLED"));
        assertTrue(config.contains("FOG_NOISE_CONTRAST"));
        assertTrue(source.contains("exp(contrast * variation)"));
        assertTrue(source.contains("volumeExpMean(contrast * baseWeight)"));
        assertTrue(source.contains("volumeExpMean(contrast * detailWeight)"));

        for (float contrast : new float[]{0.25f, 1f, 2f, 4f}) {
            // THE PEAK IS 2c / (1 - exp(-2c)), which is a real bound rather than a clamp: the resolved
            // weights sum to at most one and each decoded channel is within +-1, so |variation| <= 1 by
            // construction. exp(contrast) is the numerator alone and NOT the answer -- the normalisation
            // divides it back down, and the difference is large: at contrast 4 the peak is 8x, not 55x.
            // Written out here because that arithmetic was got wrong once already, in a tooltip.
            double peak = 2.0 * contrast / (1.0 - Math.exp(-2.0 * contrast));
            assertEquals(peak, runtimeMultiplier(1f, 1f, contrast), 1.0e-4f);
            assertTrue(runtimeMultiplier(1f, 1f, contrast) >= 1f);
            // Grows about linearly in contrast PAST 2, so the slider's reach is 2x its top value. Not
            // below it: at 0.25 the peak is 1.27, nowhere near 0.5, because the exponential is still in
            // its near-linear region there and the normalisation has almost nothing to divide out.
            if (contrast >= 2f) {
                assertTrue(peak > 1.9 * contrast && peak < 2.1 * contrast);
            }
            assertTrue(peak >= 1.0);

            // A FADED FREQUENCY RETURNS TO THE AUTHORED MEAN, whatever the contrast. Both the exponent
            // and the normalisation go to one with the weight, which is the property a constant
            // normalisation would have destroyed -- the horizon would shift density every time the
            // structure slider moved.
            assertEquals(1f, runtimeMultiplier(0f, 0f, contrast), 1.0e-6f);
            assertEquals(1f, runtimeMultiplier(0.9f, 0f, contrast), 1.0e-6f);

            // Monotone in the variation, so more structure never reads as less.
            float previous = -1f;
            for (int i = -1000; i <= 1000; ++i) {
                float m = runtimeMultiplier(i / 1000.0f, 1f, contrast);
                assertTrue(m > 0f && m >= previous);
                previous = m;
            }

            // The normalisation's own claim: against the uniform reference it divides by, the mean is
            // one. The real field is more saturated than uniform -- the bake multiplies by 3.8 and
            // clamps -- so the live mean rides somewhat above this. That residual is documented at the
            // remap and is why the fog density knob is the intended trim.
            double sum = 0;
            int n = 20001;
            for (int i = 0; i < n; ++i) {
                sum += runtimeMultiplier((float) (-1.0 + 2.0 * i / (n - 1)), 1f, contrast);
            }
            assertEquals(1.0, sum / n, 2.0e-3);
        }

        // Contrast zero is exactly height fog, so switching the structure off stays exact.
        assertEquals(1f, runtimeMultiplier(0.55f, 1f, 0f), 1.0e-6f);

        // WHAT CONTRAST DOES IS WIDEN THE SPREAD, which is not the same as raising every sample. A
        // moderately-above-average voxel gets LESS multiplier at higher contrast, not more -- at v=0.55
        // the answer falls from 1.47 at contrast 1 to 1.32 at contrast 4 -- because the mass moves to
        // the extremes and the normalisation that keeps the mean in place takes the middle down with it.
        // The crossover sits at v = ln(Z(c))/c, about 0.48 at contrast 4. Asserting "higher contrast is
        // brighter here" instead would pin a misreading of the curve; peak over trough is the property
        // that actually monotonically grows, and it grows as exp(2c).
        for (float contrast : new float[]{0.25f, 1f, 2f, 4f}) {
            double spread = runtimeMultiplier(1f, 1f, contrast) / runtimeMultiplier(-1f, 1f, contrast);
            assertEquals(Math.exp(2.0 * contrast), spread, spread * 1.0e-5);
        }
        assertTrue(runtimeMultiplier(1f, 1f, 4f) > runtimeMultiplier(1f, 1f, 1f));
    }

    @Test
    void d67BakeContrastIsOddAndRemainsPairedAfterUnormQuantisation() throws IOException {
        String bake = shader("fog_noise.comp.slang");
        float baseContrast = shaderFloatConstant(bake, "FOG_BASE_CONTRAST");
        float detailContrast = shaderFloatConstant(bake, "FOG_DETAIL_CONTRAST");

        assertEquals(3.8f, baseContrast);
        assertEquals(3.5f, detailContrast);
        assertTrue(bake.contains("fogContrast(fogPaired(p, 4, 4), FOG_BASE_CONTRAST)"));
        assertTrue(bake.contains("fogContrast(fogPaired("));
        assertTrue(bake.contains("FOG_DETAIL_CONTRAST)"));
        assertTrue(bake.contains("bool canonicalHalf = id.x < uint(FOG_NOISE_DIM / 2)"));
        assertTrue(bake.contains("fogEncodePaired(base, canonicalHalf)"));
        assertTrue(bake.contains("fogEncodePaired(detail, canonicalHalf)"));

        // clamp(g*x,-1,1) is odd. The explicit signed-code encoder gives the canonical and partner
        // voxels complementary bytes whose sum is exactly 255, so every horizontal pair averages 0.5.
        for (float contrast : new float[]{baseContrast, detailContrast}) {
            for (int i = -1000; i <= 1000; ++i) {
                float x = i / 1000.0f;
                float positive = Math.clamp(contrast * x, -1.0f, 1.0f);
                float negative = Math.clamp(contrast * -x, -1.0f, 1.0f);
                assertEquals(0.0f, positive + negative, 1.0e-6f);
                int encodedPositive = encodePairedUnorm(positive, true);
                int encodedNegative = encodePairedUnorm(negative, false);
                assertEquals(255, encodedPositive + encodedNegative);
            }
        }
    }


    /**
     * The resolved-noise pair stays split even though the view that made it visible is gone.
     *
     * <p>Debug view 25 painted the texture resolution and the final multiplier in separate bands, and it
     * was retired with the atmosphere views. What it was checking is a production property: the sample and
     * the multiplier are two functions, so a change to the ambient response cannot silently become a
     * change to what the texture resolves to. Keeping the assertion without the view is the point --
     * a diagnostic retiring must not take the invariant it demonstrated with it.
     */
    @Test
    void theResolvedNoiseAndItsMultiplierRemainSeparateFunctions() throws IOException {
        String source = shader("volume_source.slang");
        String volume = shader("volume.slang");
        String raygen = shader("world.rgen.slang");

        assertTrue(source.contains("volumeFogNoiseResolved"));
        assertTrue(source.contains("volumeFogNoiseResolvedMultiplier"));
        assertTrue(volume.contains("volumeFogNoiseResolvedMultiplier(sample.xy, sample.zw"));

        // And the retirement is complete: number, painter, and the helper that had no other caller.
        assertFalse(raygen.contains("DEBUG_VIEW_FOG_NOISE"));
        assertFalse(raygen.contains("fogNoiseDebug"));
        assertFalse(volume.contains("ambientNoiseDebugSample"),
                "its only caller was the retired view, so leaving it would be dead shader code");
    }

    private static int encodePairedUnorm(float paired, boolean canonicalHalf) {
        int magnitudeCode = Math.round(Math.clamp(Math.abs(paired), 0.0f, 1.0f) * 127.0f);
        boolean positive = paired > 0.0f || (paired == 0.0f && canonicalHalf);
        return positive ? 128 + magnitudeCode : 127 - magnitudeCode;
    }

    /**
     * The shader's multiplier for a single resolved channel, mirrored here because the JVM cannot run
     * Slang. {@code weight} is that channel's surviving share after the footprint fade.
     */
    private static float runtimeMultiplier(float signedNoise, float weight, float contrast) {
        if (contrast <= 0f) {
            return 1f;
        }
        float variation = signedNoise * weight;
        return (float) (Math.exp(contrast * variation) / expMean(contrast * weight));
    }

    /** E[exp(x*u)] for u uniform on [-1,1]; the series covers the removable singularity at zero. */
    private static double expMean(double x) {
        double a = Math.abs(x);
        if (a < 1.0e-3) {
            return 1.0 + a * a / 6.0;
        }
        return Math.sinh(a) / a;
    }

    private static float shaderFloatConstant(String source, String name) {
        Matcher matcher = Pattern.compile(name + "\\s*=\\s*([0-9.]+)").matcher(source);
        assertTrue(matcher.find(), () -> "Missing shader constant " + name);
        return Float.parseFloat(matcher.group(1));
    }

    private static String shader(String name) throws IOException {
        return repositoryFile("shaders/world/" + name);
    }

    private static String repositoryFile(String path) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(path));
    }
}
