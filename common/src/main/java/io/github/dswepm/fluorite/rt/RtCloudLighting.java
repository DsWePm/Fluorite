package io.github.dswepm.fluorite.rt;

/** CPU-side cloud-lighting coefficients that are constant for a complete frame. */
final class RtCloudLighting {
    private RtCloudLighting() {}

    /**
     * Source-radiance scale for the isotropic cloud diffusion term (D61).
     *
     * <p>For single-scattering albedo {@code w} and phase asymmetry {@code g}, similarity theory gives
     * the half-space reflectance
     *
     * <pre>
     * s    = sqrt((1 - w) / (1 - w g))
     * Rinf = (1 - s) / (1 + s).
     * </pre>
     *
     * The cloud march's thick-slab limit is {@code L = scale * E / (1 + K)}, where
     * {@code K = sqrt(3(1-w))}. Requiring {@code pi*L/E = Rinf} yields the value returned here:
     * {@code scale = Rinf(1+K)/pi = A/(4pi)}. Computing it once on the CPU avoids putting the square
     * roots and division in every in-cloud sample.
     */
    static float diffuseSourceScale(float singleScatteringAlbedo, float phaseG) {
        double w = Math.max(0.0, Math.min(1.0, singleScatteringAlbedo));
        double g = Math.max(-0.95, Math.min(0.95, phaseG));
        double similarity = Math.sqrt((1.0 - w) / Math.max(1.0 - w * g, 1.0e-12));
        double halfSpaceReflectance = (1.0 - similarity) / (1.0 + similarity);
        double diffusionRate = Math.sqrt(3.0 * (1.0 - w));
        return (float) (halfSpaceReflectance * (1.0 + diffusionRate) / Math.PI);
    }
}
