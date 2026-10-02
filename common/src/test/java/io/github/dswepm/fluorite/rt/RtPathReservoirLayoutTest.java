package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** D231: the user approved 80 B, including source density and the primary/LoD hooks. */
final class RtPathReservoirLayoutTest {
    @Test
    void theHybridRecordHasTheApprovedStrideAndOffsets() {
        assertEquals(80, PackedPathReservoirData.BYTE_SIZE);
        assertEquals(0, PackedPathReservoirData.RECON_POS_OFFSET);
        assertEquals(12, PackedPathReservoirData.PATH_SEED_OFFSET);
        assertEquals(16, PackedPathReservoirData.PRIMARY_POS_OFFSET);
        assertEquals(28, PackedPathReservoirData.PRIMARY_NRM_OFFSET);
        assertEquals(32, PackedPathReservoirData.RECON_NRM_OFFSET);
        assertEquals(36, PackedPathReservoirData.DIR_K_OFFSET);
        assertEquals(40, PackedPathReservoirData.RADIANCE_OFFSET);
        assertEquals(44, PackedPathReservoirData.W_OFFSET);
        assertEquals(48, PackedPathReservoirData.JAC_DENOM_OFFSET);
        assertEquals(52, PackedPathReservoirData.TARGET_OWN_OFFSET);
        assertEquals(56, PackedPathReservoirData.CONF_PDF_OFFSET);
        assertEquals(60, PackedPathReservoirData.BITS_OFFSET);
        assertEquals(64, PackedPathReservoirData.OBJECT_ID_OFFSET);
        assertEquals(68, PackedPathReservoirData.PRIMARY_UV_OFFSET);
        assertEquals(72, PackedPathReservoirData.RESERVED0_OFFSET);
        assertEquals(76, PackedPathReservoirData.RESERVED1_OFFSET);
        assertEquals(331_776_000L, 1920L * 1080 * 2 * PackedPathReservoirData.BYTE_SIZE);
    }
}
