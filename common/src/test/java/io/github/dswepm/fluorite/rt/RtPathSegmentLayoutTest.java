package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.rt.gen.PackedPathSegmentData;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtPathSegmentLayoutTest {
    /**
     * Pins the wavefront record at 48 bytes.
     *
     * <p>{@code BYTE_SIZE} is generated from the shader's own std430 layout, so the renderer allocates the
     * right buffer whatever this becomes — nothing here is guarding correctness. What it guards is the
     * decision, because std430 makes the next field disproportionately expensive and the cost is invisible
     * at the point you would pay it.
     *
     * <p>Every field after {@code ro} is a uint, giving the struct 16-byte alignment. 48 is already a
     * multiple of 16, so it is exactly full: one more uint rounds the stride to 64. At 1440p the queue
     * holds two records per pixel — 2 x 2560 x 1440 = 7.37M records — so that step costs **+118 MB**, not
     * the +29 MB a single uint suggests. On an 8 GB card, with the terrain and entity acceleration
     * structures and the DLSS-RR guide buffers already resident, that is not free.
     *
     * <p>{@code pathFlags} carries five bits of state in bits 0-10 and the rest are unused. A new flag
     * costs nothing. A new field costs 118 MB. If a field really is unavoidable, spend all four spare
     * lanes at once rather than one at a time — the memory is paid the moment the stride crosses 48 — and
     * update this test together with the figure above.
     */
    @Test
    void pathSegmentRecordStaysWithinOneStd430Stride() {
        assertEquals(48, PackedPathSegmentData.BYTE_SIZE,
                "PackedPathSegment changed size; see this test for what that costs before re-pinning it");
    }
}
