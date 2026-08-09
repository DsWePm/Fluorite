package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.attribute.EnvironmentAttributes;

/**
 * One immutable read of the world's environmental forcing for a rendered frame.
 *
 * <p>Fog, clouds and water used to ask Minecraft for partial time, rain and thunder independently.
 * Besides repeating work, that made their response rules shallow fragments inside {@link RtComposite}.
 * This module owns the continuous clear -> rain -> thunder projection and hands each implementation its
 * final scalar or signed bias. Raw weather never enters WorldPush and therefore adds no shader branch or
 * ABI lane.
 */
final class RtEnvironmentForcing {
    private RtEnvironmentForcing() {
    }

    static Frame capture(ClientLevel level) {
        if (level == null) {
            return new Frame(0f, 0f, 0f, 0.0);
        }
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        float rain = Math.clamp(level.getRainLevel(partial), 0f, 1f);
        float thunder = Math.clamp(level.getThunderLevel(partial), 0f, 1f);
        float sunAngle = mc.gameRenderer.mainCamera().attributeProbe()
                .getValue(EnvironmentAttributes.SUN_ANGLE, partial);
        return new Frame(rain, thunder, radiationFog(sunAngle), (level.getGameTime() + partial) / 20.0);
    }

    /** Radiation-fog cycle: dawn peak, morning burn-off, clear afternoon, overnight build-up. */
    static float radiationFog(float sunAngle) {
        float hours = ((sunAngle - 270f) % 360f + 360f) % 360f / 360f * 24f;
        float burnOff = smoothstep(0f, 5f, hours);
        float buildUp = smoothstep(11f, 24f, hours);
        return Math.clamp((1f - burnOff) + buildUp, 0f, 1f);
    }

    /** Positive coefficient response. Signed gains may reduce it, but never below zero. */
    static float positiveScale(float rain, float thunder, float rainGain, float thunderGain) {
        return Math.max(0f, 1f + rain * rainGain + thunder * thunderGain);
    }

    /** Signed fields such as cloud coverage and type are offsets, not coefficient multipliers. */
    static float signedBias(float rain, float thunder, float rainBias, float thunderBias) {
        return rain * rainBias + thunder * thunderBias;
    }

    /**
     * Advance at a fixed rate so a unit weather change takes {@code transitionSeconds} seconds.
     * This is deliberately linear rather than an exponential filter: the setting says twenty seconds,
     * and after twenty seconds a clear-to-rain transition must actually be complete.
     */
    static float moveTowards(float current, float target, float elapsedSeconds, float transitionSeconds) {
        if (!Float.isFinite(current) || !Float.isFinite(target)) {
            return target;
        }
        if (elapsedSeconds <= 0f) {
            return current;
        }
        if (transitionSeconds <= 0f) {
            return target;
        }
        float step = elapsedSeconds / transitionSeconds;
        if (target > current) {
            return Math.min(current + step, target);
        }
        return Math.max(current - step, target);
    }

