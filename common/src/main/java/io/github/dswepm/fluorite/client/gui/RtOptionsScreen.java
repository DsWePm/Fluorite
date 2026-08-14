package io.github.dswepm.fluorite.client.gui;

import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoritePresetService;
import io.github.dswepm.fluorite.FluoritePresetService.ExportResult;
import io.github.dswepm.fluorite.FluoritePresetService.ImportResult;
import io.github.dswepm.fluorite.FluoritePresetService.PresetException;
import io.github.dswepm.fluorite.client.RtVideoOptions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    private static final Logger LOGGER = LoggerFactory.getLogger("Fluorite");

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
        // File actions and the destructive reset form three unmistakable full-width rows at the bottom.
        // Styled labels differentiate them from category navigation without inventing custom button
        // rendering that would stop matching Minecraft's active/hover/disabled states.
        this.list.addSmall(List.of(Button.builder(
                        Component.translatable("fluorite.options.rt.preset.export")
                                .withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC),
                        button -> exportPreset())
                .tooltip(Tooltip.create(Component.translatable(
                        "fluorite.options.rt.preset.export.tooltip")))
                .build()));
        this.list.addSmall(List.of(Button.builder(
                        Component.translatable("fluorite.options.rt.preset.import")
                                .withStyle(ChatFormatting.BOLD, ChatFormatting.ITALIC),
                        button -> importPreset())
                .tooltip(Tooltip.create(Component.translatable(
                        "fluorite.options.rt.preset.import.tooltip")))
                .build()));
        this.list.addSmall(List.of(Button.builder(
                        Component.translatable("fluorite.options.rt.resetDefaults")
                                .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                        button -> confirmResetDefaults())
                .tooltip(Tooltip.create(Component.translatable("fluorite.options.rt.resetDefaults.tooltip")))
                .build()));
    }

    private void confirmResetDefaults() {
        this.minecraft.gui.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (confirmed) {
                        FluoriteConfig.resetAllToDefaults();
                        FluoriteConfig.save();
                    }
                    // Rebuild either way. OptionInstance captures its initial value at construction,
                    // while opening the confirmation screen removes (and therefore saves) this hub.
                    this.minecraft.gui.setScreen(new RtOptionsScreen(this.lastScreen, this.options));
                },
                Component.translatable("fluorite.options.rt.resetDefaults.confirm.title")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                Component.translatable("fluorite.options.rt.resetDefaults.confirm.message"),
                Component.translatable("fluorite.options.rt.resetDefaults.confirm.accept")
                        .withStyle(ChatFormatting.RED, ChatFormatting.BOLD),
                CommonComponents.GUI_CANCEL));
    }

    private void exportPreset() {
        try {
            Path defaultPath = FluoritePresetService.defaultExportPath();
            Files.createDirectories(defaultPath.getParent());
            String selected = chooseTomlFile(true, defaultPath);
            if (selected == null) {
                return;
            }
            ExportResult result = FluoritePresetService.exportPreset(Path.of(selected));
            showPresetResult(
                    Component.translatable("fluorite.options.rt.preset.export.success.title"),
                    Component.translatable("fluorite.options.rt.preset.export.success",
                            result.settingCount(), result.path()));
        } catch (Exception e) {
            showPresetError(e);
        }
    }

    private void importPreset() {
        try {
            Path directory = FluoritePresetService.presetDirectory();
            Files.createDirectories(directory);
            String selected = chooseTomlFile(false, directory.resolve("preset.toml"));
            if (selected == null) {
                return;
            }
            ImportResult result = FluoritePresetService.importPreset(Path.of(selected));
            String backup = result.backupPath() != null
                    ? result.backupPath().toString()
                    : Component.translatable("fluorite.options.rt.preset.backup.none").getString();
            showPresetResult(
                    Component.translatable("fluorite.options.rt.preset.import.success.title"),
                    Component.translatable("fluorite.options.rt.preset.import.success",
                            result.liveCount(), result.restartCount(), result.systemOverrideCount(), backup));
        } catch (Exception e) {
            showPresetError(e);
        }
    }

    private String chooseTomlFile(boolean save, Path defaultPath) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.toml")).flip();
            if (save) {
                return TinyFileDialogs.tinyfd_saveFileDialog(
                        Component.translatable("fluorite.options.rt.preset.export.dialog").getString(),
                        defaultPath.toString(), filters,
                        Component.translatable("fluorite.options.rt.preset.filter").getString());
            }
            return TinyFileDialogs.tinyfd_openFileDialog(
                    Component.translatable("fluorite.options.rt.preset.import.dialog").getString(),
                    defaultPath.toString(), filters,
                    Component.translatable("fluorite.options.rt.preset.filter").getString(), false);
        }
    }

    private void showPresetError(Exception error) {
        LOGGER.error("Fluorite preset operation failed", error);
        String detail = error instanceof PresetException && error.getMessage() != null
                ? error.getMessage()
                : error.toString();
        showPresetResult(
                Component.translatable("fluorite.options.rt.preset.error.title"),
                Component.translatable("fluorite.options.rt.preset.error", detail));
    }

    private void showPresetResult(Component title, Component message) {
        this.minecraft.gui.setScreen(new AlertScreen(
                () -> this.minecraft.gui.setScreen(new RtOptionsScreen(this.lastScreen, this.options)),
                title, message));
    }

    @Override
    public void removed() {
        super.removed();
        // Persist anything changed on the way through. Also covers the hub itself being closed after a
        // category screen wrote a setting, since that screen returns here rather than to Video Settings.
        FluoriteConfig.save();
    }
}
