package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import java.util.List;

/** Per-retired-push-slot immutable history roots, plus GPU publication stamps. No pixel-side growth. */
final class RtPathHistory {
    static final int HEADER_BYTES = 48;
    private final RtBuffer[] roots;
    private RtBuffer stamps;
    private boolean clearStamps;
    private final RtPathHistoryState state = new RtPathHistoryState();
    private RtPathHistoryState.Frame pending;
    private List<FluoriteConfig.RuntimeSetting<?>> settings;

    RtPathHistory(int ringSize) { roots = new RtBuffer[ringSize]; }

    RtPathHistoryState.Context context(Object level, int width, int height, int x, int y, int z,
                                      int flags, float red, float green, float blue) {
        if (settings == null) {
            FluoriteConfig.ensureRegistered();
            settings = FluoriteConfig.settings();
        }
        Object[] parameters = new Object[settings.size() + 4];
        parameters[0] = flags;
        parameters[1] = Float.floatToRawIntBits(red);
        parameters[2] = Float.floatToRawIntBits(green);
        parameters[3] = Float.floatToRawIntBits(blue);
        for (int i = 0; i < settings.size(); i++) parameters[i + 4] = settings.get(i).get();
        return new RtPathHistoryState.Context(level, width, height, x, y, z, parameters);
    }

    /** Caller has awaited this exact push slot's graphics use before allocating or writing it. */
    long prepare(RtContext ctx, int slot, long store, long frame, RtPathHistoryState.Context context,
                 Object[] terrain, RtPathSurfaceIdentity[] entities, boolean temporal, int cap) {
        if (stamps == null) {
            stamps = ctx.createBuffer(8, VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
                    | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false, "Path history frame stamps");
            clearStamps = true;
        }
        pending = state.prepare(context, frame, terrain, entities);
        long bytes = HEADER_BYTES + (long) (pending.terrainWords.length + pending.entityWords.length) * 4;
        RtBuffer root = roots[slot];
        if (root == null || root.size < bytes) {
            if (root != null) root.destroy();
            root = roots[slot] = ctx.createBuffer(Math.max(bytes, root == null ? 64 : root.size * 2),
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "Path history root " + slot);
        }
        long address = root.mapped;
        long terrainOffset = HEADER_BYTES;
        long entityOffset = terrainOffset + (long) pending.terrainWords.length * 4;
        MemoryUtil.memPutLong(address, store);
        MemoryUtil.memPutLong(address + 8, stamps.deviceAddress);
        MemoryUtil.memPutLong(address + 16, root.deviceAddress + terrainOffset);
        MemoryUtil.memPutLong(address + 24, root.deviceAddress + entityOffset);
        MemoryUtil.memPutInt(address + 32, terrain.length);
        MemoryUtil.memPutInt(address + 36, entities.length);
        MemoryUtil.memPutInt(address + 40, temporal && pending.previousValid ? 1 : 0);
        MemoryUtil.memPutInt(address + 44, cap);
        for (int i = 0; i < pending.terrainWords.length; i++)
            MemoryUtil.memPutInt(address + terrainOffset + i * 4L, pending.terrainWords[i]);
        for (int i = 0; i < pending.entityWords.length; i++)
            MemoryUtil.memPutInt(address + entityOffset + i * 4L, pending.entityWords[i]);
        root.flush(0, bytes);
        return root.deviceAddress;
    }

    void recordReset(VkCommandBuffer command) {
        if (clearStamps) {
            VK10.vkCmdFillBuffer(command, stamps.handle, 0, 8, 0);
            clearStamps = false;
        }
    }

    void publish() {
        if (pending != null) { state.publish(pending); pending = null; }
    }

    /** Called with the same quiescent ownership as the path reservoir store. */
    void destroy() {
        for (int i = 0; i < roots.length; i++) {
            if (roots[i] != null) roots[i].destroy();
            roots[i] = null;
        }
        if (stamps != null) stamps.destroy();
        stamps = null;
        pending = null;
        state.reset();
    }
}
