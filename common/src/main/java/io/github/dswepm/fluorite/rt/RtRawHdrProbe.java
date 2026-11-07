package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import io.github.dswepm.fluorite.rt.accel.RtImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkBufferMemoryBarrier;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

/** Opt-in pre-RR RGBA16F readback and an armed, same-session OFF/ON/OFF experiment. */
public final class RtRawHdrProbe {
    static final int GRID_STRIDE = 8;
    static final Plan DEFAULT_PLAN = new Plan(96, 4, 96);
    private static final String[] PHASES = {"A1", "B", "A2"};

    record Plan(int warmup, int interval, int samples) {
        Plan {
            if (warmup < 0 || interval < 1 || samples < 2) throw new IllegalArgumentException("Invalid HDR capture plan");
        }
        int blockFrames() { return Math.addExact(warmup, Math.multiplyExact(interval, samples)); }
        int phase(long relativeDraw) {
            return relativeDraw < 0 || relativeDraw >= 3L * blockFrames() ? -1 : (int) (relativeDraw / blockFrames());
        }
        int sample(long relativeDraw) {
            if (phase(relativeDraw) < 0) return -1;
            long offset = relativeDraw % blockFrames() - warmup;
            return offset >= 0 && offset % interval == 0 ? (int) (offset / interval) : -1;
        }
    }

    record Moments(double r, double g, double b, double rr, double gg, double bb, long invalid) {}
    private record Capture(long frame, long draw, int phase, int sample, boolean pathOn, int width, int height,
                           double x, double y, double z, int pose, long tick, boolean rr, int spp, int bounces) {}

    private final Path directory;
    private final RtBuffer[] readbacks;
    private final Capture[] pending;
    private long draws;
    private long startDraw = -1;
    private long invalidPixels;
    private int written;
    private boolean announced;
    private BufferedWriter csv;

    public RtRawHdrProbe(int ringSize) {
        String property = System.getProperty("fluorite.rt.rawHdrProbe");
        directory = property == null || property.isBlank() ? null : Path.of(property).toAbsolutePath();
        readbacks = new RtBuffer[ringSize];
        pending = new Capture[ringSize];
    }

    /** Before arming and after the experiment, preserve the user's configured master switch. */
    public boolean pathEnabled(boolean configured) {
        if (directory == null) return configured;
        if (!announced) {
            FluoriteMod.LOGGER.info("RT raw HDR probe waiting for {}/arm; plan {}", directory, DEFAULT_PLAN);
            announced = true;
        }
        if (startDraw < 0 && Files.isRegularFile(directory.resolve("arm"))) startDraw = draws;
        int phase = startDraw < 0 ? -1 : DEFAULT_PLAN.phase(draws - startDraw);
        return phase < 0 ? configured : phase == 1;
    }

