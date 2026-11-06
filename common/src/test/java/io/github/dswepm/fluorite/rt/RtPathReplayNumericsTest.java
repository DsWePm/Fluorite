package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual source positions distinguish separate FP32 operations from contraction during replay. */
final class RtPathReplayNumericsTest {
    private static List<float[]> witnesses() throws IOException {
        try (InputStream in = RtPathReplayNumericsTest.class.getResourceAsStream("replay-position-witnesses.csv")) {
            assertNotNull(in);
            List<float[]> result = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("ox,")) continue;
                String[] fields = line.split(",");
                assertEquals(10, fields.length);
                float[] row = new float[10];
                for (int i = 0; i < row.length; i++) row[i] = Float.parseFloat(fields[i]);
                result.add(row);
            }
            return result;
        }
    }

    @Test void capturedSourcePositionsUseSeparateFp32MultiplyAndAdd() throws IOException {
        List<float[]> rows = witnesses();
        assertEquals(164, rows.size());
        for (float[] r : rows) for (int i = 0; i < 3; i++) {
            float offset = r[4 + i] * r[3];
            assertEquals(Float.floatToRawIntBits(r[7 + i]), Float.floatToRawIntBits(r[i] + offset));
        }
    }

    @Test void contractionReproducesTheCapturedPositionFailure() throws IOException {
        int mismatched = 0;
        for (float[] r : witnesses()) {
            boolean differs = false;
            for (int i = 0; i < 3; i++) {
                differs |= Float.floatToRawIntBits(Math.fma(r[4 + i], r[3], r[i]))
                        != Float.floatToRawIntBits(r[7 + i]);
            }
            if (differs) mismatched++;
        }
        assertEquals(63, mismatched);
    }

    @Test void materialContextMustObserveTheDeclaredPayloadPrecision() {
        float rawF0 = 0.03529411926865578f;
        float capturedSourceF0 = 0.035308837890625f;
        assertNotEquals(Float.floatToRawIntBits(rawF0), Float.floatToRawIntBits(capturedSourceF0));
        assertEquals(capturedSourceF0, Float.float16ToFloat(Float.floatToFloat16(rawF0)));
    }

    @Test void payloadPrecisionIncludesSubnormalsSignedZeroAndEvenTies() {
        assertEquals(0x33800000, Float.floatToRawIntBits(Float.float16ToFloat((short) 1)));
        assertEquals(0xb3800000, Float.floatToRawIntBits(Float.float16ToFloat((short) 0x8001)));
        assertEquals(0x80000000, Float.floatToRawIntBits(Float.float16ToFloat((short) 0x8000)));
        assertEquals(0, Float.floatToFloat16(Math.scalb(1.0f, -25)));
        assertEquals(2, Float.floatToFloat16(3.0f * Math.scalb(1.0f, -25)));
    }

    @Test void theReplayEnforcesLocalArithmeticWithoutChangingTheSourceTracer() throws IOException {
        String trace = source("shaders/world/path_reuse_trace.slang");
        String replay = trace.substring(trace.indexOf("int reuseReplayPrefix("), trace.indexOf("// Fresh source-material"));
        assertTrue(replay.contains("reuseReplayPosition(ro, rd, payload.hitT)"));
        assertTrue(replay.contains("reuseCanonicalizeReplayPayload();"));
        String sourceLoop = trace.substring(trace.indexOf("public float3 tracePathReuse("));
        assertTrue(sourceLoop.contains("float3 hitPos = ro + rd * payload.hitT;"));
        assertFalse(sourceLoop.contains("reuseReplayPosition(") || sourceLoop.contains("reuseCanonicalizeReplayPayload();"));
        String numerics = source("shaders/world/path_reuse_numerics.slang");
        assertTrue(numerics.contains("OpDecorate %product NoContraction;"));
        assertTrue(numerics.contains("OpDecorate %point NoContraction;"));
        assertTrue(numerics.contains("uint remainder = significand &"));
        assertTrue(numerics.contains("(rounded & 1u) != 0u"));
        assertFalse(numerics.contains("TraceRay") || numerics.contains("worldPush."));
    }

    @Test void compiledReplayVariantsKeepTheRoundingDecorations() throws IOException {
        for (String name : List.of("world_reuse.rgen.spv", "world_ser_reuse.rgen.spv")) {
            try (InputStream in = getClass().getResourceAsStream("/fluorite/rt/" + name)) {
                assertNotNull(in, name);
                ByteBuffer bytes = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
                assertEquals(0x07230203, bytes.getInt(0));
                Set<Integer> noContraction = new HashSet<>();
                Map<Integer, Integer> arithmetic = new HashMap<>();
                Map<Integer, List<Integer>> addInputs = new HashMap<>();
                for (int at = 20; at < bytes.limit();) {
                    int header = bytes.getInt(at), size = header >>> 16, opcode = header & 0xffff;
                    assertTrue(size > 0 && at + size * 4 <= bytes.limit(), name);
                    // OpDecorate, NoContraction; arithmetic result IDs follow their result type.
                    if (opcode == 71 && size >= 3 && bytes.getInt(at + 8) == 42)
                        noContraction.add(bytes.getInt(at + 4));
                    if (opcode == 129 || opcode == 142) arithmetic.put(bytes.getInt(at + 8), opcode);
                    if (opcode == 129) addInputs.put(bytes.getInt(at + 8),
                            List.of(bytes.getInt(at + 12), bytes.getInt(at + 16)));
                    at += size * 4;
                }
                // Direction sampling also uses rounded vector products and sums. Compare the actual
                // multiply -> add edges, not unrelated instruction totals across the whole module.
                int paired = 0;
                for (int add : noContraction) {
                    for (int operand : addInputs.getOrDefault(add, List.of())) {
                        if (arithmetic.getOrDefault(operand, -1) == 142) {
                            assertTrue(noContraction.contains(operand), name + ": unrounded product feeding a rounded add");
                            paired++;
                        }
                    }
                }
                assertTrue(paired > 0, name);
            }
        }
    }

    private static String source(String relative) throws IOException {
        Path p = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (p != null && !Files.isRegularFile(p.resolve("settings.gradle"))) p = p.getParent();
        assertNotNull(p);
        return Files.readString(p.resolve(relative)).replace("\r\n", "\n");
    }
}
