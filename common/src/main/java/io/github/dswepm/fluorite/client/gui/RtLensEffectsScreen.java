package io.github.dswepm.fluorite.client.gui;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** D154A's classified film, optical-highlight, focus, motion and lens-geometry controls. */
public final class RtLensEffectsScreen extends OptionsSubScreen {
    public RtLensEffectsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("fluorite.options.rt.lensEffects.title"));
    }

    @Override
    protected void addOptions() {
        for (RtVideoOptions.Section section : RtVideoOptions.lensEffectsSections()) {
            if (!section.titleKey().isEmpty()) {
                this.list.addHeader(Component.translatable(section.titleKey()));
            }
            if (section.options().length > 0) {
                this.list.addSmall(section.options());
            }
        }
    }

    @Override
    public void removed() {
        super.removed();
        FluoriteConfig.save();
    }
}
