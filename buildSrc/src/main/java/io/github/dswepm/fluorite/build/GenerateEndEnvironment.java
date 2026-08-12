package io.github.dswepm.fluorite.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.DoubleAccumulator;
import java.util.stream.IntStream;

/** Builds the licensed End HDR environment and project-generated Kerr transfer/disk-path maps. */
public abstract class GenerateEndEnvironment extends DefaultTask {
    private static final int SKY_W = 4096;
    private static final int SKY_H = 2048;
    private static final int TRANSFER_W = 2048;
    private static final int TRANSFER_H = 1024;
    private static final double PI = Math.PI;

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getSourceHdr();

    @OutputDirectory
    public abstract DirectoryProperty getOutDir();

    @TaskAction
    public void generate() throws IOException {
        Path sourcePath = getSourceHdr().get().getAsFile().toPath();
        String sourceSha256 = sha256(sourcePath);
        if (!EndEnvironmentAsset.SHA256.equals(sourceSha256)) {
            throw new GradleException("End HDR source SHA-256 mismatch: expected "
                    + EndEnvironmentAsset.SHA256 + ", got " + sourceSha256);
        }
        Path root = getOutDir().get().getAsFile().toPath()
                .resolve("assets/fluorite/fluorite/environment");
        Files.createDirectories(root);

        Sky sky = generateSky(sourcePath);
        Map<String, String> skyMetadata = commonMetadata();
        skyMetadata.put("fluoriteLicense", "CC-BY-4.0");
        skyMetadata.put("fluoriteSourceTitle", "HDR Multi Nebulae 1");
        skyMetadata.put("fluoriteSourceCreator", "TonyS / Space Spheremaps");
        skyMetadata.put("fluoriteSourceUrl", "https://www.spacespheremaps.com/hdr-spheremaps/");
        skyMetadata.put("fluoriteSourceSha256", sourceSha256);
        skyMetadata.put("fluoriteModification",
                "solid-angle area resize 10000x5000 to 4096x2048; energy-preserving mip chain; R11G11B10 UFLOAT");
        skyMetadata.put("fluoriteMeanRadiance", format3(sky.meanR, sky.meanG, sky.meanB));
        Ktx2Writer.write(root.resolve("end_stars.ktx2"), 122, 4, SKY_W, SKY_H,
                sky.levels, Ktx2Writer.dfdR11G11B10(), skyMetadata);

        Transfer transfer = generateTransfer();
        Map<String, String> transferMetadata = commonMetadata();
        transferMetadata.put("fluoriteLicense", "CC0-1.0");
        transferMetadata.put("fluoriteKerrMethod", "Cartesian Kerr-Schild, analytic 3+1 Hamiltonian RK4");
        transferMetadata.put("fluoriteKerrParameters", String.format(Locale.ROOT,
                "a=%.6f,inclinationDeg=60,observerR=%.6f,innerR=%.9f,outerR=%.6f",
                KerrSchildEnvironment.SPIN, KerrSchildEnvironment.OBSERVER_R,
                KerrSchildEnvironment.DISK_INNER_R, KerrSchildEnvironment.DISK_OUTER_R));
        Ktx2Writer.write(root.resolve("end_kerr.ktx2"), 97, 2, TRANSFER_W, TRANSFER_H,
                List.of(transfer.directionBytes), Ktx2Writer.dfdRgba16Float(), transferMetadata);

        Map<String, String> diskMetadata = commonMetadata();
        diskMetadata.put("fluoriteLicense", "CC0-1.0");
        diskMetadata.put("fluoriteDiskModel",
                "first local Kerr chord through maximum authored disk; runtime bounded Le/T volume integration");
        diskMetadata.put("fluoriteDiskPathLayout",
                "entry=xyz,energy; exit=xyz,lambda; Kerr-Schild coordinates in M");
        diskMetadata.put("fluoriteDiskCapture", String.format(Locale.ROOT,
                "innerR=%.9f,outerR=%.6f,halfHeight=%.6f",
                KerrSchildEnvironment.DISK_INNER_R, KerrSchildEnvironment.DISK_OUTER_R,
                KerrSchildEnvironment.DISK_CAPTURE_HALF_HEIGHT));
        Ktx2Writer.write(root.resolve("end_disk_entry.ktx2"), 97, 2, TRANSFER_W, TRANSFER_H,
                List.of(transfer.diskEntryBytes), Ktx2Writer.dfdRgba16Float(), diskMetadata);
        Ktx2Writer.write(root.resolve("end_disk_exit.ktx2"), 97, 2, TRANSFER_W, TRANSFER_H,
                List.of(transfer.diskExitBytes), Ktx2Writer.dfdRgba16Float(), diskMetadata);
        // D92's static radiance map is no longer part of the runtime contract. Output directories are
        // incremental and Gradle does not remove retired task outputs on its own.
        Files.deleteIfExists(root.resolve("end_disk.ktx2"));

        if (transfer.unresolved != 0) {
            throw new GradleException("Kerr transfer left " + transfer.unresolved + " rays unresolved");
        }
        getLogger().lifecycle(String.format(Locale.ROOT,
                "End environment: HDR mean=(%.6g, %.6g, %.6g); Kerr escape=%d capture=%d diskChords=%d maxChord=%.6gM",
                sky.meanR, sky.meanG, sky.meanB, transfer.escaped, transfer.captured,
                transfer.diskPixels, transfer.maxChordLength));
    }

