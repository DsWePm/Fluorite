package io.github.dswepm.fluorite.client.gui;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import java.util.List;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** One {@link RtVideoOptions.Category}'s settings. Built fresh on open, so every row shows the live value. */
public class RtCategoryScreen extends OptionsSubScreen {
    private final RtVideoOptions.Category category;

    public RtCategoryScreen(Screen lastScreen, Options options, RtVideoOptions.Category category) {
        super(lastScreen, options, category.title());
        this.category = category;
    }

    @Override
    protected void addOptions() {
        for (RtVideoOptions.Section section : this.category.sections()) {
            if (!section.titleKey().isEmpty()) {
                this.list.addHeader(Component.translatable(section.titleKey()));
            }
            if (section.options().length > 0) {
                this.list.addSmall(section.options());
            }
        }
        if (this.category == RtVideoOptions.Category.POST_PROCESSING) {
            Button artisticGrading = Button.builder(
                            Component.translatable("fluorite.options.rt.artisticGrading"),
                            button -> this.minecraft.gui.setScreen(
                                    new RtArtisticGradingScreen(this, this.options)))
                    .tooltip(Tooltip.create(Component.translatable(
                            "fluorite.options.rt.artisticGrading.tooltip")))
                    .build();
            Button lensEffects = Button.builder(
                            Component.translatable("fluorite.options.rt.lensEffects"),
                            button -> this.minecraft.gui.setScreen(
                                    new RtLensEffectsScreen(this, this.options)))
                    .tooltip(Tooltip.create(Component.translatable(
                            "fluorite.options.rt.lensEffects.tooltip")))
                    .build();
            this.list.addHeader(Component.translatable("fluorite.options.rt.section.creativeLens"));
            this.list.addSmall(List.of(artisticGrading, lensEffects));
        }
    }

    @Override
    public void removed() {
        super.removed();
        // Every screen that can change a setting saves on the way out, rather than only the outermost one:
        // a player who alt-F4s from a category screen should still keep what they set.
        FluoriteConfig.save();
    }
}
