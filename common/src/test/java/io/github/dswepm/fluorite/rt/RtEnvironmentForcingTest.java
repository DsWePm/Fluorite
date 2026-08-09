package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contract tests for D69-D73's continuous environment projection and water response. */
final class RtEnvironmentForcingTest {
    @Test
    void positiveWeatherGainsAreContinuousAndCannotCreateNegativeCoefficients() {
        assertEquals(1f, RtEnvironmentForcing.positiveScale(0f, 0f, 1f, 0.5f));
        assertEquals(1.5f, RtEnvironmentForcing.positiveScale(0.5f, 0f, 1f, 0.5f));
        assertEquals(2.5f, RtEnvironmentForcing.positiveScale(1f, 1f, 1f, 0.5f));
        assertEquals(0f, RtEnvironmentForcing.positiveScale(1f, 1f, -1f, -1f));
    }

    @Test
    void signedFieldResponsesKeepRainAndThunderAsIndependentAxes() {
        assertEquals(0.55f, RtEnvironmentForcing.signedBias(1f, 0f, 0.55f, 0.75f));
        assertEquals(0.75f, RtEnvironmentForcing.signedBias(0f, 1f, 0.55f, 0.75f));
        assertEquals(1.30f, RtEnvironmentForcing.signedBias(1f, 1f, 0.55f, 0.75f));
    }

    @Test
    void radiationFogCurveIsContinuousAcrossTheDayWrap() {
        float beforeWrap = RtEnvironmentForcing.radiationFog(269.999f);
        float afterWrap = RtEnvironmentForcing.radiationFog(270.001f);
        assertEquals(beforeWrap, afterWrap, 1.0e-3f);
        assertEquals(1f, RtEnvironmentForcing.radiationFog(270f), 1.0e-6f);
    }

    @Test
    void waterWeatherMovesLinearlyWithoutOvershoot() {
        assertEquals(0.25f, RtEnvironmentForcing.moveTowards(0f, 1f, 5f, 20f), 1.0e-6f);
        assertEquals(1f, RtEnvironmentForcing.moveTowards(0.9f, 1f, 5f, 20f), 1.0e-6f);
        assertEquals(0.75f, RtEnvironmentForcing.moveTowards(1f, 0f, 5f, 20f), 1.0e-6f);
        assertEquals(0.5f, RtEnvironmentForcing.moveTowards(0.5f, 1f, 0f, 20f), 1.0e-6f);
    }

    @Test
    void causticContrastUsesEnergyNeutralWeatherLoad() {
        assertEquals(1f, RtEnvironmentForcing.causticContrast(1f, 1f, 1f, 0f), 1.0e-6f);
        assertEquals(0.5f, RtEnvironmentForcing.causticContrast(1f, 2f, 1f, 0f), 1.0e-6f);
        assertEquals(1f / 3f,
                RtEnvironmentForcing.causticContrast(1f, 1f, 2f, 0.5f), 1.0e-6f);
        assertEquals(0.25f,
                RtEnvironmentForcing.causticContrast(0.25f, 1f, 1f, 0f), 1.0e-6f);
    }

    @Test
    void compositeCapturesWeatherOnceAndDoesNotReadRainOrThunderItself() throws IOException {
        String composite = repositoryFile(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String options = repositoryFile(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        String world = repositoryFile("shaders/world/world_common.slang");
        String waves = repositoryFile("shaders/world/water_wave.slang");
        String water = repositoryFile("shaders/world/water.slang");

        assertEquals(1, occurrences(composite, "RtEnvironmentForcing.capture(level)"));
        assertFalse(composite.contains("getRainLevel("));
        assertFalse(composite.contains("getThunderLevel("));
        assertTrue(options.contains("WEATHER(\"weather\")"));
        assertTrue(options.contains("case WEATHER ->"));
        assertTrue(options.contains("fogNoiseWindOffset()"));
        assertTrue(options.contains(
                "Section.of(waterWaves(), waterCausticDispersion(), waterCausticStrength(),"));
        assertFalse(options.contains("waterStormSwellBias(), waterCausticStrength()"));
        assertFalse(composite.contains("WAVE_LENGTH.value()\n                        * (1f +"));
        assertTrue(waves.contains("stormSwellBias"));
        assertTrue(water.contains("fade * causticContrast"));
        // CPU resolves the environment into existing lanes; D70 must not grow the shader ABI.
        assertFalse(world.contains("rainLevel"));
        assertFalse(world.contains("thunderLevel"));
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int at = source.indexOf(needle); at >= 0; at = source.indexOf(needle, at + needle.length())) {
            ++count;
        }
        return count;
    }

    private static String repositoryFile(String path) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(path));
    }
}
