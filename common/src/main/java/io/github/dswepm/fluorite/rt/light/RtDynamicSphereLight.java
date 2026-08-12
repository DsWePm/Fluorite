package io.github.dswepm.fluorite.rt.light;

/** One per-frame finite sphere emitter in the current frame's rebased world coordinates. */
public record RtDynamicSphereLight(float x, float y, float z, float radius,
                                   float radianceR, float radianceG, float radianceB,
                                   int sourceKey) {
    public void encode(float[] destination, int offset) {
        RtLightEncoding.encodeSphere(destination, offset, x, y, z, radius,
                radianceR, radianceG, radianceB, sourceKey);
    }
}
