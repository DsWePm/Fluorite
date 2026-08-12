package io.github.dswepm.fluorite.rt.material;

import com.mojang.blaze3d.platform.NativeImage;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.mixin.SpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.ARGB;

import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Resource-epoch cache of alpha-premultiplied linear albedo means for dynamic emitter proxies.
 *
 * <p>This is deliberately a CPU-side side band. It does not alter material headers or sampled texels;
 * M18 only needs the same texture-domain mean that a finite light proxy will later preserve. Atlas
 * sprites use frame zero, matching the material compiler. Full textures are loaded lazily because many
 * runtime entity textures never occur in a given world.
 */
public final class RtAlbedoSummary {
    public static final Summary NONE = new Summary(0.0f, 0.0f, 0.0f, 0.0f);

    private static final Map<TextureAtlasSprite, Summary> SPRITES = new IdentityHashMap<>();
    private static final Map<Identifier, Summary> RESOURCES = new HashMap<>();
    private static boolean loggedFailure;

    private RtAlbedoSummary() {
    }

    public record Summary(float averageR, float averageG, float averageB, float coverage) {
        public boolean usable() {
            return coverage > 0.0f && (averageR > 0.0f || averageG > 0.0f || averageB > 0.0f);
        }
    }

    /** Alpha-premultiplied frame-zero mean for a stitched sprite. */
    public static Summary sprite(TextureAtlasSprite sprite) {
        if (sprite == null) return NONE;
        Summary cached = SPRITES.get(sprite);
        if (cached != null) return cached;
        Summary result = NONE;
        try {
            NativeImage image = ((SpriteContentsAccessor) sprite.contents()).fluorite$originalImage();
            result = summarize(image, sprite.contents().width(), sprite.contents().height());
        } catch (Throwable t) {
            warnOnce("RT dynamic-light sprite summary failed for " + sprite.contents().name(), t);
        }
        SPRITES.put(sprite, result);
        return result;
    }

    /** Alpha-premultiplied mean for a full entity texture resource. */
    public static Summary resource(Identifier location) {
        if (location == null) return NONE;
        Summary cached = RESOURCES.get(location);
        if (cached != null) return cached;
        Summary result = NONE;
        try {
            Optional<Resource> resource = Minecraft.getInstance().getResourceManager().getResource(location);
            if (resource.isPresent()) {
                try (InputStream input = resource.get().open(); NativeImage image = NativeImage.read(input)) {
                    result = summarize(image, image.getWidth(), image.getHeight());
                }
            }
        } catch (Throwable t) {
            warnOnce("RT dynamic-light texture summary failed for " + location, t);
        }
        RESOURCES.put(location, result);
        return result;
    }

    /** Resource reload invalidates both sprite identities and full-texture pixels. */
    public static void clear() {
        SPRITES.clear();
        RESOURCES.clear();
        loggedFailure = false;
    }

    private static Summary summarize(NativeImage image, int width, int height) {
        if (image == null || width <= 0 || height <= 0) return NONE;
        width = Math.min(width, image.getWidth());
        height = Math.min(height, image.getHeight());
        if (width <= 0 || height <= 0) return NONE;
        double r = 0.0;
        double g = 0.0;
        double b = 0.0;
        int covered = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getPixel(x, y);
                float alpha = ARGB.alpha(pixel) * (1.0f / 255.0f);
                r += RtMaterialTextureData.srgbToLinear(ARGB.red(pixel)) * alpha;
                g += RtMaterialTextureData.srgbToLinear(ARGB.green(pixel)) * alpha;
                b += RtMaterialTextureData.srgbToLinear(ARGB.blue(pixel)) * alpha;
                if (ARGB.alpha(pixel) > 1) covered++;
            }
        }
        float inv = 1.0f / (width * (float) height);
        return new Summary((float) r * inv, (float) g * inv, (float) b * inv, covered * inv);
    }

    private static void warnOnce(String message, Throwable throwable) {
        if (!loggedFailure) {
            loggedFailure = true;
            FluoriteMod.LOGGER.warn(message, throwable);
        }
    }
}
