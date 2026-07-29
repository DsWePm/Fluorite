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
     * <p>std430 gives the struct 16-byte alignment. Eight uints after {@code ro} come to 44 bytes, so the
     * stride is 48 with four bytes of tail padding: exactly one uint of room, and no more. A tenth uint
     * fills it for free; an eleventh rounds the stride to 64. At 1440p the queue holds two records per
     * pixel — 2 x 2560 x 1440 = 7.37M records — so that step costs **+118 MB**, not the +29 MB a single
     * uint suggests. On an 8 GB card, with the terrain and entity acceleration structures and the DLSS-RR
     * guide buffers already resident, that is not free.
     *
     * <p>So there is one lane to spend and it should go to whichever field is worth the most. The
     * participating-media work wants it for packed scatter parameters. {@code pathFlags} uses bits 0-11
     * and everything above is unused, so a new flag costs nothing and should never take the lane. If a
     * second field becomes unavoidable, design all of them at once rather than adding one at a time — the
     * 118 MB is paid the moment the stride crosses 48 — and update this test with the new figure.
     */
    @Test
    void pathSegmentRecordStaysWithinOneStd430Stride() {
        assertEquals(48, PackedPathSegmentData.BYTE_SIZE,
                "PackedPathSegment changed size; see this test for what that costs before re-pinning it");
    }
}
