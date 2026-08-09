package io.github.dswepm.fluorite.rt.sky;

import io.github.dswepm.fluorite.FluoriteConfig;
import net.minecraft.resources.Identifier;

/** Final player-authored adjustment layer for one active dimension. */
public record RtDimensionControls(boolean fogEnabled, float fogDensityScale,
                                  float fogDensityScaleLimit, float ambientScale) {
    public static final RtDimensionControls DEFAULT =
            new RtDimensionControls(true, 1f, Float.POSITIVE_INFINITY, 1f);
    private static final Identifier NETHER = Identifier.withDefaultNamespace("the_nether");

    /**
     * Resolve only explicit per-dimension controls. Unknown and modded dimensions retain their preset;
     * they must never inherit the Nether slider merely because they happen to use a similar provider.
     */
    public static RtDimensionControls forDimension(Identifier dimension) {
        if (NETHER.equals(dimension)) {
            return new RtDimensionControls(
                    FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED.value(),
                    FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.value(),
                    2f,
                    FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE.value());
        }
        return DEFAULT;
    }

    /**
     * Apply the final per-dimension product limit after the global and local player controls combine.
     * The default infinity preserves the exact identity behaviour for every dimension without a limit.
     */
    public float resolveFogDensityScale(float globalScale) {
        return Math.min(Math.max(0f, globalScale) * fogDensityScale, fogDensityScaleLimit);
    }
}
