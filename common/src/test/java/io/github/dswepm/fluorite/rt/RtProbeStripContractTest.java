package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The diagnostic surface is compiled OUT of a release build rather than branched around.
 *
 * <p>{@code pc.debugView} is a push constant, so the driver cannot prove it is zero and cannot eliminate
 * anything behind it: every debug path is live code in the compiled pipeline, and its register allocation
 * is sized for the worst path through the shader. Measured on world.rgen, the shader that owns the
 * indirect trace: 379,040 real SPIR-V instructions with probes, 257,148 without — a third of the shader.
 *
 * <p>That is also why this is a {@code -D} define and not a specialization constant. A specialization
 * constant recovers the branch but leaves the body in the binary, and ray tracing pays for code size in
 * occupancy. Neither is a runtime toggle — both need new pipelines — so the define gives up nothing.
 */
final class RtProbeStripContractTest {

    /** The flag reaches slangc, and is a tracked input so flipping it cannot serve a stale variant. */
    @Test
    void theProbeFlagIsABuildInputThatReachesEverySlangCompile() throws IOException {
        String build = read("build.gradle");

        assertTrue(build.contains("@Input abstract Property<Boolean> getProbes()"),
                "untracked, Gradle would hand back the previously compiled variant");
        assertTrue(build.contains("(probes.get() ? [\"-DFLUORITE_PROBES\"] : []) + extra"),
                "the define has to be on the shared slang command line, not on one shader");
        assertTrue(build.contains("providers.gradleProperty(\"fluorite.probes\").map { it != \"false\" }.orElse(true)"),
                "default ON: an ordinary build stays exactly what shipped");
    }

    /**
     * Every guard is closed, and what survives the strip still compiles.
     *
     * <p>{@code compositePrefixDebug} is read by the frame-splitting composite AFTER the guarded block,
     * so it lives outside the guard. A release build that could not resolve it would fail to compile —
     * which is the good failure; the bad one is a guard that swallows a closing brace and turns the rest
     * of the shader into the debug block's body.
     */
    @Test
    void theRaygenGuardsAreBalancedAndLeaveTheReleasePathIntact() throws IOException {
        String rgen = read("shaders/world/world.rgen.slang");

        assertEquals(2, directives(rgen, "#ifdef FLUORITE_PROBES"),
                "the debug view chain and the two probe writes");
        // Every guard in this file, SER's included, must be closed. Counted as DIRECTIVES -- at the start
        // of a line -- because the prose above one of them names #endif and would otherwise be counted.
        assertEquals(directives(rgen, "#ifdef "), directives(rgen, "#endif"),
                "unbalanced preprocessor guard");

        int declared = rgen.indexOf("bool compositePrefixDebug =");
        int guard = rgen.indexOf("#ifdef FLUORITE_PROBES", rgen.indexOf("DEBUG_VIEW_COMPOSITE_PREFIX_AB"));
        assertTrue(declared > 0 && declared < guard,
                "read after the guarded block, so it must be declared before it");

        assertTrue(rgen.contains("#ifdef FLUORITE_PROBES\n    restirStatsAccumulate("),
                "the stats accumulate is a probe and goes with them");
        assertTrue(rgen.contains("writeWaterMediumProbe("));
    }

    /** Occurrences at the start of a line: a preprocessor directive, never the word inside a comment. */
    private static int directives(String source, String directive) {
        return (int) source.lines().filter(line -> line.startsWith(directive)).count();
    }

    private static String read(String relativePath) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "repository root not found");
        // CR stripped: these sources are CRLF on disk, and every assertion here that spans two lines
        // would otherwise fail for the line ending rather than for the thing it is checking.
        return Files.readString(root.resolve(relativePath)).replace(String.valueOf((char) 13), "");
    }
}
