package io.github.dswepm.fluorite.rt.light;

import io.github.dswepm.fluorite.FluoriteConfig;

/**
 * One multiplier for every finite emitter's radiance: brightness, times a colour-temperature tint.
 *
 * <p><b>The tint's luminance is exactly one.</b> That is what keeps the two controls orthogonal — moving
 * the temperature changes what colour the world's lights are and not how much light there is, so the
 * brightness slider stays the only thing that answers "is this room too dark". A tint that carried its own
 * luminance would make every temperature change look like a brightness change too, and the pair would
 * become impossible to calibrate against each other.
 *
 * <p>Temperature is a real blackbody temperature, not a warm/cool feeling: the Planckian locus gives a
 * chromaticity, which becomes linear sRGB and is then divided by its own luminance. A torch sits near
 * 1800 K and a lantern near 2000 K, so those numbers mean something. What it is NOT is a claim that every
 * emitter is incandescent — glowstone and a sea lantern are not, and forcing a blackbody chromaticity onto
 * them is a deliberate art choice, which is why the control ships off.
 *
 * <p>Off is (1,1,1): brightness 1 multiplies by exactly one in IEEE, and temperature 0 skips the locus
 * entirely, so the shipped picture is unchanged bit for bit rather than approximately.
 */
public final class RtEmitterTint {
    /** Below the Planckian approximation's validity, and below any flame worth naming. */
    public static final int MIN_TEMPERATURE_K = 1000;
    public static final int MAX_TEMPERATURE_K = 12000;

    private RtEmitterTint() {
    }

    /** This frame's multiplier, from the two knobs. Index 0..2 are RGB; nothing writes index 3. */
    public static float[] current() {
        float brightness = FluoriteConfig.Rt.Composite.EMITTER_BRIGHTNESS.value();
        int kelvin = FluoriteConfig.Rt.Composite.EMITTER_TEMPERATURE_K.value();
        return of(brightness, kelvin);
    }

    /** Exposed for tests: the same computation without reading configuration. */
    public static float[] of(float brightness, int kelvin) {
        if (!Float.isFinite(brightness) || brightness < 0.0f) {
            brightness = 1.0f;
        }
        if (kelvin <= 0) {
            return new float[] {brightness, brightness, brightness};
        }
        int clamped = Math.clamp(kelvin, MIN_TEMPERATURE_K, MAX_TEMPERATURE_K);
        double[] rgb = blackbodyUnitLuminance(clamped);
        return new float[] {
                (float) (rgb[0] * brightness),
                (float) (rgb[1] * brightness),
                (float) (rgb[2] * brightness)};
    }

    /**
     * Linear sRGB of a blackbody at {@code kelvin}, scaled so its luminance is exactly 1.
     *
     * <p>Kim et al.'s cubic fit to the Planckian locus in CIE 1931 xy, then xy to XYZ at unit Y, then the
     * standard sRGB primaries. Chromaticities below about 2000 K fall outside the sRGB gamut, so a negative
     * channel is clamped to zero — the colour then sits on the gamut boundary rather than being a
     * different colour, and the renormalisation afterwards means the clamp costs saturation and not
     * brightness.
     */
    static double[] blackbodyUnitLuminance(int kelvin) {
        double t = kelvin;
        double x;
        if (t <= 4000.0) {
            x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
        } else {
            x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
        }
        double y;
        if (t <= 2222.0) {
            y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
        } else if (t <= 4000.0) {
            y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
        } else {
            y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
        }
        if (!(y > 1.0e-6)) {
            return new double[] {1.0, 1.0, 1.0};
        }
        double bigX = x / y;
        double bigY = 1.0;
        double bigZ = (1.0 - x - y) / y;
        double r = 3.2404542 * bigX - 1.5371385 * bigY - 0.4985314 * bigZ;
        double g = -0.9692660 * bigX + 1.8760108 * bigY + 0.0415560 * bigZ;
        double b = 0.0556434 * bigX - 0.2040259 * bigY + 1.0572252 * bigZ;
        r = Math.max(0.0, r);
        g = Math.max(0.0, g);
        b = Math.max(0.0, b);
        // Rec.709 luminance, the same weights the shader's luminance() uses to build the RIS target — so
        // "unit luminance" here means the same thing it means to the light that gets proposed.
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (!(luminance > 1.0e-6)) {
            return new double[] {1.0, 1.0, 1.0};
        }
        return new double[] {r / luminance, g / luminance, b / luminance};
    }
}
