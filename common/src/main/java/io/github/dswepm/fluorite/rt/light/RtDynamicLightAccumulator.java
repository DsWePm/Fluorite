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
    private double minX;
    private double minY;
    private double minZ;
    private double maxX;
    private double maxY;
    private double maxZ;
    private double maxSupportRadius;

    public void reset() {
        effectiveArea = 0.0;
        radianceAreaR = 0.0;
        radianceAreaG = 0.0;
        radianceAreaB = 0.0;
        centreWeight = 0.0;
        centreX = 0.0;
        centreY = 0.0;
        centreZ = 0.0;
        minX = minY = minZ = Double.POSITIVE_INFINITY;
        maxX = maxY = maxZ = Double.NEGATIVE_INFINITY;
        maxSupportRadius = 0.0;
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
        include(x[a], y[a], z[a]);
        include(x[b], y[b], z[b]);
        include(x[c], y[c], z[c]);
    }

    /**
     * Add a compact surface emitter proxy. {@code area} is its geometric emitting surface and
     * {@code supportRadius} encloses that surface around the supplied centre. Texture means are already
     * alpha-premultiplied, while coverage controls only the proxy's effective emitting area.
     */
    public void addEmitter(float x, float y, float z, float supportRadius, float area, float coverage,
                           float leR, float leG, float leB) {
        if (!(area > AREA_EPS) || !(coverage > 0.0f) || !(supportRadius >= 0.0f)
                || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || !Float.isFinite(supportRadius)) {
            return;
        }
        double clampedCoverage = Math.clamp(coverage, 0.0f, 1.0f);
        double r = Math.max(0.0f, leR);
        double g = Math.max(0.0f, leG);
        double b = Math.max(0.0f, leB);
        double luminance = 0.2126 * r + 0.7152 * g + 0.0722 * b;
        if (!(luminance > 0.0)) {
            return;
        }
        effectiveArea += area * clampedCoverage;
        radianceAreaR += area * r;
        radianceAreaG += area * g;
        radianceAreaB += area * b;
        double weight = area * luminance;
        centreWeight += weight;
        centreX += weight * x;
        centreY += weight * y;
        centreZ += weight * z;
        include(x, y, z);
        maxSupportRadius = Math.max(maxSupportRadius, supportRadius);
    }

    /** Finish in rebased world space; returns null for a source with no finite positive power. */
    public RtDynamicSphereLight finish(float offsetX, float offsetY, float offsetZ) {
        return finish(offsetX, offsetY, offsetZ, 0);
    }

    public RtDynamicSphereLight finish(float offsetX, float offsetY, float offsetZ, int sourceKey) {
        return finish(offsetX, offsetY, offsetZ, sourceKey, false);
    }

    /** Enclose a spatial cell's source distribution and lower radiance to preserve total flux. */
    public RtDynamicSphereLight finishCluster(float offsetX, float offsetY, float offsetZ, int sourceKey) {
        return finish(offsetX, offsetY, offsetZ, sourceKey, true);
    }

    private RtDynamicSphereLight finish(float offsetX, float offsetY, float offsetZ,
                                        int sourceKey, boolean encloseDistribution) {
        if (!(effectiveArea > AREA_EPS) || !(centreWeight > 0.0)) {
            return null;
        }
        double cx = centreX / centreWeight;
        double cy = centreY / centreWeight;
        double cz = centreZ / centreWeight;
        double radius = Math.sqrt(effectiveArea / (4.0 * Math.PI));
        if (encloseDistribution && minX <= maxX) {
            double dx = Math.max(Math.abs(cx - minX), Math.abs(maxX - cx));
            double dy = Math.max(Math.abs(cy - minY), Math.abs(maxY - cy));
            double dz = Math.max(Math.abs(cz - minZ), Math.abs(maxZ - cz));
            radius = Math.max(radius, Math.sqrt(dx * dx + dy * dy + dz * dz) + maxSupportRadius);
        }
        double sphereArea = 4.0 * Math.PI * radius * radius;
        float leR = (float) (radianceAreaR / sphereArea);
        float leG = (float) (radianceAreaG / sphereArea);
        float leB = (float) (radianceAreaB / sphereArea);
        if (!(radius > 0.0) || !Double.isFinite(radius)
                || !finitePositive(leR, leG, leB)) {
            return null;
        }
        return new RtDynamicSphereLight(
                (float) cx + offsetX, (float) cy + offsetY, (float) cz + offsetZ,
                (float) radius, leR, leG, leB, sourceKey);
    }

    private void include(double x, double y, double z) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x);
        maxY = Math.max(maxY, y);
        maxZ = Math.max(maxZ, z);
    }

    private static boolean finitePositive(float r, float g, float b) {
        return Float.isFinite(r) && Float.isFinite(g) && Float.isFinite(b)
                && r >= 0.0f && g >= 0.0f && b >= 0.0f && (r > 0.0f || g > 0.0f || b > 0.0f);
    }
}
