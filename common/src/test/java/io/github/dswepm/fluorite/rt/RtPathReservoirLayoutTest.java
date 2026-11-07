package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** R26: approved 128 B canonical endpoint, retaining all earlier direction/RGB/primary offsets. */
final class RtPathReservoirLayoutTest {
    @Test
    void theHybridRecordHasTheApprovedStrideAndOffsets() {
        assertEquals(128, PackedPathReservoirData.BYTE_SIZE);
        assertEquals(0, PackedPathReservoirData.RECON_POS_OFFSET);
        assertEquals(12, PackedPathReservoirData.PATH_SEED_OFFSET);
        assertEquals(16, PackedPathReservoirData.PRIMARY_ORIGIN_OFFSET);
        assertEquals(28, PackedPathReservoirData.PRIMARY_PACKED_DIRECTION_OFFSET);
        assertEquals(32, PackedPathReservoirData.RECON_NRM_OFFSET);
        assertEquals(36, PackedPathReservoirData.DIR_K_OFFSET);
        assertEquals(40, PackedPathReservoirData.RADIANCE_OFFSET);
        assertEquals(44, PackedPathReservoirData.W_OFFSET);
        assertEquals(48, PackedPathReservoirData.JAC_DENOM_OFFSET);
        assertEquals(52, PackedPathReservoirData.TARGET_OWN_OFFSET);
        assertEquals(56, PackedPathReservoirData.CONF_PDF_OFFSET);
        assertEquals(60, PackedPathReservoirData.BITS_OFFSET);
        assertEquals(64, PackedPathReservoirData.OBJECT_ID_OFFSET);
        assertEquals(68, PackedPathReservoirData.PRIMARY_RAY_CONE_OFFSET);
        assertEquals(72, PackedPathReservoirData.RESERVED0_OFFSET);
        assertEquals(76, PackedPathReservoirData.RESERVED1_OFFSET);
        assertEquals(80, PackedPathReservoirData.DIR_Y_OFFSET);
        assertEquals(84, PackedPathReservoirData.DIR_Z_OFFSET);
        assertEquals(88, PackedPathReservoirData.NEE_PDF_OFFSET);
        assertEquals(92, PackedPathReservoirData.SOURCE_PDF_OFFSET);
        assertEquals(96, PackedPathReservoirData.RECON_OBJECT_ID_OFFSET);
        assertEquals(100, PackedPathReservoirData.RECON_PRIMITIVE_INDEX_OFFSET);
        assertEquals(104, PackedPathReservoirData.RECON_BARYCENTRICS_OFFSET);
        assertEquals(112, PackedPathReservoirData.RECON_GEOMETRY_INDEX_OFFSET);
        assertEquals(116, PackedPathReservoirData.INCOMING_DIR_X_OFFSET);
        assertEquals(120, PackedPathReservoirData.INCOMING_DIR_Y_OFFSET);
        assertEquals(124, PackedPathReservoirData.INCOMING_DIR_Z_OFFSET);
        assertEquals(530_841_600L, 1920L * 1080 * 2 * PackedPathReservoirData.BYTE_SIZE);
    }
}
