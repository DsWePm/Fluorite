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
        // the lighting one and 816 when slice three added the cirrus layer. This buffer is not the
        // 128-byte push-constant block, so its size is a per-frame upload cost rather than a hard limit —
        // but it is pinned here so growth is a decision rather than a side effect.
        assertEquals(816, WorldPushData.BYTE_SIZE);
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
        assertTrue(names.contains("cloudHigh"), names.toString());
    }
}
