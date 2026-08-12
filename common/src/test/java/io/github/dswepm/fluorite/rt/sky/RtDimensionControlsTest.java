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
                    Identifier.parse("some_mod:nether_like")));
            assertEquals(8.0f, RtDimensionControls.DEFAULT.resolveFogDensityScale(8.0f));
        } finally {
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_ENABLED.set(oldEnabled);
            FluoriteConfig.Rt.Dimensions.NETHER_FOG_DENSITY_SCALE.set(oldDensity);
            FluoriteConfig.Rt.Dimensions.NETHER_AMBIENT_SCALE.set(oldAmbient);
        }
    }

    @Test
    void endBrightnessControlsAreIndependentAndClampedToZeroThroughEight() {
        float oldEnvironment = FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_SCALE.value();
        float oldDisk = FluoriteConfig.Rt.Dimensions.END_DISK_SCALE.value();
        float oldOuterRadius = FluoriteConfig.Rt.Dimensions.END_DISK_OUTER_RADIUS.value();
        float oldThickness = FluoriteConfig.Rt.Dimensions.END_DISK_THICKNESS.value();
        float oldRotation = FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_ROTATION_SPEED.value();
        try {
            FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_SCALE.set(9f);
            FluoriteConfig.Rt.Dimensions.END_DISK_SCALE.set(4.25f);
            FluoriteConfig.Rt.Dimensions.END_DISK_OUTER_RADIUS.set(20f);
            FluoriteConfig.Rt.Dimensions.END_DISK_THICKNESS.set(0f);
            FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_ROTATION_SPEED.set(2f);
            RtDimensionControls end = RtDimensionControls.forDimension(
                    Identifier.parse("minecraft:the_end"));
            assertEquals(8f, end.environmentScale());
            assertEquals(4.25f, end.diskScale());
            assertEquals(12f, end.diskOuterRadius());
            assertEquals(0.25f, end.diskThickness());
            assertEquals(1f, end.environmentRotationSpeed());
            assertEquals(1f, end.ambientScale());

            FluoriteConfig.Rt.Dimensions.END_DISK_SCALE.set(-1f);
            assertEquals(0f, RtDimensionControls.forDimension(
                    Identifier.parse("minecraft:the_end")).diskScale());
        } finally {
            FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_SCALE.set(oldEnvironment);
            FluoriteConfig.Rt.Dimensions.END_DISK_SCALE.set(oldDisk);
            FluoriteConfig.Rt.Dimensions.END_DISK_OUTER_RADIUS.set(oldOuterRadius);
            FluoriteConfig.Rt.Dimensions.END_DISK_THICKNESS.set(oldThickness);
            FluoriteConfig.Rt.Dimensions.END_ENVIRONMENT_ROTATION_SPEED.set(oldRotation);
        }
    }
}
