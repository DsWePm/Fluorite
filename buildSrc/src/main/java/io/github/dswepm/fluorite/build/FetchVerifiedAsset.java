package io.github.dswepm.fluorite.build;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLConnection;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/** Downloads an external build asset only when absent and refuses bytes that do not match its pin. */
public abstract class FetchVerifiedAsset extends DefaultTask {
    @Input public abstract Property<String> getSourceUrl();
    @Input public abstract Property<String> getSha256();
    @OutputFile public abstract RegularFileProperty getOutputFile();

    @TaskAction
    public void fetch() throws IOException {
        Path output = getOutputFile().get().getAsFile().toPath();
        String expected = getSha256().get().toLowerCase(Locale.ROOT);
        try {
            boolean downloaded = fetch(URI.create(getSourceUrl().get()), expected, output);
            getLogger().lifecycle(downloaded
                    ? "Downloaded and verified {} ({})"
                    : "Reused verified external asset {} ({})", output, expected);
        } catch (IOException exception) {
            throw new GradleException("Could not obtain verified End HDR source. Supply the exact asset with "
                    + "-PendHdrSource=<path>; expected SHA-256 " + expected, exception);
        }
    }

    /** Package-visible for deterministic tests using a local file URI. */
    static boolean fetch(URI source, String expectedSha256, Path output) throws IOException {
        String expected = expectedSha256.toLowerCase(Locale.ROOT);
        if (Files.isRegularFile(output) && expected.equals(sha256(output))) return false;

        Files.createDirectories(output.getParent());
        Path partial = output.resolveSibling(output.getFileName() + ".part");
        Files.deleteIfExists(partial);
        try {
            URLConnection connection = source.toURL().openConnection();
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("User-Agent", "Fluorite-build/1.0 (+https://github.com/DsWePm/Fluorite)");
            MessageDigest digest = sha256Digest();
            try (var raw = new BufferedInputStream(connection.getInputStream(), 1 << 20);
                 var input = new DigestInputStream(raw, digest);
                 var out = new BufferedOutputStream(Files.newOutputStream(partial), 1 << 20)) {
                input.transferTo(out);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            if (!expected.equals(actual)) {
                throw new IOException("External asset SHA-256 mismatch: expected " + expected
                        + ", downloaded " + actual);
            }
            try {
                Files.move(partial, output, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var input = new BufferedInputStream(Files.newInputStream(path), 1 << 20)) {
            byte[] buffer = new byte[1 << 20];
            for (int read; (read = input.read(buffer)) >= 0;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }
}
