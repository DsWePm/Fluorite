package io.github.dswepm.fluorite.rt.sky;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
