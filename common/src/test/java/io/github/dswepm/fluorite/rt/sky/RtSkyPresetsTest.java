package io.github.dswepm.fluorite.rt.sky;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtSkyPresetsTest {
    private static final Identifier SOURCE = Identifier.parse("fluorite:fluorite/sky/minecraft/the_nether.json");

    @Test
    void parsesAResourceAuthoredLocalAmbientDimension() {
        RtSkyPreset preset = RtSkyPresets.parse(JsonParser.parseString(netherJson()).getAsJsonObject(), SOURCE);

        assertEquals(RtSkyPreset.SkyProvider.LOCAL_AMBIENT, preset.skyProvider());
        assertFalse(preset.weatherEnabled());
        assertFalse(preset.cloudsEnabled());
        assertEquals(RtSkyPreset.FogProfile.HOMOGENEOUS, preset.fog().profile());
        assertEquals(RtSkyPreset.AmbientVisibility.UNOCCLUDED, preset.fog().ambientVisibility());
        assertTrue(preset.fog().localLights());
        assertFalse(preset.fog().noise());
    }

    @Test
    void unknownModDimensionsReceiveTheCompleteAtmosphere() {
        RtSkyPreset known = RtSkyPresets.parse(JsonParser.parseString(netherJson()).getAsJsonObject(), SOURCE);
        RtSkyPresets presets = RtSkyPresets.of(Map.of(Identifier.parse("minecraft:the_nether"), known));

        assertSame(RtSkyPreset.FULL_ATMOSPHERE,
                presets.forDimension(Identifier.parse("some_mod:unrecognised_dimension")));
    }

    @Test
    void authoredOverworldAndUnknownFallbackCannotDriftApart() throws Exception {
        var stream = RtSkyPresetsTest.class.getResourceAsStream(
                "/assets/fluorite/fluorite/sky/minecraft/overworld.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RtSkyPreset authored = RtSkyPresets.parse(
                    JsonParser.parseReader(reader).getAsJsonObject(),
                    Identifier.parse("fluorite:fluorite/sky/minecraft/overworld.json"));
            assertEquals(RtSkyPreset.FULL_ATMOSPHERE, authored);
        }
    }

    @Test
    void authoredNetherUsesTheApprovedLocalLightMedium() throws Exception {
        var stream = RtSkyPresetsTest.class.getResourceAsStream(
                "/assets/fluorite/fluorite/sky/minecraft/the_nether.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RtSkyPreset preset = RtSkyPresets.parse(
                    JsonParser.parseReader(reader).getAsJsonObject(), SOURCE);
            assertEquals(RtSkyPreset.SkyProvider.LOCAL_AMBIENT, preset.skyProvider());
            assertEquals(0.002f, preset.ambientRadiance().r());
            assertEquals(0.003f, preset.fog().density());
            assertEquals(0.90f, preset.fog().albedo().r());
            assertEquals(256f, preset.fog().cullDistance());
            assertFalse(preset.weatherEnabled());
            assertFalse(preset.cloudsEnabled());
            assertFalse(preset.fog().noise());
            assertTrue(preset.fog().localLights());
        }
    }

    @Test
    void mapsNestedResourcePathsToNamespacedDimensionIds() {
        assertEquals(Identifier.parse("some_mod:worlds/deep"), RtSkyPresets.dimensionFromResource(
                Identifier.parse("fluorite:fluorite/sky/some_mod/worlds/deep.json")));
    }

    @Test
    void rejectsAmbiguousForeignResourceNamespaces() {
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.dimensionFromResource(
                Identifier.parse("some_mod:fluorite/sky/minecraft/overworld.json")));
    }

    @Test
    void rejectsNonConservativeAndNonFiniteMedia() {
        String nonConservative = netherJson().replace("[0.92,0.94,0.96]", "[0.92,1.01,0.96]");
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.parse(
                JsonParser.parseString(nonConservative).getAsJsonObject(), SOURCE));

        String invertedWindow = netherJson().replace("\"start_distance\":0", "\"start_distance\":600");
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.parse(
                JsonParser.parseString(invertedWindow).getAsJsonObject(), SOURCE));
    }

    @Test
    void formatOneCannotClaimTheFormatTwoEnvironmentProvider() {
        String environment = netherJson().replace("local_ambient", "environment");
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.parse(
                JsonParser.parseString(environment).getAsJsonObject(), SOURCE));
    }

    @Test
    void authoredEndUsesTheKerrEnvironmentProvider() throws Exception {
        var stream = RtSkyPresetsTest.class.getResourceAsStream(
                "/assets/fluorite/fluorite/sky/minecraft/the_end.json");
        assertNotNull(stream);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            RtSkyPreset preset = RtSkyPresets.parse(
                    JsonParser.parseReader(reader).getAsJsonObject(),
                    Identifier.parse("fluorite:fluorite/sky/minecraft/the_end.json"));
            assertEquals(RtSkyPreset.SkyProvider.ENVIRONMENT, preset.skyProvider());
            assertNotNull(preset.environment());
            assertEquals(Identifier.parse("fluorite:fluorite/environment/end_stars.ktx2"),
                    preset.environment().radianceTexture());
            assertEquals(Identifier.parse("fluorite:fluorite/environment/end_kerr.ktx2"),
                    preset.environment().transferTexture());
            assertEquals(Identifier.parse("fluorite:fluorite/environment/end_disk_entry.ktx2"),
                    preset.environment().diskEntryTexture());
            assertEquals(Identifier.parse("fluorite:fluorite/environment/end_disk_exit.ktx2"),
                    preset.environment().diskExitTexture());
            assertEquals(0.36f, preset.environment().lightHalfAngle());
            assertFalse(preset.weatherEnabled());
            assertFalse(preset.cloudsEnabled());
            assertEquals(RtSkyPreset.FogProfile.OFF, preset.fog().profile());
        }
    }

    @Test
    void rejectsJsonStringsThatOnlyLookLikeTypedValues() {
        String stringBoolean = netherJson().replace("\"enabled\":false", "\"enabled\":\"false\"");
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.parse(
                JsonParser.parseString(stringBoolean).getAsJsonObject(), SOURCE));

        String stringNumber = netherJson().replace("\"density\":0.0016", "\"density\":\"0.0016\"");
        assertThrows(IllegalArgumentException.class, () -> RtSkyPresets.parse(
                JsonParser.parseString(stringNumber).getAsJsonObject(), SOURCE));
    }

    /**
     * The unoccluded floor is refused wherever mediumSkyRadiance is DERIVED rather than authored.
     *
     * <p>It is added to every diffuse surface with no visibility sampled, and the diffuse continuation
     * leaving that surface collects the same radiance again when it escapes. For local_ambient both entries
     * are the one authored floor D78A approved. For the other two providers the value is the sky-view
     * integral or an HDRI mean, and the result is a sealed room as bright as the field outside it -- with
     * no error anywhere, because a uniformly wrong picture is still a picture.
     *
     * <p>Both cases start from a SHIPPED preset and change one field, and that is what makes the assertion
     * tight: the unmodified document parses, so nothing else in it can be why the modified one throws. A
     * hand-built environment fixture would have been rejected by the format-2 rule long before reaching
     * this guard, and would have stayed green with the guard deleted.
     */
    @Test
    void unoccludedAmbientIsRefusedWhereTheSkyRadianceIsDerivedRatherThanAuthored() {
        // assertAll rather than a loop: a loop stops at the first dimension, so the second would be
        // asserted only while the first passes -- and a guard that covered atmosphere and missed
        // environment would still read as green here.
        assertAll(
                () -> assertUnoccludedIsRefused("overworld"),
                () -> assertUnoccludedIsRefused("the_end"));
    }

    private static void assertUnoccludedIsRefused(String dimension) throws Exception {
        Identifier source = Identifier.parse("fluorite:fluorite/sky/minecraft/" + dimension + ".json");
        String authored = shippedPreset(dimension);
        assertNotNull(RtSkyPresets.parse(JsonParser.parseString(authored).getAsJsonObject(), source),
                dimension + " must parse unmodified, or the rejection below proves nothing");

        String unoccluded = authored.replace(
                "\"ambient_visibility\": \"sky\"", "\"ambient_visibility\": \"unoccluded\"");
        assertNotEquals(authored, unoccluded, "the substitution never applied to " + dimension);
        assertThrows(IllegalArgumentException.class,
                () -> RtSkyPresets.parse(JsonParser.parseString(unoccluded).getAsJsonObject(), source),
                dimension + " would light every diffuse surface with a derived sky and no visibility");
    }

    /** A pairing rule, not a ban: the combination D78A approved is still the one the Nether ships. */
    @Test
    void theApprovedUnoccludedPairingStaysLegal() throws Exception {
        RtSkyPreset preset = RtSkyPresets.parse(
                JsonParser.parseString(shippedPreset("the_nether")).getAsJsonObject(), SOURCE);
        assertEquals(RtSkyPreset.SkyProvider.LOCAL_AMBIENT, preset.skyProvider());
        assertEquals(RtSkyPreset.AmbientVisibility.UNOCCLUDED, preset.fog().ambientVisibility());
    }

    private static String shippedPreset(String dimension) throws Exception {
        try (var stream = RtSkyPresetsTest.class.getResourceAsStream(
                "/assets/fluorite/fluorite/sky/minecraft/" + dimension + ".json")) {
            assertNotNull(stream, dimension);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String netherJson() {
        return """
                {
                  "format": 1,
                  "sky": {"provider":"local_ambient", "ambient_radiance":[0.002,0.002,0.002]},
                  "weather": {"enabled":false},
                  "clouds": {"enabled":false},
                  "fog": {
                    "profile":"homogeneous", "density":0.0016,
                    "extinction":[1.0,1.0,1.0], "albedo":[0.92,0.94,0.96], "phase_g":0.0,
                    "start_distance":0, "cull_distance":512,
                    "height_scale":0, "height_base":0,
                    "noise":false, "ambient_visibility":"unoccluded", "local_lights":true
                  }
                }
                """;
    }
}
