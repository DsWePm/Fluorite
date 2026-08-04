package io.github.dswepm.fluorite.client.gui;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The hub: one button per {@link RtVideoOptions.Category}, reached from a single entry in the vanilla
 * Video Settings screen.
 *
 * <p>The renderer's runtime settings outgrew a trailing section in someone else's screen — a flat list of
 * twenty-eight rows makes the fog's nine sliders and the two water controls look like the same kind of
 * thing, and every milestone still to come adds more. Two levels means a player looking for turbidity
 * reads eight words instead of scanning twenty-eight.
 *
 * <p>Vanilla's own two-level options do exactly this ({@code ControlsScreen} → {@code KeyBindsScreen}),
 * which is why this is an {@code OptionsSubScreen} holding buttons rather than a hand-built layout: the
 * list, the title, the Done button and the repositioning on resize all come with it.
 */
public class RtOptionsScreen extends OptionsSubScreen {
    public RtOptionsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, Component.translatable("fluorite.options.rt.title"));
    }

    @Override
    protected void addOptions() {
        // Two per row, not one. Eight full-width buttons stacked is a column of headlines you read top to
        // bottom to find one word, and every milestone left in the plan adds another; paired, the whole
        // renderer fits on one screen with no scrolling and the eye can sweep it. addSmall(List) lays the
        // pairs out the same way every options row in the game is laid out, so this needs no geometry.
        List<AbstractWidget> buttons = new ArrayList<>();
        for (RtVideoOptions.Category category : RtVideoOptions.Category.values()) {
            buttons.add(Button.builder(category.title(),
                            button -> this.minecraft.gui.setScreen(
                                    new RtCategoryScreen(this, this.options, category)))
                    .tooltip(Tooltip.create(category.description()))
                    .build());
        }
        this.list.addSmall(buttons);
        // A full-width row of its own, below the pairs. Deliberately not paired with a category: it is the
        // one button here that changes settings rather than navigating to them, and putting it in the grid
        // would make it look like a ninth place to go rather than a thing that happens.
        this.list.addSmall(List.of(Button.builder(
                        Component.translatable("fluorite.options.rt.resetDefaults"),
                        button -> {
                            FluoriteConfig.resetAllToDefaults();
                            FluoriteConfig.save();
                            // Rebuild so every category button's screen is constructed fresh: an
                            // OptionInstance captures its setting's value when it is built, so any screen
                            // made before this point would still be showing the old numbers.
                            this.minecraft.gui.setScreen(new RtOptionsScreen(this.lastScreen, this.options));
                        })
                .tooltip(Tooltip.create(Component.translatable("fluorite.options.rt.resetDefaults.tooltip")))
                .build()));
    }

    @Override
    public void removed() {
        super.removed();
        // Persist anything changed on the way through. Also covers the hub itself being closed after a
        // category screen wrote a setting, since that screen returns here rather than to Video Settings.
        FluoriteConfig.save();
    }
}
