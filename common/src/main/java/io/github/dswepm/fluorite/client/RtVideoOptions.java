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
        MATERIAL("material"),
        SKY("sky"),
        WEATHER("weather"),
        DIMENSIONS("dimensions"),
        WATER("water"),
        FOG("fog"),
        UPSCALING("upscaling"),
        POST_PROCESSING("postProcessing"),
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
                // The medium estimators sit here rather than under FOG, and that is a correctness point
                // about the UI rather than a tidiness one: every one of them acts on the fog AND on the
                // water, so filing them under "fog" told the player something false. MULTI_SCATTER was
                // built because deep water went black as well as dense fog; SCATTER_VERTEX's default was
                // settled by a measurement taken UNDERWATER (D30); emitter NEE lights both. A player who
                // finds them under Fog and concludes their water is unaffected has been misled by the
                // menu.
                case TRACING -> List.of(
                        Section.of(spp(), maxBounces(), sunSize(), entities(), particles(),
                                particleShadows()),
                        Section.titled("fluorite.options.rt.section.media",
                                volumeMultiScatter(), volumeScatterVertex(), volumeEmitterNee()));
                case MATERIAL -> List.of(
                        Section.of(sunMis(), anisotropy()),
                        Section.titled("fluorite.options.rt.section.subsurface",
                                subsurfaceMode(), subsurfaceThickness(), subsurfaceMaxEvents()));
                // Clouds are sky, not fog. They share a config namespace with the fog for historical
                // reasons and nothing else: a cloud deck is a thing you look UP at, authored alongside
                // the sun and the sky it hangs in, while the fog is the air you stand in.
                case SKY -> List.of(
                        Section.titled("fluorite.options.rt.section.sunArt",
                                sunIntensity(), sunTemperature()),
                        Section.titled("fluorite.options.rt.section.skyArt",
                                skyIntensity(), skyTemperature()),
                        // The low convective deck keeps the volume controls and 3D march budget.
                        Section.titled("fluorite.options.rt.section.cumulus",
                                clouds(), cloudCoverage(), cloudType(), cloudDensity(),
                                cloudExtinction(), cloudAltitude(), cloudThickness(),
                                cloudFieldScale(), cloudBaseScale(), cloudDetailScale(),
                                cloudWindSpeed(), cloudWindOffset()),
                        Section.titled("fluorite.options.rt.section.cloudLighting",
                                cloudSunSteps(), cloudMultiScatter(), cloudPhaseG(), cloudAlbedo(),
                                cloudSecondary()),
                        // D163-D168 high clouds are two analytic optical-depth sheets. Their shape scale,
                        // shared weather controls and wind remain independent from the low volume.
                        Section.titled("fluorite.options.rt.section.cloudCirrus",
                                cloudCirrus(), cloudCirrusCoverage(), cloudCirrusDensity(),
                                cloudCirrusPatchStrength(),
                                cloudCirrusExtinction(), cloudCirrusAltitude(), cloudCirrusThickness(),
                                cloudCirrusPatchSpacing(), cloudCirrusPatchDiameter(),
                                cloudCirrusWindSpeed(), cloudCirrusWindOffset()));
                case WEATHER -> List.of(
                        Section.of(windAngle()),
                        Section.titled("fluorite.options.rt.section.weatherRainExposure",
                                rainSurfacesEnabled(), rainExposureQuality(), rainSlantDegrees(),
                                wetFillSeconds(), wetDrySeconds(), puddleFillSeconds(),
                                puddleDrySeconds(), daylightDrying()),
                        Section.titled("fluorite.options.rt.section.weatherWetSurface",
                                wetFilmStrength(), wetFilmRoughness(), puddlesEnabled(),
                                puddleCoverage(), puddleScale(), puddleRippleStrength(), rainRippleSize(),
                                defaultWetAbsorption(), defaultWetDarkening(),
                                defaultWetFilm(), defaultPuddleAffinity()),
                        Section.titled("fluorite.options.rt.section.weatherRainCalibration",
                                wetDarkeningGain(), wetCoatGain(), puddleLayerGain(), puddleRoughness(),
                                puddleExtraDarkening(), puddleNormalFlattening(),
                                wetFilmNormalFlattening(), rainRippleWidth()),
                        Section.titled("fluorite.options.rt.section.weatherRainParticles",
                                rainParticlesEnabled(), rainStreakQuality(), rainStreakDensity(),
                                rainStreakSpeed(), rainStreakLength(), rainSplashTarget(),
                                rainSplashSize(), rainSplashOpacity(), rainSplashBrightness()),
                        Section.titled("fluorite.options.rt.section.weatherFog",
                                fogTimeGain(), fogWeatherGain(), fogThunderDensityGain(),
                                fogTimeStructureGain(), fogRainStructureGain(),
                                fogThunderStructureGain()),
                        Section.titled("fluorite.options.rt.section.weatherCloud",
                                cloudWeather(), cloudRainCoverageBias(), cloudRainDensityGain(),
                                cloudThunderTypeBias()),
                        Section.titled("fluorite.options.rt.section.weatherWater",
                                waveWeather(), waterStormSwellBias(),
                                waterRainScatterGain(), waterThunderScatterGain()));
                case DIMENSIONS -> List.of(
                        Section.titled("fluorite.options.rt.section.dimensionNether",
                                netherFogEnabled(), netherFogDensity(), netherAmbientBrightness()),
                        Section.titled("fluorite.options.rt.section.dimensionEnd",
                                endEnvironmentBrightness(), endEnvironmentRotationSpeed(),
                                endDiskBrightness(), endDiskOuterRadius(), endDiskThickness()));
                case WATER -> List.of(
                        Section.of(waterWaves(), waterCausticDispersion(), waterCausticStrength(),
                                waterScatterSource(),
                                bool("fluorite.options.rt.waterSunShadow",
                                        FluoriteConfig.Rt.Water.SUN_SHADOW),
                                waterPhaseG()),
                        Section.titled("fluorite.options.rt.section.waterScatter",
                                waterScatterStrength(), waterScatterR(), waterScatterG(), waterScatterB()),
                        Section.titled("fluorite.options.rt.section.waterSim",
                                waterSim(), waterSimRange(), waterSimHeight(), waterSimReanchor(),
                                waterSimStrength(),
                                waterSimSpeed(), waterSimDamping(), waterSimImpulse(),
                                waterSimImpulseSize(), waterSimImpulseDepth()),
                        // The geometry, separate from the ripples that ride on it: one controls whether
                        // the surface is a shape at all, the others how much of it is and how finely.
                        // THE WAVE FIELD IS NOT THE DEFORMATION. Everything here shapes the field
                        // itself, and the field reaches the picture through the NORMAL whether or not
                        // any geometry ever moves -- the deformation is one optional consumer of it,
                        // and a band-limited one at that. Filing these under it said the opposite, and
                        // would have had anyone with deformation off assume the whole group was inert.
                        Section.titled("fluorite.options.rt.section.waterWaves",
                                waveWindOffset(), waveLength(), waveAmplitude(),
                                waveSpeed(), waveComplexity(), waveCrossAngle(),
                                waveWarp(), waveWarpScale(),
                                waveFirst(), waveLast(), waveBandLimit(),
                                waveGust(), waveGustScale(), waveGustSpeed()),
                        // What is genuinely about moving vertices.
                        Section.titled("fluorite.options.rt.section.waterDeform",
                                waterDeform(), waterDeformMode(), waterDeformRange(), waterDeformCell(),
                                waterDeformReanchor()),
                        Section.titled("fluorite.options.rt.section.waterAbsorb",
                                waterAbsorbOverride(), waterAbsorbStrength(),
                                waterAbsorbR(), waterAbsorbG(), waterAbsorbB()));
                // What is left is genuinely the fog and only the fog. fogSunShadowRays stays because the
                // water has its own sun-shadow switch under WATER and these two do not affect each other.
                case FOG -> List.of(
                        Section.of(fogEnabled(), fogDensity(), fogAlbedoScale(),
                                fogHeightBase(), fogHeightScale(), fogStartDistance(), fogCullDistance(),
                                fogPhaseG(), fogScatterTint(), fogSunShadowRays()),
                        Section.titled("fluorite.options.rt.section.fogNoise",
                                fogNoiseEnabled(), fogNoiseContrast(), fogNoiseFieldScale(),
                                fogNoiseWindSpeed(), fogNoiseWindOffset(), fogNoiseMarchSteps()));
                case UPSCALING -> List.of(Section.of(dlssEnabled(), dlssQuality()));
                case POST_PROCESSING -> List.of(
                        Section.titled("fluorite.options.rt.section.exposure",
                                exposureMode(), autoExposureCompensation(), manualExposureEv(),
                                brightAdaptationTime(), darkAdaptationTime()),
                        Section.titled("fluorite.options.rt.section.outputTransform",
                                outputTransform(), hdrEnabled(), acesHdrPreset(),
                                hdrPaperWhite(), hdrPeak()));
                case DIAGNOSTICS -> List.of(Section.of(debugView(), fogSegmentSource(),
                        bool("fluorite.options.rt.waterMediumTrace",
                                FluoriteConfig.Rt.Diagnostics.WATER_MEDIUM_TRACE)));
            };
        }
    }

    /** D154A's classified lens/camera submenu; the post-processing root keeps only global output state. */
    public static List<Section> lensEffectsSections() {
        return List.of(
                        Section.titled("fluorite.options.rt.section.filmGrain",
                                filmGrainEnabled(), filmGrainIntensity(), filmGrainSize(),
                                filmGrainChromatic(), filmGrainShadows(), filmGrainMidtones(),
                                filmGrainHighlights()),
                        Section.titled("fluorite.options.rt.section.hdrHighlights",
                                highlightThreshold(), highlightSoftKnee()),
                        Section.titled("fluorite.options.rt.section.bloom",
                                bloomEnabled(), bloomIntensity(), bloomRadius()),
                        Section.titled("fluorite.options.rt.section.lensFlare",
                                lensFlareEnabled(), lensFlareIntensity(), lensFlareGhosts(),
                                lensFlareHalo(), lensFlareStreaks(), lensFlareThreshold(),
                                lensFlareBokehSize()),
                        Section.titled("fluorite.options.rt.section.depthOfField",
                                depthOfFieldEnabled(), depthOfFieldFocusMode(), depthOfFieldFocusDistance(),
                                depthOfFieldFStop(), depthOfFieldMaxRadius(), depthOfFieldApertureBlades()),
                        Section.titled("fluorite.options.rt.section.motionBlur",
                                motionBlurEnabled(), motionBlurShutterAngle(), motionBlurSamples(),
                                motionBlurMaxRadius()),
                        Section.titled("fluorite.options.rt.section.lensGeometry",
                                lensDistortionEnabled(), lensDistortionStrength(),
                                chromaticAberrationEnabled(), chromaticAberrationStrength()),
                        Section.titled("fluorite.options.rt.section.framing",
                                vignetteEnabled(), vignetteIntensity(), vignetteStart(), vignetteSoftness()));
    }

    /** The D147A nested UE-style artistic grading page, built fresh each time it is opened. */
    public static List<Section> artisticGradingSections() {
        return List.of(
                Section.of(colorGradingEnabled()),
                Section.titled("fluorite.options.rt.section.gradingGlobal",
                        colorTemperature(), colorTint(), colorContrast(), colorSaturation(), colorHue()),
                Section.titled("fluorite.options.rt.section.gradingShadows",
                        gradeEv("fluorite.options.rt.shadowExposure",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_EXPOSURE_EV, -4.0f, 4.0f),
                        gradeEv("fluorite.options.rt.shadowRed",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_RED_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.shadowGreen",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_GREEN_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.shadowBlue",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_BLUE_EV, -2.0f, 2.0f),
                        gradeScale("fluorite.options.rt.shadowSaturation",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_SATURATION),
                        gradeScale("fluorite.options.rt.shadowContrast",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_CONTRAST)),
                Section.titled("fluorite.options.rt.section.gradingMidtones",
                        gradeEv("fluorite.options.rt.midExposure",
                                FluoriteConfig.Rt.PostProcessing.MID_EXPOSURE_EV, -4.0f, 4.0f),
                        gradeEv("fluorite.options.rt.midRed",
                                FluoriteConfig.Rt.PostProcessing.MID_RED_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.midGreen",
                                FluoriteConfig.Rt.PostProcessing.MID_GREEN_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.midBlue",
                                FluoriteConfig.Rt.PostProcessing.MID_BLUE_EV, -2.0f, 2.0f),
                        gradeScale("fluorite.options.rt.midSaturation",
                                FluoriteConfig.Rt.PostProcessing.MID_SATURATION),
                        gradeScale("fluorite.options.rt.midContrast",
                                FluoriteConfig.Rt.PostProcessing.MID_CONTRAST)),
                Section.titled("fluorite.options.rt.section.gradingHighlights",
                        gradeEv("fluorite.options.rt.highlightExposure",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_EXPOSURE_EV, -4.0f, 4.0f),
                        gradeEv("fluorite.options.rt.highlightRed",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_RED_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.highlightGreen",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_GREEN_EV, -2.0f, 2.0f),
                        gradeEv("fluorite.options.rt.highlightBlue",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_BLUE_EV, -2.0f, 2.0f),
                        gradeScale("fluorite.options.rt.highlightSaturation",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_SATURATION),
                        gradeScale("fluorite.options.rt.highlightContrast",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_CONTRAST)),
                Section.titled("fluorite.options.rt.section.gradingRanges",
                        gradeEv("fluorite.options.rt.shadowBoundary",
                                FluoriteConfig.Rt.PostProcessing.SHADOW_BOUNDARY_EV, -8.0f, 0.0f),
                        gradeEv("fluorite.options.rt.highlightBoundary",
                                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_BOUNDARY_EV, 0.0f, 8.0f)));
    }

    /**
     * Let particles cast shadows. In the UI because it is the A/B lever for its own cost: the work lands
     * in the any-hit shader, which is the hottest in the frame, so it has to be judged by flipping it at
     * a fixed camera position rather than compared across sessions.
     */
    private static OptionInstance<Boolean> particleShadows() {
        return bool("fluorite.options.rt.particleShadows", FluoriteConfig.Rt.Entities.PARTICLE_SHADOWS);
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

    private static OptionInstance<Integer> autoExposureCompensation() {
        return exposureEvSlider("fluorite.options.rt.autoExposureCompensation",
                FluoriteConfig.Rt.Exposure.AUTO_EV_BIAS);
    }

    private static OptionInstance<Integer> manualExposureEv() {
        return exposureEvSlider("fluorite.options.rt.manualExposureEv",
                FluoriteConfig.Rt.Exposure.MANUAL_EV);
    }

    private static OptionInstance<Integer> exposureEvSlider(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
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

    private static OptionInstance<Integer> brightAdaptationTime() {
        return floatRangeSlider("fluorite.options.rt.brightAdaptationTime",
                FluoriteConfig.Rt.Exposure.BRIGHT_ADAPT_SECONDS, 1, 100, 0.05f, " s");
    }

    private static OptionInstance<Integer> darkAdaptationTime() {
        return floatRangeSlider("fluorite.options.rt.darkAdaptationTime",
                FluoriteConfig.Rt.Exposure.DARK_ADAPT_SECONDS, 1, 100, 0.1f, " s");
    }

    private static OptionInstance<String> outputTransform() {
        StringSetting setting = FluoriteConfig.Rt.PostProcessing.OUTPUT_TRANSFORM;
        return new OptionInstance<>(
            "fluorite.options.rt.outputTransform",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.outputTransform.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.outputTransform." + value),
            new OptionInstance.Enum<>(List.of("agx", "aces2-lut", "aces2-exact"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> acesHdrPreset() {
        IntSetting setting = FluoriteConfig.Rt.PostProcessing.ACES_HDR_PRESET;
        return new OptionInstance<>(
            "fluorite.options.rt.acesHdrPreset",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.acesHdrPreset.tooltip")),
            (caption, nits) -> Options.genericValueLabel(caption, Component.literal(nits + " nits")),
            new OptionInstance.Enum<>(List.of(500, 1000, 2000, 4000), Codec.INT),
            FluoriteConfig.Rt.PostProcessing.acesHdrPresetNits(),
            setting::set);
    }

    private static OptionInstance<Boolean> colorGradingEnabled() {
        return bool("fluorite.options.rt.colorGrading",
                FluoriteConfig.Rt.PostProcessing.COLOR_GRADING_ENABLED);
    }

    private static OptionInstance<Integer> colorTemperature() {
        IntSetting setting = FluoriteConfig.Rt.PostProcessing.TEMPERATURE_K;
        return new OptionInstance<>(
            "fluorite.options.rt.colorTemperature",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.colorTemperature.tooltip")),
            (caption, hundreds) -> Options.genericValueLabel(caption,
                    Component.literal((hundreds * 100) + " K")),
            new OptionInstance.IntRange(20, 120),
            Math.clamp(Math.round(setting.value() / 100.0f), 20, 120),
            hundreds -> setting.set(hundreds * 100));
    }

    private static OptionInstance<Integer> colorTint() {
        FloatSetting setting = FluoriteConfig.Rt.PostProcessing.TINT;
        return signedIntegerSlider("fluorite.options.rt.colorTint", setting, -100, 100);
    }

    private static OptionInstance<Integer> colorContrast() {
        return gradeScale("fluorite.options.rt.colorContrast",
                FluoriteConfig.Rt.PostProcessing.CONTRAST);
    }

    private static OptionInstance<Integer> colorSaturation() {
        return gradeScale("fluorite.options.rt.colorSaturation",
                FluoriteConfig.Rt.PostProcessing.SATURATION);
    }

    private static OptionInstance<Integer> colorHue() {
        FloatSetting setting = FluoriteConfig.Rt.PostProcessing.HUE_DEGREES;
        return new OptionInstance<>(
            "fluorite.options.rt.colorHue",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.colorHue.tooltip")),
            (caption, degrees) -> Options.genericValueLabel(caption,
                    Component.literal((degrees > 0 ? "+" : "") + degrees + " deg")),
            new OptionInstance.IntRange(-180, 180),
            Math.clamp(Math.round(setting.value()), -180, 180),
            degrees -> setting.set(degrees.floatValue()));
    }

    private static OptionInstance<Integer> gradeEv(String captionKey, FloatSetting setting,
                                                    float minimum, float maximum) {
        int minTenths = Math.round(minimum * 10.0f);
        int maxTenths = Math.round(maximum * 10.0f);
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, tenths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%+.1f EV", tenths / 10.0f))),
            new OptionInstance.IntRange(minTenths, maxTenths),
            Math.clamp(Math.round(setting.value() * 10.0f), minTenths, maxTenths),
            tenths -> setting.set(tenths / 10.0f));
    }

    private static OptionInstance<Boolean> filmGrainEnabled() {
        return bool("fluorite.options.rt.filmGrain",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_ENABLED);
    }

    private static OptionInstance<Integer> filmGrainIntensity() {
        return unitSlider("fluorite.options.rt.filmGrainIntensity",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_INTENSITY);
    }

    private static OptionInstance<Integer> filmGrainSize() {
        return floatRangeSlider("fluorite.options.rt.filmGrainSize",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_SIZE, 5, 40, 0.1f, " px");
    }

    private static OptionInstance<Integer> filmGrainChromatic() {
        return unitSlider("fluorite.options.rt.filmGrainChromatic",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_CHROMATIC);
    }

    private static OptionInstance<Integer> filmGrainShadows() {
        return gradeScale("fluorite.options.rt.filmGrainShadows",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_SHADOWS);
    }

    private static OptionInstance<Integer> filmGrainMidtones() {
        return gradeScale("fluorite.options.rt.filmGrainMidtones",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_MIDTONES);
    }

    private static OptionInstance<Integer> filmGrainHighlights() {
        return gradeScale("fluorite.options.rt.filmGrainHighlights",
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_HIGHLIGHTS);
    }

    private static OptionInstance<Integer> highlightThreshold() {
        return floatRangeSlider("fluorite.options.rt.highlightThreshold",
                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_FILTER_THRESHOLD, 0, 160, 0.1f, "");
    }

    private static OptionInstance<Integer> highlightSoftKnee() {
        return unitSlider("fluorite.options.rt.highlightSoftKnee",
                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_FILTER_SOFT_KNEE);
    }

    private static OptionInstance<Boolean> bloomEnabled() {
        return bool("fluorite.options.rt.bloom", FluoriteConfig.Rt.PostProcessing.BLOOM_ENABLED);
    }

    private static OptionInstance<Integer> bloomIntensity() {
        return gradeScale("fluorite.options.rt.bloomIntensity",
                FluoriteConfig.Rt.PostProcessing.BLOOM_INTENSITY);
    }

    private static OptionInstance<Integer> bloomRadius() {
        return unitSlider("fluorite.options.rt.bloomRadius",
                FluoriteConfig.Rt.PostProcessing.BLOOM_RADIUS);
    }

    private static OptionInstance<Boolean> lensFlareEnabled() {
        return bool("fluorite.options.rt.lensFlare",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_ENABLED);
    }

    private static OptionInstance<Integer> lensFlareIntensity() {
        return gradeScale("fluorite.options.rt.lensFlareIntensity",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_INTENSITY);
    }

    private static OptionInstance<Integer> lensFlareGhosts() {
        return gradeScale("fluorite.options.rt.lensFlareGhosts",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_GHOSTS);
    }

    private static OptionInstance<Integer> lensFlareHalo() {
        return gradeScale("fluorite.options.rt.lensFlareHalo",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_HALO);
    }

    private static OptionInstance<Integer> lensFlareStreaks() {
        return gradeScale("fluorite.options.rt.lensFlareStreaks",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_STREAKS);
    }

    private static OptionInstance<Integer> lensFlareThreshold() {
        return floatRangeSlider("fluorite.options.rt.lensFlareThreshold",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_THRESHOLD, 0, 320, 0.1f, "");
    }

    private static OptionInstance<Integer> lensFlareBokehSize() {
        return floatRangeSlider("fluorite.options.rt.lensFlareBokehSize",
                FluoriteConfig.Rt.PostProcessing.LENS_FLARE_BOKEH_SIZE, 2, 32, 1.0f, " px");
    }

    private static OptionInstance<Boolean> depthOfFieldEnabled() {
        return bool("fluorite.options.rt.depthOfField",
                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_ENABLED);
    }

    private static OptionInstance<String> depthOfFieldFocusMode() {
        return enumString("fluorite.options.rt.depthOfFieldFocusMode",
                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_FOCUS_MODE,
                List.of("auto", "manual"));
    }

    /** Logarithmic travel keeps the approved 0.5-block near focus usable across a 256-block range. */
    private static OptionInstance<Integer> depthOfFieldFocusDistance() {
        FloatSetting setting = FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_FOCUS_DISTANCE;
        final double minimum = 0.5;
        final double maximum = 256.0;
        final int steps = 900;
        int initial = Math.clamp((int) Math.round(
                Math.log(setting.value() / minimum) / Math.log(maximum / minimum) * steps), 0, steps);
        return new OptionInstance<>(
            "fluorite.options.rt.depthOfFieldFocusDistance",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.depthOfFieldFocusDistance.tooltip")),
            (caption, tick) -> {
                double value = minimum * Math.pow(maximum / minimum, tick / (double) steps);
                String text = value < 10.0
                        ? String.format(Locale.ROOT, "%.1f blocks", value)
                        : String.format(Locale.ROOT, "%.0f blocks", value);
                return Options.genericValueLabel(caption, Component.literal(text));
            },
            new OptionInstance.IntRange(0, steps),
            initial,
            tick -> setting.set((float) (minimum * Math.pow(maximum / minimum, tick / (double) steps))));
    }

    private static OptionInstance<Integer> depthOfFieldFStop() {
        return floatRangeSlider("fluorite.options.rt.depthOfFieldFStop",
                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_F_STOP, 7, 320, 0.1f, "");
    }

    private static OptionInstance<Integer> depthOfFieldMaxRadius() {
        return floatRangeSlider("fluorite.options.rt.depthOfFieldMaxRadius",
                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_MAX_RADIUS, 0, 64, 1.0f, " px");
    }

    private static OptionInstance<Integer> depthOfFieldApertureBlades() {
        IntSetting setting = FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_APERTURE_BLADES;
        List<Integer> values = List.of(0, 5, 6, 7, 8, 9);
        return new OptionInstance<>(
            "fluorite.options.rt.depthOfFieldApertureBlades",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.depthOfFieldApertureBlades.tooltip")),
            (caption, value) -> Component.translatable(
                    value == 0 ? "fluorite.options.rt.depthOfFieldApertureBlades.circular"
                            : "fluorite.options.rt.depthOfFieldApertureBlades.blades", value),
            new OptionInstance.Enum<>(values, Codec.INT),
            values.contains(setting.value()) ? setting.value() : 0,
            setting::set);
    }

    private static OptionInstance<Boolean> motionBlurEnabled() {
        return bool("fluorite.options.rt.motionBlur",
                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_ENABLED);
    }

    private static OptionInstance<Integer> motionBlurShutterAngle() {
        return floatRangeSlider("fluorite.options.rt.motionBlurShutterAngle",
                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_SHUTTER_ANGLE, 0, 360, 1.0f, "°");
    }

    private static OptionInstance<Integer> motionBlurSamples() {
        IntSetting setting = FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_SAMPLES;
        return new OptionInstance<>(
            "fluorite.options.rt.motionBlurSamples",
            OptionInstance.cachedConstantTooltip(Component.translatable(
                    "fluorite.options.rt.motionBlurSamples.tooltip")),
            (caption, value) -> Options.genericValueLabel(caption, Component.literal(Integer.toString(value))),
            new OptionInstance.Enum<>(List.of(8, 16), Codec.INT),
            setting.value(),
            setting::set);
    }

    private static OptionInstance<Integer> motionBlurMaxRadius() {
        return floatRangeSlider("fluorite.options.rt.motionBlurMaxRadius",
                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_MAX_RADIUS, 0, 64, 1.0f, " px");
    }

    private static OptionInstance<Boolean> lensDistortionEnabled() {
        return bool("fluorite.options.rt.lensDistortion",
                FluoriteConfig.Rt.PostProcessing.LENS_DISTORTION_ENABLED);
    }

    private static OptionInstance<Integer> lensDistortionStrength() {
        FloatSetting setting = FluoriteConfig.Rt.PostProcessing.LENS_DISTORTION_STRENGTH;
        String key = "fluorite.options.rt.lensDistortionStrength";
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, hundredths) -> {
                String value = hundredths == 0 ? "0.00"
                        : String.format(Locale.ROOT, "%+.2f", hundredths / 100.0f);
                String shape = hundredths < 0 ? ".barrel" : hundredths > 0 ? ".pincushion" : ".neutral";
                return Options.genericValueLabel(caption, Component.translatable(key + shape, value));
            },
            new OptionInstance.IntRange(-100, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), -100, 100),
            hundredths -> setting.set(hundredths / 100.0f));
    }

    private static OptionInstance<Boolean> chromaticAberrationEnabled() {
        return bool("fluorite.options.rt.chromaticAberration",
                FluoriteConfig.Rt.PostProcessing.CHROMATIC_ABERRATION_ENABLED);
    }

    private static OptionInstance<Integer> chromaticAberrationStrength() {
        return floatRangeSlider("fluorite.options.rt.chromaticAberrationStrength",
                FluoriteConfig.Rt.PostProcessing.CHROMATIC_ABERRATION_STRENGTH, 0, 80, 0.1f, " px");
    }

    private static OptionInstance<Boolean> vignetteEnabled() {
        return bool("fluorite.options.rt.vignette", FluoriteConfig.Rt.PostProcessing.VIGNETTE_ENABLED);
    }

    private static OptionInstance<Integer> vignetteIntensity() {
        return unitSlider("fluorite.options.rt.vignetteIntensity",
                FluoriteConfig.Rt.PostProcessing.VIGNETTE_INTENSITY);
    }

    private static OptionInstance<Integer> vignetteStart() {
        return unitSlider("fluorite.options.rt.vignetteStart",
                FluoriteConfig.Rt.PostProcessing.VIGNETTE_START);
    }

    private static OptionInstance<Integer> vignetteSoftness() {
        return floatRangeSlider("fluorite.options.rt.vignetteSoftness",
                FluoriteConfig.Rt.PostProcessing.VIGNETTE_SOFTNESS, 5, 100, 0.01f, "");
    }

    private static OptionInstance<Integer> gradeScale(String captionKey, FloatSetting setting) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, hundredths) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2fx", hundredths / 100.0f))),
            new OptionInstance.IntRange(0, 200),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 200),
            hundredths -> setting.set(hundredths / 100.0f));
    }

    private static OptionInstance<Integer> signedIntegerSlider(String captionKey, FloatSetting setting,
                                                                int min, int max) {
        return new OptionInstance<>(
            captionKey,
            OptionInstance.cachedConstantTooltip(Component.translatable(captionKey + ".tooltip")),
            (caption, value) -> Options.genericValueLabel(caption,
                    Component.literal((value > 0 ? "+" : "") + value)),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.value()), min, max),
            value -> setting.set(value.floatValue()));
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
    private static OptionInstance<Integer> waterScatterStrength() {
        return coefficientSlider("fluorite.options.rt.waterScatterStrength",
                FluoriteConfig.Rt.Water.SCATTER_STRENGTH,
                Math.round(FluoriteConfig.Rt.Water.SCATTER_FULL_SCALE * 1000.0f));
    }

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

    private static OptionInstance<Integer> waterAbsorbStrength() {
        return coefficientSlider("fluorite.options.rt.waterAbsorbStrength",
                FluoriteConfig.Rt.Water.ABSORB_STRENGTH,
                Math.round(FluoriteConfig.Rt.Water.ABSORB_FULL_SCALE * 1000.0f));
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

    /** Caustic contrast belongs beside dispersion; weather attenuates it but does not own it. */
    private static OptionInstance<Integer> waterCausticStrength() {
        return unitSlider("fluorite.options.rt.waterCausticStrength",
                FluoriteConfig.Rt.Water.CAUSTIC_STRENGTH);
    }

    private static OptionInstance<Boolean> waterSim() {
        return bool("fluorite.options.rt.waterSim", FluoriteConfig.Rt.Water.WATER_SIM);
    }

    private static OptionInstance<Integer> waveAmplitude() {
        return scaleSlider("fluorite.options.rt.waveAmplitude",
                FluoriteConfig.Rt.Water.WAVE_AMPLITUDE);
    }

    private static OptionInstance<Boolean> waterDeform() {
        return bool("fluorite.options.rt.waterDeform", FluoriteConfig.Rt.Water.WATER_DEFORM);
    }

    /** Restart-scoped: switching it would rebuild every water section, which is the cost it avoids. */
    private static OptionInstance<String> waterDeformMode() {
        StringSetting setting = FluoriteConfig.Rt.Water.WATER_DEFORM_MODE;
        return new OptionInstance<>(
            "fluorite.options.rt.waterDeformMode",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterDeformMode.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.waterDeformMode." + value),
            new OptionInstance.Enum<>(List.of("all", "near"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Integer> waterDeformRange() {
        return blockSlider("fluorite.options.rt.waterDeformRange",
                FluoriteConfig.Rt.Water.WATER_DEFORM_RANGE, 8, 128);
    }

    /** Blocks per mesh cell, in eighths — the whole useful range sits below one block. */
    private static OptionInstance<Integer> waterDeformCell() {
        FloatSetting setting = FluoriteConfig.Rt.Water.WATER_DEFORM_CELL;
        return new OptionInstance<>(
            "fluorite.options.rt.waterDeformCell",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterDeformCell.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.3f", v / 8.0))),
            new OptionInstance.IntRange(1, 8),
            Math.clamp(Math.round(setting.value() * 8f), 1, 8),
            v -> setting.set(v / 8.0f));
    }

    private static OptionInstance<Integer> waterDeformReanchor() {
        // Down to ZERO, which re-anchors on every block of movement -- the continuous case, kept
        // reachable so the cost of it can be felt rather than argued about.
        return blockSlider("fluorite.options.rt.waterDeformReanchor",
                FluoriteConfig.Rt.Water.WATER_DEFORM_REANCHOR, 0, 32);
    }

    private static OptionInstance<Integer> waterSimRange() {
        return blockSlider("fluorite.options.rt.waterSimRange",
                FluoriteConfig.Rt.Water.WATER_SIM_RANGE, 32, 256);
    }

    private static OptionInstance<Integer> waveLength() {
        return blockSlider("fluorite.options.rt.waveLength",
                FluoriteConfig.Rt.Water.WAVE_LENGTH, 2, 40);
    }

    private static OptionInstance<Integer> waterSimHeight() {
        return blockSlider("fluorite.options.rt.waterSimHeight",
                FluoriteConfig.Rt.Water.WATER_SIM_HEIGHT, 8, 128);
    }

    private static OptionInstance<Integer> waterSimReanchor() {
        return blockSlider("fluorite.options.rt.waterSimReanchor",
                FluoriteConfig.Rt.Water.WATER_SIM_REANCHOR, 4, 64);
    }

    private static OptionInstance<Integer> waterSimStrength() {
        return scaleSlider("fluorite.options.rt.waterSimStrength",
                FluoriteConfig.Rt.Water.WATER_SIM_STRENGTH);
    }

    private static OptionInstance<Integer> waterSimSpeed() {
        return scaleSlider("fluorite.options.rt.waterSimSpeed", FluoriteConfig.Rt.Water.WATER_SIM_SPEED);
    }

    /** Per-step energy retention, in thousandths — the interesting range is all above 0.99. */
    private static OptionInstance<Integer> waterSimDamping() {
        FloatSetting setting = FluoriteConfig.Rt.Water.WATER_SIM_DAMPING;
        return new OptionInstance<>(
            "fluorite.options.rt.waterSimDamping",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterSimDamping.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.3f", 0.9 + v / 1000.0))),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round((setting.value() - 0.9f) * 1000f), 0, 100),
            v -> setting.set(0.9f + v / 1000.0f));
    }

    /** As a multiple of the entity's width, in quarters — this is the ripple's wavelength. */
    private static OptionInstance<Integer> waterSimImpulseSize() {
        FloatSetting setting = FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE_SIZE;
        return new OptionInstance<>(
            "fluorite.options.rt.waterSimImpulseSize",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.waterSimImpulseSize.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2fx", v / 4.0))),
            new OptionInstance.IntRange(1, 24),
            Math.clamp(Math.round(setting.value() * 4f), 1, 24),
            v -> setting.set(v / 4.0f));
    }

    private static OptionInstance<Integer> waterSimImpulseDepth() {
        return blockSlider("fluorite.options.rt.waterSimImpulseDepth",
                FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE_DEPTH, 1, 16);
    }

    private static OptionInstance<Integer> waterSimImpulse() {
        return coefficientSlider("fluorite.options.rt.waterSimImpulse",
                FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE, 250);
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

    /** A plain integer count, labelled with the bare number. */
    private static OptionInstance<Integer> countSlider(String key, IntSetting setting, int min, int max) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(v))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(setting.value(), min, max),
            setting::set);
    }

    /** A physical mean coefficient in inverse blocks, exposed in thousandths for useful water steps. */
    private static OptionInstance<Integer> coefficientSlider(String key, FloatSetting setting, int maxMilli) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.3f / block", v / 1000.0))),
            new OptionInstance.IntRange(0, maxMilli),
            Math.clamp(Math.round(setting.value() * 1000.0f), 0, maxMilli),
            v -> setting.set(v / 1000.0f));
    }

    // ---- Ambient participating medium. Every one of these is re-read per frame straight into the world
    // push, so they belong here rather than on the -Dfluorite.* startup surface. The two that would need
    // resources rebuilt when the froxel pass exists — slice count and march steps — will not.

    private static OptionInstance<Boolean> fogEnabled() {
        return bool("fluorite.options.rt.fog", FluoriteConfig.Rt.Volumetrics.ENABLED);
    }

    private static OptionInstance<Integer> fogTimeGain() {
        return scaleSlider("fluorite.options.rt.fogTimeGain",
                FluoriteConfig.Rt.Volumetrics.FOG_TIME_GAIN);
    }

    private static OptionInstance<Integer> fogWeatherGain() {
        return scaleSlider("fluorite.options.rt.fogWeatherGain",
                FluoriteConfig.Rt.Volumetrics.FOG_WEATHER_GAIN);
    }

    private static OptionInstance<Integer> fogThunderDensityGain() {
        return gainSlider("fluorite.options.rt.fogThunderDensityGain",
                FluoriteConfig.Rt.Weather.FOG_THUNDER_DENSITY_GAIN);
    }

    private static OptionInstance<Integer> fogTimeStructureGain() {
        return gainSlider("fluorite.options.rt.fogTimeStructureGain",
                FluoriteConfig.Rt.Weather.FOG_TIME_STRUCTURE_GAIN);
    }

    private static OptionInstance<Integer> fogRainStructureGain() {
        return gainSlider("fluorite.options.rt.fogRainStructureGain",
                FluoriteConfig.Rt.Weather.FOG_RAIN_STRUCTURE_GAIN);
    }

    private static OptionInstance<Integer> fogThunderStructureGain() {
        return gainSlider("fluorite.options.rt.fogThunderStructureGain",
                FluoriteConfig.Rt.Weather.FOG_THUNDER_STRUCTURE_GAIN);
    }

    private static OptionInstance<Integer> cloudRainCoverageBias() {
        return biasSlider("fluorite.options.rt.cloudRainCoverageBias",
                FluoriteConfig.Rt.Weather.CLOUD_RAIN_COVERAGE_BIAS);
    }

    private static OptionInstance<Integer> cloudRainDensityGain() {
        return gainSlider("fluorite.options.rt.cloudRainDensityGain",
                FluoriteConfig.Rt.Weather.CLOUD_RAIN_DENSITY_GAIN);
    }

    private static OptionInstance<Integer> cloudThunderTypeBias() {
        return biasSlider("fluorite.options.rt.cloudThunderTypeBias",
                FluoriteConfig.Rt.Weather.CLOUD_THUNDER_TYPE_BIAS);
    }

    private static OptionInstance<Integer> waterRainScatterGain() {
        return gainSlider("fluorite.options.rt.waterRainScatterGain",
                FluoriteConfig.Rt.Weather.WATER_RAIN_SCATTER_GAIN);
    }

    private static OptionInstance<Integer> waterThunderScatterGain() {
        return gainSlider("fluorite.options.rt.waterThunderScatterGain",
                FluoriteConfig.Rt.Weather.WATER_THUNDER_SCATTER_GAIN);
    }

    private static OptionInstance<Integer> waterStormSwellBias() {
        return unitSlider("fluorite.options.rt.waterStormSwellBias",
                FluoriteConfig.Rt.Weather.WATER_STORM_SWELL_BIAS);
    }

    private static OptionInstance<Boolean> rainSurfacesEnabled() {
        return bool("fluorite.options.rt.rainSurfacesEnabled",
                FluoriteConfig.Rt.Weather.RAIN_SURFACES_ENABLED);
    }

    private static OptionInstance<String> rainExposureQuality() {
        StringSetting setting = FluoriteConfig.Rt.Weather.RAIN_EXPOSURE_QUALITY;
        return enumString("fluorite.options.rt.rainExposureQuality", setting, List.of("low", "high"));
    }

    private static OptionInstance<Integer> rainSlantDegrees() {
        return floatRangeSlider("fluorite.options.rt.rainSlantDegrees",
                FluoriteConfig.Rt.Weather.RAIN_SLANT_DEGREES, 0, 30, 1f, "°");
    }

    private static OptionInstance<Integer> wetFillSeconds() {
        return floatRangeSlider("fluorite.options.rt.wetFillSeconds",
                FluoriteConfig.Rt.Weather.WET_FILL_SECONDS, 1, 60, 1f, " s");
    }

    private static OptionInstance<Integer> wetDrySeconds() {
        return floatRangeSlider("fluorite.options.rt.wetDrySeconds",
                FluoriteConfig.Rt.Weather.WET_DRY_SECONDS, 10, 600, 1f, " s");
    }

    private static OptionInstance<Integer> puddleFillSeconds() {
        return floatRangeSlider("fluorite.options.rt.puddleFillSeconds",
                FluoriteConfig.Rt.Weather.PUDDLE_FILL_SECONDS, 5, 300, 1f, " s");
    }

    private static OptionInstance<Integer> puddleDrySeconds() {
        return floatRangeSlider("fluorite.options.rt.puddleDrySeconds",
                FluoriteConfig.Rt.Weather.PUDDLE_DRY_SECONDS, 30, 1800, 1f, " s");
    }

    private static OptionInstance<Boolean> daylightDrying() {
        return bool("fluorite.options.rt.daylightDrying", FluoriteConfig.Rt.Weather.DAYLIGHT_DRYING);
    }

    private static OptionInstance<Integer> wetFilmStrength() {
        return unitSlider("fluorite.options.rt.wetFilmStrength",
                FluoriteConfig.Rt.Weather.WET_FILM_STRENGTH);
    }

    private static OptionInstance<Integer> wetFilmRoughness() {
        return floatRangeSlider("fluorite.options.rt.wetFilmRoughness",
                FluoriteConfig.Rt.Weather.WET_FILM_ROUGHNESS, 1, 30, 0.01f, "");
    }

    private static OptionInstance<Boolean> puddlesEnabled() {
        return bool("fluorite.options.rt.puddlesEnabled", FluoriteConfig.Rt.Weather.PUDDLES_ENABLED);
    }

    private static OptionInstance<Integer> puddleCoverage() {
        return unitSlider("fluorite.options.rt.puddleCoverage", FluoriteConfig.Rt.Weather.PUDDLE_COVERAGE);
    }

    private static OptionInstance<Integer> puddleScale() {
        return blockSlider("fluorite.options.rt.puddleScale", FluoriteConfig.Rt.Weather.PUDDLE_SCALE, 2, 32);
    }

    private static OptionInstance<Integer> puddleRippleStrength() {
        return floatRangeSlider("fluorite.options.rt.puddleRippleStrength",
                FluoriteConfig.Rt.Weather.PUDDLE_RIPPLE_STRENGTH, 0, 300, 0.01f, "x");
    }

    private static OptionInstance<Integer> rainRippleSize() {
        return floatRangeSlider("fluorite.options.rt.rainRippleSize",
                FluoriteConfig.Rt.Weather.RAIN_RIPPLE_SIZE, 3, 30, 0.01f, " blocks");
    }

    private static OptionInstance<Integer> wetDarkeningGain() {
        return floatRangeSlider("fluorite.options.rt.wetDarkeningGain",
                FluoriteConfig.Rt.Weather.WET_DARKENING_GAIN, 0, 800, 0.01f, "x");
    }

    private static OptionInstance<Integer> wetCoatGain() {
        return floatRangeSlider("fluorite.options.rt.wetCoatGain",
                FluoriteConfig.Rt.Weather.WET_COAT_GAIN, 0, 200, 0.01f, "x");
    }

    private static OptionInstance<Integer> puddleLayerGain() {
        return floatRangeSlider("fluorite.options.rt.puddleLayerGain",
                FluoriteConfig.Rt.Weather.PUDDLE_LAYER_GAIN, 0, 300, 0.01f, "x");
    }

    private static OptionInstance<Integer> puddleRoughness() {
        return floatRangeSlider("fluorite.options.rt.puddleRoughness",
                FluoriteConfig.Rt.Weather.PUDDLE_ROUGHNESS, 2, 150, 0.001f, "");
    }

    private static OptionInstance<Integer> puddleExtraDarkening() {
        return floatRangeSlider("fluorite.options.rt.puddleExtraDarkening",
                FluoriteConfig.Rt.Weather.PUDDLE_EXTRA_DARKENING, 0, 25, 0.01f, "");
    }

    private static OptionInstance<Integer> puddleNormalFlattening() {
        return floatRangeSlider("fluorite.options.rt.puddleNormalFlattening",
                FluoriteConfig.Rt.Weather.PUDDLE_NORMAL_FLATTENING, 0, 300, 0.01f, "x");
    }

    private static OptionInstance<Integer> wetFilmNormalFlattening() {
        return unitSlider("fluorite.options.rt.wetFilmNormalFlattening",
                FluoriteConfig.Rt.Weather.WET_FILM_NORMAL_FLATTENING);
    }

    private static OptionInstance<Integer> rainRippleWidth() {
        return floatRangeSlider("fluorite.options.rt.rainRippleWidth",
                FluoriteConfig.Rt.Weather.RAIN_RIPPLE_WIDTH, 1, 8, 0.01f, " blocks");
    }

    private static OptionInstance<Integer> defaultWetAbsorption() {
        return unitSlider("fluorite.options.rt.defaultWetAbsorption",
                FluoriteConfig.Rt.Weather.DEFAULT_WET_ABSORPTION);
    }

    private static OptionInstance<Integer> defaultWetDarkening() {
        return unitSlider("fluorite.options.rt.defaultWetDarkening",
                FluoriteConfig.Rt.Weather.DEFAULT_WET_DARKENING);
    }

    private static OptionInstance<Integer> defaultWetFilm() {
        return unitSlider("fluorite.options.rt.defaultWetFilm",
                FluoriteConfig.Rt.Weather.DEFAULT_WET_FILM);
    }

    private static OptionInstance<Integer> defaultPuddleAffinity() {
        return unitSlider("fluorite.options.rt.defaultPuddleAffinity",
                FluoriteConfig.Rt.Weather.DEFAULT_PUDDLE_AFFINITY);
    }

    private static OptionInstance<Boolean> rainParticlesEnabled() {
        return bool("fluorite.options.rt.rainParticlesEnabled",
                FluoriteConfig.Rt.Weather.RAIN_PARTICLES_ENABLED);
    }

    private static OptionInstance<Integer> rainSplashSize() {
        return floatRangeSlider("fluorite.options.rt.rainSplashSize",
                FluoriteConfig.Rt.Weather.RAIN_SPLASH_SIZE, 5, 50, 0.01f, " blocks");
    }

    private static OptionInstance<Integer> rainSplashOpacity() {
        return unitSlider("fluorite.options.rt.rainSplashOpacity",
                FluoriteConfig.Rt.Weather.RAIN_SPLASH_OPACITY);
    }

    private static OptionInstance<Integer> rainSplashBrightness() {
        return floatRangeSlider("fluorite.options.rt.rainSplashBrightness",
                FluoriteConfig.Rt.Weather.RAIN_SPLASH_BRIGHTNESS, 5, 125, 0.01f, "x");
    }

    private static OptionInstance<String> rainStreakQuality() {
        return enumString("fluorite.options.rt.rainStreakQuality",
                FluoriteConfig.Rt.Weather.RAIN_STREAK_QUALITY, List.of("low", "medium", "high"));
    }

    private static OptionInstance<Integer> rainStreakDensity() {
        return scaleSlider("fluorite.options.rt.rainStreakDensity",
                FluoriteConfig.Rt.Weather.RAIN_STREAK_DENSITY, 2f);
    }

    private static OptionInstance<Integer> rainStreakSpeed() {
        return floatRangeSlider("fluorite.options.rt.rainStreakSpeed",
                FluoriteConfig.Rt.Weather.RAIN_STREAK_SPEED, 1, 64, 1f, " blocks/s");
    }

    private static OptionInstance<Integer> rainStreakLength() {
        return floatRangeSlider("fluorite.options.rt.rainStreakLength",
                FluoriteConfig.Rt.Weather.RAIN_STREAK_LENGTH, 1, 20, 0.1f, " blocks");
    }

    private static OptionInstance<Integer> rainSplashTarget() {
        return countSlider("fluorite.options.rt.rainSplashTarget",
                FluoriteConfig.Rt.Weather.RAIN_SPLASH_TARGET, 0, 256);
    }

    private static OptionInstance<Integer> fogDensity() {
        return scaleSlider("fluorite.options.rt.fogDensity", FluoriteConfig.Rt.Volumetrics.DENSITY_SCALE);
    }

    private static OptionInstance<Boolean> netherFogEnabled() {
        return bool("fluorite.options.rt.netherFogEnabled",
                FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED);
    }

    private static OptionInstance<Integer> netherFogDensity() {
        return scaleSlider("fluorite.options.rt.netherFogDensity",
                FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE, 2.0f);
    }

    private static OptionInstance<Integer> netherAmbientBrightness() {
        return intensity("fluorite.options.rt.netherAmbientBrightness",
                FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE);
    }

    private static OptionInstance<Integer> endEnvironmentBrightness() {
        return intensity("fluorite.options.rt.endEnvironmentBrightness",
                FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_SCALE);
    }

    private static OptionInstance<Integer> endDiskBrightness() {
        return intensity("fluorite.options.rt.endDiskBrightness",
                FluoriteConfig.Rt.Dimensions.END_DISK_SCALE);
    }

    private static OptionInstance<Integer> endDiskOuterRadius() {
        FloatSetting setting = FluoriteConfig.Rt.Dimensions.END_DISK_OUTER_RADIUS;
        return new OptionInstance<>(
                "fluorite.options.rt.endDiskOuterRadius",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "fluorite.options.rt.endDiskOuterRadius.tooltip")),
                (caption, value) -> Options.genericValueLabel(caption, Component.literal(value + " M")),
                new OptionInstance.IntRange(4, 12),
                Math.clamp(Math.round(setting.value()), 4, 12),
                value -> setting.set((float) value));
    }

    private static OptionInstance<Integer> endDiskThickness() {
        FloatSetting setting = FluoriteConfig.Rt.Dimensions.END_DISK_THICKNESS;
        return new OptionInstance<>(
                "fluorite.options.rt.endDiskThickness",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "fluorite.options.rt.endDiskThickness.tooltip")),
                (caption, hundredths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.2fx", hundredths / 100.0f))),
                new OptionInstance.IntRange(25, 200),
                Math.clamp(Math.round(setting.value() * 100.0f), 25, 200),
                hundredths -> setting.set(hundredths / 100.0f));
    }

    private static OptionInstance<Integer> endEnvironmentRotationSpeed() {
        FloatSetting setting = FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_ROTATION_SPEED;
        return new OptionInstance<>(
                "fluorite.options.rt.endEnvironmentRotationSpeed",
                OptionInstance.cachedConstantTooltip(Component.translatable(
                        "fluorite.options.rt.endEnvironmentRotationSpeed.tooltip")),
                (caption, hundredths) -> Options.genericValueLabel(caption,
                        Component.literal(String.format(Locale.ROOT, "%.2f deg/s", hundredths / 100.0f))),
                new OptionInstance.IntRange(0, 100),
                Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
                hundredths -> setting.set(hundredths / 100.0f));
    }

    private static OptionInstance<Boolean> fogNoiseEnabled() {
        return bool("fluorite.options.rt.fogNoiseEnabled",
                FluoriteConfig.Rt.Volumetrics.FOG_NOISE_ENABLED);
    }

    private static OptionInstance<Integer> fogNoiseContrast() {
        FloatSetting setting = FluoriteConfig.Rt.Volumetrics.FOG_NOISE_CONTRAST;
        return new OptionInstance<>(
            "fluorite.options.rt.fogNoiseContrast",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.fogNoiseContrast.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(0, 400),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 400),
            v -> setting.set(v / 100.0f));
    }

    private static OptionInstance<Integer> fogNoiseFieldScale() {
        return blockSlider("fluorite.options.rt.fogNoiseFieldScale",
                FluoriteConfig.Rt.Volumetrics.FOG_NOISE_FIELD_SCALE, 64, 2048);
    }

    private static OptionInstance<Integer> fogNoiseWindSpeed() {
        String key = "fluorite.options.rt.fogNoiseWindSpeed";
        FloatSetting setting = FluoriteConfig.Rt.Volumetrics.FOG_NOISE_WIND_SPEED;
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(0, 800),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 800),
            v -> setting.set(v / 100.0f));
    }

    private static OptionInstance<Integer> fogNoiseWindOffset() {
        return windOffset("fluorite.options.rt.fogNoiseWindOffset",
                FluoriteConfig.Rt.Volumetrics.FOG_NOISE_WIND_OFFSET);
    }

    private static OptionInstance<Integer> fogNoiseMarchSteps() {
        IntSetting setting = FluoriteConfig.Rt.Volumetrics.FOG_NOISE_MARCH_STEPS;
        return new OptionInstance<>(
            "fluorite.options.rt.fogNoiseMarchSteps",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.fogNoiseMarchSteps.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(v))),
            new OptionInstance.IntRange(1, 31),
            Math.clamp(setting.value(), 1, 31),
            setting::set);
    }

    private static OptionInstance<Integer> fogAlbedoScale() {
        FloatSetting setting = FluoriteConfig.Rt.Volumetrics.ALBEDO_SCALE;
        return new OptionInstance<>(
            "fluorite.options.rt.fogAlbedo",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.fogAlbedo.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            v -> setting.set(v / 100.0f));
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
     * <p>Acts on EVERY participating medium — it was built because deep water went black as readily as
     * dense fog did, and both symptoms are the same bug.
     *
     * <p>In the UI for the same reason the ray count is: off must reproduce the old behaviour exactly, so
     * this is an A/B rather than a dial, and an A/B is only worth anything flipped at a fixed camera
     * position inside one session.
     */
    private static OptionInstance<Boolean> volumeMultiScatter() {
        return bool("fluorite.options.rt.volumeMultiScatter", FluoriteConfig.Rt.Volumetrics.MULTI_SCATTER);
    }

    /**
     * Sample one scattering event per segment instead of assuming a constant source along it.
     *
     * <p>In the UI for the same reason as the two above: it trades noise for the ability to ask questions
     * at a position, and both halves of that trade have to be judged by flipping it at a fixed camera
     * position inside one session.
     */
    /**
     * Volumetric clouds. The complete M11 path remains in the UI for a same-session, fixed-camera A/B;
     * R19's full-path cost is still unmeasured.
     */
    private static OptionInstance<Boolean> clouds() {
        return bool("fluorite.options.rt.clouds", FluoriteConfig.Rt.Volumetrics.CLOUDS);
    }

    /**
     * Let vanilla's rain and thunder move the sky.
     *
     * <p>In the UI beside the sliders it competes with, because with it on the sliders measure themselves
     * plus the weather — so authoring a sky means turning this off first, and that has to be one click
     * away rather than a config file away.
     */
    private static OptionInstance<Boolean> cloudWeather() {
        return bool("fluorite.options.rt.cloudWeather", FluoriteConfig.Rt.Volumetrics.CLOUD_WEATHER);
    }

    private static OptionInstance<Integer> cloudCoverage() {
        return biasSlider("fluorite.options.rt.cloudCoverage", FluoriteConfig.Rt.Volumetrics.CLOUD_COVERAGE);
    }

    private static OptionInstance<Integer> cloudDensity() {
        return scaleSlider("fluorite.options.rt.cloudDensity", FluoriteConfig.Rt.Volumetrics.CLOUD_DENSITY);
    }

    /** Stratus at the low end, cumulus in the middle, cumulonimbus at the top. */
    private static OptionInstance<Integer> cloudType() {
        return biasSlider("fluorite.options.rt.cloudType", FluoriteConfig.Rt.Volumetrics.CLOUD_TYPE);
    }

    private static OptionInstance<Integer> cloudExtinction() {
        return coefficientSlider("fluorite.options.rt.cloudExtinction",
                FluoriteConfig.Rt.Volumetrics.CLOUD_EXTINCTION, 500);
    }

    private static OptionInstance<Integer> cloudAltitude() {
        return blockSlider("fluorite.options.rt.cloudAltitude",
                FluoriteConfig.Rt.Volumetrics.CLOUD_ALTITUDE, 64, 1024);
    }

    private static OptionInstance<Integer> cloudThickness() {
        return blockSlider("fluorite.options.rt.cloudThickness",
                FluoriteConfig.Rt.Volumetrics.CLOUD_THICKNESS, 32, 1024);
    }

    private static OptionInstance<Integer> cloudBaseScale() {
        return blockSlider("fluorite.options.rt.cloudBaseScale",
                FluoriteConfig.Rt.Volumetrics.CLOUD_BASE_SCALE, 200, 8000);
    }

    /** How fast the field drifts. 0 freezes the sky, which is the A/B for whether motion is the fix. */
    private static OptionInstance<Integer> cloudWindSpeed() {
        return scaleSlider("fluorite.options.rt.cloudWindSpeed",
                FluoriteConfig.Rt.Volumetrics.CLOUD_WIND_SPEED);
    }

    /** The world's one weather heading. Layer speeds and signed offsets remain in their own categories. */
    private static OptionInstance<Integer> windAngle() {
        FloatSetting setting = FluoriteConfig.Rt.Composite.WIND_ANGLE;
        return new OptionInstance<>(
            "fluorite.options.rt.windAngle",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.windAngle.tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(v + "°")),
            new OptionInstance.IntRange(0, 359),
            Math.clamp(Math.round(setting.value()), 0, 359),
            v -> setting.set((float) v));
    }

    /** Degrees off the global wind, signed. */
    private static OptionInstance<Integer> windOffset(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal((v > 0 ? "+" : "") + v + "°")),
            new OptionInstance.IntRange(-180, 180),
            Math.clamp(Math.round(setting.value()), -180, 180),
            v -> setting.set((float) v));
    }

    private static OptionInstance<Integer> cloudWindOffset() {
        return windOffset("fluorite.options.rt.cloudWindOffset",
                FluoriteConfig.Rt.Volumetrics.CLOUD_WIND_OFFSET);
    }

    private static OptionInstance<Integer> waveWeather() {
        return scaleSlider("fluorite.options.rt.waveWeather", FluoriteConfig.Rt.Water.WAVE_WEATHER);
    }

    /** A plain integer index over a float-backed setting; the wave components are numbered, not sized. */
    private static OptionInstance<Integer> indexSlider(String key, FloatSetting setting, int min, int max) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.valueOf(v))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.value()), min, max),
            v -> setting.set((float) v));
    }

    private static OptionInstance<Integer> waveFirst() {
        return indexSlider("fluorite.options.rt.waveFirst", FluoriteConfig.Rt.Water.WAVE_FIRST, 1, 10);
    }

    private static OptionInstance<Integer> waveLast() {
        return indexSlider("fluorite.options.rt.waveLast", FluoriteConfig.Rt.Water.WAVE_LAST, 1, 10);
    }

    private static OptionInstance<Boolean> waveBandLimit() {
        return bool("fluorite.options.rt.waveBandLimit", FluoriteConfig.Rt.Water.WAVE_BAND_LIMIT);
    }

    private static OptionInstance<Integer> waveWarp() {
        return blockSlider("fluorite.options.rt.waveWarp", FluoriteConfig.Rt.Water.WAVE_WARP, 0, 40);
    }

    private static OptionInstance<Integer> waveWarpScale() {
        return blockSlider("fluorite.options.rt.waveWarpScale",
                FluoriteConfig.Rt.Water.WAVE_WARP_SCALE, 20, 400);
    }

    private static OptionInstance<Integer> waveSpeed() {
        return scaleSlider("fluorite.options.rt.waveSpeed", FluoriteConfig.Rt.Water.WAVE_SPEED);
    }

    private static OptionInstance<Integer> waveGust() {
        return scaleSlider("fluorite.options.rt.waveGust", FluoriteConfig.Rt.Water.WAVE_GUST);
    }

    private static OptionInstance<Integer> waveGustScale() {
        return blockSlider("fluorite.options.rt.waveGustScale",
                FluoriteConfig.Rt.Water.WAVE_GUST_SCALE, 8, 200);
    }

    private static OptionInstance<Integer> waveGustSpeed() {
        return blockSlider("fluorite.options.rt.waveGustSpeed",
                FluoriteConfig.Rt.Water.WAVE_GUST_SPEED, 0, 30);
    }

    private static OptionInstance<Integer> waveComplexity() {
        return scaleSlider("fluorite.options.rt.waveComplexity",
                FluoriteConfig.Rt.Water.WAVE_COMPLEXITY);
    }

    private static OptionInstance<Integer> waveCrossAngle() {
        return windOffset("fluorite.options.rt.waveCrossAngle",
                FluoriteConfig.Rt.Water.WAVE_CROSS_ANGLE);
    }

    private static OptionInstance<Integer> waveWindOffset() {
        return windOffset("fluorite.options.rt.waveWindOffset",
                FluoriteConfig.Rt.Water.WAVE_WIND_OFFSET);
    }

    /** The 2D field: how far apart the sky's clumps and clearings are, as opposed to how big one cloud is. */
    private static OptionInstance<Integer> cloudFieldScale() {
        return blockSlider("fluorite.options.rt.cloudFieldScale",
                FluoriteConfig.Rt.Volumetrics.CLOUD_FIELD_SCALE, 500, 40000);
    }

    private static OptionInstance<Integer> cloudDetailScale() {
        return blockSlider("fluorite.options.rt.cloudDetailScale",
                FluoriteConfig.Rt.Volumetrics.CLOUD_DETAIL_SCALE, 40, 2000);
    }

    private static OptionInstance<Boolean> volumeScatterVertex() {
        return bool("fluorite.options.rt.volumeScatterVertex",
                FluoriteConfig.Rt.Volumetrics.SCATTER_VERTEX);
    }

    /** Forward asymmetry of the cloud's phase function — how hard a backlit rim glows. */
    private static OptionInstance<Integer> cloudPhaseG() {
        return biasSlider("fluorite.options.rt.cloudPhaseG", FluoriteConfig.Rt.Volumetrics.CLOUD_PHASE_G);
    }

    /**
     * Single-scattering albedo, in thousandths, over the top half of its range only.
     *
     * <p>Its own slider rather than a shared helper because everything interesting happens between 0.99
     * and 1: the diffusion rate goes as the square root of one minus this, so the last percent of the
     * range is worth more than the first half, and a linear 0..1 slider would spend nearly all its travel
     * on values that make clouds look like soot. So the travel is logarithmic in {@code 1 - albedo} —
     * every 250 units is another factor of ten closer to a pure scatterer.
     *
     * <p>The range starts at 75 rather than 0 because that is where the mapping reaches the setting's own
     * lower clamp of 0.5. A slider whose travel runs past the clamp behind it stops responding partway
     * along, which reads as a bug rather than as a limit.
     */
    private static OptionInstance<Integer> cloudAlbedo() {
        String key = "fluorite.options.rt.cloudAlbedo";
        FloatSetting setting = FluoriteConfig.Rt.Volumetrics.CLOUD_ALBEDO;
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.4f", albedoFromSlider(v)))),
            new OptionInstance.IntRange(75, 1000),
            Math.clamp((int) Math.round(-250.0 * Math.log10(Math.max(1.0e-4, 1.0 - setting.value()))),
                    75, 1000),
            v -> setting.set((float) albedoFromSlider(v)));
    }

    private static double albedoFromSlider(int v) {
        return 1.0 - Math.pow(10.0, -v / 250.0);
    }

    /** Steps in the cloud self-shadow march. The milestone's cost dial; 0 flattens every cloud. */
    private static OptionInstance<Integer> cloudSunSteps() {
        return countSlider("fluorite.options.rt.cloudSunSteps",
                FluoriteConfig.Rt.Volumetrics.CLOUD_SUN_STEPS, 0, 8);
    }

    private static OptionInstance<Boolean> cloudMultiScatter() {
        return bool("fluorite.options.rt.cloudMultiScatter",
                FluoriteConfig.Rt.Volumetrics.CLOUD_MULTI_SCATTER);
    }

    private static OptionInstance<Boolean> cloudCirrus() {
        return bool("fluorite.options.rt.cloudCirrus", FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS);
    }

    private static OptionInstance<Integer> cloudCirrusAltitude() {
        return blockSlider("fluorite.options.rt.cloudCirrusAltitude",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_ALTITUDE, 128, 2048);
    }

    private static OptionInstance<Integer> cloudCirrusThickness() {
        return blockSlider("fluorite.options.rt.cloudCirrusThickness",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_THICKNESS, 8, 400);
    }

    private static OptionInstance<Integer> cloudCirrusCoverage() {
        return biasSlider("fluorite.options.rt.cloudCirrusCoverage",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_COVERAGE);
    }

    private static OptionInstance<Integer> cloudCirrusPatchDiameter() {
        return blockSlider("fluorite.options.rt.cloudCirrusPatchDiameter",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_DIAMETER, 500, 40000);
    }

    private static OptionInstance<Integer> cloudCirrusPatchSpacing() {
        return blockSlider("fluorite.options.rt.cloudCirrusPatchSpacing",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_SPACING, 1000, 80000);
    }

    private static OptionInstance<Integer> cloudCirrusDensity() {
        return scaleSlider("fluorite.options.rt.cloudCirrusDensity",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_DENSITY);
    }

    private static OptionInstance<Integer> cloudCirrusPatchStrength() {
        return scaleSlider("fluorite.options.rt.cloudCirrusPatchStrength",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_STRENGTH);
    }

    private static OptionInstance<Integer> cloudCirrusWindSpeed() {
        return scaleSlider("fluorite.options.rt.cloudCirrusWindSpeed",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_WIND_SPEED);
    }

    private static OptionInstance<Integer> cloudCirrusWindOffset() {
        return windOffset("fluorite.options.rt.cloudCirrusWindOffset",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_WIND_OFFSET);
    }

    private static OptionInstance<Integer> cloudCirrusExtinction() {
        return coefficientSlider("fluorite.options.rt.cloudCirrusExtinction",
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_EXTINCTION, 100);
    }

    /**
     * What clouds a ray that is not the first of its path gets.
     *
     * <p>In the UI because it is the cost lever for the whole milestone in reflections, and a cost has to
     * be measured by flipping it at a fixed camera position inside one session.
     */
    private static OptionInstance<String> cloudSecondary() {
        StringSetting setting = FluoriteConfig.Rt.Volumetrics.CLOUD_SECONDARY;
        return new OptionInstance<>(
            "fluorite.options.rt.cloudSecondary",
            OptionInstance.cachedConstantTooltip(
                    Component.translatable("fluorite.options.rt.cloudSecondary.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.cloudSecondary." + value),
            new OptionInstance.Enum<>(List.of("off", "reduced", "full"), Codec.STRING),
            setting.get(),
            setting::set);
    }

    private static OptionInstance<Boolean> fogScatterVertex() {
        return bool("fluorite.options.rt.fogScatterVertex", FluoriteConfig.Rt.Volumetrics.SCATTER_VERTEX);
    }

    /**
     * Let block emitters light the medium at the sampled scattering event.
     *
     * <p>Beside the vertex switch it depends on, so the pair reads as the one feature it is.
     */
    private static OptionInstance<Boolean> volumeEmitterNee() {
        return bool("fluorite.options.rt.volumeEmitterNee",
                FluoriteConfig.Rt.Volumetrics.VOLUME_EMITTER_NEE);
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
        return scaleSlider(key, setting, 10.0f);
    }

    /** A non-negative multiplier with an explicit product limit, stepped in tenths. */
    private static OptionInstance<Integer> scaleSlider(String key, FloatSetting setting, float maximum) {
        int maxTenths = Math.max(0, Math.round(maximum * 10.0f));
        int initial = Math.clamp(Math.round(setting.value() * 10.0f), 0, maxTenths);
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.format("%.1f", v / 10.0))),
            new OptionInstance.IntRange(0, maxTenths),
            initial,
            v -> setting.set(v / 10.0f));
    }

    /** A signed weather gain in [-1, 4]. -1 can cancel a positive coefficient at full forcing. */
    private static OptionInstance<Integer> gainSlider(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%+.2f", v / 100.0))),
            new OptionInstance.IntRange(-100, 400),
            Math.clamp(Math.round(setting.value() * 100.0f), -100, 400),
            v -> setting.set(v / 100.0f));
    }

    /**
     * A slider over a SIGNED bias in [-1, 1], shown in hundredths.
     *
     * <p>Signed because the settings it drives are added to a field rather than multiplied into one, and
     * a bias whose neutral value sits at the middle of its travel is the only kind that can be turned
     * both up and down from what the world authored.
     */
    private static OptionInstance<Integer> biasSlider(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%+.2f", v / 100.0))),
            new OptionInstance.IntRange(-100, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), -100, 100),
            v -> setting.set(v / 100.0f));
    }

    /** A unit interval shown in hundredths, for bounded strengths rather than unbounded scale gains. */
    private static OptionInstance<Integer> unitSlider(String key, FloatSetting setting) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, "%.2f", v / 100.0))),
            new OptionInstance.IntRange(0, 100),
            Math.clamp(Math.round(setting.value() * 100.0f), 0, 100),
            v -> setting.set(v / 100.0f));
    }

    /** Reusable string-backed cycle whose labels live beside the setting's ordinary translation key. */
    private static OptionInstance<String> enumString(String key, StringSetting setting, List<String> values) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, value) -> Component.translatable(key + "." + value),
            new OptionInstance.Enum<>(values, Codec.STRING),
            setting.get(),
            setting::set);
    }

    /** Integer-backed slider mapped to an arbitrary positive float step. */
    private static OptionInstance<Integer> floatRangeSlider(String key, FloatSetting setting,
                                                             int min, int max, float step, String suffix) {
        return new OptionInstance<>(
            key,
            OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
            (caption, v) -> Options.genericValueLabel(caption,
                    Component.literal(String.format(Locale.ROOT, step < 1f ? "%.2f%s" : "%.0f%s",
                            v * step, suffix))),
            new OptionInstance.IntRange(min, max),
            Math.clamp(Math.round(setting.value() / step), min, max),
            v -> setting.set(v * step));
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

    private static OptionInstance<String> hdrEnabled() {
        BooleanSetting setting = FluoriteConfig.Rt.Hdr.ENABLED;
        return new OptionInstance<>(
            "fluorite.options.rt.hdr",
            OptionInstance.cachedConstantTooltip(Component.translatable("fluorite.options.rt.hdr.tooltip")),
            (caption, value) -> Component.translatable("fluorite.options.rt.hdr." + value),
            new OptionInstance.Enum<>(List.of("sdr", "hdr10"), Codec.STRING),
            setting.value() ? "hdr10" : "sdr",
            value -> setting.set("hdr10".equals(value)));
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
                    List.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23,
                            24, 25, 26, 27),
                    Codec.INT),
            Math.clamp(setting.value(), 0, 27),
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
