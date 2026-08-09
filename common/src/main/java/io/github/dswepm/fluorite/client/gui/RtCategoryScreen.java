package io.github.dswepm.fluorite.client.gui;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import net.minecraft.client.Options;
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
    }

    @Override
    public void removed() {
        super.removed();
        // Every screen that can change a setting saves on the way out, rather than only the outermost one:
        // a player who alt-F4s from a category screen should still keep what they set.
        FluoriteConfig.save();
    }
}
