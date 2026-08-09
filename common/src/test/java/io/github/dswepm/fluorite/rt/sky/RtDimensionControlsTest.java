package io.github.dswepm.fluorite.rt.sky;

import io.github.dswepm.fluorite.FluoriteConfig;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

final class RtDimensionControlsTest {
    @Test
    void netherReadsItsOwnLiveControlsOnly() {
        boolean oldEnabled = FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED.value();
        float oldDensity = FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.value();
        float oldAmbient = FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE.value();
        try {
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED.set(false);
            // High-density Nether fog makes distant low-coverage volume samples reconstruct green under
            // the current DLSS-RR path. The setting boundary is also the config/system-property guard;
            // the UI independently stops at the same product limit.
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.set(8.0f);
            FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE.set(3.25f);

            RtDimensionControls nether = RtDimensionControls.forDimension(
                    Identifier.parse("minecraft:the_nether"));
            assertFalse(nether.fogEnabled());
            assertEquals(2.0f, FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.value());
            assertEquals(2.0f, nether.fogDensityScale());
            assertEquals(2.0f, nether.resolveFogDensityScale(8.0f));
            assertEquals(3.25f, nether.ambientScale());

            assertSame(RtDimensionControls.DEFAULT, RtDimensionControls.forDimension(
                    Identifier.parse("minecraft:overworld")));
            assertSame(RtDimensionControls.DEFAULT, RtDimensionControls.forDimension(
                    Identifier.parse("minecraft:the_end")));
            assertSame(RtDimensionControls.DEFAULT, RtDimensionControls.forDimension(
                    Identifier.parse("some_mod:nether_like")));
            assertEquals(8.0f, RtDimensionControls.DEFAULT.resolveFogDensityScale(8.0f));
        } finally {
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED.set(oldEnabled);
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.set(oldDensity);
            FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE.set(oldAmbient);
        }
    }
}
