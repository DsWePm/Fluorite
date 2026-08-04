package io.github.dswepm.fluorite.mixin;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import io.github.dswepm.fluorite.client.gui.RtOptionsScreen;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Surfaces the runtime-tunable RT settings inside the vanilla Video Settings screen when the RT renderer is
 * enabled. Two changes, both gated on {@link FluoriteConfig.Rt#ENABLED}:
 *
 * <ul>
 *   <li>The Quality section drops the vanilla options the path tracer supersedes (Ambient Occlusion and
 *       Entity Shadows are computed by RT global illumination / RT shadows).</li>
 *   <li>A "Ray Tracing" section adds one button opening {@link RtOptionsScreen}.</li>
 * </ul>
 *
 * <p>One button rather than the settings themselves: they are Fluorite's screens to lay out, and
 * twenty-eight rows appended to someone else's list is not a layout. Everything below that button lives in
 * {@link RtVideoOptions.Category}.
 *
 * <p>When RT is disabled the screen is left exactly as vanilla built it.
 */
@Mixin(VideoSettingsScreen.class)
public abstract class VideoSettingsScreenMixin {
    @Shadow
    private static OptionInstance<?>[] qualityOptions(Options options) {
        throw new AssertionError("mixin stub");
    }

    private static final Component FLUORITE$RT_HEADER = Component.translatable("fluorite.options.rt.header");
    private static final Component FLUORITE$RT_OPEN = Component.translatable("fluorite.options.rt.open");

    @Redirect(
        method = "addOptions",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/screens/options/VideoSettingsScreen;qualityOptions(Lnet/minecraft/client/Options;)[Lnet/minecraft/client/OptionInstance;"))
    private OptionInstance<?>[] fluorite$filterQualityOptions(Options options) {
        OptionInstance<?>[] base = qualityOptions(options);
        if (!FluoriteConfig.Rt.ENABLED.value()) {
            return base;
        }
        List<OptionInstance<?>> kept = new ArrayList<>(base.length);
        for (OptionInstance<?> option : base) {
            // Path-traced GI + RT shadows make these vanilla raster controls inert under RT.
            if (option == options.ambientOcclusion() || option == options.entityShadows()) {
                continue;
            }
            kept.add(option);
        }
        return kept.toArray(OptionInstance<?>[]::new);
    }

    @Inject(method = "addOptions", at = @At("HEAD"))
    private void fluorite$addRtOptions(CallbackInfo ci) {
        if (!FluoriteConfig.Rt.ENABLED.value()) {
            return;
        }
        OptionsList list = ((OptionsSubScreenAccessor) (Object) this).getList();
        if (list == null) {
            return;
        }
        VideoSettingsScreen self = (VideoSettingsScreen) (Object) this;
        list.addHeader(FLUORITE$RT_HEADER);
        list.addBig(Button.builder(FLUORITE$RT_OPEN,
                        button -> Minecraft.getInstance().gui
                                .setScreen(new RtOptionsScreen(self, Minecraft.getInstance().options)))
                .build());
    }

    @Inject(method = "removed", at = @At("TAIL"))
    private void fluorite$saveConfig(CallbackInfo ci) {
        // Persist any RT settings the player changed in this screen to the TOML config.
        FluoriteConfig.save();
    }
}