    private static Map<String, String> commonMetadata() {
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("KTXorientation", "rd");
        metadata.put("KTXwriter", "Fluorite GenerateEndEnvironment");
        return metadata;
    }

    private static Sky generateSky(Path sourcePath) throws IOException {
        RadianceHdr.Image source = RadianceHdr.read(sourcePath);
        if (source.width() != 10000 || source.height() != 5000) {
            throw new IOException("D87A requires HDR Multi Nebulae 1 at 10000x5000, got "
                    + source.width() + "x" + source.height());
        }
        float[] base = solidAngleResize(source, SKY_W, SKY_H);
        double[] mean = solidAngleMean(base, SKY_W, SKY_H);
        List<byte[]> levels = new ArrayList<>();
        int width = SKY_W;
        int height = SKY_H;
        float[] level = base;
        while (true) {
            levels.add(Ktx2Writer.packR11G11B10(level));
            if (width == 1 && height == 1) break;
            level = downsampleMip(level, width, height);
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        return new Sky(levels, mean[0], mean[1], mean[2]);
    }

    private static float[] solidAngleResize(RadianceHdr.Image source, int width, int height) {
        Contribution[] xs = contributions(source.width(), width, false);
        Contribution[] ys = contributions(source.height(), height, true);
        float[] output = new float[width * height * 3];
        IntStream.range(0, height).parallel().forEach(y -> {
            Contribution yc = ys[y];
            for (int x = 0; x < width; x++) {
                Contribution xc = xs[x];
                double r = 0.0, g = 0.0, b = 0.0, total = 0.0;
                for (int yi = 0; yi < yc.indices.length; yi++) {
                    for (int xi = 0; xi < xc.indices.length; xi++) {
                        double weight = yc.weights[yi] * xc.weights[xi];
                        int sx = xc.indices[xi], sy = yc.indices[yi];
                        r += source.red(sx, sy) * weight;
                        g += source.green(sx, sy) * weight;
                        b += source.blue(sx, sy) * weight;
                        total += weight;
                    }
                }
                int offset = (y * width + x) * 3;
                output[offset] = finitePositive(r / total);
                output[offset + 1] = finitePositive(g / total);
                output[offset + 2] = finitePositive(b / total);
            }
        });
        return output;
    }

    private static Contribution[] contributions(int sourceSize, int destinationSize, boolean sphericalY) {
        Contribution[] result = new Contribution[destinationSize];
        for (int destination = 0; destination < destinationSize; destination++) {
            double begin = destination * (double) sourceSize / destinationSize;
            double end = (destination + 1.0) * sourceSize / destinationSize;
            int first = (int) Math.floor(begin);
            int last = Math.min(sourceSize - 1, (int) Math.ceil(end) - 1);
            int[] indices = new int[last - first + 1];
            double[] weights = new double[indices.length];
            for (int i = 0; i < indices.length; i++) {
                int source = first + i;
                double overlapBegin = Math.max(begin, source);
                double overlapEnd = Math.min(end, source + 1.0);
                indices[i] = source;
                weights[i] = sphericalY
                        ? Math.cos(PI * overlapBegin / sourceSize) - Math.cos(PI * overlapEnd / sourceSize)
                        : overlapEnd - overlapBegin;
            }
            result[destination] = new Contribution(indices, weights);
        }
        return result;
    }

    private static float[] downsampleMip(float[] source, int width, int height) {
        int nextWidth = Math.max(1, width / 2);
        int nextHeight = Math.max(1, height / 2);
        float[] result = new float[nextWidth * nextHeight * 3];
        for (int y = 0; y < nextHeight; y++) {
            int yCount = height == 1 ? 1 : 2;
            for (int x = 0; x < nextWidth; x++) {
                int xCount = width == 1 ? 1 : 2;
                double r = 0.0, g = 0.0, b = 0.0, total = 0.0;
                for (int oy = 0; oy < yCount; oy++) {
                    int sy = y * 2 + oy;
                    double rowWeight = Math.cos(PI * sy / height) - Math.cos(PI * (sy + 1.0) / height);
                    for (int ox = 0; ox < xCount; ox++) {
                        int offset = (sy * width + x * 2 + ox) * 3;
                        r += source[offset] * rowWeight;
                        g += source[offset + 1] * rowWeight;
                        b += source[offset + 2] * rowWeight;
                        total += rowWeight;
                    }
                }
                int destination = (y * nextWidth + x) * 3;
                result[destination] = (float) (r / total);
                result[destination + 1] = (float) (g / total);
                result[destination + 2] = (float) (b / total);
            }
        }
        return result;
    }

    private static double[] solidAngleMean(float[] rgb, int width, int height) {
        double r = 0.0, g = 0.0, b = 0.0, total = 0.0;
        for (int y = 0; y < height; y++) {
            double rowWeight = Math.cos(PI * y / height) - Math.cos(PI * (y + 1.0) / height);
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 3;
                r += rgb[offset] * rowWeight;
                g += rgb[offset + 1] * rowWeight;
                b += rgb[offset + 2] * rowWeight;
                total += rowWeight;
            }
        }
        return new double[]{r / total, g / total, b / total};
    }

