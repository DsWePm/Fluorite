package io.github.dswepm.fluorite.rt.light;

/**
 * Reduces the emissive quads submitted for one held item to one finite sphere without inventing a
 * fixed colour, radius, or power. The sphere area equals the quads' emissive-covered area and its
 * radiance preserves their Lambertian flux: {@code pi*A_sphere*Le_sphere = pi*sum(A_quad*Le_quad)}.
 */
public final class RtDynamicLightAccumulator {
    private static final double AREA_EPS = 1.0e-12;

    private double effectiveArea;
    private double radianceAreaR;
    private double radianceAreaG;
    private double radianceAreaB;
    private double centreWeight;
    private double centreX;
    private double centreY;
    private double centreZ;

    public void reset() {
        effectiveArea = 0.0;
        radianceAreaR = 0.0;
        radianceAreaG = 0.0;
        radianceAreaB = 0.0;
        centreWeight = 0.0;
        centreX = 0.0;
        centreY = 0.0;
        centreZ = 0.0;
    }

    /** Add one transformed quad whose RGB is its texture-domain mean emitted radiance. */
    public void addQuad(float[] x, float[] y, float[] z, float coverage,
                        float leR, float leG, float leB) {
        if (x.length < 4 || y.length < 4 || z.length < 4 || !(coverage > 0.0f)) {
            return;
        }
        addTriangle(x, y, z, 0, 1, 2, coverage, leR, leG, leB);
        addTriangle(x, y, z, 0, 2, 3, coverage, leR, leG, leB);
    }

    private void addTriangle(float[] x, float[] y, float[] z, int a, int b, int c, float coverage,
                             float leR, float leG, float leB) {
        double e1x = x[b] - x[a];
        double e1y = y[b] - y[a];
        double e1z = z[b] - z[a];
        double e2x = x[c] - x[a];
        double e2y = y[c] - y[a];
        double e2z = z[c] - z[a];
        double cx = e1y * e2z - e1z * e2y;
        double cy = e1z * e2x - e1x * e2z;
        double cz = e1x * e2y - e1y * e2x;
        double area = 0.5 * Math.sqrt(cx * cx + cy * cy + cz * cz);
        if (!(area > AREA_EPS)) {
            return;
        }

        effectiveArea += area * Math.clamp(coverage, 0.0f, 1.0f);
        radianceAreaR += area * Math.max(0.0f, leR);
        radianceAreaG += area * Math.max(0.0f, leG);
        radianceAreaB += area * Math.max(0.0f, leB);
        double luminance = 0.2126 * Math.max(0.0f, leR)
                + 0.7152 * Math.max(0.0f, leG) + 0.0722 * Math.max(0.0f, leB);
        double weight = area * luminance;
        centreWeight += weight;
        centreX += weight * (x[a] + x[b] + x[c]) / 3.0;
        centreY += weight * (y[a] + y[b] + y[c]) / 3.0;
        centreZ += weight * (z[a] + z[b] + z[c]) / 3.0;
    }

    /** Finish in rebased world space; returns null for a source with no finite positive power. */
    public RtDynamicSphereLight finish(float offsetX, float offsetY, float offsetZ) {
        if (!(effectiveArea > AREA_EPS) || !(centreWeight > 0.0)) {
            return null;
        }
        float radius = (float) Math.sqrt(effectiveArea / (4.0 * Math.PI));
        float leR = (float) (radianceAreaR / effectiveArea);
        float leG = (float) (radianceAreaG / effectiveArea);
        float leB = (float) (radianceAreaB / effectiveArea);
        if (!(radius > 0.0f) || !Float.isFinite(radius)
                || !finitePositive(leR, leG, leB)) {
            return null;
        }
        return new RtDynamicSphereLight(
                (float) (centreX / centreWeight) + offsetX,
                (float) (centreY / centreWeight) + offsetY,
                (float) (centreZ / centreWeight) + offsetZ,
                radius, leR, leG, leB);
    }

    private static boolean finitePositive(float r, float g, float b) {
        return Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b)
                && r >= 0.0f && g >= 0.0f && b >= 0.0f && (r > 0.0f || g > 0.0f || b > 0.0f);
    }
}
