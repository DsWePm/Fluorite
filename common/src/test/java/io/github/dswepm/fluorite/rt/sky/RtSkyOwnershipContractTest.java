package io.github.dswepm.fluorite.rt.sky;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Guard the owned sky resource graph at its real owner; Vulkan validation is the native regression. */
class RtSkyOwnershipContractTest {
    @Test
    void everyOwnedSkyIsReleasedBeforeItsDeviceContext() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        String owner = code(Files.readString(root.resolve(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java")));
        int start = owner.indexOf("public void destroy() {");
        String teardown = owner.substring(start, owner.indexOf("\n    }", start));
        var fields = Pattern.compile("private\\s+RtSky\\s+(\\w+)\\s*;").matcher(owner);
        int count = 0;
        while (fields.find()) {
            String member = fields.group(1);
            assertTrue(owner.contains(member + " = RtSky.create("), "the owner creates this graph");
            assertTrue(teardown.contains(member + ".destroy();"), "missing owned sky cleanup: " + member);
            assertTrue(teardown.contains(member + " = null;"), "device recreation must not reuse a dead sky");
            count++;
        }
        assertTrue(count > 0);
        String lifecycle = code(Files.readString(root.resolve(
                "common/src/main/java/io/github/dswepm/fluorite/FluoriteLifecycle.java")));
        int shutdown = lifecycle.indexOf("public static void shutdown()");
        int idle = lifecycle.indexOf("ctx.waitIdle();", shutdown);
        int release = lifecycle.indexOf("RtComposite.INSTANCE.destroy();", shutdown);
        int device = lifecycle.indexOf("ctx.destroy();", shutdown);
        assertTrue(idle >= 0 && release > idle && device > release,
                "retain the GPU idle wait and release the sky while its context is alive");
    }

    private static String code(String text) {
        return text.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("(?m)//.*$", "");
    }

    @Test
    void skyReleasesEveryScalarBakeIncludingWaterDeformation() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) root = root.getParent();
        assertNotNull(root);
        String sky = code(Files.readString(root.resolve(
                "common/src/main/java/io/github/dswepm/fluorite/rt/sky/RtSky.java")));
        int start = sky.indexOf("public void destroy() {");
        String teardown = sky.substring(start, sky.indexOf("\n    }", start));
        var fields = Pattern.compile("private\\s+(?:final\\s+)?Bake\\s+(\\w+)\\s*;").matcher(sky);
        int count = 0;
        while (fields.find()) {
            String member = fields.group(1);
            assertTrue(teardown.contains(member + ".destroy(vk);"), "missing owned bake cleanup: " + member);
            count++;
        }
        assertTrue(count > 0);
    }
}
