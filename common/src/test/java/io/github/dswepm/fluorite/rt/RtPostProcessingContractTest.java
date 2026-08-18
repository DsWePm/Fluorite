package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fixed-version and cross-pass contracts for M22.1's display transform and creative grade. */
final class RtPostProcessingContractTest {
    private static final Map<String, String> ACES2_SHA256 = Map.of(
            "aces2_sdr100.glsl", "714735c73eb132e3bb4b04b094a0f0dc106d7aa6036ce2dbae0a52b5e576ce69",
            "aces2_hdr500.glsl", "f56c3fed9c42dbdddf56a8c579400e98360fb13d1b66320c362d768da985bf00",
            "aces2_hdr1000.glsl", "85de0f435a55dcebe66edce2f599ffc44bdf450732b9d46a12bfa6bcab67d1d7",
            "aces2_hdr2000.glsl", "d0e529c6ec10da41751f1463528ed2b7a25be045a56f13b72785a01389fec976",
            "aces2_hdr4000.glsl", "67a1cbe37b539a817c196a83d488e47fa1e86a287ec0e257fb9286cad8e34b4c");

    @Test
    void generatedAces2ModulesRemainPinnedToTheReviewedOfficialTransform() throws Exception {
        for (Map.Entry<String, String> entry : ACES2_SHA256.entrySet()) {
            String shader = source("shaders/display/" + entry.getKey()).replace("\r\n", "\n");
            assertTrue(shader.contains("SPDX-License-Identifier: Apache-2.0"));
            assertTrue(shader.contains("ACES v2.0.0+2025.04.04"));
            assertTrue(shader.contains("[363]"));
            assertFalse(shader.contains("sampler1D"));
            assertFalse(shader.contains("texture("));
            assertTrue(entry.getValue().equals(sha256(shader)),
                    entry.getKey() + " changed without regenerating/reviewing its official tables");
        }
    }

    @Test
    void displayPassKeepsMeteringBeforeGradeAndSelectsStrictAcesPresets() throws IOException {
        String shader = source("shaders/display/display_common.glsl");
        String fastShader = source("shaders/display/display.comp");
        String exactShader = source("shaders/display/display_aces_exact.comp");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String options = source("common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        String categoryScreen = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/gui/RtCategoryScreen.java");
        String lensScreen = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/gui/RtLensEffectsScreen.java");

        assertTrue(fastShader.contains("#define FLUORITE_ACES_EXACT 0"));
        assertTrue(exactShader.contains("#define FLUORITE_ACES_EXACT 1"));
        assertTrue(shader.contains("pc.outputTransform == 1 ? aces2SdrLutSample(exposed) : agx(exposed)"));
        assertTrue(shader.contains("vec3 ldr = aces2SdrExact(exposed)"));
        assertTrue(shader.contains("pc.gradingEnabled != 0"));
        assertTrue(shader.contains("fluoriteAces2Hdr500"));
        assertTrue(shader.contains("fluoriteAces2Hdr1000"));
        assertTrue(shader.contains("fluoriteAces2Hdr2000"));
        assertTrue(shader.contains("fluoriteAces2Hdr4000"));
        assertTrue(shader.contains("nits2020") && shader.contains("* 100.0"));
        assertTrue(composite.indexOf("exposure.record") < composite.indexOf("displayPipeline.dispatch"));
        assertTrue(options.contains("POST_PROCESSING(\"postProcessing\")"));
        assertFalse(options.contains("EXPOSURE(\"exposure\")"));
        assertFalse(options.contains("HDR(\"hdr\")"));
        assertTrue(options.contains("case POST_PROCESSING -> List.of("));
        assertTrue(options.contains("lensEffectsSections()"));
        assertTrue(categoryScreen.contains("new RtArtisticGradingScreen"));
        assertTrue(categoryScreen.contains("new RtLensEffectsScreen"));
        assertTrue(categoryScreen.contains("List.of(artisticGrading, lensEffects)"));
        assertTrue(lensScreen.contains("RtVideoOptions.lensEffectsSections()"));
    }

