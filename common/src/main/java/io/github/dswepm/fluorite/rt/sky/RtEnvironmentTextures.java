package io.github.dswepm.fluorite.rt.sky;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import net.minecraft.client.Minecraft;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Resource-epoch-owned environment arrays. One layer set corresponds to one environment preset. */
public final class RtEnvironmentTextures {
    private final SampledArray radiance;
    private final SampledArray transfer;
    private final SampledArray diskEntry;
    private final SampledArray diskExit;
    private final Map<RtSkyPreset.Environment, Entry> entries;

    private RtEnvironmentTextures(SampledArray radiance, SampledArray transfer,
                                  SampledArray diskEntry, SampledArray diskExit,
                                  Map<RtSkyPreset.Environment, Entry> entries) {
        this.radiance = radiance;
        this.transfer = transfer;
        this.diskEntry = diskEntry;
        this.diskExit = diskExit;
        this.entries = entries;
    }

    public static RtEnvironmentTextures load(RtContext ctx, RtSkyPresets presets) {
        List<RtSkyPreset.Environment> environments = presets.environments().stream()
                .distinct()
                .sorted(Comparator.comparing(environment -> environment.radianceTexture().toString()
                        + "\n" + environment.transferTexture() + "\n" + environment.diskEntryTexture()
                        + "\n" + environment.diskExitTexture()))
                .toList();
        List<RtKtx2.Image> radianceImages = new ArrayList<>();
        List<RtKtx2.Image> transferImages = new ArrayList<>();
        List<RtKtx2.Image> diskEntryImages = new ArrayList<>();
        List<RtKtx2.Image> diskExitImages = new ArrayList<>();
        Map<RtSkyPreset.Environment, Entry> entries = new IdentityHashMap<>();
        for (RtSkyPreset.Environment environment : environments) {
            try {
                RtKtx2.Image sky = load(environment.radianceTexture());
                RtKtx2.Image kerr = load(environment.transferTexture());
                RtKtx2.Image diskEntry = load(environment.diskEntryTexture());
                RtKtx2.Image diskExit = load(environment.diskExitTexture());
                validateSky(sky);
                validateTransfer(kerr);
                validateDiskPath(diskEntry, "entry");
                validateDiskPath(diskExit, "exit");
                RtSkyPreset.Rgb mean = parseMean(sky);
                int layer = radianceImages.size();
                radianceImages.add(sky);
                transferImages.add(kerr);
                diskEntryImages.add(diskEntry);
                diskExitImages.add(diskExit);
                entries.put(environment, new Entry(layer, mean));
            } catch (Exception exception) {
                // The caller sees no Entry and replaces this one dimension with FULL_ATMOSPHERE. Other
                // environment dimensions keep their own valid layers and remain usable.
                FluoriteMod.LOGGER.warn("Ignoring invalid RT environment {} / {} / {} / {}",
                        environment.radianceTexture(), environment.transferTexture(),
                        environment.diskEntryTexture(), environment.diskExitTexture(), exception);
            }
        }
        if (radianceImages.isEmpty()) {
            radianceImages.add(blackSky());
            transferImages.add(capturedTransfer());
            diskEntryImages.add(emptyDiskPath());
            diskExitImages.add(emptyDiskPath());
        }
        return new RtEnvironmentTextures(
                new SampledArray(ctx, radianceImages, VK10.VK_FORMAT_B10G11R11_UFLOAT_PACK32,
                        "environment radiance array"),
                new SampledArray(ctx, transferImages, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "Kerr transfer array"),
                new SampledArray(ctx, diskEntryImages, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "Kerr disk entry array"),
                new SampledArray(ctx, diskExitImages, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "Kerr disk exit array"),
                Map.copyOf(entries));
    }

    public Entry entry(RtSkyPreset preset) {
        return preset.environment() == null ? null : entries.get(preset.environment());
    }

    public long radianceView() { return radiance.view; }
    public long transferView() { return transfer.view; }
    public long diskEntryView() { return diskEntry.view; }
    public long diskExitView() { return diskExit.view; }

    public void destroy() {
        radiance.destroy();
        transfer.destroy();
        diskEntry.destroy();
        diskExit.destroy();
    }

    public record Entry(int layer, RtSkyPreset.Rgb meanRadiance) {}

    private static RtKtx2.Image load(net.minecraft.resources.Identifier id) throws Exception {
        try (InputStream input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
            return RtKtx2.read(input);
        }
    }

    private static void validateSky(RtKtx2.Image image) {
        if (image.vkFormat() != 122 || image.width() != 4096 || image.height() != 2048 || image.layers() != 1
                || image.levels().size() != 13) {
            throw new IllegalArgumentException("Environment radiance must be 4096x2048 R11G11B10 with 13 mips");
        }
        if (!"rd".equals(image.metadata().get("KTXorientation"))) {
            throw new IllegalArgumentException("Environment radiance must use KTX orientation rd");
        }
    }

