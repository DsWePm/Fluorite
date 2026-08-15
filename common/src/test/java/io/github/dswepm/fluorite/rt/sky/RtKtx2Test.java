package io.github.dswepm.fluorite.rt.sky;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtKtx2Test {
    @Test
    void generatedStarEnvironmentHasThePublishedRuntimeContract() throws Exception {
        RtKtx2.Image image = resource("end_stars.ktx2");
        assertEquals(122, image.vkFormat());
        assertEquals(4, image.typeSize());
        assertEquals(4096, image.width());
        assertEquals(2048, image.height());
        assertEquals(1, image.layers());
        assertEquals(13, image.levels().size());
        assertEquals("rd", image.metadata().get("KTXorientation"));
        assertEquals("CC-BY-4.0", image.metadata().get("fluoriteLicense"));
        assertEquals("HDR Multi Nebulae 1", image.metadata().get("fluoriteSourceTitle"));
        assertEquals("TonyS / Space Spheremaps", image.metadata().get("fluoriteSourceCreator"));
        assertEquals("dad11594feb1658d939db6d267f5b20a30e436fcbf61d5283421bd85fd393d90",
                image.metadata().get("fluoriteSourceSha256"));
        assertTrue(image.metadata().get("fluoriteModification").contains("10000x5000 to 4096x2048"));
        assertNotNull(image.metadata().get("fluoriteMeanRadiance"));
        assertTrue(image.levels().stream().allMatch(level -> level.length > 0));
    }

    @Test
    void generatedHighCloudPatchesHaveEqualisedOpticalDepthAndPinnedProvenance() throws Exception {
        RtKtx2.Image image = cloudResource("high_cloud_patches.ktx2");
        assertEquals(9, image.vkFormat());
        assertEquals(1, image.typeSize());
        assertEquals(1024, image.width());
        assertEquals(1024, image.height());
        assertEquals(10, image.layers());
        assertEquals(11, image.levels().size());
        assertEquals(10 * 1024 * 1024, image.levels().getFirst().length);
        assertEquals("CC0-1.0", image.metadata().get("fluoriteLicense"));
        assertEquals("83514a391c765819bc159b7f9ab61ec9f06c0caa2b5865af20a5bd3b2a14cca4",
                image.metadata().get("fluoriteSourceSha256"));

        byte[] base = image.levels().getFirst();
        double minimumMean = Double.POSITIVE_INFINITY;
        double maximumMean = Double.NEGATIVE_INFINITY;
        int layerBytes = 1024 * 1024;
        for (int layer = 0; layer < 10; layer++) {
            long sum = 0;
            double transmittance = 0.0;
            for (int i = 0; i < layerBytes; i++) sum += base[layer * layerBytes + i] & 0xff;
            for (int i = 0; i < layerBytes; i++) {
                double tau = (base[layer * layerBytes + i] & 0xff) * (4.0 / 255.0);
                transmittance += Math.exp(-tau);
            }
            double mean = sum / (255.0 * layerBytes);
            minimumMean = Math.min(minimumMean, mean);
            maximumMean = Math.max(maximumMean, mean);
            double expectedTau = -Math.log(transmittance / layerBytes);
            byte[] lastMip = image.levels().getLast();
            double storedTau = (lastMip[layer] & 0xff) * (4.0 / 255.0);
            assertTrue(Math.abs(expectedTau - storedTau) < 0.025,
                    "Patch mip chain changed Beer transmittance in layer " + layer);
        }
        assertTrue(maximumMean - minimumMean < 0.002,
                "Patch choice changes integrated tau: " + minimumMean + ".." + maximumMean);
    }

    @Test
    void generatedKerrTransferHasThePublishedRuntimeContract() throws Exception {
        RtKtx2.Image image = resource("end_kerr.ktx2");
        assertEquals(97, image.vkFormat());
        assertEquals(2, image.typeSize());
        assertEquals(2048, image.width());
        assertEquals(1024, image.height());
        assertEquals(1, image.levels().size());
        assertEquals(2048 * 1024 * 8, image.levels().getFirst().length);
        assertEquals("rd", image.metadata().get("KTXorientation"));
        assertEquals("CC0-1.0", image.metadata().get("fluoriteLicense"));
        assertTrue(image.metadata().get("fluoriteKerrParameters").contains("a=0.900000"));
        assertTrue(image.metadata().get("fluoriteKerrMethod").contains("Cartesian Kerr-Schild"));
        ByteBuffer transfer = ByteBuffer.wrap(image.levels().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        int escaped = 0;
        int captured = 0;
        for (int offset = 0; offset < image.levels().getFirst().length; offset += 8) {
            float tag = Float.float16ToFloat(transfer.getShort(offset + 6));
            if (tag < -0.5f) escaped++;
            else if (Math.abs(tag) < 0.5f) captured++;
            else throw new AssertionError("Unexpected Kerr transfer tag " + tag);
        }
        assertTrue(escaped > 2_000_000);
        assertTrue(captured > 1_000);
    }

    @Test
    void generatedDiskPathSupportsRuntimeRadiusThicknessAndNee() throws Exception {
        RtKtx2.Image entryImage = resource("end_disk_entry.ktx2");
        RtKtx2.Image exitImage = resource("end_disk_exit.ktx2");
        for (RtKtx2.Image image : new RtKtx2.Image[]{entryImage, exitImage}) {
            assertEquals(97, image.vkFormat());
            assertEquals(2, image.typeSize());
            assertEquals(2048, image.width());
            assertEquals(1024, image.height());
            assertEquals(1, image.levels().size());
            assertEquals(2048 * 1024 * 8, image.levels().getFirst().length);
            assertEquals("rd", image.metadata().get("KTXorientation"));
            assertTrue(image.metadata().get("fluoriteDiskModel").contains("runtime bounded Le/T"));
            assertTrue(image.metadata().get("fluoriteDiskPathLayout").contains("entry=xyz,energy"));
            assertTrue(image.metadata().get("fluoriteDiskCapture").contains("outerR=12.000000"));
        }

        ByteBuffer entries = ByteBuffer.wrap(entryImage.levels().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer exits = ByteBuffer.wrap(exitImage.levels().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        int diskPixels = 0;
        int coveredPixels = 0;
        double maximumChord = 0.0;
        for (int y = 0; y < entryImage.height(); y++) {
            double theta = Math.PI * (y + 0.5) / entryImage.height();
            double radial = Math.sin(theta);
            double canonicalY = Math.cos(theta);
            for (int x = 0; x < entryImage.width(); x++) {
                int offset = (y * entryImage.width() + x) * 8;
                float energy = Float.float16ToFloat(entries.getShort(offset + 6));
                float lambda = Float.float16ToFloat(exits.getShort(offset + 6));
                assertTrue(Float.isFinite(energy) && Float.isFinite(lambda));
                if (!(energy > 0.0f)) continue;
                diskPixels++;
                double chordSquared = 0.0;
                for (int channel = 0; channel < 3; channel++) {
                    float entry = Float.float16ToFloat(entries.getShort(offset + channel * 2));
                    float exit = Float.float16ToFloat(exits.getShort(offset + channel * 2));
                    assertTrue(Float.isFinite(entry) && Float.isFinite(exit));
                    chordSquared += (exit - entry) * (exit - entry);
                }
                maximumChord = Math.max(maximumChord, Math.sqrt(chordSquared));

                double phi = 2.0 * Math.PI * ((x + 0.5) / entryImage.width() - 0.5);
                double canonicalX = radial * Math.sin(phi);
                double canonicalZ = radial * Math.cos(phi);
                assertTrue(canonicalZ > 0.0, "A disk chord lies behind the proposal tangent plane");
                double extent = Math.max(Math.abs(canonicalX / canonicalZ),
                        Math.abs(canonicalY / canonicalZ));
                if (extent < Math.tan(0.36)) coveredPixels++;
            }
        }
        assertTrue(diskPixels > 10_000 && diskPixels < 100_000);
        assertTrue(maximumChord > 0.1 && maximumChord < 30.0);
        assertEquals(diskPixels, coveredPixels,
                "The finite celestial proposal must cover every runtime disk chord");
    }

    @Test
    void kerrTransferRemainsContinuousAcrossTheOldPolarAxisSeam() throws Exception {
        RtKtx2.Image image = resource("end_kerr.ktx2");
        ByteBuffer pixels = ByteBuffer.wrap(image.levels().getFirst()).order(ByteOrder.LITTLE_ENDIAN);
        int left = image.width() / 2 - 1;
        int right = image.width() / 2;
        for (int y : new int[]{64, 96, 128, 160, 192}) {
            float leftTag = half(pixels, image.width(), left, y, 3);
            float rightTag = half(pixels, image.width(), right, y, 3);
            assertTrue(leftTag < -0.5f && rightTag < -0.5f,
                    "The weak-lensing continuity probe must compare two escaped rays at y=" + y);
            double angle = Math.acos(clamp(dot(
                    vector(pixels, image.width(), left, y),
                    vector(pixels, image.width(), right, y)), -1.0, 1.0));
            assertTrue(angle < Math.toRadians(2.0),
                    "Adjacent weak-lensing rays diverged by " + Math.toDegrees(angle)
                            + " degrees at y=" + y);
        }
    }

    private static float[] vector(ByteBuffer pixels, int width, int x, int y) {
        float[] value = new float[]{
                half(pixels, width, x, y, 0),
                half(pixels, width, x, y, 1),
                half(pixels, width, x, y, 2)};
        double length = Math.sqrt(dot(value, value));
        value[0] /= (float) length;
        value[1] /= (float) length;
        value[2] /= (float) length;
        return value;
    }

    private static float half(ByteBuffer pixels, int width, int x, int y, int channel) {
        return Float.float16ToFloat(pixels.getShort((y * width + x) * 8 + channel * 2));
    }

    private static double dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static RtKtx2.Image resource(String name) throws Exception {
        try (InputStream input = RtKtx2Test.class.getResourceAsStream(
                "/assets/fluorite/fluorite/environment/" + name)) {
            assertNotNull(input);
            return RtKtx2.read(input);
        }
    }

    private static RtKtx2.Image cloudResource(String name) throws Exception {
        try (InputStream input = RtKtx2Test.class.getResourceAsStream(
                "/assets/fluorite/fluorite/cloud/" + name)) {
            assertNotNull(input);
            return RtKtx2.read(input);
        }
    }
}
