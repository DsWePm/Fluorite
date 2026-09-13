package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M30 (workstream C) C-0: the LabPBR height channel — the _n texture's ALPHA — survives the whole trip
 * from resource pack to the shading side, so parallax (C-1) and puddle-relief linkage (C-2) can build on
 * it without touching the upload pipeline.
 *
 * <p>Pinned at the source level because nothing at runtime reads the height yet: the decode writes it
 * (RtMaterialTextureData), the page upload carries four channels (R8G8B8A8_UNORM — an RG8 or RGB format
 * here would silently amputate it), and the rchit fetches the full float4 into perturbNormal even though
 * the .w lane is currently dropped on the floor. C-1 adds the read; this test makes sure the read has
 * something to read.
 */
final class RtReliefContractTest {

    @Test
    void theDecodeWritesTheHeightIntoTheNormalAoAlpha() throws IOException {
        String data = source("common/src/main/java/io/github/dswepm/fluorite/rt/material/RtMaterialTextureData.java");
        // LabPBR _n alpha is summed per 2x2 mip sample and written back as the pixel's alpha.
        assertTrue(data.contains("heightValue += src.normalAo[si + 3];"));
        assertTrue(data.contains("normalAo[di + 3] = clamp01(heightValue * inv);"));
    }

    @Test
    void thePageUploadCarriesFourChannels() throws IOException {
        String page = source("common/src/main/java/io/github/dswepm/fluorite/rt/material/RtMaterialPageTexture.java");
        // Both the image and the view: an RG8 shortcut here would amputate the height channel with no
        // error anywhere — the shader would just read 1.0 (or 0.0) forever.
        assertTrue(page.contains("VK_FORMAT_R8G8B8A8_UNORM"));
    }

    @Test
    void theHeightReachesTheShadingSideAsPartOfTheFetch() throws IOException {
        String rchit = source("shaders/world/world.rchit.slang");
        // The page fetch returns the full float4 and hands it to perturbNormal — the height is on the
        // shading side even before anything reads lane .w.
        assertTrue(rchit.contains("float4 normalAo = samplePageNormalAo(page, pageUv, materialLod);"));
        assertTrue(rchit.contains("vdir, normalAo, surface.ao)"));
    }

    private static String source(String relativePath) throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return String.join("\n", java.nio.file.Files.readAllLines(root.resolve(relativePath)));
    }
}
