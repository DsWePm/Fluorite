package io.github.dswepm.fluorite;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

final class WaterCoefficientControlsTest {
    @Test
    void changingCoefficientColourPreservesArithmeticMeanStrength() {
        float strength = 0.08f;
        for (int[] colour : new int[][] {{255, 255, 255}, {255, 32, 8}, {4, 190, 71}, {0, 0, 0}}) {
            float[] coefficient = FluoriteConfig.Rt.Water.coefficientRgb(
                    colour[0], colour[1], colour[2], strength);
            assertEquals(strength, (coefficient[0] + coefficient[1] + coefficient[2]) / 3.0f,
                    1.0e-6f);
        }
    }

    @Test
    void migratedLegacyRgbProducesIdenticalCoefficients() {
        assertLegacyMigration(24, 26, 28, 0.2f);
        assertLegacyMigration(60, 42, 12, 0.4f);
    }

    private static void assertLegacyMigration(int r, int g, int b, float fullScale) {
        float[] legacy = {
                r / 255.0f * fullScale,
                g / 255.0f * fullScale,
                b / 255.0f * fullScale};
        float migratedStrength = (legacy[0] + legacy[1] + legacy[2]) / 3.0f;

        assertArrayEquals(legacy,
                FluoriteConfig.Rt.Water.coefficientRgb(r, g, b, migratedStrength),
                1.0e-6f);
    }
}
