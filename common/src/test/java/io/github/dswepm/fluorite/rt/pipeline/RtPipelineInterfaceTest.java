package io.github.dswepm.fluorite.rt.pipeline;

import io.github.dswepm.fluorite.rt.accel.RtAccel;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

final class RtPipelineInterfaceTest {
    @Test void terrainAndEntityShadowGroupsBothUseTheOriginalPayloadInterface() {
        // Check all records, including reserved entity material slots. Testing only terrain would
        // misclassify entity radiance indices 8..11 as shadow after the first four records.
        boolean[] expected = {false, false, false, false, true, true, true, true,
                              false, false, false, false, true, true, true, true};
        assertEquals(expected.length, RtAccel.SBT_HIT_GROUP_COUNT);
        for (int record = 0; record < expected.length; record++) {
            assertEquals(expected[record], RtPipeline.hitGroupIsShadow(record), "SBT record " + record);
        }
    }

    @Test void completeVariantShaderTablesAreImmutableAndCannotBeEmpty() {
        var miss = new ArrayList<>(List.of("sky", "shadow", "radiance guide"));
        var shaders = new RtPipeline.VariantShaders(List.of("radiance"), miss,
                "joint hit", "joint alpha", "original shadow alpha");
        miss.clear();
        assertEquals(3, shaders.miss().size());
        assertThrows(UnsupportedOperationException.class, () -> shaders.miss().clear());
        assertThrows(IllegalArgumentException.class, () -> new RtPipeline.VariantShaders(
                List.of(), List.of("miss"), "hit", null, null));
        assertThrows(IllegalArgumentException.class, () -> new RtPipeline.VariantShaders(
                List.of("rgen"), List.of(), "hit", null, null));
    }
}