    private static void validateTransfer(RtKtx2.Image image) {
        if (image.vkFormat() != 97 || image.width() != 2048 || image.height() != 1024 || image.layers() != 1
                || image.levels().size() != 1) {
            throw new IllegalArgumentException("Kerr transfer must be 2048x1024 RGBA16F with one level");
        }
        if (!"rd".equals(image.metadata().get("KTXorientation"))) {
            throw new IllegalArgumentException("Kerr transfer must use KTX orientation rd");
        }
    }

    private static void validateDiskPath(RtKtx2.Image image, String endpoint) {
        if (image.vkFormat() != 97 || image.width() != 2048 || image.height() != 1024 || image.layers() != 1
                || image.levels().size() != 1) {
            throw new IllegalArgumentException("Kerr disk " + endpoint
                    + " must be 2048x1024 RGBA16F with one level");
        }
        if (!"rd".equals(image.metadata().get("KTXorientation"))) {
            throw new IllegalArgumentException("Kerr disk " + endpoint + " must use KTX orientation rd");
        }
        if (!image.metadata().containsKey("fluoriteDiskPathLayout")) {
            throw new IllegalArgumentException("Kerr disk " + endpoint + " has no path layout metadata");
        }
    }

    private static RtSkyPreset.Rgb parseMean(RtKtx2.Image image) {
        String text = image.metadata().get("fluoriteMeanRadiance");
        if (text == null) throw new IllegalArgumentException("Environment radiance has no solid-angle mean");
        String[] parts = text.split(",", -1);
        if (parts.length != 3) throw new IllegalArgumentException("Invalid environment mean radiance");
        float r = Float.parseFloat(parts[0]);
        float g = Float.parseFloat(parts[1]);
        float b = Float.parseFloat(parts[2]);
        if (!Float.isFinite(r) || !Float.isFinite(g) || !Float.isFinite(b)
                || r < 0f || g < 0f || b < 0f) {
            throw new IllegalArgumentException("Non-finite environment mean radiance");
        }
        return new RtSkyPreset.Rgb(r, g, b);
    }

    private static RtKtx2.Image blackSky() {
        List<byte[]> levels = new ArrayList<>();
        int width = 4096, height = 2048;
        while (true) {
            levels.add(new byte[Math.multiplyExact(Math.multiplyExact(width, height), 4)]);
            if (width == 1 && height == 1) break;
            width = Math.max(1, width / 2);
            height = Math.max(1, height / 2);
        }
        return new RtKtx2.Image(122, 4, 4096, 2048, 1, levels,
                Map.of("KTXorientation", "rd", "fluoriteMeanRadiance", "0,0,0"));
    }

    private static RtKtx2.Image capturedTransfer() {
        return new RtKtx2.Image(97, 2, 2048, 1024, 1,
                List.of(new byte[2048 * 1024 * 8]), Map.of("KTXorientation", "rd"));
    }

    private static RtKtx2.Image emptyDiskPath() {
        return new RtKtx2.Image(97, 2, 2048, 1024, 1,
                List.of(new byte[2048 * 1024 * 8]),
                Map.of("KTXorientation", "rd", "fluoriteDiskPathLayout", "empty"));
    }

    private static final class SampledArray {
        private final long vma;
        private final VkDevice vk;
        private final long image;
        private final long allocation;
        private final long view;
        private boolean destroyed;

