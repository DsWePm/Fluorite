package io.github.dswepm.fluorite.rt.material;

import com.google.gson.JsonParser;
import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.rt.light.RtEmitterTint;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-material emission colour temperature, and the three things about it that are easy to get backwards.
 *
 * <p>It <b>replaces</b> the global setting rather than multiplying it, because two colour temperatures
 * multiplied together are not a colour temperature. It is applied <b>after</b> overrides, because tinting
 * before would leave an authored temperature recolouring a colour that had already been recoloured. And an
 * authored <b>0 is not "unset"</b> — it is how an emitter that is nothing like an incandescent body says
 * so against a global setting that would otherwise recolour it.
 */
final class RtEmissionTemperatureContractTest {

    private static final RtMaterialDesc.EmissionSummary WARM_WHITE =
            new RtMaterialDesc.EmissionSummary(1.0f, 1.0f, 1.0f, 4.0f, 0.5f);

    /** The tint moves colour and nothing else, so strength stays the only brightness control. */
    @Test
    void tintingChangesColourButNeitherLuminanceNorCoverage() {
        RtMaterialDesc.EmissionSummary tinted = RtEmitterTint.tint(WARM_WHITE, 1800, true);
        assertEquals(WARM_WHITE.integratedLuminance(), tinted.integratedLuminance());
        assertEquals(WARM_WHITE.coverage(), tinted.coverage());
        assertTrue(tinted.averageR() > tinted.averageB(), "1800K must come out warm");
        // Rec.709 over the averages is unchanged, which is exactly why the proposal density — which
        // reconstructs a light's power from that luminance — never notices any of this.
        double before = 0.2126 * WARM_WHITE.averageR() + 0.7152 * WARM_WHITE.averageG()
                + 0.0722 * WARM_WHITE.averageB();
        double after = 0.2126 * tinted.averageR() + 0.7152 * tinted.averageG()
                + 0.0722 * tinted.averageB();
        assertEquals(before, after, 1.0e-4);
    }

    /**
     * An authored zero keeps the material's own colour; an unauthored zero inherits the global setting.
     *
     * <p>Collapsing the two would make it impossible to say "this one is not incandescent" — the emitter
     * would be dragged along by whatever the global slider was set to, with no way out.
     */
    @Test
    void authoredZeroMeansKeepMyOwnColourRatherThanUnset() {
        // The global has to be NON-ZERO for this to prove anything. With it at its default of 0 the two
        // readings of zero coincide and the assertion passes either way — which is exactly what happened:
        // the first version of this test stayed green against a version that inferred "authored" from
        // kelvin > 0, because there was nothing for the inference to get wrong.
        int restore = FluoriteConfig.Rt.Composite.EMITTER_TEMPERATURE_K.value();
        try {
            FluoriteConfig.Rt.Composite.EMITTER_TEMPERATURE_K.set(6500);
            assertSame(WARM_WHITE, RtEmitterTint.tint(WARM_WHITE, 0, true),
                    "an authored 0 must keep the texture's own colour against a global setting");
            assertNotSame(WARM_WHITE, RtEmitterTint.tint(WARM_WHITE, 0, false),
                    "an UNAUTHORED 0 must inherit that same global setting");
        } finally {
            FluoriteConfig.Rt.Composite.EMITTER_TEMPERATURE_K.set(restore);
        }
        // A non-emissive summary is never tinted either: there is no colour to move.
        assertSame(RtMaterialDesc.EmissionSummary.NONE,
                RtEmitterTint.tint(RtMaterialDesc.EmissionSummary.NONE, 1800, true));
    }

    /**
     * Out-of-range temperatures are rejected at parse time rather than silently clamped.
     *
     * <p>Exercised through the parser rather than asserted against its source. The first version of this
     * checked that the range text was present, and stayed green against a version with the NaN and
     * negative checks deleted — the text it was looking for belonged to the half that survived.
     */
    @Test
    void theAuthoredRangeIsEnforcedAndZeroIsInsideIt() {
        assertEquals(1800, parseTemperature("1800"), "an in-range value survives");
        assertEquals(0, parseTemperature("0"), "and so does zero, which is meaningful here");
        assertNull(parseTemperature(null), "omitting the key must stay distinct from writing 0");
        for (String rejected : new String[] {"999", "12001", "-1", "NaN"}) {
            assertThrows(RuntimeException.class, () -> parseTemperature(rejected),
                    "must reject " + rejected);
        }
    }

    private static Integer parseTemperature(String literal) {
        String emission = literal == null ? "{\"strength\":1.0}"
                : "{\"strength\":1.0,\"temperature_k\":" + literal + "}";
        String json = "{\"format\":2,\"match\":{\"sprite\":\"minecraft:block/torch\"},"
                + "\"emission\":" + emission + "}";
        return RtMaterialOverrides.parse(JsonParser.parseString(json).getAsJsonObject(),
                Identifier.parse("test:fluorite/materials/torch.json")).emissionTemperatureK();
    }

    /**
     * The tint is applied after overrides, and only there.
     *
     * <p>variantSummary is the funnel every compiled material's emission passes through, and doing it
     * there would tint before an override could name its own temperature — leaving the authored value
     * recolouring an already-recoloured colour, which is a different colour from the one asked for.
     */
    @Test
    void theTemperatureIsAppliedAfterOverridesNotBeforeThem() throws IOException {
        String registry = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/material/RtMaterialRegistry.java"));
        String variant = between(registry, "private static RtMaterialDesc.EmissionSummary variantSummary(", "\n    }");
        assertFalse(variant.contains("RtEmitterTint"), "variantSummary must hand back the untinted colour");

        // Every place a rule is applied must pair it with the temperature pass, or a material with an
        // override silently keeps the global colour.
        int applies = count(registry, "rule.apply(");
        int tinted = count(registry, "withEmitterTemperature(");
        assertEquals(applies + 1, tinted,
                "every rule.apply must be wrapped, plus the one declaration of the wrapper");
        assertTrue(registry.contains("rule.emissionTemperatureK()"));
    }

    /** Authoring it has to be documented, or it may as well not exist. */
    @Test
    void theFieldIsDocumentedForPackAuthors() throws IOException {
        String doc = source("docs/MATERIAL_FORMAT.md");
        assertTrue(doc.contains("`emission.temperature_k`"), "the field needs a table row");
        assertTrue(doc.contains("\"temperature_k\": 1800"), "and a worked example");
    }

    /** Literal occurrences, so the assertion needs no regex escaping to count a method call. */
    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
    }

    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "not found: " + needle);
        int stop = haystack.indexOf(end, start);
        assertTrue(stop > start, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    private static String code(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (String line : text.split("\n", -1)) {
            int slash = line.indexOf("//");
            out.append(slash >= 0 ? line.substring(0, slash) : line).append('\n');
        }
        return out.toString();
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "repository root not found");
        return Files.readString(root.resolve(relativePath));
    }
}
