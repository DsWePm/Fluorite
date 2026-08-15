package io.github.dswepm.fluorite.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Builds D163's lighting-free, world-space high-cloud patch optical-depth array. */
public abstract class GenerateHighCloudAssets extends DefaultTask {
    static final int PATCH_SIZE = 1024;
    static final int PATCH_COUNT = 10;
    static final float PATCH_TAU_CAP = 4.0f;

    @InputFile public abstract RegularFileProperty getPatchZip();
    @OutputDirectory public abstract DirectoryProperty getOutDir();

    @TaskAction
    public void generate() throws Exception {
        verifyPinned(getPatchZip().get().getAsFile().toPath(), HighCloudAssets.PATCH_SHA256,
                "high-cloud patch source");
        Path root = getOutDir().get().getAsFile().toPath()
                .resolve("assets/fluorite/fluorite/cloud");
        Files.createDirectories(root);
        writePatches(root.resolve("high_cloud_patches.ktx2"));
    }

    static void verifyPinned(Path path, String expected, String label) throws IOException {
        String actual = FetchVerifiedAsset.sha256(path);
        if (!expected.equals(actual)) {
            throw new GradleException(label + " SHA-256 mismatch: expected " + expected + ", got " + actual);
        }
    }

    private void writePatches(Path output) throws Exception {
        List<BufferedImage> sources = readPatchPngs(getPatchZip().get().getAsFile().toPath());
        List<float[]> tauLayers = new ArrayList<>(PATCH_COUNT);
        double[] means = new double[PATCH_COUNT];
        for (int layer = 0; layer < PATCH_COUNT; layer++) {
            BufferedImage source = sources.get(layer);
            if (source.getWidth() != PATCH_SIZE * 2 || source.getHeight() != PATCH_SIZE * 2) {
                throw new IOException("High-cloud patch " + (layer + 1) + " is not 2048x2048");
            }
            float[] tau = new float[PATCH_SIZE * PATCH_SIZE];
            double sum = 0.0;
            for (int y = 0; y < PATCH_SIZE; y++) {
                for (int x = 0; x < PATCH_SIZE; x++) {
                    double transmittance = 0.0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dx = 0; dx < 2; dx++) {
                            int alpha = source.getRGB(x * 2 + dx, y * 2 + dy) >>> 24;
                            transmittance += 1.0 - alpha / 255.0;
                        }
                    }
                    // Area-filter TRANSMITTANCE before returning to optical depth. Averaging alpha or
                    // tau directly would make a half-covered mip either too dark or too transparent.
                    float value = (float) -Math.log(Math.max(transmittance * 0.25, 1.0 / 256.0));
                    tau[y * PATCH_SIZE + x] = value;
                    sum += value;
                }
            }
            means[layer] = sum / tau.length;
            tauLayers.add(tau);
        }

        double[] orderedMeans = means.clone();
        Arrays.sort(orderedMeans);
        double targetMean = 0.5 * (orderedMeans[4] + orderedMeans[5]);
        List<float[]> normalized = new ArrayList<>(PATCH_COUNT);
        for (float[] layer : tauLayers) {
            double scale = scaleForCappedMean(layer, targetMean, PATCH_TAU_CAP);
            float[] values = new float[layer.length];
            for (int i = 0; i < layer.length; i++) {
                values[i] = Math.min(layer[i] * (float) scale, PATCH_TAU_CAP) / PATCH_TAU_CAP;
            }
            normalized.add(values);
        }

        List<byte[]> levels = r8ArrayMipChain(normalized, PATCH_SIZE, PATCH_SIZE);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("KTXorientation", "rd");
        metadata.put("fluoriteLicense", "CC0-1.0");
        metadata.put("fluoriteSourceTitle", "Clouds with Transparency");
        metadata.put("fluoriteSourceCreator", "WickedInsignia / OpenGameArt");
        metadata.put("fluoriteSourceUrl", "https://opengameart.org/content/clouds-with-transparency");
        metadata.put("fluoriteSourceSha256", HighCloudAssets.PATCH_SHA256);
        metadata.put("fluoriteOpticalDepthEncoding", "R8_UNORM * 4.0; ten normalized-alpha layers");
        metadata.put("fluoriteModification",
                "PNG alpha -> Beer optical depth; 2x area transmittance filter; equal integrated tau; 1024 mips");
        Ktx2Writer.write(output, 9, 1, PATCH_SIZE, PATCH_SIZE, PATCH_COUNT,
                levels, Ktx2Writer.dfdR8Unorm(), metadata);
    }

    static List<BufferedImage> readPatchPngs(Path zipPath) throws IOException {
        Map<String, BufferedImage> found = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = Path.of(entry.getName()).getFileName().toString();
                if (!name.matches("FX_CloudAlpha(0[1-9]|10)\\.png")) continue;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                zip.transferTo(bytes);
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes.toByteArray()));
                if (image == null || !image.getColorModel().hasAlpha()) {
                    throw new IOException("Cloud patch has no readable alpha: " + name);
                }
                if (found.putIfAbsent(name, image) != null) {
                    throw new IOException("Duplicate cloud patch in archive: " + name);
                }
            }
        }
        List<BufferedImage> ordered = new ArrayList<>(PATCH_COUNT);
        for (int index = 1; index <= PATCH_COUNT; index++) {
            String name = "FX_CloudAlpha%02d.png".formatted(index);
            BufferedImage image = found.get(name);
            if (image == null) throw new IOException("Cloud patch archive is missing " + name);
            ordered.add(image);
        }
        return ordered;
    }

    private static float bilinear(float[] pixels, int width, int height, double x, double y) {
        int x0 = floorMod((int) Math.floor(x), width);
        int x1 = (x0 + 1) % width;
        int y0 = Math.max(0, Math.min(height - 1, (int) Math.floor(y)));
        int y1 = Math.min(height - 1, y0 + 1);
        float fx = (float) (x - Math.floor(x));
        float fy = (float) (y - Math.floor(y));
        float a = pixels[y0 * width + x0] * (1.0f - fx) + pixels[y0 * width + x1] * fx;
        float b = pixels[y1 * width + x0] * (1.0f - fx) + pixels[y1 * width + x1] * fx;
        return a * (1.0f - fy) + b * fy;
    }

    private static List<byte[]> r8ArrayMipChain(List<float[]> baseLayers, int width, int height) {
        List<byte[]> levels = new ArrayList<>();
        List<float[]> layers = baseLayers;
        int w = width, h = height;
        while (true) {
            byte[] level = new byte[Math.multiplyExact(Math.multiplyExact(w, h), layers.size())];
            int cursor = 0;
            for (float[] layer : layers) {
                for (float value : layer) level[cursor++] = (byte) Math.round(saturate(value) * 255.0f);
            }
            levels.add(level);
            if (w == 1 && h == 1) break;
            int nextW = Math.max(1, w / 2), nextH = Math.max(1, h / 2);
            List<float[]> nextLayers = new ArrayList<>(layers.size());
            for (float[] layer : layers) {
                nextLayers.add(downsampleOpticalDepth(layer, w, h, nextW, nextH, PATCH_TAU_CAP));
            }
            layers = nextLayers;
            w = nextW;
            h = nextH;
        }
        return levels;
    }

    /** Beer-preserving mip filter: average transmittance, then return to the stored tau domain. */
    private static float[] downsampleOpticalDepth(float[] source, int width, int height,
                                                   int nextWidth, int nextHeight, float tauRange) {
        float[] result = new float[nextWidth * nextHeight];
        for (int y = 0; y < nextHeight; y++) {
            int y0 = Math.min(height - 1, y * 2);
            int y1 = Math.min(height - 1, y0 + 1);
            for (int x = 0; x < nextWidth; x++) {
                int x0 = Math.min(width - 1, x * 2);
                int x1 = Math.min(width - 1, x0 + 1);
                double transmittance = Math.exp(-source[y0 * width + x0] * tauRange)
                        + Math.exp(-source[y0 * width + x1] * tauRange)
                        + Math.exp(-source[y1 * width + x0] * tauRange)
                        + Math.exp(-source[y1 * width + x1] * tauRange);
                float tau = (float) -Math.log(Math.max(transmittance * 0.25, 1.0e-12));
                result[y * nextWidth + x] = tau / tauRange;
            }
        }
        return result;
    }

    private static double scaleForCappedMean(float[] values, double target, float cap) {
        double lo = 0.0, hi = 1.0;
        while (cappedMean(values, hi, cap) < target && hi < 1.0e6) hi *= 2.0;
        for (int iteration = 0; iteration < 64; iteration++) {
            double mid = 0.5 * (lo + hi);
            if (cappedMean(values, mid, cap) < target) lo = mid;
            else hi = mid;
        }
        return 0.5 * (lo + hi);
    }

    private static double cappedMean(float[] values, double scale, float cap) {
        double sum = 0.0;
        for (float value : values) sum += Math.min(value * scale, cap);
        return sum / values.length;
    }

    private static int floorMod(int value, int modulus) {
        int result = value % modulus;
        return result < 0 ? result + modulus : result;
    }

    private static float smoothstep(float lo, float hi, float value) {
        float t = saturate((value - lo) / (hi - lo));
        return t * t * (3.0f - 2.0f * t);
    }

    private static float saturate(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
