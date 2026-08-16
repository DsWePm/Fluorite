package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * One Vulkan result check, and it is {@link RtContext#check}.
 *
 * <p>That method reports {@code VK_ERROR_DEVICE_LOST} to {@code VulkanDiagnostics} before it throws.
 * A private {@code check} that only throws looks identical at the call site and behaves identically in
 * every case except the one the diagnostics exist for -- so the copies are invisible until the day a
 * device is lost and the forensics are not there.
 *
 * <p>Three classes had such a copy: RtMaterialPageTexture, RtEnvironmentTextures, RtHighCloudTextures.
 * All three allocate images and create image views, which is precisely where a lost device surfaces, so
 * the copies were switched off at the call sites most likely to need them. Thirteen pipeline classes
 * called RtContext.check correctly over the same period, which is what made the three legible as copies
 * rather than as a house convention.
 *
 * <p>Structural rather than behavioural for the usual reason: the JVM cannot lose a Vulkan device, so
 * there is nothing here to execute. What can be checked is that the weaker implementation does not come
 * back, and that is what this does.
 */
final class RtVulkanErrorHandlingTest {
    /** A method declaration named check taking a Vulkan result -- the shape RtContext.check already has. */
    private static final Pattern LOCAL_CHECK =
            Pattern.compile("(?m)^\\s*(?:private|static|public|protected|final|\\s)*\\s+void\\s+check\\s*\\(\\s*(?:final\\s+)?int\\b");

    @Test
    void noClassReimplementsTheVulkanResultCheck() throws IOException {
        Path root = repositoryRoot();
        Path owner = root.resolve("common/src/main/java/io/github/dswepm/fluorite/rt/RtContext.java");

        List<String> offenders = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root.resolve("common/src/main/java"))) {
            for (Path source : (Iterable<Path>) sources.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (source.equals(owner)) {
                    continue;
                }
                if (LOCAL_CHECK.matcher(Files.readString(source)).find()) {
                    offenders.add(root.relativize(source).toString().replace('\\', '/'));
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("These classes declare their own Vulkan result check instead of using RtContext.check: "
                    + offenders + ". RtContext.check reports VK_ERROR_DEVICE_LOST to VulkanDiagnostics "
                    + "before throwing; a local copy that only throws silently drops that forensics on the "
                    + "paths where a device is actually lost.");
        }

        // And the owner still has the behaviour worth centralising. Without this, deleting the
        // device-lost report from RtContext would leave every assertion above passing.
        String context = Files.readString(owner);
        assertTrue(context.contains("VK_ERROR_DEVICE_LOST"),
                "RtContext.check must still special-case a lost device");
        assertTrue(context.contains("VulkanDiagnostics.reportDeviceLost"),
                "RtContext.check must still report a lost device to VulkanDiagnostics");
    }

    private static Path repositoryRoot() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return root;
    }
}
