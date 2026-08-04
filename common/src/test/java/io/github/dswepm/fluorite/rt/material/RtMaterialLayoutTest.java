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
        // 11 uint64_t addresses (world/table/material/material-extension, 5 light buffers, path
        // queue) + 3 uint, tail-padded to the struct's 8-byte alignment. Still under Vulkan's
        // guaranteed 128, but the headroom is now 24 bytes: the next address added here spends 8 of
        // them, and whoever adds it should redo this arithmetic rather than trust this comment.
        // (The 12th address, M9's sky-light grid, was retired in M15.0 with the CPU grid it pointed
        // at.) Every uint added here is paid by the closest-hit shader, which reads pc on every hit
        // and never dereferences WorldPush — that is why shading switches land here rather than there.
        assertEquals(112, WorldPushConstantsData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(WorldPushConstantsData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new WorldPushConstantsData(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L,
                13, 14, 15).write(data);
        assertEquals(4L, data.getLong(WorldPushConstantsData.MATERIAL_TABLE_ADDR_OFFSET));
        assertEquals(5L, data.getLong(WorldPushConstantsData.MATERIAL_EXTENSION_ADDR_OFFSET));
        assertEquals(6L, data.getLong(WorldPushConstantsData.LIGHT_BUF_ADDR_OFFSET));
        assertEquals(10L, data.getLong(WorldPushConstantsData.LIGHT_GRID_SPAN_ADDR_OFFSET));
        assertEquals(11L, data.getLong(WorldPushConstantsData.PATH_QUEUE_ADDR_OFFSET));
        assertEquals(12L, data.getLong(WorldPushConstantsData.WATER_PROBE_ADDR_OFFSET));
        assertEquals(13, data.getInt(WorldPushConstantsData.FRAME_INDEX_OFFSET));
        assertEquals(14, data.getInt(WorldPushConstantsData.DEBUG_VIEW_OFFSET));
        assertEquals(15, data.getInt(WorldPushConstantsData.SHADE_FLAGS_OFFSET));
    }
}
