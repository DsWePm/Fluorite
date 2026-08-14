package io.github.dswepm.fluorite.rt.sky;

import io.github.dswepm.fluorite.rt.gen.WorldPushData;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSkyMediumLayoutTest {
    @Test
    void worldPushCarriesOneSharedMediumSkyRadiance() {
        assertTrue(Arrays.stream(WorldPushData.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("mediumSkyRadiance")));
        // 736 through M16; 768 when M11's slice one added the two authored cloud vectors, 784 with the
        // rebase origin that anchors them to the world instead of to the player, 800 when slice two added
        // the lighting one, 816 when slice three added the cirrus layer and 848 when that layer stopped
        // deriving its shape and wind from the deck below it. M12 and M12.5 grew the water domain and
        // authored wave lanes to 928; M13 residual adds one 16-byte world anchor for the advected fog
        // field, reaching 944. M21 adds six persistent rain/exposure vectors plus two temporary visual-
        // calibration vectors and reaches 1072. This buffer is not the
        // 128-byte push-constant block, so its size is a per-frame upload cost rather than a hard limit —
        // but it is pinned here so growth is a decision rather than a side effect.
        assertEquals(1072, WorldPushData.BYTE_SIZE);
        // AND THEIR ORDER, which the size alone cannot see. WorldPushData is generated from the shader's
        // reflection, so its constructor is POSITIONAL: RtComposite must pass these in the order
        // world_common declares them. Passing them in a different order compiles, runs, and feeds every
        // lane the contents of the one beside it -- which is exactly what happened when the two wave
        // lanes were added, and the symptom was a steepness of zero and a sea that had gone perfectly
        // flat with no error anywhere.
        //
        // If these numbers move, the declaration order changed, and the call site needs re-checking.
        // M14 spends the struct's existing eight padding bytes on provider/capability ids, so adding
        // dimension architecture must not grow this upload or move any medium vector after it.
        assertEquals(584, WorldPushData.SKY_PROVIDER_OFFSET);
        assertEquals(588, WorldPushData.ENVIRONMENT_FLAGS_OFFSET);
        assertEquals(592, WorldPushData.FOG_PARAMS_OFFSET);
        assertEquals(656, WorldPushData.FOG_NOISE_ORIGIN_OFFSET);
        assertEquals(864, WorldPushData.WATER_SIM_DOMAIN_OFFSET);
        assertEquals(880, WorldPushData.WATER_WAVE_SHAPE_OFFSET);
        assertEquals(896, WorldPushData.WATER_WAVE_GUST_OFFSET);
        assertEquals(912, WorldPushData.WATER_WAVE_WARP_OFFSET);
        assertEquals(928, WorldPushData.WATER_SIM_PLANE_OFFSET);
        assertEquals(944, WorldPushData.RAIN_EXPOSURE_ORIGIN_OFFSET);
        assertEquals(960, WorldPushData.RAIN_DIRECTION_OFFSET);
        assertEquals(976, WorldPushData.RAIN_STATE_OFFSET);
        assertEquals(992, WorldPushData.RAIN_MATERIAL_DEFAULTS_OFFSET);
        assertEquals(1008, WorldPushData.RAIN_SURFACE_OFFSET);
        assertEquals(1024, WorldPushData.RAIN_PUDDLE_OFFSET);
        assertEquals(1040, WorldPushData.RAIN_CALIBRATION0_OFFSET);
        assertEquals(1056, WorldPushData.RAIN_CALIBRATION1_OFFSET);
        assertTrue(Arrays.stream(WorldPushData.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("fogNoiseOrigin")));
    }

    /**
     * The cloud density field is authored, not compiled in — which is what lets vanilla's weather move it.
     *
     * <p>Pinned because the alternative was constants in cloud.slang, and a sky that cannot respond to
     * the weather was the thing this pair of vectors exists to prevent.
     */
    @Test
    void worldPushCarriesTheAuthoredCloudFields() {
        var names = Arrays.stream(WorldPushData.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertTrue(names.contains("cloudParams"), names.toString());
        assertTrue(names.contains("cloudShape"), names.toString());
        assertTrue(names.contains("cloudRebase"), names.toString());
        assertTrue(names.contains("cloudLighting"), names.toString());
        assertTrue(names.contains("cloudHighShape"), names.toString());
        assertTrue(names.contains("cloudHighOrigin"), names.toString());
        assertTrue(names.contains("cloudHigh"), names.toString());
    }
}