    /** Called after the existing graphics-use wait; adds no fence or extra wait. */
    public void reportRecycledSlot(int slot) {
        Capture capture = pending[slot];
        if (capture == null) return;
        pending[slot] = null;
        RtBuffer buffer = readbacks[slot];
        buffer.invalidate(0, buffer.size);
        ByteBuffer bytes = MemoryUtil.memByteBuffer(buffer.mapped, Math.toIntExact(buffer.size)).order(ByteOrder.LITTLE_ENDIAN);
        Moments m = moments(bytes, Math.multiplyExact(capture.width, capture.height));
        try {
            if (csv == null) {
                Files.createDirectories(directory);
                csv = Files.newBufferedWriter(directory.resolve("frames.csv"), StandardOpenOption.CREATE_NEW);
                csv.write("frame,draw,phase,sample,path_on,width,height,camera_x,camera_y,camera_z,pose,tick,rr,spp,bounces,mean_r,mean_g,mean_b,spatial_second_r,spatial_second_g,spatial_second_b,invalid_pixels,grid\n");
            }
            String name = String.format(Locale.ROOT, "%s-%03d-f%d.rgb16", PHASES[capture.phase], capture.sample, capture.frame);
            Files.write(directory.resolve(name), grid(bytes, capture.width, capture.height), StandardOpenOption.CREATE_NEW);
            double count = (double) capture.width * capture.height;
            double scale = m.invalid == 0 ? 1.0 / count : Double.NaN;
            csv.write(String.format(Locale.ROOT,
                    "%d,%d,%s,%d,%b,%d,%d,%.17g,%.17g,%.17g,%d,%d,%b,%d,%d,%.17g,%.17g,%.17g,%.17g,%.17g,%.17g,%d,%s%n",
                    capture.frame, capture.draw, PHASES[capture.phase], capture.sample, capture.pathOn,
                    capture.width, capture.height, capture.x, capture.y, capture.z, capture.pose, capture.tick,
                    capture.rr, capture.spp, capture.bounces, m.r * scale, m.g * scale, m.b * scale,
                    m.rr * scale, m.gg * scale, m.bb * scale, m.invalid, name));
            csv.flush();
            invalidPixels += m.invalid;
            written++;
            if (written % 24 == 0) FluoriteMod.LOGGER.info("RT raw HDR probe {}/{} samples, phase {}, invalid pixels {}",
                    written, 3 * DEFAULT_PLAN.samples, PHASES[capture.phase], invalidPixels);
            if (written == 3 * DEFAULT_PLAN.samples) {
                Files.writeString(directory.resolve("complete.json"), String.format(Locale.ROOT,
                        "{\"samples\":%d,\"grid_stride\":%d,\"invalid_pixels\":%d,\"format\":\"RGB16F little endian\",\"source\":\"pre-RR trace color\"}%n",
                        written, GRID_STRIDE, invalidPixels), StandardOpenOption.CREATE_NEW);
                FluoriteMod.LOGGER.info("RT raw HDR probe complete: {} samples in {}", written, directory);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Writing raw HDR experiment", e);
        }
    }

    /** The source is pass B's noisy composite, before RR, exposure or display mapping. */
    public void record(RtContext ctx, VkCommandBuffer cmd, MemoryStack stack, int slot, RtImage source,
                       long frame, boolean pathOn, double x, double y, double z, int pose,
                       long tick, boolean rr, int spp, int bounces) {
        if (directory == null) return;
        long draw = draws++;
        if (startDraw < 0) return;
        long relative = draw - startDraw;
        int phase = DEFAULT_PLAN.phase(relative), sample = DEFAULT_PLAN.sample(relative);
        if (sample < 0) return;
        if (pathOn != (phase == 1)) throw new IllegalStateException("Raw HDR phase does not match the actual path pipeline");
        if (pending[slot] != null) throw new IllegalStateException("Raw HDR slot was not recycled");
        long size = Math.multiplyExact(8L, Math.multiplyExact((long) source.width, source.height));
        if (readbacks[slot] != null && readbacks[slot].size != size) {
            readbacks[slot].destroy();
            readbacks[slot] = null;
        }
        if (readbacks[slot] == null) readbacks[slot] = ctx.createBuffer(size, VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                true, "raw HDR readback " + slot);

        VkImageMemoryBarrier.Buffer image = VkImageMemoryBarrier.calloc(1, stack);
        image.get(0).sType$Default().image(source.image)
                .oldLayout(VK10.VK_IMAGE_LAYOUT_GENERAL).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
                .srcAccessMask(VK10.VK_ACCESS_SHADER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        image.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                0, null, null, image);
        VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
        copy.get(0).imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
        copy.get(0).imageExtent().set(source.width, source.height, 1);
        VK10.vkCmdCopyImageToBuffer(cmd, source.image, VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                readbacks[slot].handle, copy);
        image.get(0).oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL).newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_READ_BIT)
                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT | VK10.VK_ACCESS_SHADER_WRITE_BIT);
        VkBufferMemoryBarrier.Buffer host = VkBufferMemoryBarrier.calloc(1, stack);
        host.get(0).sType$Default().buffer(readbacks[slot].handle).offset(0).size(size)
                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT).dstAccessMask(VK10.VK_ACCESS_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED);
        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT | VK10.VK_PIPELINE_STAGE_HOST_BIT, 0, null, host, image);
        pending[slot] = new Capture(frame, draw, phase, sample, pathOn, source.width, source.height,
                x, y, z, pose, tick, rr, spp, bounces);
    }

    static Moments moments(ByteBuffer bytes, int pixels) {
        double r = 0, g = 0, b = 0, rr = 0, gg = 0, bb = 0;
        long invalid = 0;
        for (int i = 0; i < pixels; i++) {
            float x = Float.float16ToFloat(bytes.getShort(i * 8));
            float y = Float.float16ToFloat(bytes.getShort(i * 8 + 2));
            float z = Float.float16ToFloat(bytes.getShort(i * 8 + 4));
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) { invalid++; continue; }
            r += x; g += y; b += z;
            rr += (double) x * x; gg += (double) y * y; bb += (double) z * z;
        }
        return new Moments(r, g, b, rr, gg, bb, invalid);
    }

    static byte[] grid(ByteBuffer bytes, int width, int height) {
        int startX = Math.min(GRID_STRIDE / 2, width - 1), startY = Math.min(GRID_STRIDE / 2, height - 1);
        int columns = (width - 1 - startX) / GRID_STRIDE + 1, rows = (height - 1 - startY) / GRID_STRIDE + 1;
        ByteBuffer result = ByteBuffer.allocate(Math.multiplyExact(columns * rows, 6)).order(ByteOrder.LITTLE_ENDIAN);
        for (int y = startY; y < height; y += GRID_STRIDE)
            for (int x = startX; x < width; x += GRID_STRIDE) {
                int at = (y * width + x) * 8;
                result.putShort(bytes.getShort(at)).putShort(bytes.getShort(at + 2)).putShort(bytes.getShort(at + 4));
            }
        return result.array();
    }

    public void destroy() {
        for (int i = 0; i < readbacks.length; i++) {
            if (readbacks[i] != null) { readbacks[i].destroy(); readbacks[i] = null; }
            pending[i] = null;
        }
        if (csv != null) {
            try { csv.close(); } catch (IOException e) { throw new IllegalStateException(e); }
            csv = null;
        }
    }
}
