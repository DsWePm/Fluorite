package io.github.dswepm.fluorite.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.dswepm.fluorite.client.VanillaRenderController;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ScreenEffectRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Suppresses vanilla's full-screen underwater overlay while the ray tracer owns the world image.
 *
 * <p>{@code submitWater} paints {@code textures/misc/underwater.png} across the entire screen the moment
 * {@code player.isEyeInFluid(WATER)} flips. That is a raster-era stand-in for a medium the path tracer
 * now renders for real — absorption since the beginning, scattering since M9 — so with it drawn on top
 * the water is being paid for twice, and the cheaper copy wins.
 *
 * <p>It is also why the transition has no waterline. Half-submerged, the ray tracer produces one on its
 * own and for free: the water surface is meshed geometry, the eye is on one side of it, and rays leaving
 * the camera cross it or do not depending on which way they point — the line is the surface plane's own
 * horizon, at eye level, with no code anywhere deciding it. A screen-wide texture keyed off a screen-wide
 * boolean cannot express that, and drawing it hides the version that can.
 *
 * <p>Fire and the view-blocking-block overlays are left alone: those are HUD-ish indicators of state the
 * renderer does not otherwise show, not stand-ins for a medium it simulates.
 */
@Mixin(ScreenEffectRenderer.class)
public abstract class ScreenEffectRendererMixin {
	@Inject(method = "submitWater", at = @At("HEAD"), cancellable = true)
	private static void fluorite$suppressUnderwaterOverlay(Minecraft minecraft, PoseStack poseStack,
			SubmitNodeCollector submitNodeCollector, CallbackInfo ci) {
		if (VanillaRenderController.rtRuntimeWorkRequested()) {
			ci.cancel();
		}
	}
}