    @Test
    void exposureUsesHistogramPercentilesAndEvDomainHumanAdaptation() throws IOException {
        String resolve = source("shaders/display/exposure_resolve.comp");
        String exposure = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtExposure.java");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String options = source("common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(resolve.contains("float(total) * 0.50"));
        assertTrue(resolve.contains("float(total) * 0.95"));
        assertTrue(resolve.contains("float targetEv = log2(max(pc.key, 1.0e-6)) + pc.evBias - avgLogLum"));
        assertTrue(resolve.contains("targetEv < previousEv ? pc.brightAdaptSeconds : pc.darkAdaptSeconds"));
        assertTrue(resolve.contains("1.0 - exp(-pc.frameTimeSeconds / max(timeConstant"));
        assertTrue(resolve.contains("stateBuf.initialized == 0u ? 1.0"));
        assertTrue(resolve.contains("float exposure = exp2(exposureEv)"));

        assertTrue(exposure.contains("Manual mode is an absolute EV"));
        assertTrue(exposure.contains("Rt.Exposure.AUTO_EV_BIAS.value()"));
        assertTrue(exposure.contains("Rt.Exposure.MANUAL_EV.value()"));
        assertTrue(config.contains("exposure.auto-ev-bias\", 0.0f, -5.0f, 5.0f"));
        assertTrue(config.contains("exposure.bright-adaptation-seconds\", 0.25f, 0.05f, 5.0f"));
        assertTrue(config.contains("exposure.dark-adaptation-seconds\", 1.5f, 0.1f, 10.0f"));
        assertTrue(config.contains("AUTO_EV_BIAS.set(MANUAL_EV.value())"));
        assertTrue(config.contains("FILE.remove(\"exposure.adapt-up\")"));
        assertTrue(options.contains("autoExposureCompensation()"));
        assertTrue(options.contains("manualExposureEv()"));
        assertTrue(options.contains("brightAdaptationTime()"));
        assertTrue(options.contains("darkAdaptationTime()"));
    }

    @Test
    void fastAcesPathUsesFiveResidentLogShapedHalfFloatLuts() throws IOException {
        String shader = source("shaders/display/display_common.glsl");
        String bake = source("shaders/display/aces2_lut_bake.comp");
        String luts = source("common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtAces2Luts.java");
        String pipeline = source("common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtDisplayPipeline.java");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(shader.contains("layout(binding = 4, set = 0) uniform sampler3D acesSdrLut"));
        assertTrue(shader.contains("layout(binding = 8, set = 0) uniform sampler3D acesHdr4000Lut"));
        assertTrue(shader.contains("const float ACES2_SHAPER_MIN_EV = -16.0"));
        assertTrue(shader.contains("const float ACES2_SHAPER_MAX_EV = 16.0"));
        assertTrue(shader.contains("const float ACES2_LUT_SIZE = 65.0"));
        assertTrue(shader.contains("return (index + 0.5) / ACES2_LUT_SIZE"));

        assertTrue(bake.contains("layout(binding = 0, set = 0, rgba16f)"));
        assertTrue(bake.contains("if (index == 0) return 0.0"));
        assertTrue(bake.contains("imageStore(outputLut"));
        assertTrue(luts.contains("static final int LUT_SIZE = 65"));
        assertTrue(luts.contains("static final int LUT_COUNT = 5"));
        assertTrue(luts.contains("VK10.VK_FORMAT_R16G16B16A16_SFLOAT"));
        assertTrue(luts.contains("VK10.VK_FILTER_LINEAR"));
        assertTrue(pipeline.contains("outputTransformMode == 2 ? exactPipeline : fastPipeline"));
        assertTrue(config.contains("case \"aces2-lut\" -> 1"));
        assertTrue(config.contains("case \"aces2-exact\" -> 2"));
    }

