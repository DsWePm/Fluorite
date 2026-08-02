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
 * Builds the {@link OptionInstance} widgets shown on Fluorite's own options screens (opened from the
 * vanilla Video Settings screen by {@code VideoSettingsScreenMixin}). Each option is bound straight to a
 * {@link FluoriteConfig} runtime setting: the initial value is read from the current config, and the
 * value-update listener writes back through {@code set(...)} so changes take effect on the next frame.
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

    /**
     * One run of options under an optional header, inside a category.
     *
     * <p>{@code titleKey} empty means no header — the common case. It exists for the categories that hold
     * two related but distinct groups and would otherwise need a screen each for two rows.
     */
    public record Section(String titleKey, OptionInstance<?>[] options) {
        static Section of(OptionInstance<?>... options) {
            return new Section("", options);
        }

        static Section titled(String titleKey, OptionInstance<?>... options) {
            return new Section(titleKey, options);
        }
    }

    /**
     * A screen's worth of settings.
     *
     * <p>Grouped by what the player is looking at rather than by which config class holds the setting —
     * water waves live in {@code Rt.Composite} and turbidity in {@code Rt.Water}, and nobody tuning water
     * cares. The one place the two agree is fog, which is why {@code Rt.Volumetrics} maps 1:1.
     *
     * <p>Options are built when a category's screen opens, never up front: an {@link OptionInstance}
     * captures its setting's value at construction, so one built to label a button would show whatever the
     * config said when the hub was drawn.
     */
    public enum Category {
        TRACING("tracing"),
        EXPOSURE("exposure"),
        MATERIAL("material"),
        SKY("sky"),
        WATER("water"),
        FOG("fog"),
        UPSCALING("upscaling"),
        HDR("hdr"),
        DIAGNOSTICS("diagnostics");

        private final String key;

        Category(String key) {
            this.key = key;
        }

        public Component title() {
            return Component.translatable("fluorite.options.rt.category." + key);
        }

        public Component description() {
            return Component.translatable("fluorite.options.rt.category." + key + ".tooltip");
        }

        /** Freshly built, in display order. Paired two-per-row by {@code OptionsList.addSmall}. */
        public List<Section> sections() {
            return switch (this) {
                case TRACING -> List.of(Section.of(spp(), maxBounces(), sunSize(), entities(), particles()));
                case EXPOSURE -> List.of(Section.of(exposureMode(), manualEv()));
                case MATERIAL -> List.of(
                        Section.of(sunMis(), anisotropy()),
                        Section.titled("fluorite.options.rt.section.subsurface",
                                subsurfaceMode(), subsurfaceThickness(), subsurfaceMaxEvents()));
                case SKY -> List.of(
                        Section.titled("fluorite.options.rt.section.sunArt",
                                sunIntensity(), sunTemperature()),
                        Section.titled("fluorite.options.rt.section.skyArt",
                                skyIntensity(), skyTemperature()));
                case WATER -> List.of(
                        Section.of(waterWaves(), waterCausticDispersion(), waterScatterSource(),
                                bool("fluorite.options.rt.waterSunShadow",
                                        FluoriteConfig.Rt.Water.SUN_SHADOW),
                                waterAmbientScale(), waterPhaseG()),
                        Section.titled("fluorite.options.rt.section.waterScatter",
                                waterScatterR(), waterScatterG(), waterScatterB()),
                        Section.titled("fluorite.options.rt.section.waterAbsorb",
                                waterAbsorbOverride(), waterAbsorbR(), waterAbsorbG(), waterAbsorbB()));
                case FOG -> List.of(Section.of(fogEnabled(), fogDensity(), fogIntensity(),
                        fogHeightBase(), fogHeightScale(), fogStartDistance(), fogCullDistance(),
                        fogPhaseG(), fogScatterTint(), fogSunShadowRays(), fogMultiScatter()));
                case UPSCALING -> List.of(Section.of(dlssEnabled(), dlssQuality()));
                case HDR -> List.of(Section.of(hdrEnabled(), hdrPaperWhite(), hdrPeak()));
                case DIAGNOSTICS -> List.of(Section.of(debugView(), fogSegmentSource()));
            };
        }
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

    /**
     * The water's two coefficients, dialled side by side.
     *
     * <p>Both here on purpose. They are what the medium IS — absorption takes light out, scattering
     * sends it elsewhere — and the look lives in their ratio, not in either alone: the colour deep water
     * settles on is {@code sigma_s/(sigma_a+sigma_s)} times the sky, with the extinction cancelling out
     * entirely. Tuning one without seeing the other is how "murkier" ended up meaning "paler".
     *
     * <p>Raise scattering alone and the water goes pale and bright. Raise both and it goes murky and
     * dark, which is what suspended matter really does and what could not be expressed at all while a
     * single turbidity multiplier drove scattering by itself.
     */
    private static OptionInstance<Integer> waterScatterR() {
        return byteSlider("fluorite.options.rt.waterScatterR", FluoriteConfig.Rt.Water.SCATTER_R);
    }

    private static OptionInstance<Integer> waterScatterG() {
        return byteSlider("fluorite.options.rt.waterScatterG", FluoriteConfig.Rt.Water.SCATTER_G);
    }

    private static OptionInstance<Integer> waterScatterB() {
        return byteSlider("fluorite.options.rt.waterScatterB", FluoriteConfig.Rt.Water.SCATTER_B);
    }

    private static OptionInstance<Boolean> waterAbsorbOverride() {
        return bool("fluorite.options.rt.waterAbsorbOverride", FluoriteConfig.Rt.Water.ABSORB_OVERRIDE);
    }

    private static OptionInstance<Integer> waterAbsorbR() {
        return byteSlider("fluorite.options.rt.waterAbsorbR", FluoriteConfig.Rt.Water.ABSORB_R);
    }

    private static OptionInstance<Integer> waterAbsorbG() {
        return byteSlider("fluorite.options.rt.waterAbsorbG", FluoriteConfig.Rt.Water.ABSORB_G);
    }

    private static OptionInstance<Integer> waterAbsorbB() {
        return byteSlider("fluorite.options.rt.waterAbsorbB", FluoriteConfig.Rt.Water.ABSORB_B);
    }

    /**
     * How bright the sky is inside the water. The single brightness control for the murk: deep water
     * saturates at albedo times this, so it is half of why the water was ever too pale.
     */
    private static OptionInstance<Integer> waterAmbientScale() {
        FloatSetting setting = FluoriteConfig.Rt.Water.AMBIENT_SCALE;
        return new OptionInstance<>(
            "fluorite.options.rt.waterAmbient",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterAmbient.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100f), 0, 100),
            v -> setting.set(v / 100.0f));
    }

    /**
     * Which source feeds the water's scattering. A measurement control, deliberately in the UI rather
     * than the TOML: the question it answers ("which term is doing that?") comes up while looking at the
     * water, and an answer that needs a restart is one nobody collects.
     */
    private static OptionInstance<String> waterScatterSource() {
        StringSetting setting = FluoriteConfig.Rt.Water.SCATTER_SOURCE;
        return new OptionInstance<>(
            "fluorite.options.rt.waterScatterSource",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterScatterSource.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.waterScatterSource." + value),
            new OptionInstance.Enum<>(List.of("both", "sun", "sky", "none"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    /** Caustic dispersion, in tenths so the physical 1.0 is reachable and exaggeration is a step away. */
    private static OptionInstance<Integer> waterCausticDispersion() {
        FloatSetting setting = FluoriteConfig.Rt.Water.CAUSTIC_DISPERSION;
        return new OptionInstance<>(
            "fluorite.options.rt.waterCausticDispersion",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterCausticDispersion.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.1fx", v / 10.0))),
            new OptionInstance.IntRange(0, 1000),
            Math.clamp(Math.round(setting.value() * 10f), 0, 1000),
            v -> setting.set(v / 10.0f));
    }

    private static OptionInstance<Integer> waterPhaseG() {
        FloatSetting setting = FluoriteConfig.Rt.Water.PHASE_G;
        return new OptionInstance<>(
            "fluorite.options.rt.waterPhaseG",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterPhaseG.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(-90, 90),
            Math.clamp(Math.round(setting.value() * 100f), -90, 90),
            v -> setting.set(v / 100.0f));
    }

    /** A 0-255 per-channel coefficient. The medium's coefficients are authored the way a colour is. */
    private static OptionInstance<Integer> byteSlider(String key, IntSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(v))),
            new OptionInstance.IntRange(0, 255),
            Math.clamp(setting.value(), 0, 255),
            setting::set);
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

    /**
     * Let light reaching a scattering point decay at the diffusion rate instead of the beam's.
     *
     * <p>In the UI for the same reason the ray count is: off must reproduce the old behaviour exactly, so
     * this is an A/B rather than a dial, and an A/B is only worth anything flipped at a fixed camera
     * position inside one session.
     */
    private static OptionInstance<Boolean> fogMultiScatter() {
        return bool("fluorite.options.rt.fogMultiScatter", FluoriteConfig.Rt.Volumetrics.MULTI_SCATTER);
    }

    /**
     * Jittered shadow rays for the fog's sun term, 0 to 4.
     *
     * <p>In the UI rather than the config file because its cost has to be measured by flipping it at a
     * FIXED camera position inside one session. Two sessions in two places produce two numbers that
     * cannot be subtracted, which is exactly the trap the first attempt at this measurement fell into.
     */
    private static OptionInstance<Integer> fogSunShadowRays() {
        IntSetting setting = FluoriteConfig.Rt.Volumetrics.SUN_SHADOW_RAYS;
        return new OptionInstance<>(
            "fluorite.options.rt.fogSunShadowRays",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.fogSunShadowRays.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.translatable(v == 0 ? "fluorite.options.rt.fogSunShadowRays.grid"
                                                  : "fluorite.options.rt.fogSunShadowRays.rays",
                                           String.valueOf(v))),
            new OptionInstance.IntRange(0, 4),
            Math.clamp(setting.value(), 0, 4),
            setting::set);
    }

    /**
     * Which of the fog's two in-scatter machines are live. A diagnostic rather than a look, which is why
     * it sits beside the debug views instead of under Fog: the froxel covers the camera's prefix segment
     * and resolves visibility per world-space cell, every bounce segment runs the unshadowed closed form,
     * and on screen the two are added. Silencing one is the only way to say which produced a brightness.
     */
    private static OptionInstance<String> fogSegmentSource() {
        StringSetting setting = FluoriteConfig.Rt.Volumetrics.SEGMENT_SOURCE;
        return new OptionInstance<>(
            "fluorite.options.rt.fogSegmentSource",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.fogSegmentSource.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.fogSegmentSource." + value),
            new OptionInstance.Enum<>(List.of("both", "froxel", "marched", "none"), Codec.STRING),
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
    /**
     * Thin-shell thickness. In the UI rather than the TOML because after M8 the thin shell is the
     * default subsurface path, not a fallback — it is the look most people will ever see, so its one
     * shaping control should be reachable while looking at a leaf.
     */
    private static OptionInstance<Integer> subsurfaceThickness() {
        FloatSetting setting = FluoriteConfig.Rt.Bsdf.SUBSURFACE_THICKNESS;
        return new OptionInstance<>(
            "fluorite.options.rt.subsurfaceThickness",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.subsurfaceThickness.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(5, 500),
            Math.clamp(Math.round(setting.value() * 100f), 5, 500),
            v -> setting.set(v / 100.0f));
    }

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

    // The four art-direction knobs. Every default is the identity, so the slider that reads "physical"
    // or "1.00x" is not a preset among others — it is the renderer with nothing added.
    private static OptionInstance<Integer> sunIntensity() {
        return intensity("fluorite.options.rt.sunIntensity", FluoriteConfig.Rt.Sky.SUN_INTENSITY);
    }

    private static OptionInstance<Integer> skyIntensity() {
        return intensity("fluorite.options.rt.skyIntensity", FluoriteConfig.Rt.Sky.SKY_INTENSITY);
    }

    private static OptionInstance<Integer> sunTemperature() {
        return temperature("fluorite.options.rt.sunTemperature", FluoriteConfig.Rt.Sky.SUN_TEMPERATURE);
    }

    private static OptionInstance<Integer> skyTemperature() {
        return temperature("fluorite.options.rt.skyTemperature", FluoriteConfig.Rt.Sky.SKY_TEMPERATURE);
    }

    /** A 0..8x multiplier, carried in hundredths because OptionInstance sliders are integer-valued. */
    private static OptionInstance<Integer> intensity(String captionKey, FloatSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2fx", hundredths / 100.0f))),
            new OptionInstance.IntRange(0, 800),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 800),
            hundredths -> setting.set(hundredths / 100.0f));
    }

    /**
     * A colour temperature in kelvin, with 0 reading as "physical" rather than as a very cold black.
     *
     * <p>The step is 100 K and the range starts at 1500: below that the Planckian fit this is drawn from
     * stops being meaningful, and a slider that can be dragged into a region where the number lies is
     * worse than one that cannot reach it.
     */
    private static OptionInstance<Integer> temperature(String captionKey, IntSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, steps) -> Options.genericValueLabel(caption, steps == 0
                    ? Component.translatable("fluorite.options.rt.temperature.physical")
                    : Component.literal((1500 + (steps - 1) * 100) + " K")),
            new OptionInstance.IntRange(0, 186),
            setting.value() <= 0 ? 0 : Math.clamp((setting.value() - 1500) / 100 + 1, 1, 186),
            steps -> setting.set(steps == 0 ? 0 : 1500 + (steps - 1) * 100));
    }

    private static OptionInstance<Integer> debugView() {
        IntSetting setting = FluoriteConfig.Rt.Composite.DEBUG_VIEW;
        return new OptionInstance<>(
            "fluorite.options.rt.debugView",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.debugView.tooltip")),
            // CycleButton (used for Enum values) already prepends "caption: " itself (DisplayState.
            // NAME_AND_VALUE), so this must return only the value's text, not caption + value again.
            (caption, value) -> Component.translatable("fluorite.options.rt.debugView." + value),
            // 0-7 are pass A's guide buffers; 8-11 are pass B's volume views, which describe the segments
            // between hits rather than the hits themselves. See world.rgen's volumeDebug. 12 is neither —
            // 12 and 13 are neither — they paint the atmosphere's own tables, ignoring the scene entirely.
            new OptionInstance.Enum<>(
                    List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19),
                    Codec.INT),
            Math.clamp(setting.value(), 0, 19),
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
