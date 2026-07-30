package io.github.dswepm.fluorite.client;

import com.mojang.serialization.Codec;
import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteConfig.BooleanSetting;
import io.github.dswepm.fluorite.FluoriteConfig.FloatSetting;
import io.github.dswepm.fluorite.FluoriteConfig.IntSetting;
import io.github.dswepm.fluorite.FluoriteConfig.StringSetting;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/**
 * Builds the {@link OptionInstance} widgets shown in the RT section of the vanilla Video Settings screen
 * (injected by {@code VideoSettingsScreenMixin}). Each option is bound straight to a {@link FluoriteConfig}
 * runtime setting: the initial value is read from the current config, and the value-update listener writes
 * back through {@code set(...)} so changes take effect on the next frame.
 *
 * <p>Only settings the renderer re-reads per-frame are exposed here — toggles that would require a device or
 * buffer-pool rebuild (worker threads, OMM, max-entity capacities, PBR material flags) are intentionally
 * left to the {@code -Dfluorite.*} startup surface. DLSS-RR quality is the exception: the render resolution
 * is queried from NGX for the chosen quality mode on every resize (see
 * {@code RtDlssRr.queryOptimalRenderSize}), and the RR feature itself is recreated live whenever
 * {@code quality} changes (see {@code RtDlssRr.ensureFeature}), so it is safe to expose here.
 */
public final class RtVideoOptions {
    private RtVideoOptions() {
    }

