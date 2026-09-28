package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every name a call site hands to {@link RtFrameStats} must be registered in its name arrays, because
 * {@code stage()}/{@code count()} throw on an unknown name -- and the throw lands MID-FRAME, on the
 * first frame the stats are switched on or a counter first fires, where it fails the whole composite
 * and hands rendering back to vanilla. That is not hypothetical: D217's pathReservoir counter was
 * recorded at the call site without being registered here, and the user's first flip of the S2 switch
 * took the renderer down (the D208-era gpu.* comment in RtFrameStats documents the same trap).
 *
 * <p>The throw only fires when stats are enabled, which is never during ordinary development builds'
 * automated runs either -- so no runtime exercise would have caught it. The registry is now public
 * ({@link RtFrameStats#FRAME_STAGE_NAMES}/{@link RtFrameStats#FRAME_COUNTER_NAMES}) and this test
 * scans every {@code FRAME.count("...")}, {@code FRAME.stage("...")} and GPU zone-name literal under
 * src/main, failing in {@code gradlew test} instead of in the user's game.
 */
final class RtFrameStatsNameRegistryTest {

    private static final Pattern COUNT_SITE = Pattern.compile("FRAME\\.count\\(\\s*\"([^\"]+)\"");
    private static final Pattern STAGE_SITE = Pattern.compile("FRAME\\.stage\\(\\s*\"([^\"]+)\"");
    private static final Pattern GPU_ZONE = Pattern.compile("\\\"(gpu\\.[A-Za-z]+)\\\"");

    @Test
    void everyCountedNameIsRegistered() throws IOException {
        assertAllRegistered(COUNT_SITE, RtFrameStats.FRAME_COUNTER_NAMES, "count");
    }

    @Test
    void everyStagedNameIsRegistered() throws IOException {
        assertAllRegistered(STAGE_SITE, RtFrameStats.FRAME_STAGE_NAMES, "stage");
    }

    @Test
    void everyGpuZoneNameIsRegistered() throws IOException {
        // GPU zone names live at the RtGpuTimers.create call in RtComposite and are resolved into
        // FRAME stage names several frames later -- a missing one surfaces as a delayed composite
        // failure, not at the dispatch. Scanning the whole main tree for "gpu.*" literals keeps the
        // net under any future redeclaration site.
        assertAllRegistered(GPU_ZONE, RtFrameStats.FRAME_STAGE_NAMES, "gpu zone");
    }

    private static void assertAllRegistered(Pattern pattern, String[] registry, String kind)
            throws IOException {
        Set<String> registryNames = Set.of(registry);
        Set<String> used = new LinkedHashSet<>();
        try (Stream<Path> paths = Files.walk(mainRoot())) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    Matcher m = pattern.matcher(Files.readString(p));
                    while (m.find()) {
                        used.add(m.group(1));
                    }
                } catch (IOException ignored) {
                    // an unreadable file cannot contribute a call site
                }
            });
        }
        for (String name : used) {
            assertTrue(registryNames.contains(name),
                    () -> kind + " name \"" + name + "\" is used at a call site but not registered in "
                            + "RtFrameStats -- it will throw mid-frame and revert rendering to vanilla. "
                            + "Add it to the registry in the same commit.");
        }
    }

    private static Path mainRoot() {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("Could not locate repository root");
        }
        return root.resolve("common/src/main/java");
    }
}