    @Test
    void approvedLensEffectsStaySceneLinearDepthAwareAndSingleScratch() throws IOException {
        String lens = source("shaders/display/lens_spatial.comp");
        String dof = source("shaders/display/depth_of_field.comp");
        String display = source("shaders/display/display_common.glsl");
        String pipeline = source("common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtLensPipeline.java");
        String dofPipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtDepthOfFieldPipeline.java");
        String displayPipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtDisplayPipeline.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");

        assertTrue(lens.contains("mode 1: depth-aware motion blur"));
        assertTrue(lens.contains("pc.inverseProjection * vec4(ndc, hardwareDepth, 1.0)"));
        assertTrue(lens.contains("imageLoad(motionImage, guide).rg"));
        assertTrue(lens.contains("for (int i = 0; i < 16; ++i)"));
        assertTrue(lens.contains("/ 0.35"));
        assertFalse(lens.contains("srgb"));
        assertFalse(lens.contains("pqEncode"));

        assertTrue(dof.contains("float signedCircleOfConfusion"));
        assertTrue(dof.contains("side * clamp(radiusPixels"));
        assertTrue(dof.contains("layout(binding = 4, set = 0, r16f)"));
        assertTrue(dof.contains("classifyTile"));
        assertTrue(dof.contains("dilateTile"));
        assertTrue(dof.contains("const int GATHER_SAMPLES = 24"));
        assertTrue(dof.contains("PrefilterSample prefilter2x2"));
        assertTrue(dof.contains("nearImage"));
        assertTrue(dof.contains("farImage"));
        assertTrue(dof.contains("nearLayer.a"));
        assertTrue(dof.contains("apertureBoundary"));
        assertFalse(dof.contains("srgb"));
        assertFalse(dof.contains("pqEncode"));

        assertTrue(pipeline.contains("RR_TO_SCRATCH = 0"));
        assertTrue(pipeline.contains("SCRATCH_TO_RR = 1"));
        assertTrue(pipeline.contains("stack.longs(dsl, dsl)"));
        assertTrue(dofPipeline.contains("stack.longs(dsl, dsl)"));
        assertTrue(dofPipeline.contains("VulkanCommandEncoder.memoryBarrier"));
        assertTrue(composite.contains("private RtImage lensScratch"));
        assertFalse(composite.contains("private RtImage lensScratch2"));
        assertTrue(composite.contains("private RtImage depthOfFieldCoc"));
        assertTrue(composite.contains("VK10.VK_FORMAT_R16_SFLOAT"));
        assertTrue(composite.contains("depthOfFieldPipeline.record"));
        assertTrue(composite.contains("\"guide reversed-Z depth \""));
        assertTrue(composite.indexOf("exposure.record(ctx, cmd, stack, rrOutput)")
                < composite.indexOf("lensPipeline.motionBlur"));
        assertTrue(composite.indexOf("depthOfFieldPipeline.record")
                < composite.indexOf("displayPipeline.dispatch"));
        assertTrue(composite.contains("GPU_ZONE_LENS_SPATIAL"));
        assertTrue(composite.contains("GPU_ZONE_DISPLAY_MAP"));

        assertTrue(display.contains("layout(binding = 9, set = 0) uniform sampler2D rtLinear"));
        assertTrue(display.contains("sceneLinearWithLensEffects(pix, size) * exposure"));
        assertTrue(display.contains("vec2 lensDistortionUv(vec2 uv, ivec2 size)"));
        assertTrue(display.contains("float k1 = -0.15 * strength"));
        assertTrue(display.contains("float k2 = -0.05 * strength"));
        assertTrue(display.contains("float cropScale = max(1.0, 1.0 + k1 + k2)"));
        assertTrue(display.contains("vec2(aspect, 1.0)"));
        assertTrue(display.contains("textureLod(rtLinear, sceneUv + offset"));
        assertTrue(display.contains("pc.vignetteIntensity"));
        assertTrue(displayPipeline.contains("PUSH_BYTES = 32 * Integer.BYTES"));
        assertTrue(displayPipeline.contains("push.putInt(72, lensDistortionEnabled ? 1 : 0)"));
        assertTrue(displayPipeline.contains("push.putFloat(76, lensDistortionStrength)"));

        assertTrue(config.contains("depth-of-field.focus-distance\", 10.0f, 0.5f, 256.0f"));
        assertTrue(config.contains("depth-of-field.f-stop\", 4.0f, 0.7f, 32.0f"));
        assertTrue(config.contains("depth-of-field.aperture-blades"));
        assertTrue(config.contains("motion-blur.shutter-angle\", 180.0f, 0.0f, 360.0f"));
        assertTrue(config.contains("post-processing.lens.distortion.strength\", 0.0f, -1.0f, 1.0f"));
        assertTrue(config.contains("chromatic-aberration.strength-px\", 0.0f, 0.0f, 8.0f"));
    }

