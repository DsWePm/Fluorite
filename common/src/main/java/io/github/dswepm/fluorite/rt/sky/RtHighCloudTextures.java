package io.github.dswepm.fluorite.rt.sky;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.rt.RtContext;
import io.github.dswepm.fluorite.rt.RtDebugLabels;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
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
import java.util.List;
import java.util.Map;

/** Resource-epoch-owned D163 optical-depth sources; source RGB never reaches the renderer. */
public final class RtHighCloudTextures {
    private static final Identifier PATCHES = Identifier.fromNamespaceAndPath(
            "fluorite", "fluorite/cloud/high_cloud_patches.ktx2");

    private final SampledTexture patches;

    private RtHighCloudTextures(SampledTexture patches) {
        this.patches = patches;
    }

    public static RtHighCloudTextures load(RtContext ctx) {
        RtKtx2.Image patchImage;
        try {
            patchImage = load(PATCHES);
            validatePatches(patchImage);
        } catch (Exception exception) {
            // Keep RT usable if a development resource tree is incomplete. The fallback is zero tau, so
            // this is a visible missing-high-cloud failure rather than undefined memory or coloured RGB.
            FluoriteMod.LOGGER.error("Invalid generated high-cloud optical-depth resources", exception);
            patchImage = new RtKtx2.Image(9, 1, 1, 1, 10,
                    List.of(new byte[10]), Map.of("KTXorientation", "rd"));
        }
        return new RtHighCloudTextures(new SampledTexture(ctx, patchImage, VK10.VK_FORMAT_R8_UNORM,
                "high-cloud patch tau array"));
    }

    public long patchView() { return patches.view; }

    public void destroy() {
        patches.destroy();
    }

    private static RtKtx2.Image load(Identifier id) throws Exception {
        try (InputStream input = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open()) {
            return RtKtx2.read(input);
        }
    }

    private static void validatePatches(RtKtx2.Image image) {
        if (image.vkFormat() != 9 || image.typeSize() != 1 || image.width() != 1024
                || image.height() != 1024 || image.layers() != 10 || image.levels().size() != 11) {
            throw new IllegalArgumentException("High-cloud patches must be 10x1024x1024 R8 with 11 mips");
        }
        validateMetadata(image, "83514a391c765819bc159b7f9ab61ec9f06c0caa2b5865af20a5bd3b2a14cca4");
    }

    private static void validateMetadata(RtKtx2.Image image, String sourceHash) {
        if (!"rd".equals(image.metadata().get("KTXorientation"))
                || !"CC0-1.0".equals(image.metadata().get("fluoriteLicense"))
                || !sourceHash.equals(image.metadata().get("fluoriteSourceSha256"))) {
            throw new IllegalArgumentException("High-cloud texture metadata does not match the pinned CC0 source");
        }
    }

    private static final class SampledTexture {
        private final long vma;
        private final VkDevice vk;
        private final long image;
        private final long allocation;
        private final long view;
        private boolean destroyed;

        SampledTexture(RtContext ctx, RtKtx2.Image source, int format, String label) {
            vma = ctx.vma();
            vk = ctx.vk();
            long createdImage = 0L, createdAllocation = 0L, createdView = 0L;
            RtBuffer staging = null;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack).sType$Default()
                        .imageType(VK10.VK_IMAGE_TYPE_2D).format(format)
                        .mipLevels(source.levels().size()).arrayLayers(source.layers())
                        .samples(VK10.VK_SAMPLE_COUNT_1_BIT).tiling(VK10.VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK10.VK_IMAGE_USAGE_SAMPLED_BIT | VK10.VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED);
                imageInfo.extent().set(source.width(), source.height(), 1);
                VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack)
                        .usage(Vma.VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE);
                LongBuffer imageOut = stack.mallocLong(1);
                PointerBuffer allocationOut = stack.mallocPointer(1);
                RtContext.check(Vma.vmaCreateImage(vma, imageInfo, allocationInfo, imageOut, allocationOut, null),
                        "vmaCreateImage(" + label + ")");
                createdImage = imageOut.get(0);
                createdAllocation = allocationOut.get(0);
                RtDebugLabels.nameImage(ctx, createdImage, label);

                int viewType = source.layers() > 1
                        ? VK10.VK_IMAGE_VIEW_TYPE_2D_ARRAY : VK10.VK_IMAGE_VIEW_TYPE_2D;
                VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(createdImage).viewType(viewType).format(format);
                viewInfo.subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0).levelCount(source.levels().size())
                        .baseArrayLayer(0).layerCount(source.layers());
                LongBuffer viewOut = stack.mallocLong(1);
                RtContext.check(VK10.vkCreateImageView(vk, viewInfo, null, viewOut),
                        "vkCreateImageView(" + label + ")");
                createdView = viewOut.get(0);
                RtDebugLabels.nameImageView(ctx, createdView, label + " view");

                long totalBytes = 0L;
                for (byte[] level : source.levels()) totalBytes = Math.addExact(totalBytes, level.length);
                staging = ctx.createUploadBuffer(totalBytes, label + " upload");
                ByteBuffer mapped = MemoryUtil.memByteBuffer(staging.mapped, Math.toIntExact(totalBytes));
                long[] levelOffsets = new long[source.levels().size()];
                int cursor = 0;
                for (int level = 0; level < source.levels().size(); level++) {
                    levelOffsets[level] = cursor;
                    byte[] data = source.levels().get(level);
                    mapped.position(cursor).put(data);
                    cursor += data.length;
                }
                staging.flush();

                long uploadImage = createdImage;
                long uploadBuffer = staging.handle;
                int levelCount = source.levels().size();
                int layerCount = source.layers();
                int baseWidth = source.width(), baseHeight = source.height();
                int bytesPerPixel = format == VK10.VK_FORMAT_R8_UNORM ? 1 : 2;
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
                        int width = baseWidth, height = baseHeight;
                        for (int level = 0; level < levelCount; level++) {
                            long layerBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), bytesPerPixel);
                            for (int layer = 0; layer < layerCount; layer++) {
                                VkBufferImageCopy region = copies.get(copy++);
                                region.bufferOffset(levelOffsets[level] + layerBytes * layer)
                                        .bufferRowLength(0).bufferImageHeight(0);
                                region.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                                        .mipLevel(level).baseArrayLayer(layer).layerCount(1);
                                region.imageOffset().set(0, 0, 0);
                                region.imageExtent().set(width, height, 1);
                            }
                            width = Math.max(1, width / 2);
                            height = Math.max(1, height / 2);
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
            FluoriteMod.LOGGER.info("RT {}: {} layer(s), {}x{}, {} mip(s)", label,
                    source.layers(), source.width(), source.height(), source.levels().size());
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