    /**
     * Fade only the deviation of a caustic from neutral irradiance.
     *
     * <p>The result is a contrast, not a brightness multiplier: the shader applies
     * {@code 1 + contrast * (focus - 1)}, so both bright folds and their compensating dark regions
     * collapse toward one together. The current cloud term is a global weather approximation; D73's
     * future 2D sun-transmittance map can replace that input without changing the caustic model.
     */
    static float causticContrast(float authoredStrength, float finalFogScale,
                                 float finalCloudDensity, float positiveCoverageBias) {
        float fogLoad = Math.max(finalFogScale - 1f, 0f);
        float cloudLoad = Math.max(finalCloudDensity * (1f + Math.max(positiveCoverageBias, 0f)) - 1f, 0f);
        float strength = Math.clamp(authoredStrength, 0f, 1f);
        return strength / (1f + fogLoad + cloudLoad);
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    record Frame(float rain, float thunder, float radiationFog, double gameSeconds) {
        float fogDensityScale() {
            float time = 1f + radiationFog * FluoriteConfig.Rt.Volumetrics.FOG_TIME_GAIN.value();
            float weather = positiveScale(rain, thunder,
                    FluoriteConfig.Rt.Volumetrics.FOG_WEATHER_GAIN.value(),
                    FluoriteConfig.Rt.Weather.FOG_THUNDER_DENSITY_GAIN.value());
            return Math.max(0f, time * weather);
        }

        /** Zero is the canonical off value consumed before every texture fetch. */
        float fogStructureContrast() {
            if (!FluoriteConfig.Rt.Volumetrics.FOG_NOISE_ENABLED.value()) {
                return 0f;
            }
            float response = 1f
                    + radiationFog * FluoriteConfig.Rt.Weather.FOG_TIME_STRUCTURE_GAIN.value()
                    + rain * FluoriteConfig.Rt.Weather.FOG_RAIN_STRUCTURE_GAIN.value()
                    + thunder * FluoriteConfig.Rt.Weather.FOG_THUNDER_STRUCTURE_GAIN.value();
            return Math.clamp(FluoriteConfig.Rt.Volumetrics.FOG_NOISE_CONTRAST.value()
                    * Math.max(response, 0f), 0f, 4f);
        }

        float cloudCoverageBias() {
            if (!FluoriteConfig.Rt.Volumetrics.CLOUD_WEATHER.value()) {
                return 0f;
            }
            return signedBias(rain, thunder,
                    FluoriteConfig.Rt.Weather.CLOUD_RAIN_COVERAGE_BIAS.value(), 0f);
        }

        float cloudDensityScale() {
            if (!FluoriteConfig.Rt.Volumetrics.CLOUD_WEATHER.value()) {
                return 1f;
            }
            return positiveScale(rain, thunder,
                    FluoriteConfig.Rt.Weather.CLOUD_RAIN_DENSITY_GAIN.value(), 0f);
        }

        float cloudTypeBias() {
            if (!FluoriteConfig.Rt.Volumetrics.CLOUD_WEATHER.value()) {
                return 0f;
            }
            return signedBias(rain, thunder, 0f,
                    FluoriteConfig.Rt.Weather.CLOUD_THUNDER_TYPE_BIAS.value());
        }

        float waterStorm() {
            return (rain + thunder * 0.6f) * FluoriteConfig.Rt.Water.WAVE_WEATHER.value();
        }

        float waterScatterScale() {
            return positiveScale(rain, thunder,
                    FluoriteConfig.Rt.Weather.WATER_RAIN_SCATTER_GAIN.value(),
                    FluoriteConfig.Rt.Weather.WATER_THUNDER_SCATTER_GAIN.value());
        }

        float waterCausticContrast() {
            boolean fogOn = FluoriteConfig.Rt.Volumetrics.ENABLED.value()
                    && FluoriteConfig.Rt.Volumetrics.HEIGHT_FOG.value();
            float finalFogScale = fogOn
                    ? FluoriteConfig.Rt.Volumetrics.DENSITY_SCALE.value() * fogDensityScale()
                    : 1f;

            boolean cloudsOn = FluoriteConfig.Rt.Volumetrics.CLOUDS.value();
            float finalCloudDensity = cloudsOn
                    ? FluoriteConfig.Rt.Volumetrics.CLOUD_DENSITY.value() * cloudDensityScale()
                    : 1f;
            float positiveCoverageBias = cloudsOn
                    ? Math.max(FluoriteConfig.Rt.Volumetrics.CLOUD_COVERAGE.value()
                            + cloudCoverageBias(), 0f)
                    : 0f;
            return causticContrast(FluoriteConfig.Rt.Water.CAUSTIC_STRENGTH.value(),
                    finalFogScale, finalCloudDensity, positiveCoverageBias);
        }
    }
}