        SampledArray(RtContext ctx, List<RtKtx2.Image> images, int format, String label) {
            RtKtx2.Image first = images.getFirst();
            for (RtKtx2.Image image : images) {
                if (image.vkFormat() != first.vkFormat() || image.width() != first.width()
                        || image.height() != first.height() || image.levels().size() != first.levels().size()) {
                    throw new IllegalArgumentException("Environment array layers do not share one shape");
                }
                if (image.layers() != 1) {
                    throw new IllegalArgumentException("Environment source must not already be an array");
                }
            }
            this.vma = ctx.vma();
            this.vk = ctx.vk();
            long createdImage = 0L, createdAllocation = 0L, createdView = 0L;
            RtBuffer staging = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                        .mipLevels(first.levels().size()).arrayLayers(images.size())
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
                imageInfo.extent().set(first.width(), first.height(), 1);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                LongBuffer imageOut = stack.mallocLong(1);
                PointerBuffer allocationOut = stack.mallocPointer(1);
                RtContext.check(Vma.vmaCreateImage(vma, imageInfo, allocationInfo, imageOut, allocationOut, null),
                        "vmaCreateImage(" + label + ")");
                createdImage = imageOut.get(0);
                createdAllocation = allocationOut.get(0);
                RtDebugLabels.nameImage(ctx, createdImage, label);

                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(createdImage).viewType(VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY).format(format);
                viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(first.levels().size())
                        .baseArrayLayer(0).layerCount(images.size());
                LongBuffer viewOut = stack.mallocLong(1);
                RtContext.check(VK10.vkCreateImageView(vk, viewInfo, null, viewOut),
                        "vkCreateImageView(" + label + ")");
                createdView = viewOut.get(0);
                RtDebugLabels.nameImageView(ctx, createdView, label + " view");

                long totalBytes = 0L;
                for (RtKtx2.Image source : images) {
                    for (byte[] level : source.levels()) totalBytes = Math.addExact(totalBytes, level.length);
                }
                staging = ctx.createUploadBuffer(totalBytes, label + " upload");
                ByteBuffer mapped = MemoryUtil.memByteBuffer(staging.mapped, Math.toIntExact(totalBytes));
                long[][] offsets = new long[images.size()][first.levels().size()];
                int cursor = 0;
                for (int layer = 0; layer < images.size(); layer++) {
                    for (int level = 0; level < first.levels().size(); level++) {
                        offsets[layer][level] = cursor;
                        byte[] data = images.get(layer).levels().get(level);
                        mapped.position(cursor).put(data);
                        cursor += data.length;
                    }
                }
                staging.flush();

                long uploadImage = createdImage;
                long uploadBuffer = staging.handle;
                int layerCount = images.size();
                int levelCount = first.levels().size();
                int baseWidth = first.width(), baseHeight = first.height();
                ctx.submitSync(cmd -> {
                    try (MemoryStack upload = MemoryStack.stackPush()) {
                        VkImageMemoryBarrier.Buffer toTransfer = VkImageMemoryBarrier.calloc(1, upload);
                        toTransfer.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED)
                                .newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                                .srcAccessMask(0).dstAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                        toTransfer.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(levelCount).baseArrayLayer(0).layerCount(layerCount);
                        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                                VK10.VK_PIPELINE_STAGE_TRANSFER_BIT, 0, null, null, toTransfer);

                        VkBufferImageCopy.Buffer copies = VkBufferImageCopy.calloc(layerCount * levelCount, upload);
                        int copy = 0;
                        for (int layer = 0; layer < layerCount; layer++) {
                            int width = baseWidth, height = baseHeight;
                            for (int level = 0; level < levelCount; level++) {
                                VkBufferImageCopy region = copies.get(copy++);
                                region.bufferOffset(offsets[layer][level]).bufferRowLength(0).bufferImageHeight(0);
                                region.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                        .mipLevel(level).baseArrayLayer(layer).layerCount(1);
                                region.imageOffset().set(0, 0, 0);
                                region.imageExtent().set(width, height, 1);
                                width = Math.max(1, width / 2);
                                height = Math.max(1, height / 2);
                            }
                        }
                        VK10.vkCmdCopyBufferToImage(cmd, uploadBuffer, uploadImage,
                                VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copies);

                        VkImageMemoryBarrier.Buffer toGeneral = VkImageMemoryBarrier.calloc(1, upload);
                        toGeneral.get(0).sType$Default().oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                                .newLayout(VK10.VK_IMAGE_LAYOUT_GENERAL)
                                .srcAccessMask(VK10.VK_ACCESS_TRANSFER_WRITE_BIT)
                                .dstAccessMask(VK10.VK_ACCESS_SHADER_READ_BIT)
                                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED).image(uploadImage);
                        toGeneral.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                .baseMipLevel(0).levelCount(levelCount).baseArrayLayer(0).layerCount(layerCount);
                        VK10.vkCmdPipelineBarrier(cmd, VK10.VK_PIPELINE_STAGE_TRANSFER_BIT,
                                VK10.VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, 0, null, null, toGeneral);
                    }
                });
            } catch (Throwable throwable) {
                if (createdView != 0L) VK10.vkDestroyImageView(vk, createdView, null);
                if (createdImage != 0L) Vma.vmaDestroyImage(vma, createdImage, createdAllocation);
                throw throwable;
            } finally {
                if (staging != null) staging.destroy();
            }
            image = createdImage;
            allocation = createdAllocation;
            view = createdView;
            FluoriteMod.LOGGER.info("RT {}: {} layer(s), {}x{}, {} mip(s)",
                    label, images.size(), first.width(), first.height(), first.levels().size());
        }

        void destroy() {
            if (destroyed) return;
            VK10.vkDestroyImageView(vk, view, null);
            Vma.vmaDestroyImage(vma, image, allocation);
            destroyed = true;
        }

        // No local check() here -- see the note in RtMaterialPageTexture. RtContext.check reports a lost
        // device to VulkanDiagnostics before throwing, and image allocation is where that shows up.
    }
}