    /** Runtime-tunable RT options, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
    public static OptionInstance<?>[] runtimeOptions() {
        return new OptionInstance<?>[] {
            exposureMode(),
            manualEv(),
            spp(),
            maxBounces(),
            sunSize(),
            entities(),
            particles(),
            waterWaves(),
            fogEnabled(),
            fogDensity(),
            fogIntensity(),
            fogHeightBase(),
            fogStartDistance(),
            fogCullDistance(),
            fogHeightScale(),
            fogPhaseG(),
            fogScatterTint(),
            sunMis(),
            anisotropy(),
            subsurfaceMode(),
            subsurfaceMaxEvents(),
            dlssEnabled(),
            dlssQuality(),
            hdrEnabled(),
            hdrPaperWhite(),
            hdrPeak(),
            debugView(),
        };
    }

    private static OptionInstance<String> exposureMode() {
        StringSetting setting = FluoriteConfig.Rt.Exposure.MODE;
        return new OptionInstance<>(
            "fluorite.options.rt.exposureMode",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.exposureMode.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("fluorite.options.rt.exposureMode." + value),
            new OptionInstance.Enum<>(List.of("auto", "manual"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> manualEv() {
        FloatSetting setting = FluoriteConfig.Rt.Exposure.MANUAL_EV;
        return new OptionInstance<>(
            "fluorite.options.rt.manualEv",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.manualEv.tooltip")),
            (caption, tenths) -> {
                float ev = tenths / 10.0f;
                String sign = ev > 0.0f ? "+" : "";
                return Options.genericValueLabel(caption,
                        Component.literal(sign + String.format(Locale.ROOT, "%.1f EV", ev)));
            },
            new OptionInstance.IntRange(-50, 50),
            Math.clamp(Math.round(setting.value() * 10.0f), -50, 50),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Integer> spp() {
        IntSetting setting = FluoriteConfig.Rt.Composite.SPP;
        return new OptionInstance<>(
            "fluorite.options.rt.spp",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.spp.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(setting.value(), 1, 8),
            setting::set);
    }

    private static OptionInstance<Integer> maxBounces() {
        IntSetting setting = FluoriteConfig.Rt.Composite.MAX_BOUNCES;
        return new OptionInstance<>(
            "fluorite.options.rt.maxBounces",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.maxBounces.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(2, 8),
            Math.clamp(setting.value(), 2, 8),
            setting::set);
    }

    private static OptionInstance<Integer> sunSize() {
        // Stored in radians via the degrees->radians sanitizer; the slider works in tenths of a degree.
        FloatSetting setting = FluoriteConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        int initialTenths = Math.clamp(Math.round((float) Math.toDegrees(setting.value()) * 10.0f), 1, 50);
        return new OptionInstance<>(
            "fluorite.options.rt.sunSize",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.sunSize.tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f°", tenths / 10.0))),
            new OptionInstance.IntRange(1, 50),
            initialTenths,
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> entities() {
        return bool("fluorite.options.rt.entities", FluoriteConfig.Rt.Entities.ENABLED);
    }

    private static OptionInstance<Boolean> particles() {
        return bool("fluorite.options.rt.particles", FluoriteConfig.Rt.Entities.PARTICLES_ENABLED);
    }

    private static OptionInstance<Boolean> waterWaves() {
        return bool("fluorite.options.rt.waterWaves", FluoriteConfig.Rt.Composite.WATER_WAVES);
    }

    // ---- Ambient participating medium. Every one of these is re-read per frame straight into the world
    // push, so they belong here rather than on the -Dfluorite.* startup surface. The two that would need
    // resources rebuilt when the froxel pass exists — slice count and march steps — will not.

    private static OptionInstance<Boolean> fogEnabled() {
        return bool("fluorite.options.rt.fog", FluoriteConfig.Rt.Volumetrics.ENABLED);
    }

    private static OptionInstance<Integer> fogDensity() {
        return scaleSlider("fluorite.options.rt.fogDensity", FluoriteConfig.Rt.Volumetrics.DENSITY_SCALE);
    }

    private static OptionInstance<Integer> fogIntensity() {
        return scaleSlider("fluorite.options.rt.fogIntensity", FluoriteConfig.Rt.Volumetrics.INTENSITY_SCALE);
    }

    private static OptionInstance<Integer> fogHeightBase() {
        return blockSlider("fluorite.options.rt.fogHeightBase",
                FluoriteConfig.Rt.Volumetrics.HEIGHT_BASE, -64, 320);
    }

    private static OptionInstance<Integer> fogStartDistance() {
        return blockSlider("fluorite.options.rt.fogStart",
                FluoriteConfig.Rt.Volumetrics.START_DISTANCE, 0, 256);
    }

    private static OptionInstance<Integer> fogCullDistance() {
        return blockSlider("fluorite.options.rt.fogCull",
                FluoriteConfig.Rt.Volumetrics.CULL_DISTANCE, 16, 2048);
    }

    private static OptionInstance<Integer> fogHeightScale() {
        return blockSlider("fluorite.options.rt.fogHeightScale",
                FluoriteConfig.Rt.Volumetrics.HEIGHT_SCALE, 1, 384);
    }

    private static OptionInstance<Integer> fogPhaseG() {
        // Hundredths, so the slider can reach the useful -0.9..0.9 range at a readable step.
        FloatSetting setting = FluoriteConfig.Rt.Volumetrics.PHASE_G;
        int initial = Math.clamp(Math.round(setting.value() * 100.0f), -90, 90);
        return new OptionInstance<>(
            "fluorite.options.rt.fogPhaseG",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.fogPhaseG.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.format("%.2f", v / 100.0))),
            new OptionInstance.IntRange(-90, 90),
            initial,
            v -> setting.set(v / 100.0f));
    }

    private static OptionInstance<String> fogScatterTint() {
        StringSetting setting = FluoriteConfig.Rt.Volumetrics.SCATTER_TINT;
        return new OptionInstance<>(
            "fluorite.options.rt.fogTint",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.fogTint.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.fogTint." + value),
            new OptionInstance.Enum<>(List.of("neutral", "warm", "cool", "green", "violet"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    /** A 0.0-10.0 multiplier, stepped in tenths. IntRange is the only slider vanilla exposes. */
    private static OptionInstance<Integer> scaleSlider(String key, FloatSetting setting) {
        int initial = Math.clamp(Math.round(setting.value() * 10.0f), 0, 100);
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f", v / 10.0))),
            new OptionInstance.IntRange(0, 100),
            initial,
            v -> setting.set(v / 10.0f));
    }

    /** A slider over a distance in blocks. */
    private static OptionInstance<Integer> blockSlider(String key, FloatSetting setting, int min, int max) {
        int initial = Math.clamp(Math.round(setting.value()), min, max);
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(v + " blocks")),
            new OptionInstance.IntRange(min, max),
            initial,
            v -> setting.set((float) v));
    }

    /**
      * The reference switch.
      *
      * <p>With Ray Reconstruction off the renderer traces at display resolution and shows the raw path
      * trace — noisy at low spp, and with no temporal accumulation whatsoever. That is the only way to
      * judge whether a change to the BSDF is right: every denoised comparison is also a comparison of how
      * the denoiser reacted, and the two are hard to tell apart. A specular highlight that trails behind
      * the camera under RR is perfectly still here, because the trail is temporal lag rather than
      * anything the material did.
      *
      * <p>Raise spp and bounces alongside it for a converged reference. ensureOutput already treats the
      * toggle as a resize trigger, so this takes effect on the next frame.
      */
    private static OptionInstance<Boolean> dlssEnabled() {
        return bool("fluorite.options.rt.dlssEnabled", FluoriteConfig.Rt.DlssRr.ENABLED);
    }

    /**
     * Multiple importance sampling for the sun and moon.
     *
     * <p>Here rather than in the TOML because it exists to be flipped back and forth: it is the only
     * change so far that alters how bright a material is, and the comparison is the point. Read alongside
     * the reference switch above — RR off is where a brightness change is judged, since a denoiser
     * reacting to a sharper highlight looks a lot like the highlight itself changing.
     */
    private static OptionInstance<Boolean> sunMis() {
        return bool("fluorite.options.rt.sunMis", FluoriteConfig.Rt.Bsdf.MIS_ENABLED);
    }

    /**
     * The anisotropic specular lobe. Here for the same reason as the switch above: it changes how a
     * material looks, and the comparison is the point. A material that authored no anisotropy is
     * identical either way, so this is only ever visible on packs that use it.
     */
    private static OptionInstance<Boolean> anisotropy() {
        return bool("fluorite.options.rt.anisotropy", FluoriteConfig.Rt.Bsdf.ANISOTROPY_ENABLED);
    }

    /** Subsurface estimator: off, the thin-shell approximation, or a real walk through the medium. */
    private static OptionInstance<String> subsurfaceMode() {
        StringSetting setting = FluoriteConfig.Rt.Bsdf.SUBSURFACE_MODE;
        return new OptionInstance<>(
            "fluorite.options.rt.subsurfaceMode",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.subsurfaceMode.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.subsurfaceMode." + value),
            new OptionInstance.Enum<>(List.of("off", "thin", "random-walk"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    /**
     * The walk's event budget — the lever, not a quality dial you set and forget.
     *
     * <p>Cost is near-linear in it: every event is one traversal. Lowering it does not darken anything,
     * because a walk that runs out falls back to a diffuse bounce; it makes thick material less accurate.
     */
    private static OptionInstance<Integer> subsurfaceMaxEvents() {
        IntSetting setting = FluoriteConfig.Rt.Bsdf.SUBSURFACE_MAX_EVENTS;
        return new OptionInstance<>(
            "fluorite.options.rt.subsurfaceMaxEvents",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.subsurfaceMaxEvents.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, value),
            new OptionInstance.IntRange(0, 7),
            Math.clamp(setting.value(), 0, 7),
            setting::set);
    }

    // NVSDK_NGX_PerfQuality_Value, ordered performance -> quality for the slider. Per NVIDIA's DLSS-RR
    // programming guide, Ray Reconstruction only supports Performance(0), Balanced(1), Quality(2),
    // Ultra-Performance(3), and DLAA(5) — Ultra Quality(4) is not a valid PerfQualityValue for RR (its
    // optimal-settings query returns a zeroed render size for it) and is deliberately excluded here.
    private static final List<Integer> DLSS_QUALITY_ORDER = List.of(3, 0, 1, 2, 5);

    private static OptionInstance<Integer> dlssQuality() {
        IntSetting setting = FluoriteConfig.Rt.DlssRr.QUALITY;
        int initialQuality = DLSS_QUALITY_ORDER.contains(setting.value()) ? setting.value() : 0;
        int initialPosition = DLSS_QUALITY_ORDER.indexOf(initialQuality);
        return new OptionInstance<>(
            "fluorite.options.rt.dlssQuality",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.dlssQuality.tooltip")),
            (caption, position) -> Options.genericValueLabel(caption,
                    Component.translatable("fluorite.options.rt.dlssQuality." + DLSS_QUALITY_ORDER.get(position))),
            new OptionInstance.IntRange(0, DLSS_QUALITY_ORDER.size() - 1),
            initialPosition,
            position -> setting.set(DLSS_QUALITY_ORDER.get(position)));
    }

    private static OptionInstance<Boolean> hdrEnabled() {
        return bool("fluorite.options.rt.hdr", FluoriteConfig.Rt.Hdr.ENABLED);
    }

    private static OptionInstance<Integer> hdrPaperWhite() {
        FloatSetting setting = FluoriteConfig.Rt.Hdr.PAPER_WHITE_NITS;
        return new OptionInstance<>(
            "fluorite.options.rt.hdrPaperWhite",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.hdrPaperWhite.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 1000),
            Math.clamp(Math.round(setting.value()), 80, 1000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> hdrPeak() {
        FloatSetting setting = FluoriteConfig.Rt.Hdr.PEAK_NITS;
        return new OptionInstance<>(
            "fluorite.options.rt.hdrPeak",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.hdrPeak.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.IntRange(80, 10000),
            Math.clamp(Math.round(setting.value()), 80, 10000),
            nits -> setting.set(nits.floatValue()));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = FluoriteConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "fluorite.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("fluorite.options.rt.debugView." + value),
            new OptionInstance.Enum<>(List.of(0, 1, 2, 3, 4, 5, 6, 7), Codec.INT),
            Math.clamp(setting.value(), 0, 7),
            setting::set);
    }

    private static OptionInstance<Boolean> bool(String captionKey, BooleanSetting setting) {
        return OptionInstance.createBoolean(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            setting.value(),
            setting::set);
    }
}
