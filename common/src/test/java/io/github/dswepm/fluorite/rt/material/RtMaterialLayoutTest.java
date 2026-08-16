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
        // 13 uint64_t addresses (world/table/material/material-extension, 5 light buffers, path queue,
        // water probe, and M21's per-column precipitation classes) + 3 uint, tail-padded to the struct's
        // 8-byte alignment: 13*8 + 12 = 116, rounded to 120. Vulkan guarantees 128.
        //
        // THE HEADROOM IS NOW 8 BYTES, and that is the whole of it. One more address takes the struct to
        // 14*8 + 12 = 124, padded to exactly 128 — the guaranteed floor, with nothing spare and no
        // margin for a uint after it. The one after that does not fit on hardware that offers only the
        // minimum, and AMD offers exactly the minimum. Whoever needs a fourteenth address should weigh
        // putting it in WorldPush instead, which is a BDA struct with no such limit and costs one
        // dereference; the reason addresses live here at all is that the closest-hit shader reads pc on
        // every hit and never touches WorldPush.
        //
        // (The retired 12th address, M9's sky-light grid, went with the CPU grid it pointed at in M15.0.)
        assertEquals(120, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L, 13L,
                14, 15, 16).write(data);
        assertEquals(4L, data.getLong(WorldPushConstantsData.MATERIAL_TABLE_ADDR_OFFSET));
        assertEquals(5L, data.getLong(WorldPushConstantsData.MATERIAL_EXTENSION_ADDR_OFFSET));
        assertEquals(6L, data.getLong(WorldPushConstantsData.LIGHT_BUF_ADDR_OFFSET));
        assertEquals(10L, data.getLong(WorldPushConstantsData.LIGHT_GRID_SPAN_ADDR_OFFSET));
        assertEquals(11L, data.getLong(WorldPushConstantsData.PATH_QUEUE_ADDR_OFFSET));
        assertEquals(12L, data.getLong(WorldPushConstantsData.WATER_PROBE_ADDR_OFFSET));
        assertEquals(13L, data.getLong(WorldPushConstantsData.RAIN_PRECIPITATION_ADDR_OFFSET));
        assertEquals(14, data.getInt(WorldPushConstantsData.FRAME_INDEX_OFFSET));
        assertEquals(15, data.getInt(WorldPushConstantsData.DEBUG_VIEW_OFFSET));
        assertEquals(16, data.getInt(WorldPushConstantsData.SHADE_FLAGS_OFFSET));
    }
}