    @Test
    void bloomAndPentagonalLensFlareUseIndependentVisibleHdrFilters() throws IOException {
        String shader = source("shaders/display/bloom_flare.comp");
        String pipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtBloomFlarePipeline.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String options = source("common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(shader.contains("uniform readonly image2D sourceImage"));
        assertTrue(shader.contains("uniform readonly image2D exposureImage"));
        assertTrue(shader.contains("float brightness = max(max(sceneLinear.r, sceneLinear.g), sceneLinear.b) * exposure"));
        assertTrue(shader.contains("10 composites optional bloom/lens flare"));
        assertTrue(shader.contains("if ((pc.flags & 1) != 0)"));
        assertTrue(shader.contains("if ((pc.flags & 2) != 0)"));
        assertTrue(shader.contains("const ivec2 dx = ivec2(1, 0)"));
        assertTrue(shader.contains("const ivec2 dy = ivec2(0, 1)"));
        assertFalse(shader.contains("int(round(radius))"));
        assertTrue(shader.contains("layout(binding = 13, set = 0, rgba16f) uniform image2D flareBokehImage"));
        assertTrue(shader.contains("return extractHighlight(centre, pc.flareThreshold) * isolation"));
        assertTrue(shader.contains("bool insidePentagon"));
        assertTrue(shader.contains("vec3 pentagonalBokeh"));
        assertTrue(shader.contains("if (pc.mode == 11)"));
        assertTrue(shader.contains("if (pc.mode == 12)"));
        assertTrue(shader.contains("flareBokehMasked(sourceUv)"));
        assertFalse(shader.toLowerCase().contains("srgb"));
        assertFalse(shader.contains("pqEncode"));

        assertTrue(pipeline.contains("LEVEL_COUNT = 5"));
        assertTrue(pipeline.contains("BINDING_COUNT = 4 + LEVEL_COUNT * 2"));
        assertTrue(pipeline.contains("PUSH_BYTES = 56"));
        assertTrue(pipeline.contains("stack.longs(dsl, dsl)"));
        assertTrue(pipeline.contains("dispatch(cmd, stack, push, 11"));
        assertTrue(pipeline.contains("dispatch(cmd, stack, push, 12"));
        assertTrue(pipeline.contains("VulkanCommandEncoder.memoryBarrier"));
        assertTrue(composite.contains("private final RtImage[] bloomBright"));
        assertTrue(composite.contains("private final RtImage[] bloomPyramid"));
        assertTrue(composite.contains("private RtImage flareBokeh"));
        assertFalse(composite.contains("private RtImage lensScratch2"));
        assertTrue(composite.indexOf("depthOfFieldPipeline.record")
                < composite.indexOf("bloomFlarePipeline.record"));
        assertTrue(composite.indexOf("bloomFlarePipeline.record")
                < composite.indexOf("displayPipeline.dispatch"));
        assertTrue(composite.contains("GPU_ZONE_BLOOM_FLARE"));
        assertTrue(composite.contains("frame.bloomFlare"));

        assertTrue(config.contains("post-processing.highlights.threshold\", 1.0f, 0.0f, 16.0f"));
        assertTrue(config.contains("post-processing.bloom.enabled\", false"));
        assertTrue(config.contains("post-processing.lens-flare.enabled\", false"));
        assertTrue(config.contains("post-processing.lens-flare.threshold\", 4.0f, 0.0f, 32.0f"));
        assertTrue(config.contains("post-processing.lens-flare.bokeh-size-px\", 12.0f, 2.0f, 32.0f"));
        assertTrue(options.contains("section.hdrHighlights"));
        assertTrue(options.contains("section.bloom"));
        assertTrue(options.contains("section.lensFlare"));
        assertTrue(options.contains("lensFlareThreshold()"));
        assertTrue(options.contains("lensFlareBokehSize()"));
    }

    /**
     * bloom1 is TWO things — the bloom pyramid's level 1, and the flare seed's scratch — and only the
     * dispatch order keeps that from mattering.
     *
     * <p>The pyramid is built coarse to fine and each level reads the one below it, so bloom0 is built
     * FROM bloom1; the flare then overwrites bloom1 with its own isolation-weighted seed. Today that is
     * safe for two reasons and no others: mode 11 is dispatched after the pyramid finishes, and the
     * composite samples level 0 alone. Break either and bloom silently takes its shape from the flare
     * seed whenever the flare is on and looks correct whenever it is off — a setting-dependent artefact
     * with no error attached, and the most natural edit in this file (widening bloom by blending level 1)
     * is exactly the one that trips it.
     *
     * <p>Not a live defect. Pinned because nothing in either file says the buffer is shared.
     */
    @Test
    void theFlareSeedOverwritesBloomLevelOneAndMayOnlyDoSoAfterThePyramidIsBuilt() throws IOException {
        String shader = source("shaders/display/bloom_flare.comp");
        String pipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtBloomFlarePipeline.java");

        // The aliasing itself: the flare seed lands in the pyramid's level 1.
        assertTrue(shader.contains("imageStore(bloom1, pixel, vec4(flareSeed(pixel), 1.0))"));
        assertTrue(shader.contains("imageLoad(bloom1, samplePixel).rgb * tapWeight"),
                "and the bokeh pass is the only thing meant to read it back");

        // Why bloom0 depends on it: each level is seeded by the coarser one.
        assertTrue(shader.contains("+ bilinearBloom(level + 1, uv).rgb * propagation"));

        // The composite reads level 0 ALONE. A blend with level 1 here would pick up the flare seed.
        assertTrue(shader.contains("scene += bilinearBloom(0, uv).rgb * max(pc.bloomIntensity, 0.0);"));
        assertFalse(shader.contains("bilinearBloom(1, uv)"),
                "sampling level 1 at composite time would read the flare seed, but only with flare on");

        // And the order that makes all of the above safe.
        assertTrue(pipeline.indexOf("for (int mode = 6; mode <= 9; mode++)")
                        < pipeline.indexOf("dispatch(cmd, stack, push, 11"),
                "the pyramid must be finished before its level 1 is repurposed");
        assertTrue(pipeline.indexOf("dispatch(cmd, stack, push, 12")
                        < pipeline.indexOf("dispatch(cmd, stack, push, 10"),
                "and the bokeh must be built before the composite reads it");
    }

    @Test
    void artisticGradeUsesDirtyBakedSceneLinearLutAndFilmGrainStaysBeforeOutput() throws IOException {
        String display = source("shaders/display/display_common.glsl");
        String gradeBake = source("shaders/display/creative_grading_lut.comp");
        String gradeLut = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtCreativeGradingLut.java");
        String grainBake = source("shaders/display/film_grain_noise_bake.comp");
        String pipeline = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/pipeline/RtDisplayPipeline.java");
        String options = source("common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");

        assertTrue(display.contains("layout(binding = 10, set = 0) uniform sampler3D creativeGradingLut"));
        assertTrue(display.contains("layout(binding = 11, set = 0) uniform sampler2D filmGrainNoise"));
        assertTrue(display.contains("texture(creativeGradingLut, aces2LutCoord(exposed))"));
        assertTrue(display.indexOf("exposed = applyFilmGrain") < display.indexOf("aces2SdrExact(exposed)"));
        assertTrue(gradeBake.contains("layout(binding = 0, set = 0, rgba16f)"));
        assertTrue(gradeBake.contains("shadowWeight"));
        assertTrue(gradeBake.contains("midWeight"));
        assertTrue(gradeBake.contains("highlightWeight"));
        assertTrue(gradeLut.contains("grade.equals(lastGrade)"));
        assertTrue(gradeLut.contains("ctx.waitIdle()"));
        assertTrue(gradeLut.contains("LUT_SIZE = 65"));
        assertTrue(pipeline.contains("creativeGradingLut.recordIfDirty"));
        assertTrue(pipeline.contains("VulkanCommandEncoder.memoryBarrier"));
        assertTrue(grainBake.contains("RANK_8X8[64]"));
        assertTrue(display.contains("filmGrainShadows"));
        assertTrue(display.contains("filmGrainMidtones"));
        assertTrue(display.contains("filmGrainHighlights"));
        assertTrue(display.contains("filmGrainChromatic"));
        assertTrue(display.contains("if (chromatic > 0.0)"));
        assertTrue(display.contains("vec3 grainNoise = vec3(sharedNoise)"));
        assertTrue(display.contains("textureLod(filmGrainNoise"));
        assertTrue(pipeline.contains("push.putFloat(116, filmGrain.chromaticSeparation())"));
        assertTrue(options.contains("artisticGradingSections"));
        assertTrue(options.contains("filmGrainChromatic()"));
        assertTrue(options.contains("SDR") || options.contains("hdrEnabled"));
    }

    @Test
    void approvedLensDistortionCurveStaysMonotonicAndInsideItsSourceDomain() {
        for (int strengthStep = -100; strengthStep <= 100; strengthStep++) {
            double strength = strengthStep / 100.0;
            double k1 = -0.15 * strength;
            double k2 = -0.05 * strength;
            double cropScale = Math.max(1.0, 1.0 + k1 + k2);
            double previous = 0.0;
            for (int radiusStep = 0; radiusStep <= 1000; radiusStep++) {
                double radius = radiusStep / 1000.0;
                double radius2 = radius * radius;
                double mapped = radius * (1.0 + k1 * radius2 + k2 * radius2 * radius2) / cropScale;
                assertTrue(mapped >= previous - 1.0e-12,
                        "radial lookup folded at strength=" + strength + ", radius=" + radius);
                assertTrue(mapped >= -1.0e-12 && mapped <= 1.0 + 1.0e-12,
                        "automatic crop escaped source domain at strength=" + strength + ", radius=" + radius);
                previous = mapped;
            }
        }
    }

    @Test
    void everyCompositeGpuTimerZoneIsRegisteredInTheFrameProfile() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String frameStats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtFrameStats.java");
        int timerStart = composite.indexOf("RtGpuTimers.create(ctx");
        int timerEnd = composite.indexOf(");", timerStart);
        assertTrue(timerStart >= 0 && timerEnd > timerStart);
        String timerDeclaration = composite.substring(timerStart, timerEnd);
        for (String fragment : timerDeclaration.split("\"")) {
            if (fragment.startsWith("gpu.")) {
                assertTrue(frameStats.contains("\"" + fragment + "\""),
                        fragment + " resolves asynchronously into RtFrameStats and must be registered there");
            }
        }
    }

