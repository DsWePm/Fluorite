package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathReservoirData;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPathReservoirLayoutTest {
    /**
     * Pins the path reservoir record at 48 bytes -- the number the store's slot arithmetic assumes.
     *
     * <p>{@code BYTE_SIZE} is generated from the shader's own std430 layout, and RtComposite now takes
     * its allocation stride from the same generated constant, so a drift is impossible by construction.
     * This test pins the VALUE because the value is a decision: 48 is the queue record's cache line, a
     * per-frame read/write buffer must not straddle one, and the only room left is none -- ten fields
     * already fill it exactly ({@code reconPos} leads because std430 gives float3 a 16-byte alignment;
     * any uint declared before it pads the stride to 64, which is what D220's GPU fault was made of).
     * A larger record is a decision about ~35% more VRAM per switch-on at 1080p, made here or nowhere.
     */
    @Test
    void pathReservoirRecordStaysAt48Bytes() {
        assertEquals(48, PackedPathReservoirData.BYTE_SIZE,
                "PackedPathReservoir changed stride; see this test for what that costs before re-pinning it");
    }
}
