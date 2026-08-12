package io.github.dswepm.fluorite.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FetchVerifiedAssetTest {
    @TempDir Path temporary;

    @Test
    void fetchesExactBytesThenReusesTheVerifiedCacheWithoutTheSource() throws Exception {
        Path source = temporary.resolve("source.bin");
        byte[] bytes = "pinned external asset".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(source, bytes);
        String hash = FetchVerifiedAsset.sha256(source);
        Path output = temporary.resolve("cache/asset.bin");

        assertTrue(FetchVerifiedAsset.fetch(source.toUri(), hash, output));
        Files.delete(source);
        assertFalse(FetchVerifiedAsset.fetch(source.toUri(), hash, output));
        assertArrayEquals(bytes, Files.readAllBytes(output));
    }

    @Test
    void rejectsAndDoesNotPublishUnexpectedBytes() throws Exception {
        Path source = temporary.resolve("wrong.bin");
        Files.writeString(source, "wrong");
        Path output = temporary.resolve("cache/asset.bin");

        assertThrows(IOException.class, () -> FetchVerifiedAsset.fetch(
                source.toUri(), "0".repeat(64), output));
        assertFalse(Files.exists(output));
        assertFalse(Files.exists(output.resolveSibling("asset.bin.part")));
    }
}