    @Test
    void everyDirectFrameStageScopeIsRegisteredInTheFrameProfile() throws IOException {
        String frameStats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtFrameStats.java");
        java.util.regex.Pattern stageCall = java.util.regex.Pattern.compile(
                "RtFrameStats\\.FRAME\\.stage\\(\\s*\"([^\"]+)\"");
        Path javaRoot = repositoryRoot().resolve("common/src/main/java");
        java.util.List<String> missing = new java.util.ArrayList<>();
        try (var paths = Files.walk(javaRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java")).toList()) {
                var matcher = stageCall.matcher(Files.readString(path));
                while (matcher.find()) {
                    String stageName = matcher.group(1);
                    if (!frameStats.contains("\"" + stageName + "\"")) {
                        missing.add(stageName + " used by " + javaRoot.relativize(path));
                    }
                }
            }
        }
        assertTrue(missing.isEmpty(),
                "Direct frame scopes must be registered before their conditional paths run: " + missing);
    }

    @Test
    void finalDisplayEncodingMatchesOfficialOcioReferencePixels() {
        // ACES 2 Output Transform XYZ values generated by OCIO 2.5.2 from official ACES 2 built-ins.
        assertArrayEquals(new double[]{0.34918761, 0.34918812, 0.34918788},
                encodeSdr(new double[]{0.09504490, 0.09999935, 0.10890502}), 5.0e-6);
        assertArrayEquals(new double[]{0.76986384, 0.09199714, 0.04260050},
                encodeSdr(new double[]{0.23221964, 0.12435378, 0.01490075}), 5.0e-6);
        assertArrayEquals(new double[]{0.32983702, 0.32983717, 0.32983705},
                encodeHdr(new double[]{0.13792588, 0.14511561, 0.15803918}), 2.0e-6);
        assertArrayEquals(new double[]{0.45211667, 0.25336164, 0.15173797},
                encodeHdr(new double[]{0.37002161, 0.18590945, 0.01250463}), 2.0e-6);
    }

