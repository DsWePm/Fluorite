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

    @Test
    void d68ContrastRemapIsOddBoundedAndKeepsMeanOne() throws IOException {
        String source = shader("volume_source.slang");
        String config = repositoryFile(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(config.contains("FOG_NOISE_ENABLED"));
        assertTrue(config.contains("FOG_NOISE_CONTRAST"));
        assertTrue(source.contains("(contrast * variation)"
                + " / (1.0 + (contrast - 1.0) * abs(variation))"));

        for (float contrast : new float[]{0.25f, 1f, 2f, 4f}) {
            for (int i = -1000; i <= 1000; ++i) {
                float x = i / 1000.0f;
                float positive = runtimeMultiplier(x, contrast);
                float negative = runtimeMultiplier(-x, contrast);
                assertTrue(positive >= 0f && positive <= 2f);
                assertEquals(2f, positive + negative, 1.0e-6f);
            }
        }
        assertEquals(1.55f, runtimeMultiplier(0.55f, 1f), 1.0e-6f);
        assertTrue(runtimeMultiplier(0.55f, 4f) > runtimeMultiplier(0.55f, 1f));
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

    @Test
    void debugView25SeparatesTextureResolutionAndFinalMultiplier() throws IOException {
        String source = shader("volume_source.slang");
        String volume = shader("volume.slang");
        String raygen = shader("world.rgen.slang");
        String options = repositoryFile(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(source.contains("volumeFogNoiseResolved"));
        assertTrue(volume.contains("ambientNoiseDebugSample"));
        assertTrue(raygen.contains("DEBUG_VIEW_FOG_NOISE = 25u"));
        assertTrue(raygen.contains("fogNoiseDebug(dispatchIndex, dimensions)"));
        assertTrue(raygen.contains("band < 1u"));
        assertTrue(raygen.contains("band < 2u"));
        assertTrue(raygen.contains("band < 3u"));
        // 25 must remain selectable, but later diagnostics may legitimately extend the upper bound.
        assertTrue(Pattern.compile("List\\.of\\([^;]*\\b25\\s*,", Pattern.DOTALL)
                .matcher(options).find());
    }

    private static int encodePairedUnorm(float paired, boolean canonicalHalf) {
        int magnitudeCode = Math.round(Math.clamp(Math.abs(paired), 0.0f, 1.0f) * 127.0f);
        boolean positive = paired > 0.0f || (paired == 0.0f && canonicalHalf);
        return positive ? 128 + magnitudeCode : 127 - magnitudeCode;
    }

    private static float runtimeMultiplier(float variation, float contrast) {
        float shaped = contrast <= 1f
                ? contrast * variation
                : contrast * variation / (1f + (contrast - 1f) * Math.abs(variation));
        return 1f + Math.clamp(shaped, -1f, 1f);
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