    private static Transfer generateTransfer() {
        byte[] directions = new byte[TRANSFER_W * TRANSFER_H * 8];
        byte[] diskEntry = new byte[TRANSFER_W * TRANSFER_H * 8];
        byte[] diskExit = new byte[TRANSFER_W * TRANSFER_H * 8];
        AtomicInteger escaped = new AtomicInteger();
        AtomicInteger captured = new AtomicInteger();
        AtomicInteger diskPixels = new AtomicInteger();
        AtomicInteger unresolved = new AtomicInteger();
        DoubleAccumulator maxChordLength = new DoubleAccumulator(Double::max, 0.0);

        IntStream.range(0, TRANSFER_H).parallel().forEach(y -> {
            ByteBuffer directionOut = ByteBuffer.wrap(directions).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer diskEntryOut = ByteBuffer.wrap(diskEntry).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer diskExitOut = ByteBuffer.wrap(diskExit).order(ByteOrder.LITTLE_ENDIAN);
            double theta = PI * (y + 0.5) / TRANSFER_H;
            double radial = Math.sin(theta);
            for (int x = 0; x < TRANSFER_W; x++) {
                double phi = 2.0 * PI * ((x + 0.5) / TRANSFER_W - 0.5);
                KerrSchildEnvironment.Trace trace = KerrSchildEnvironment.trace(
                        new KerrSchildEnvironment.Vec3(radial * Math.sin(phi), Math.cos(theta),
                                radial * Math.cos(phi)));
                int offset = (y * TRANSFER_W + x) * 8;
                if (trace.kind() == KerrSchildEnvironment.Kind.ESCAPE) {
                    KerrSchildEnvironment.Vec3 direction = trace.escapedDirection();
                    Ktx2Writer.putHalf4(directionOut, offset, (float) direction.x(),
                            (float) direction.y(), (float) direction.z(), -1.0f);
                    escaped.incrementAndGet();
                } else if (trace.kind() == KerrSchildEnvironment.Kind.CAPTURE) {
                    Ktx2Writer.putHalf4(directionOut, offset, 0.0f, 0.0f, 0.0f, 0.0f);
                    captured.incrementAndGet();
                } else {
                    Ktx2Writer.putHalf4(directionOut, offset, 1.0f, 0.0f, 1.0f, 1.0f);
                    unresolved.incrementAndGet();
                }
                KerrSchildEnvironment.DiskChord chord = trace.diskChord();
                if (chord.present()) {
                    KerrSchildEnvironment.Vec3 entry = chord.entry();
                    KerrSchildEnvironment.Vec3 exit = chord.exit();
                    Ktx2Writer.putHalf4(diskEntryOut, offset, (float) entry.x(), (float) entry.y(),
                            (float) entry.z(), (float) chord.energy());
                    Ktx2Writer.putHalf4(diskExitOut, offset, (float) exit.x(), (float) exit.y(),
                            (float) exit.z(), (float) chord.lambda());
                    diskPixels.incrementAndGet();
                    maxChordLength.accumulate(exit.sub(entry).length());
                } else {
                    Ktx2Writer.putHalf4(diskEntryOut, offset, 0f, 0f, 0f, 0f);
                    Ktx2Writer.putHalf4(diskExitOut, offset, 0f, 0f, 0f, 0f);
                }
            }
        });
        return new Transfer(directions, diskEntry, diskExit, escaped.get(), captured.get(),
                diskPixels.get(), unresolved.get(), maxChordLength.get());
    }

    private static float finitePositive(double value) {
        if (!Double.isFinite(value)) return 0.0f;
        return (float) Math.max(0.0, value);
    }

    private static String format3(double x, double y, double z) {
        return String.format(Locale.ROOT, "%.9g,%.9g,%.9g", x, y, z);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[1 << 20];
                for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private record Contribution(int[] indices, double[] weights) {}
    private record Sky(List<byte[]> levels, double meanR, double meanG, double meanB) {}
    private record Transfer(byte[] directionBytes, byte[] diskEntryBytes, byte[] diskExitBytes,
                            int escaped, int captured, int diskPixels, int unresolved,
                            double maxChordLength) {}
}