    private static double[] encodeSdr(double[] xyz) {
        double[] rgb = multiply(new double[][]{
                {3.2409699419, -1.5373831776, -0.4986107603},
                {-0.9692436363, 1.8759675015, 0.0415550574},
                {0.0556300797, -0.2039769589, 1.0569715142}}, xyz);
        for (int i = 0; i < 3; i++) {
            double x = Math.max(rgb[i], 0.0);
            rgb[i] = x <= 0.0031308 ? 12.92 * x : 1.055 * Math.pow(x, 1.0 / 2.4) - 0.055;
        }
        return rgb;
    }

    private static double[] encodeHdr(double[] xyz) {
        double[] rgb = multiply(new double[][]{
                {1.7166511880, -0.3556707838, -0.2533662814},
                {-0.6666843518, 1.6164812366, 0.0157685458},
                {0.0176398574, -0.0427706133, 0.9421031212}}, xyz);
        for (int i = 0; i < 3; i++) {
            double y = Math.pow(Math.max(rgb[i] * 100.0, 0.0) / 10000.0, 0.1593017578125);
            rgb[i] = Math.pow((0.8359375 + 18.8515625 * y) / (1.0 + 18.6875 * y), 78.84375);
        }
        return rgb;
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                result[row] += matrix[row][column] * vector[column];
            }
        }
        return result;
    }

    private static String sha256(String value) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private static String source(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath));
    }

    private static Path repositoryRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        if (root == null) throw new IOException("Could not locate repository root");
        return root;
    }
}
