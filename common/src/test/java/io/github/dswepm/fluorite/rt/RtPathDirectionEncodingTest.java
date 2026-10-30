package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/** The reflected production serializer must preserve the sampled vector, including its length. */
final class RtPathDirectionEncodingTest {
    private static PackedPathReservoirData record(int x, int y, int z) {
        return new PackedPathReservoirData(new PackedPathReservoirData.Float3(1, 2, 3), 17,
                new PackedPathReservoirData.Float3(4, 5, 6), 19, 23, x,
                Float.floatToRawIntBits(.7f), 2.5f, .2f, 1e-4f, 0x3c00, 0x1a125,
                0x123, 0x34567890, Float.floatToRawIntBits(1e-10f), Float.floatToRawIntBits(1e10f), y, z);
    }

    private static ByteBuffer write(int x, int y, int z) {
        ByteBuffer bytes = ByteBuffer.allocate(PackedPathReservoirData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        record(x, y, z).write(bytes);
        return bytes;
    }

    @Test
    void finiteSignedFloatPatternsSurviveWithoutNormalizingOrQuantizing() {
        SplittableRandom random = new SplittableRandom(20261031);
        for (int i = 0; i < 20_000; i++) {
            int x = random.nextInt(), y = random.nextInt(), z = random.nextInt();
            if (!Float.isFinite(Float.intBitsToFloat(x)) || !Float.isFinite(Float.intBitsToFloat(y))
                    || !Float.isFinite(Float.intBitsToFloat(z))) continue;
            ByteBuffer bytes = write(x, y, z);
            assertEquals(x, bytes.getInt(PackedPathReservoirData.DIR_K_OFFSET));
            assertEquals(y, bytes.getInt(PackedPathReservoirData.DIR_Y_OFFSET));
            assertEquals(z, bytes.getInt(PackedPathReservoirData.DIR_Z_OFFSET));
        }
        assertEquals(0x80000000, write(0, 0x80000000, 1).getInt(80));
        assertEquals(1, write(0, 0x80000000, 1).getInt(84));
    }

    @Test
    void directionWritesLeaveAllOtherFieldsAndTheAdjacentRecordIntact() {
        ByteBuffer a = write(0, 0, 0), b = write(0x3f000000, 0xbf000000, 0x3f3504f3);
        for (int i = 0; i < a.capacity(); i++) {
            if (i >= 36 && i < 40 || i >= 80 && i < 88) continue;
            assertEquals(a.get(i), b.get(i), "unrelated byte " + i);
        }
        ByteBuffer slots = ByteBuffer.allocate(192).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 96; i < 192; i++) slots.put(i, (byte) 0x5a);
        record(1, 2, 3).write(slots.slice(0, 96).order(ByteOrder.LITTLE_ENDIAN));
        for (int i = 96; i < 192; i++) assertEquals((byte) 0x5a, slots.get(i));
        record(4, 5, 6).write(slots.slice(96, 96).order(ByteOrder.LITTLE_ENDIAN));
        assertEquals(1, slots.getInt(36));
        assertEquals(4, slots.getInt(96 + 36));
        assertEquals(6, slots.getInt(96 + 84));
        for (int i = 88; i < 96; i++) assertEquals(0, slots.get(i), "tail padding");
    }

    @Test
    void theProducerAndItsEvaluationConsumerUseTheSameLosslessCodec() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        String tree = Files.readString(root.resolve("shaders/world/path_reuse_tree.slang"));
        for (String term : new String[]{"record.dirK = asuint(direction.x);", "record.dirY = asuint(direction.y);",
                "record.dirZ = asuint(direction.z);",
                "float3(asfloat(record.dirK), asfloat(record.dirY), asfloat(record.dirZ))"})
            assertTrue(tree.contains(term), term);
        String trace = Files.readString(root.resolve("shaders/world/path_reuse_trace.slang"));
        assertTrue(trace.contains("dRecord = reuseStoreDirection(dRecord, rd);"));
        assertTrue(trace.contains("float3 packedDirection = reuseLoadDirection(dRecord);"));
        assertFalse(trace.contains("octEncode(rd)") || trace.contains("unpackUnorm16x2(dRecord.dirK)"));
    }
}
