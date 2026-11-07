package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the generated real serializer; the GPU isolation loop verifies the shader itself. */
final class RtPathIncidentEncodingTest {
    private static ByteBuffer write(float r, float g, float b) {
        ByteBuffer bytes = ByteBuffer.allocate(PackedPathReservoirData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        new PackedPathReservoirData(new PackedPathReservoirData.Float3(1, 2, 3), 0x12345678,
                new PackedPathReservoirData.Float3(4, 5, 6), 0x31415926, 0x27182818, 0x43214321,
                Float.floatToRawIntBits(r), 2.5f, .2f, 1e-4f, 0x00003c00, 0x0001a125,
                0x123, 0x34567890, Float.floatToRawIntBits(g), Float.floatToRawIntBits(b),
                0xabcdef01, 0x12345678, 0f, 0f, 7, 13,
                new PackedPathReservoirData.Float2(.25f, .5f), 1, 1, 0, 0).write(bytes);
        return bytes;
    }
    private static void roundtrip(float r, float g, float b) {
        ByteBuffer bytes = write(r, g, b);
        assertEquals(Float.floatToRawIntBits(r), bytes.getInt(PackedPathReservoirData.RADIANCE_OFFSET));
        assertEquals(Float.floatToRawIntBits(g), bytes.getInt(PackedPathReservoirData.RESERVED0_OFFSET));
        assertEquals(Float.floatToRawIntBits(b), bytes.getInt(PackedPathReservoirData.RESERVED1_OFFSET));
        assertEquals(128, bytes.capacity());
    }

    @Test
    void theWeakChannelSurvivesBothColourRatioAndSmallMagnitudeCases() {
        roundtrip(32768, 1, 0);
        roundtrip(1e-8f, 1e-6f, 0);
        assertEquals(1, write(32768, 1, 0).getFloat(72));
        assertEquals(1e-8f, write(1e-8f, 1e-6f, 0).getFloat(40));
    }

    @Test
    void zeroRedCannotInvalidateARecordWhoseOtherChannelsContribute() {
        roundtrip(0, 3, 1e-12f);
        roundtrip(-0.0f, 2, 0);
        assertEquals(3, write(0, 3, 1e-12f).getFloat(72));
    }

    @Test
    void finiteHdrAndSubnormalFloat32PatternsRoundtripWithoutAnRgb9e5RangeLimit() {
        roundtrip(Float.MAX_VALUE, Float.MIN_NORMAL, Float.MIN_VALUE);
        roundtrip(1e12f, 1e-30f, 1);
        SplittableRandom random = new SplittableRandom(20261030);
        for (int i = 0; i < 20_000; i++) {
            roundtrip(Float.intBitsToFloat(random.nextInt(0x7f800000)),
                    Float.intBitsToFloat(random.nextInt(0x7f800000)),
                    Float.intBitsToFloat(random.nextInt(0x7f800000)));
        }
    }

    @Test
    void changingIncidentLeavesThePrimaryHooksDirectionWeightsAndTagsUntouched() {
        ByteBuffer a = write(0, 0, 0), b = write(.7f, 1e-10f, 1e10f);
        for (int i = 0; i < PackedPathReservoirData.BYTE_SIZE; i++) {
            if (i >= 40 && i < 44 || i >= 72 && i < 80) continue;
            assertEquals(a.get(i), b.get(i), "unrelated byte " + i);
        }
        assertEquals(0x123, b.getInt(64));
        assertEquals(0x34567890, b.getInt(68));
        assertEquals(0x43214321, b.getInt(36));
        assertEquals(530_841_600L, 1920L * 1080 * 2 * b.capacity());
    }

    @Test
    void theShaderUsesTheSameThreeRealLanesAndHasNoRedOnlyValidityCheck() throws Exception {
        String shader = source("shaders/world/path_reuse_tree.slang");
        for (String term : new String[]{"record.radiance = asuint(incident.r);",
                "record.reserved0 = asuint(incident.g);", "record.reserved1 = asuint(incident.b);",
                "float3(asfloat(record.radiance), asfloat(record.reserved0), asfloat(record.reserved1))",
                "record = reuseStoreIncident(record, tree.selectedIncident);",
                "!any(reuseLoadIncident(record) > 0.0)"}) assertTrue(shader.contains(term), term);
        assertFalse(shader.contains("record.radiance == 0u"));
        assertFalse(shader.contains("packRgb9e5(") || shader.contains("65408"));
        assertTrue(shader.contains("!reuseNonnegativeFinite(tree.selectedIncident)"));
    }

    @Test
    void theRealDiagnosticConsumerDecodesAllThreeStoredChannels() throws Exception {
        String shader = source("shaders/world/path_reuse_trace.slang");
        assertTrue(shader.contains("dPackedSourceWeight * reuseLoadIncident(produced)"));
        assertTrue(shader.contains("dSourceWeight * reuseLoadIncident(produced)"));
        assertFalse(shader.contains("unpackRgb9e5(produced.radiance)"));
        assertFalse(source("shaders/world/world.rgen.slang").contains("reuseStoreIncident("));
    }

    private static String source(String relative) throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        return Files.readString(root.resolve(relative));
    }
}
