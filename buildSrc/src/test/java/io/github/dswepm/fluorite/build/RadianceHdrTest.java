package io.github.dswepm.fluorite.build;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class RadianceHdrTest {
    @TempDir Path temporary;

    @Test
    void decodesModernRleRgbeWithoutApplyingDisplayGamma() throws Exception {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("#?RADIANCE\nGAMMA=1\nEXPOSURE=1\nFORMAT=32-bit_rle_rgbe\n\n-Y 1 +X 8\n"
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        file.write(new byte[]{2, 2, 0, 8});
        for (int channel = 0; channel < 3; channel++) file.write(new byte[]{(byte) 136, (byte) (128 + channel * 32)});
        file.write(new byte[]{(byte) 136, (byte) 129});
        Path path = temporary.resolve("fixture.hdr");
        Files.write(path, file.toByteArray());

        RadianceHdr.Image image = RadianceHdr.read(path);
        assertEquals(8, image.width());
        assertEquals(1, image.height());
        assertEquals(1.0f, image.red(3, 0));
        assertEquals(1.25f, image.green(3, 0));
        assertEquals(1.5f, image.blue(3, 0));
    }

    @Test
    void rejectsAnOrientationThatWouldSilentlyMirrorTheEnvironment() throws Exception {
        Path path = temporary.resolve("mirrored.hdr");
        Files.writeString(path, "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n\n+Y 1 +X 8\n");
        assertThrows(java.io.IOException.class, () -> RadianceHdr.read(path));
    }
}
