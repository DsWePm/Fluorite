package io.github.dswepm.fluorite.rt.material;

import io.github.dswepm.fluorite.rt.gen.MaterialHeaderData;
import io.github.dswepm.fluorite.rt.gen.MaterialHeaderData.Float4;
import io.github.dswepm.fluorite.rt.gen.WorldPushConstantsData;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialLayoutTest {
    @Test
    void reflectedMaterialHeaderMatchesHotAbi() {
        assertEquals(80, MaterialHeaderData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialHeaderData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialHeaderData(3, 5, 7, 11,
                new Float4(0.01f, 0.02f, 0.03f, 0.04f),
                new Float4(0.05f, 0.06f, 7.0f, 8.0f),
                new Float4(0.1f, 0.2f, 1.52f, 1.0f),
                new Float4(0.3f, 0.4f, 0.5f, 0.6f)).write(data);
        assertEquals(3, data.getInt(0));
        assertEquals(5, data.getInt(4));
        assertEquals(7, data.getInt(8));
        assertEquals(11, data.getInt(12));
        assertEquals(0.01f, data.getFloat(16));
        assertEquals(7.0f, data.getFloat(40));
        assertEquals(0.1f, data.getFloat(48));
        assertEquals(1.52f, data.getFloat(56));
        assertEquals(0.6f, data.getFloat(76));
    }

    @Test
    void reflectedWorldPushConstantsIncludeLightBuffersAndDebugView() {
        // ARITHMETIC REDONE, as the previous note here instructed whoever spent the next address.
        //
        // 14 uint64_t addresses (world/table/material/material-extension, 5 light buffers, path queue,
        // water probe, M21's per-column precipitation classes, and the ReSTIR reservoir store) + 3 uint,
        // tail-padded to the struct's 8-byte alignment: 14*8 + 12 = 124, rounded to 128.
        //
        // THERE IS NO HEADROOM LEFT. 128 is not a comfortable size, it is Vulkan's GUARANTEED FLOOR for
        // maxPushConstantsSize — the amount every conformant implementation must offer and the amount AMD
        // offers exactly. This struct now occupies all of it. A fifteenth address does not fit, and
        // neither does a fourth uint.
        //
        // The previous revision of this comment predicted this arithmetic while the count was thirteen,
        // and said what to do next: put the fifteenth in WorldPush instead. That is a BDA struct with no
        // such ceiling and costs one dereference. Addresses live here at all only because the closest-hit
        // shader reads pc on every hit and deliberately never touches WorldPush — so the test for whether
        // a new address belongs here is "does the hit shader need it", and for everything else the answer
        // is now no by necessity rather than by preference.
        //
        // (The retired 12th address, M9's sky-light grid, went with the CPU grid it pointed at in M15.0.)
        assertEquals(128, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L, 14L,
                15, 16, 17, 18.0f).write(data);
        assertEquals(4L, data.getLong(WorldPushConstantsData.MATERIAL_TABLE_ADDR_OFFSET));
        assertEquals(5L, data.getLong(WorldPushConstantsData.MATERIAL_EXTENSION_ADDR_OFFSET));
        assertEquals(6L, data.getLong(WorldPushConstantsData.LIGHT_BUF_ADDR_OFFSET));
        assertEquals(10L, data.getLong(WorldPushConstantsData.LIGHT_GRID_SPAN_ADDR_OFFSET));
        assertEquals(11L, data.getLong(WorldPushConstantsData.PATH_QUEUE_ADDR_OFFSET));
        assertEquals(12L, data.getLong(WorldPushConstantsData.WATER_PROBE_ADDR_OFFSET));
        assertEquals(13L, data.getLong(WorldPushConstantsData.RAIN_PRECIPITATION_ADDR_OFFSET));
        assertEquals(14L, data.getLong(WorldPushConstantsData.RESERVOIR_ADDR_OFFSET));
        assertEquals(15, data.getInt(WorldPushConstantsData.FRAME_INDEX_OFFSET));
        assertEquals(16, data.getInt(WorldPushConstantsData.DEBUG_VIEW_OFFSET));
        assertEquals(17, data.getInt(WorldPushConstantsData.SHADE_FLAGS_OFFSET));
        // M27's debug probe distance, at 124 -- the four bytes this block was already rounding up to
        // reach Vulkan's guaranteed 128. The size assertion above is what makes that claim rather than
        // this line: if it ever needed a byte more, 128 would have become 136 and said so.
        assertEquals(18.0f, data.getFloat(WorldPushConstantsData.DEBUG_PROBE_OFFSET));
    }
}
