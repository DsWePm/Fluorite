package io.github.dswepm.fluorite.rt.sky;

import net.minecraft.resources.Identifier;

/**
 * Resource-authored base state for one dimension's sky and ambient participating medium.
 *
 * <p>The preset owns the dimension's physical baseline. Weather/time forcing is applied only when
 * {@link #weatherEnabled()} allows it, and player settings are global adjustments on the resolved
 * result. Keeping those three layers separate lets resource packs author genuinely different
 * dimensions without taking the player's accessibility controls away.
 */
public record RtSkyPreset(
        SkyProvider skyProvider,
        Rgb ambientRadiance,
        boolean weatherEnabled,
        boolean cloudsEnabled,
        Fog fog,
        Environment environment) {

    /** Safe fallback for every unknown or invalid dimension: the existing complete atmosphere. */
    public static final RtSkyPreset FULL_ATMOSPHERE = new RtSkyPreset(
            SkyProvider.ATMOSPHERE,
            Rgb.BLACK,
            true,
            true,
            new Fog(FogProfile.HEIGHT, 0.0016f,
                    new Rgb(0.92f, 0.96f, 1.0f),
                    new Rgb(0.92f, 0.94f, 0.96f),
                    0.55f, 16f, 512f, 48f, 62f,
                    true, AmbientVisibility.SKY, false),
            null);

    /** Stable shader ABI ids. Add new providers; never reinterpret an existing id. */
    public enum SkyProvider {
        ATMOSPHERE(0),
        LOCAL_AMBIENT(1),
        ENVIRONMENT(2);

        private final int shaderId;

        SkyProvider(int shaderId) {
            this.shaderId = shaderId;
        }

        public int shaderId() {
            return shaderId;
        }
    }

    public enum FogProfile {
        OFF,
        HEIGHT,
        HOMOGENEOUS
    }

    /**
     * Whether the ambient term samples visibility, or is taken as arriving from everywhere.
     *
     * <p>{@code UNOCCLUDED} is D78A's approved non-physical readability floor and is legal ONLY with
     * {@link SkyProvider#LOCAL_AMBIENT}, which is the one provider whose {@code mediumSkyRadiance} is the
     * authored floor itself. The other two derive that value -- from the sky-view integral, or from an
     * HDRI's mean -- and adding a derived daylight radiance to every diffuse surface without visibility
     * would light a sealed room as brightly as the field outside it. RtSkyPresets enforces the pairing.
     */
    public enum AmbientVisibility {
        SKY,
        UNOCCLUDED
    }

    public record Rgb(float r, float g, float b) {
        public static final Rgb BLACK = new Rgb(0f, 0f, 0f);
    }

    /** Static environment resources and their world orientation. Kerr parameters live in the LUT. */
    public record Environment(
            Identifier radianceTexture,
            Identifier transferTexture,
            Identifier diskEntryTexture,
            Identifier diskExitTexture,
            Vec3 direction,
            Vec3 up,
            float lightHalfAngle) {
    }

    public record Vec3(float x, float y, float z) {
    }

    public record Fog(
            FogProfile profile,
            float density,
            Rgb extinction,
            Rgb albedo,
            float phaseG,
            float startDistance,
            float cullDistance,
            float heightScale,
            float heightBase,
            boolean noise,
            AmbientVisibility ambientVisibility,
            boolean localLights) {
    }
}
