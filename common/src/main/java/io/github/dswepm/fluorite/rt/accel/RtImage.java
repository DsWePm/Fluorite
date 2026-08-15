package io.github.dswepm.fluorite.rt.accel;

import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

/**
 * A VMA-backed image + view, created in {@code VK_IMAGE_LAYOUT_GENERAL}. Used for RT output
 * storage images. Created via {@link io.github.dswepm.fluorite.rt.RtContext#createStorageImage}; freed with {@link #destroy()}.
 */
public final class RtImage {
    public final long image;
    public final long allocation;
    public final long view;
    /**
     * A view of mip 0 alone, for writing.
     *
     * <p>Vulkan requires a storage-image view to name exactly ONE level, while a sampled view wants all
     * of them, so a mipped image needs two. For a single-level image this is the same handle as
     * {@link #view} and is not destroyed twice.
     */
    public final long storageView;
    public final int levels;
    public final int width;
    public final int height;

    private final long vma;
    private final VkDevice vk;
    private boolean destroyed;

    public RtImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height) {
        this(vma, vk, image, allocation, view, view, 1, width, height);
    }

    public RtImage(long vma, VkDevice vk, long image, long allocation, long view, long storageView,
                   int levels, int width, int height) {
        this.vma = vma;
        this.vk = vk;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.storageView = storageView;
        this.levels = levels;
        this.width = width;
        this.height = height;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (storageView != 0L && storageView != view) {
            VK10.vkDestroyImageView(vk, storageView, null);
        }
        if (view != 0L) {
            VK10.vkDestroyImageView(vk, view, null);
        }
        if (image != 0L) {
            Vma.vmaDestroyImage(vma, image, allocation);
        }
        destroyed = true;
    }
}
