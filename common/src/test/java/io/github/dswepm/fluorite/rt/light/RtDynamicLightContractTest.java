package io.github.dswepm.fluorite.rt.light;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtDynamicLightContractTest {
    @Test
    void sphereUsesTheSharedThirtyTwoByteRecordAndReservedTypeBit() {
        float[] record = new float[RtLightEncoding.RECORD_FLOATS];
        RtLightEncoding.encodeSphere(record, 0, 1.0f, 2.0f, 3.0f, 0.125f,
                0.25f, 5.0f, 31.5f);

        assertEquals(32, RtLightEncoding.RECORD_BYTES);
        assertEquals(1.0f, record[0]);
        assertEquals(2.0f, record[1]);
        assertEquals(3.0f, record[2]);
        int packedLe = Float.floatToRawIntBits(record[3]);
        assertEquals(0.25f, RtLightEncoding.unpackUnsignedFloat(packedLe & 0x7ff, 6), 0.004f);
        assertEquals(5.0f, RtLightEncoding.unpackUnsignedFloat((packedLe >>> 11) & 0x7ff, 6), 0.04f);
        assertEquals(31.5f, RtLightEncoding.unpackUnsignedFloat((packedLe >>> 22) & 0x3ff, 5), 0.5f);
        int packedRadius = Float.floatToRawIntBits(record[4]);
        assertEquals(0.125f, Float.float16ToFloat((short) (packedRadius & 0xffff)), 0.0001f);
        assertEquals(0.0f, record[5]);
        assertEquals(0.0f, record[6]);
        assertEquals(RtLightEncoding.TYPE_SPHERE_BIT, Float.floatToRawIntBits(record[7]));
    }

    @Test
    void proxySpherePreservesSubmittedLambertianFlux() {
        RtDynamicLightAccumulator accumulator = new RtDynamicLightAccumulator();
        float[] x = {0, 1, 1, 0};
        float[] y = {0, 0, 1, 1};
        float[] z = {0, 0, 0, 0};
        // Texture-domain mean Le=1 with 25% emissive coverage. The proxy raises local radiance to 4
        // while shrinking its emitting area to 0.25, preserving A*Le exactly.
        accumulator.addQuad(x, y, z, 0.25f, 1.0f, 0.5f, 0.25f);

        RtDynamicSphereLight light = accumulator.finish(10.0f, 20.0f, 30.0f);
        assertNotNull(light);
        assertEquals(10.5f, light.x(), 1.0e-6f);
        assertEquals(20.5f, light.y(), 1.0e-6f);
        assertEquals(30.0f, light.z(), 1.0e-6f);
        float sphereArea = 4.0f * (float) Math.PI * light.radius() * light.radius();
        assertEquals(0.25f, sphereArea, 1.0e-6f);
        assertEquals(1.0f, sphereArea * light.radianceR(), 1.0e-6f);
        assertEquals(0.5f, sphereArea * light.radianceG(), 1.0e-6f);
        assertEquals(0.25f, sphereArea * light.radianceB(), 1.0e-6f);
    }

    @Test
    void shaderDeclaresTheSameSphereTagWithoutBindingItIntoSampling() throws IOException {
        String common = source("shaders/world/world_common.slang");
        String sampling = source("shaders/world/light_sampling.slang");
        assertTrue(common.contains("LIGHT_TYPE_SPHERE = 0x80000000u"));
        assertTrue(common.contains("lightIsSphere"));
        // D98A: rectangle sampling remains unchanged during the collection-only milestone.
        assertTrue(sampling.contains("public float emitterArea(Light light)"));
        assertTrue(sampling.contains("return 4.0 * length(emitterCrossUV(light));"));
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        if (!Files.exists(root.resolve(relativePath))) {
            root = root.getParent();
        }
        return Files.readString(root.resolve(relativePath));
    }
}
