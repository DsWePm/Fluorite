package io.github.dswepm.fluorite.rt.material;

/** Immutable physical material description produced at the resource-epoch boundary. */
public record RtMaterialDesc(
        int model,
        Source source,
        int features,
        float roughness,
        float metalness,
        float ior,
        float transmission,
        EmissionSource emissionSource,
        /**
         * Final HDR emission strength: {@code EMISSIVE_STRENGTH} (the material-compile-time baseline,
         * see {@link RtMaterialRegistry}) times any resource-pack {@code emission.strength} multiplier.
         * 0 when {@code emissionSource == NONE}. Applied uniformly regardless of source — LabPBR,
         * heuristic-mask, or state-uniform all get the same baseline, an override just scales it.
         */
        float emissionStrength,
        EmissionSummary emissionSummary,
        /**
         * Optional Disney parameters. {@link Disney#NONE} for the overwhelming majority of materials,
         * which then get no extension record and no extra load at shading time.
         */
        Disney disney
) {
    /**
     * Disney principled parameters a source format does not carry.
     *
     * <p>No source format in Minecraft's ecosystem defines these — LabPBR 1.3 has no channel for sheen,
     * clearcoat or anisotropy — so they arrive from the resource pack's material JSON rather than from a
     * texture, and every material that does not mention them keeps {@link #NONE}. That is what lets an
     * unmodified LabPBR pack keep working unchanged while a newer pack opts into more of the BSDF.
     *
     * <p>Roughness-like quantities here are linear and are the GGX alpha, like everywhere else in this
     * renderer. See the header of {@code bsdf.slang}.
     */
    public record Disney(float sheen, float sheenTint,
                         float clearcoat, float clearcoatGloss,
                         float specularTint, float anisotropy,
                         float subsurfaceWeight, float subsurfacePhaseG,
                         float subsurfaceRadiusR, float subsurfaceRadiusG, float subsurfaceRadiusB) {
        public static final Disney NONE = new Disney(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

        /**
         * Whether this material needs an extension record at all.
         *
         * <p>Keyed on the four lobe weights rather than on equality with {@link #NONE}: a pack that
         * authors a sheen tint but leaves sheen at zero has expressed nothing, and should not be charged
         * a record for it.
         */
        public boolean absent() {
            return sheen == 0.0f && clearcoat == 0.0f && anisotropy == 0.0f && subsurfaceWeight == 0.0f;
        }
    }

    public enum Source {
        OVERRIDE,
        LAB_PBR,
        HEURISTIC,
        NEUTRAL
    }

    public enum EmissionSource {
        NONE,
        LAB_PBR,
        HEURISTIC_MASK,
        STATE_UNIFORM
    }

    /** Normalized compiler output; per-primitive state light multiplies it when the source is state-gated. */
    public record EmissionSummary(float averageR, float averageG, float averageB,
                                  float integratedLuminance, float coverage) {
        public static final EmissionSummary NONE = new EmissionSummary(0, 0, 0, 0, 0);

        public boolean emissive() {
            return integratedLuminance > 0.0f && coverage > 0.0f;
        }
    }

    public RtMaterialDesc {
        if (source == null || emissionSource == null || emissionSummary == null) {
            throw new IllegalArgumentException("Material description enums/summary must be present");
        }
        if (disney == null) {
            throw new IllegalArgumentException("Material description Disney parameters must be present");
        }
        if (!finite01(roughness) || !finite01(metalness) || !Float.isFinite(ior) || ior <= 0.0f
                || !finite01(transmission) || !Float.isFinite(emissionStrength) || emissionStrength < 0.0f) {
            throw new IllegalArgumentException("Invalid physical material parameters");
        }
    }

    private static boolean finite01(float value) {
        return Float.isFinite(value) && value >= 0.0f && value <= 1.0f;
    }
}
