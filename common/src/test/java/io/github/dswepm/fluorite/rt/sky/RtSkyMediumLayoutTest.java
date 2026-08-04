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
        assertEquals(736, WorldPushData.BYTE_SIZE);
    }
}
