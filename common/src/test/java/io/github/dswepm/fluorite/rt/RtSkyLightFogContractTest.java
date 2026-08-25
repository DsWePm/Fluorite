package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M27 slice two: where the marched fog goes for sky openness once it leaves the visibility box.
 *
 * <p>For as long as this renderer has existed the answer outside the box was the literal 1.0, which is
 * the whole of #41's symptom. Two attempts came before this one. M25 changed what volumeSkyOpenness does
 * with an out-of-range coordinate and never reached here, because this code was not calling it; calling
 * it was then tried and rejected in game, because clamping to the boundary cell darkened distant fog
 * outdoors as well. Minecraft's sky light is the first answer that has data at that distance.
 */
final class RtSkyLightFogContractTest {

    /** Off is the published renderer, or the comparison this switch exists for means nothing. */
    @Test
    void theOffStateIsTheShippedGate() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String volume = shader("volume.slang");

        assertTrue(config.contains(
                "\"fluorite.rt.fog.farSkyLight\", \"volumetrics.far-fog-sky-light\", false"));
        assertTrue(composite.contains("if (FluoriteConfig.Rt.Volumetrics.FAR_FOG_SKY_LIGHT.value()) {\n"
                + "            flags |= 1 << 3; // ENVIRONMENT_FAR_FOG_SKY_LIGHT"));
        // With the bit down the outer regions get exactly the constant they always got.
        assertTrue(volume.contains("(farSkyLight ? pow(skyLightAt(p), skyLightCurve) : 1.0)"));
    }

    /**
     * The grid keeps the inside of its box, and sky light only answers outside it.
     *
     * <p>They are different quantities: a hemisphere integral against a flood fill that falls one step
     * per block from an opening. Letting sky light answer inside the box would throw away the near-field
     * quality the ray-traced grid exists for -- the fog under a canopy being lit is what that work bought
     * -- and would replace a measurement with an approximation in the one region where the measurement
     * is available.
     */
    @Test
    void theGridStillOwnsTheInsideOfItsBox() throws IOException {
        String volume = shader("volume.slang");
        assertTrue(volume.contains("float skyOpen = gridRegion\n"
                + "                    ? volumeSkyOpenness(p)"));
    }

    /**
     * The response curve is applied by the FOG, never inside the field's reader.
     *
     * <p>The debug view calls skyLightAt too. A curve applied there would make the diagnostic agree with
     * whatever the dial was set to, which is a diagnostic that can no longer contradict the thing it is
     * supposed to be checking.
     */
    @Test
    void theCurveIsAppliedAtTheFogAndNotInTheFieldReader() throws IOException {
        String volume = shader("volume.slang");
        String field = shader("sky_light_field.slang");

        assertTrue(volume.contains("pow(skyLightAt(p), skyLightCurve)"));
        assertFalse(field.contains("pow("), "skyLightAt must return the field as Minecraft stores it");
    }

    /**
     * ONE LANE, TWO JOBS, and the invariant that makes that safe.
     *
     * <p>skyLightField.w is both "there is something to read" and the fog's curve. That works only while
     * a usable field never publishes zero -- the reader's guard is w &lt;= 0, so a curve of zero would
     * read as an absent field and silently restore the old behaviour. The CPU floors it at the setting's
     * own minimum, which is what keeps the two meanings from colliding.
     */
    @Test
    void theCurveLaneIsNeverZeroWhileTheFieldIsUsable() throws IOException {
        String composite = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java");
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        String field = shader("sky_light_field.slang");

        assertTrue(composite.contains(
                "Math.max(0.25f, FluoriteConfig.Rt.Volumetrics.FAR_FOG_SKY_LIGHT_CURVE.value())"));
        assertTrue(config.contains("\"volumetrics.far-fog-sky-light-curve\", 1.0f, 0.25f, 4.0f"));
        assertTrue(field.contains("if (worldPush.skyLightField.w <= 0.0) {"));
        // And the unusable case still publishes a hard zero, so the guard has something to catch.
        assertTrue(composite.contains("return new Float4(0f, 0f, 0f, 0f);"));
    }

    /** The flag is read once per region, not once per step: the loop is the fog march. */
    @Test
    void theFlagIsReadOncePerRegionRatherThanPerStep() throws IOException {
        String volume = shader("volume.slang");
        int decl = volume.indexOf("bool farSkyLight = (worldPush.environmentFlags");
        assertTrue(decl >= 0, "farSkyLight must be a hoisted local");
        int loop = volume.indexOf("[loop]", decl);
        assertTrue(loop > decl, "the hoist must come before the march loop it serves");
        String body = volume.substring(loop, volume.indexOf("\n}", loop));
        assertFalse(body.contains("ENVIRONMENT_FAR_FOG_SKY_LIGHT"),
                "the capability word must not be sampled inside the step loop");
    }

    private static String shader(String name) throws IOException {
        return source("shaders/world/" + name);
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from "
                    + System.getProperty("user.dir"));
        }
        // Normalised, because the working tree carries CRLF while every pattern here is written with LF.
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}
