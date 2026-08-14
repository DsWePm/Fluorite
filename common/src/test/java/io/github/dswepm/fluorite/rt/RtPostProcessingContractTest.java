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
        assertTrue(options.contains("section.lensEffects"));
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
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        if (root == null) throw new IOException("Could not locate repository root");
        return Files.readString(root.resolve(relativePath));
    }
}
