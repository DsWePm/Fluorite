package io.github.dswepm.fluorite.rt.light;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two emitter controls, and the two ways they fail by doing nothing visible.
 *
 * <p>The first is arithmetic: if the temperature tint carried its own luminance, every temperature change
 * would also be a brightness change and the pair could never be calibrated against each other.
 *
 * <p>The second is where the multiply happens. The proposal density reconstructs a light's power as area
 * times luminance and normalises it by an inverse total power the CPU published untinted — so tinting the
 * Light DECODE scales every pdf by the brightness, {@code W = wSum/(M*phat)} divides by it again, and the
 * two cancel exactly. The slider would travel its whole range without moving one pixel. That one is
 * invisible in review and invisible in play, which is why it gets a test rather than a comment.
 */
final class RtEmitterTintTest {

    /** Off has to be exactly one, not approximately one. */
    @Test
    void offIsTheShippedPictureExactly() {
        float[] off = RtEmitterTint.of(1.0f, 0);
        assertEquals(1.0f, off[0]);
        assertEquals(1.0f, off[1]);
        assertEquals(1.0f, off[2]);
    }

    /**
     * The tint's luminance is one at every temperature, so temperature and brightness stay orthogonal.
     *
     * <p>Rec.709 weights, the same ones the shader's {@code luminance()} uses to build the RIS target —
     * "unit luminance" has to mean the same thing to the estimator as it does here.
     */
    @Test
    void everyTemperatureHasUnitLuminanceSoItNeverChangesBrightness() {
        for (int kelvin = RtEmitterTint.MIN_TEMPERATURE_K;
                kelvin <= RtEmitterTint.MAX_TEMPERATURE_K; kelvin += 100) {
            float[] tint = RtEmitterTint.of(1.0f, kelvin);
            double luminance = 0.2126 * tint[0] + 0.7152 * tint[1] + 0.0722 * tint[2];
            assertEquals(1.0, luminance, 1.0e-4, "luminance drifted at " + kelvin + "K");
            assertTrue(tint[0] >= 0.0f && tint[1] >= 0.0f && tint[2] >= 0.0f,
                    "negative channel at " + kelvin + "K");
        }
    }

    /** Brightness scales all three channels equally, whatever the temperature is doing. */
    @Test
    void brightnessScalesTheTintWithoutTurningIt() {
        float[] unit = RtEmitterTint.of(1.0f, 2000);
        float[] doubled = RtEmitterTint.of(2.0f, 2000);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(unit[channel] * 2.0f, doubled[channel], 1.0e-6f);
        }
    }

    /** Warm is warm: a flame temperature must be redder than daylight, or the sign is inverted. */
    @Test
    void lowTemperaturesAreRedderThanHighOnes() {
        float[] flame = RtEmitterTint.of(1.0f, 1800);
        float[] daylight = RtEmitterTint.of(1.0f, 6500);
        assertTrue(flame[0] / flame[2] > daylight[0] / daylight[2],
                "1800K must be redder relative to blue than 6500K");
        // And near daylight the tint is close to neutral, since sRGB's white point is D65.
        float[] d65 = RtEmitterTint.of(1.0f, 6500);
        for (int channel = 0; channel < 3; channel++) {
            assertEquals(1.0f, d65[channel], 0.1f, "6500K should be near neutral in sRGB");
        }
    }

    /**
     * The tint is applied to outgoing light, never to a decoded Light record.
     *
     * <p>Named functions, because the failure is silent in both directions: tint the decode and the
     * brightness knob cancels itself; tint the storage and ReSTIR's light-identity check compares a tinted
     * stored sample against an untinted re-read record and rejects every one of them.
     */
    @Test
    void theTintIsAppliedWhereRadianceBecomesLightAndNowhereElse() throws IOException {
        String lighting = code(source("shaders/world/lighting.slang"));

        // Applied: the target function, the survivor's extra lobes, and the volume estimator.
        assertTrue(between(lighting, "public float3 evalSampleContrib(", "\n}")
                        .contains("le *= worldPush.emitterTint.xyz;"),
                "the target function is where radiance becomes light");
        assertTrue(between(lighting, "public float3 shadeReservoir(", "\n}")
                        .contains("(s.le * worldPush.emitterTint.xyz)"),
                "the Disney lobes take s.le directly and would otherwise stay untinted");
        assertTrue(between(lighting, "public float3 volumeNee(", "\n}")
                        .contains("(emitter.radiance * worldPush.emitterTint.xyz)"));

        // NOT applied: the decode, or the proposal density that shares its value.
        assertFalse(between(lighting, "public float3 lightRadiance(", "\n}").contains("emitterTint"),
                "tinting the decode makes the brightness slider cancel itself exactly");
        assertFalse(between(lighting, "public float proposalPdf(", "\n}").contains("emitterTint"),
                "the proposal is normalised by an untinted total power");
        assertFalse(between(lighting, "public bool sampleSphereEmitter(", "\n}").contains("emitterTint"),
                "sphere sampling produces geometry and radiance for the same two consumers");

        // NOT applied to what ReSTIR stores, or its light check compares two different quantities.
        String restir = code(source("shaders/world/restir.slang"));
        assertFalse(between(restir, "public PackedReservoir packReservoir(", "\n}").contains("emitterTint"));
        assertFalse(between(restir, "public bool storedLightStillPresent(", "\n}").contains("emitterTint"));

        // The emissive surface's own glow is tinted too, or a torch lights the room warm while looking
        // unchanged itself.
        assertTrue(code(source("shaders/world/world.rgen.slang"))
                        .contains("albedo * emission * worldPush.emitterTint.xyz"));
    }

    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "not found: " + needle);
        int stop = haystack.indexOf(end, start);
        assertTrue(stop > start, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    /** Strip // comments so an assertion cannot be satisfied by the prose that describes the trap. */
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
