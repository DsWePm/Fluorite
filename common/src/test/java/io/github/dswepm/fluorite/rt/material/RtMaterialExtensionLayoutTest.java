package io.github.dswepm.fluorite.rt.material;

import io.github.dswepm.fluorite.rt.gen.MaterialExtensionData;
import io.github.dswepm.fluorite.rt.gen.MaterialExtensionData.Float4;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RtMaterialExtensionLayoutTest {
    /**
     * Pins the optional Disney parameter record's layout.
     *
     * <p>Unlike the material header, this record is addressed indirectly and is written for only the few
     * materials that author sheen, clearcoat, anisotropy or subsurface — everything else keeps
     * {@code extensionOffset == 0} and costs neither a record nor a load. That indirection is the point:
     * ten loose scalars in the header would be paid by every material in the world.
     *
     * <p>Packed into three float4s because std430 aligns a float3 to 16 bytes, so the loose form would
     * round well past 48. The test asserts the field offsets rather than only the size, because the
     * packing is the part a reader has to trust: which scalar lives in which lane is not recoverable from
     * the type, only from the comment on the Slang struct.
     */
    @Test
    void reflectedExtensionMatchesThePackedLayout() {
        assertEquals(48, MaterialExtensionData.BYTE_SIZE);
        ByteBuffer data = ByteBuffer.allocateDirect(MaterialExtensionData.BYTE_SIZE)
                .order(ByteOrder.nativeOrder());
        new MaterialExtensionData(
                new Float4(0.1f, 0.2f, 0.3f, 0.4f),   // sheen, sheenTint, clearcoat, clearcoatGloss
                new Float4(0.5f, 0.6f, 0.7f, 0.8f),   // specularTint, anisotropy, sssWeight, sssPhaseG
                new Float4(0.9f, 1.0f, 1.1f, 0.0f))   // subsurface radius rgb, reserved
                .write(data);

        assertEquals(0.1f, data.getFloat(0), "sheen");
        assertEquals(0.2f, data.getFloat(4), "sheenTint");
        assertEquals(0.3f, data.getFloat(8), "clearcoat");
        assertEquals(0.4f, data.getFloat(12), "clearcoatGloss");
        assertEquals(0.5f, data.getFloat(16), "specularTint");
        assertEquals(0.6f, data.getFloat(20), "anisotropy");
        assertEquals(0.7f, data.getFloat(24), "subsurfaceWeight");
        assertEquals(0.8f, data.getFloat(28), "subsurfacePhaseG");
        assertEquals(0.9f, data.getFloat(32), "subsurfaceRadius.r");
        assertEquals(1.0f, data.getFloat(36), "subsurfaceRadius.g");
        assertEquals(1.1f, data.getFloat(40), "subsurfaceRadius.b");
    }
}
