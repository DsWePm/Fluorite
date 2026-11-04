package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real grazing samples expose the reduction-order difference hidden by a normal dot product. */
final class RtPathOrderedDotTest {
    @Test void capturedDotsReproduceTheTwoDifferentFp32ReductionOrders() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("grazing-dot-witnesses.csv")) {
            assertNotNull(in);
            int cases = 0;
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (line.isBlank() || line.startsWith("nx,")) continue;
                String[] fields = line.split(",");
                assertEquals(10, fields.length);
                float[] r = new float[10];
                for (int i = 0; i < r.length; i++) r[i] = Float.parseFloat(fields[i]);
                float source = Math.fma(r[2], r[5], Math.fma(r[0], r[3], r[1] * r[4]));
                float evaluated = Math.fma(r[2], r[5], Math.fma(r[1], r[4], r[0] * r[3]));
                assertEquals(Float.floatToRawIntBits(r[6]), Float.floatToRawIntBits(source));
                assertEquals(Float.floatToRawIntBits(r[7]), Float.floatToRawIntBits(evaluated));
                assertTrue(Math.abs(source - evaluated) / source > .001f);
                assertTrue(Math.abs(r[8] - r[9]) / r[8] > .001f);
                cases++;
            }
            assertEquals(2, cases);
        }
    }

    @Test void samplerAndEvaluatorShareOnlyTheApprovedNormalDotSubstitution() throws IOException {
        String trace = source("shaders/world/path_reuse_trace.slang");
        String sample = trace.substring(trace.indexOf("bool reuseSampleContinuation("),
                trace.indexOf("// ---- The hybrid shift's replay"));
        assertEquals(2, sample.split(java.util.regex.Pattern.quote("reuseOrderedNdot(n, l)"), -1).length - 1);
        assertEquals(1, sample.split(java.util.regex.Pattern.quote("reuseOrderedNdot(n, rd)"), -1).length - 1);
        assertFalse(sample.contains("float ndl2 = dot(n, l);"));
        assertTrue(source("shaders/world/path_reuse_eval.slang").contains("float ndl = reuseOrderedNdot(c.n, wi);"));
        assertTrue(trace.indexOf("#include \"path_reuse_dot.slang\"") < trace.indexOf("#include \"path_reuse_eval.slang\""));
        assertFalse(source("shaders/world/world.rgen.slang").contains("reuseOrderedNdot("));
    }

    @Test void compiledVariantsPreserveTheRoundedFirstProductAndTwoExplicitFmas() throws IOException {
        for (String name : List.of("world_reuse.rgen.spv", "world_ser_reuse.rgen.spv")) {
            try (InputStream in = getClass().getResourceAsStream("/fluorite/rt/" + name)) {
                assertNotNull(in, name);
                ByteBuffer b = ByteBuffer.wrap(in.readAllBytes()).order(ByteOrder.LITTLE_ENDIAN);
                Set<Integer> precise = new HashSet<>(), products = new HashSet<>();
                Map<Integer, Integer> fmaAddends = new HashMap<>();
                for (int at = 20; at < b.limit();) {
                    int word = b.getInt(at), size = word >>> 16, op = word & 0xffff;
                    assertTrue(size > 0 && at + size * 4 <= b.limit());
                    if (op == 71 && size >= 3 && b.getInt(at + 8) == 42) precise.add(b.getInt(at + 4));
                    if (op == 133) products.add(b.getInt(at + 8)); // OpFMul
                    if (op == 12 && size == 8 && b.getInt(at + 16) == 50)
                        fmaAddends.put(b.getInt(at + 8), b.getInt(at + 28)); // GLSL.std.450 Fma
                    at += size * 4;
                }
                products.retainAll(precise);
                assertTrue(fmaAddends.values().stream().anyMatch(inner ->
                        products.contains(fmaAddends.get(inner))), name);
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
