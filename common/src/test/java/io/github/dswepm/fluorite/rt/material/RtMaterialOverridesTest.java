package io.github.dswepm.fluorite.rt.material;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RtMaterialOverridesTest {
    /**
     * A format-1 file must keep meaning exactly what it meant.
     *
     * <p>This is the compatibility promise the whole material-import design rests on: an existing LabPBR
     * pack, which cannot possibly mention Disney parameters, has to keep rendering identically after the
     * format gains them. Absent parameters therefore have to be absent — not defaulted to something that
     * happens to look similar — so the material gets no extension record and the shader takes the same
     * path it always did.
     */
    @Test
    void formatOneStaysLoadableAndAuthorsNoDisneyParameters() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:block/stone"},
                "base":{"roughness":0.4}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/stone.json"));
        assertNull(rule.disney(), "a format-1 rule cannot express Disney parameters");

        RtMaterialDesc base = neutralBase();
        assertTrue(rule.apply(base).disney().absent(), "no record should be charged for it");
        assertEquals(base.disney(), rule.apply(base).disney());
    }

    /** A rule that mentions only a tint expresses nothing, and must not be charged an extension record. */
    @Test
    void weightlessDisneyParametersDoNotEarnAnExtensionRecord() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":2,"match":{"sprite":"minecraft:block/stone"},
                "sheen":{"tint":1.0}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/stone.json"));
        assertEquals(1.0f, rule.disney().sheenTint());
        assertTrue(rule.disney().absent(), "sheen tint without sheen amount expresses nothing");
    }

    @Test
    void parsesTheDisneyBlocks() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":2,"match":{"sprite":"minecraft:block/oak_leaves"},
                "sheen":{"amount":0.4,"tint":0.8},
                "clearcoat":{"amount":0.25,"gloss":0.7},
                "specular":{"tint":0.3},
                "anisotropy":{"amount":-0.5},
                "subsurface":{"weight":0.9,"phase":0.6,"radius":[0.5,0.3,0.2]}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/leaves.json"));
        RtMaterialDesc.Disney d = rule.disney();
        assertEquals(0.4f, d.sheen());
        assertEquals(0.8f, d.sheenTint());
        assertEquals(0.25f, d.clearcoat());
        assertEquals(0.7f, d.clearcoatGloss());
        assertEquals(0.3f, d.specularTint());
        assertEquals(-0.5f, d.anisotropy());
        assertEquals(0.9f, d.subsurfaceWeight());
        assertEquals(0.6f, d.subsurfacePhaseG());
        assertEquals(0.5f, d.subsurfaceRadiusR());
        assertFalse(d.absent());

        // Replaces rather than multiplies: nothing in any source format supplies a value to scale.
        assertEquals(d, rule.apply(neutralBase()).disney());
    }

    @Test
    void formatThreeParsesIndependentWeatherLanesAndOlderFormatsCannotClaimThem() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":3,"match":{"sprite":"minecraft:block/stone"},
                "weather":{"absorption":0.8,"film_retention":0.25}}
                """).getAsJsonObject(), Identifier.parse("test:weather.json"));
        assertEquals(0.8f, rule.weather().absorption());
        assertEquals(-1f, rule.weather().darkening());
        assertEquals(0.25f, rule.weather().filmRetention());
        RtMaterialDesc.Weather inherited = new RtMaterialDesc.Weather(-1f, 0.4f, -1f, 0.7f);
        RtMaterialDesc base = neutralBaseWithWeather(inherited);
        assertEquals(new RtMaterialDesc.Weather(0.8f, 0.4f, 0.25f, 0.7f), rule.apply(base).weather());

        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("""
                        {"format":2,"match":{"sprite":"minecraft:block/stone"},
                        "weather":{"absorption":0.5}}
                        """).getAsJsonObject(), Identifier.parse("test:old-weather.json")));
    }

    @Test
    void rejectsOutOfRangeDisneyParameters() {
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("""
                        {"format":2,"match":{"sprite":"minecraft:block/stone"},
                        "anisotropy":{"amount":2.0}}
                        """).getAsJsonObject(), Identifier.parse("test:m.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("""
                        {"format":2,"match":{"sprite":"minecraft:block/stone"},
                        "subsurface":{"weight":0.5,"radius":[1.0,1.0]}}
                        """).getAsJsonObject(), Identifier.parse("test:m.json")));
    }

    private static RtMaterialDesc neutralBase() {
        return neutralBaseWithWeather(RtMaterialDesc.Weather.NONE);
    }

    private static RtMaterialDesc neutralBaseWithWeather(RtMaterialDesc.Weather weather) {
        return new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE, RtMaterialDesc.Source.LAB_PBR,
                RtMaterialRegistry.FEATURE_SPEC, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f,
                RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.Disney.NONE, weather);
    }

    @Test
    void parsesVersionedExtensibleMaterialProperties() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"block":"minecraft:blue_stained_glass",
                "sprite":"minecraft:block/blue_stained_glass"},"model":"dielectric",
                "base":{"roughness":0.06,"metalness":0.0},
                "emission":{"strength":2.0,"color_source":"albedo"},
                "transmission":{"factor":1.0,"ior":1.52}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/glass.json"));
        assertEquals(Identifier.parse("minecraft:block/blue_stained_glass"), rule.sprite());
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, rule.model());
        assertEquals(1.52f, rule.ior());
        assertEquals(2.0f, rule.emissionStrength());
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.LAB_PBR, RtMaterialRegistry.FEATURE_SPEC,
                0.8f, 0.0f, 1.0f, 0.0f, RtMaterialDesc.EmissionSource.LAB_PBR,
                5.0f, new RtMaterialDesc.EmissionSummary(0.2f, 0.1f, 0.05f, 0.1f, 0.5f),
                RtMaterialDesc.Disney.NONE);
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialDesc.Source.OVERRIDE, applied.source());
        // emission.strength is a multiplier on the already-resolved strength: it scales LabPBR's own
        // emission rather than replacing it, so the source and summary stay exactly base's.
        assertEquals(RtMaterialDesc.EmissionSource.LAB_PBR, applied.emissionSource());
        assertEquals(10.0f, applied.emissionStrength());
        assertEquals(base.emissionSummary(), applied.emissionSummary());
        assertEquals(0.06f, applied.roughness());
        assertEquals(1.0f, applied.transmission());
    }

    @Test
    void transmissionIorOverridesTheBuiltInIndex() {
        RtMaterialDesc glassBase = new RtMaterialDesc(RtMaterialRegistry.MODEL_DIELECTRIC,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.0025f, 0.0f, RtDielectrics.GLASS_IOR, 1.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.Disney.NONE);
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/crystal"},
                "model":"dielectric","transmission":{"ior":2.417}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/crystal.json"));
        RtMaterialDesc applied = rule.apply(glassBase);
        assertEquals(RtMaterialRegistry.MODEL_DIELECTRIC, applied.model());
        assertEquals(2.417f, applied.ior());

        // Omitting ior on a rule that does not change the model leaves the base index alone.
        var silent = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/crystal"},"base":{"roughness":0.5}}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/crystal.json"));
        assertEquals(RtDielectrics.GLASS_IOR, silent.apply(glassBase).ior());
    }

    @Test
    void waterModelKeepsItsOwnIndexWhenSelectedByName() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"somemod:block/pool"},"model":"water"}
                """).getAsJsonObject(), Identifier.parse("test:fluorite/materials/pool.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.Disney.NONE);
        RtMaterialDesc applied = rule.apply(base);
        assertEquals(RtMaterialRegistry.MODEL_WATER, applied.model());
        assertEquals(RtDielectrics.WATER_IOR, applied.ior());
        assertEquals(1.0f, applied.transmission());
    }

    @Test
    void emissionStrengthCannotForceEmissionOntoANonEmissiveMaterial() {
        var rule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:block/stone"},
                "emission":{"strength":5.0}}
                """).getAsJsonObject(), Identifier.parse("test:boost.json"));
        RtMaterialDesc base = new RtMaterialDesc(RtMaterialRegistry.MODEL_OPAQUE,
                RtMaterialDesc.Source.HEURISTIC, 0, 0.8f, 0.0f, 1.0f, 0.0f,
                RtMaterialDesc.EmissionSource.NONE, 0.0f, RtMaterialDesc.EmissionSummary.NONE, RtMaterialDesc.Disney.NONE);

        RtMaterialDesc applied = rule.apply(base);

        assertEquals(RtMaterialDesc.EmissionSource.NONE, applied.emissionSource());
        assertEquals(0.0f, applied.emissionStrength());
    }

    @Test
    void rejectsUnknownVersionsAndOutOfRangePhysicalValues() {
        // A version this build does not know about. Anything up to FORMAT is accepted, because an older
        // file still means what it meant — see formatOneStaysLoadableAndAuthorsNoDisneyParameters.
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":99,\"match\":{\"sprite\":\"minecraft:block/stone\"}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":0,\"match\":{\"sprite\":\"minecraft:block/stone\"}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
        assertThrows(IllegalArgumentException.class, () -> RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"base\":{\"metalness\":2}}")
                        .getAsJsonObject(), Identifier.parse("test:bad.json")));
    }

    @Test
    void clampsOutOfRangeEmissionStrengthInsteadOfThrowing() {
        var rule = RtMaterialOverrides.parse(
                JsonParser.parseString("{\"format\":1,\"match\":{\"sprite\":\"minecraft:block/stone\"},"
                        + "\"emission\":{\"strength\":5.1}}")
                        .getAsJsonObject(), Identifier.parse("test:clamp.json"));
        assertEquals(5.0f, rule.emissionStrength());
    }

    @Test
    void spriteWideRulesApplyToCompiledEntityResources() {
        var entityRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie"},
                "base":{"roughness":0.7}}
                """).getAsJsonObject(), Identifier.parse("test:entity.json"));
        var blockRule = RtMaterialOverrides.parse(JsonParser.parseString("""
                {"format":1,"match":{"sprite":"minecraft:entity/zombie/zombie",
                "block":"minecraft:stone"}}
                """).getAsJsonObject(), Identifier.parse("test:block.json"));

        assertTrue(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
        assertFalse(entityRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/husk")));
        assertFalse(blockRule.matchesEntity(Identifier.parse("minecraft:entity/zombie/zombie")));
    }
}
