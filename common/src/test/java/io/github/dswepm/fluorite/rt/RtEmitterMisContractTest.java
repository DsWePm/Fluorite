package io.github.dswepm.fluorite.rt;

import io.github.dswepm.fluorite.FluoriteConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M24 S4: the emitter estimator and BSDF sampling weighed against each other instead of gated.
 *
 * <p>The 1/0 gate that stood here claimed the previous vertex's RIS covered the diffuse continuation and
 * that specular bounces were therefore free to gather the emitter in full. The first half was true. The
 * second was not — the RIS target function evaluates the diffuse AND specular lobes, so an emitter reached
 * by a specular bounce was counted twice, and the alpha floor is most likely why nobody ever saw it as a
 * doubling: it made the RIS half a blurred version of the sharp highlight rather than a copy of it.
 *
 * <p>Every assertion here guards something that stays a lit picture when it breaks.
 */
final class RtEmitterMisContractTest {

    /** End of a top-level Slang function body. */
    private static final String CLOSE = "\n}";

    /**
     * Both sides of the weight are built from ONE pair of functions.
     *
     * <p>A MIS weight is unbiased for any pair of positive functions, and biased the moment the two sides
     * disagree about what those functions are. Two spellings that match today are exactly how they stop
     * matching later, so the pair exists as named functions that every site reaches for.
     */
    @Test
    void oneDensityPairServesEverySite() throws IOException {
        String lighting = code(source("shaders/world/lighting.slang"));
        String rgen = code(source("shaders/world/world.rgen.slang"));
        String bsdf = code(source("shaders/world/bsdf.slang"));

        assertTrue(lighting.contains("public float emitterAreaDensity(float3 le)"));
        assertTrue(lighting.contains("public float bsdfAreaDensity(float solidAnglePdf,"));
        assertTrue(bsdf.contains("public float bsdfContinuationPdf(BsdfContext c, float3 wi,"));

        // The sun's NEE used to spell its own copy of the continuation density inline. It shares now.
        assertTrue(rgen.contains("float pdfB = bsdfContinuationPdf(bc, lightDir, pf, ps, walkSssShare);"));
        assertFalse(rgen.contains("float diffuseShare = baseShare * (1.0 - ps) * (1.0 - walkSssShare);"),
                "the inline copy must be gone, not merely joined by a shared one");

        assertTrue(lighting.contains("powerHeuristic(emitterAreaDensity("));
        assertTrue(rgen.contains("emitterAreaDensity(emitted)"));
        assertTrue(lighting.contains("bsdfAreaDensity(") && rgen.contains("bsdfAreaDensity(bsdfPdf,"));
    }

    /**
     * The area cancels, which is what makes the emitter density computable where no light is identified.
     *
     * <p>A BSDF ray knows it hit something emissive and nothing about which Light record that is. The
     * proposal picks a light in proportion to area times luminance and then a point on it with density one
     * over that same area, so the joint density of a point is its radiance times the inverse total power —
     * and the extent that could not have been looked up drops out of both factors at once.
     */
    @Test
    void theEmitterDensityNeedsNoLightIdentity() throws IOException {
        String density = between(code(source("shaders/world/lighting.slang")),
                "public float emitterAreaDensity(", CLOSE);
        assertTrue(density.contains("luminance(le) * worldPush.lightRebase.w"));
        assertFalse(density.contains("area"), "an area here would mean the cancellation was not taken");
        assertFalse(density.contains("lightBufAddr"), "nothing may be looked up: the hit has no index");
    }

    /**
     * A proxied emitter is partitioned, never weighed.
     *
     * <p>A dynamic sphere stands in for a mesh the BSDF ray actually hits. The two strategies sample
     * different domains, so their densities never meet, and weights built from them would not sum to one —
     * a quiet energy error rather than a visible one. Both the raygen and the reservoir shading have to
     * make that exclusion, because either one alone leaves the other side weighted.
     */
    @Test
    void proxiedEmittersArePartitionedAndNotWeighted() throws IOException {
        String rgen = code(source("shaders/world/world.rgen.slang"));
        String shade = between(code(source("shaders/world/lighting.slang")),
                "public float3 shadeReservoir(", CLOSE);

        // The proxy check reaches the partition branch; the isolation switch shares that branch, which is
        // why this asserts the proxy is IN the condition rather than that it is the whole of it.
        assertTrue(rgen.contains("if (payloadEmitterProxied() || !emitterMisOn()) {"));
        assertTrue(shade.contains("if (emitterMisOn() && !reservoirLightIsDynamic(s.lightIndex)) {"),
                "the reservoir side must exclude the same samples the raygen does");
        assertTrue(shade.contains("float misWeight = 1.0;"),
                "an excluded sample keeps its full weight rather than losing it");
    }

    /**
     * A delta lobe takes the emitter in full.
     *
     * <p>It has no finite density for the emitter strategy to compete with, and the RIS estimator's own
     * specular term vanishes at alpha zero — so a mirror shows the emitter and nothing double counts.
     * Weighing it as though it had a density would darken every mirrored light source.
     */
    @Test
    void aDeltaLobeIsNotWeighed() throws IOException {
        String rgen = code(source("shaders/world/world.rgen.slang"));
        assertTrue(rgen.contains("emitterShare = bsdfPdf < 0.0 ? 1.0"),
                "a negative bsdfPdf is this renderer's delta marker");
    }

    /**
     * The floor is gone from the emitter path whenever the weight is on, and returns with it when it goes.
     *
     * <p>The pair is one decision. A floor on top of a MIS weight blurs a highlight that is no longer at
     * risk of exploding; a weight removed without the floor coming back leaves an unfloored highlight being
     * counted twice, which is a picture that never shipped. Both halves are asserted because either half
     * alone still renders, and renders wrong in a way no probe reports.
     */
    @Test
    void theAlphaFloorTracksTheWeightExactly() throws IOException {
        String lighting = code(source("shaders/world/lighting.slang"));
        String core = code(source("shaders/world/world_core.slang"));
        String rgen = code(source("shaders/world/world.rgen.slang"));

        // ONE predicate drives every floored site in the target function, so the halves cannot drift.
        String eval = between(lighting, "public float3 evalSampleContrib(", CLOSE);
        assertTrue(eval.contains("bool floorAlpha = !emitterMisOn();"));
        assertTrue(eval.contains("rainFilmBrdf(c, wi, floorAlpha)"), "the rain film rides the same flag");
        assertEquals(3, eval.split("UNWEIGHTED_SPEC_ALPHA_FLOOR", -1).length - 1,
                "three floored alphas: both anisotropic ones and the isotropic D");

        // THE ASYMMETRY IS THE SHIPPED BEHAVIOUR. The anisotropic branch floors D and its Smith term; the
        // isotropic branch floors D alone, because masking is within a fraction of a percent of 1 at any
        // alpha this small. Flooring the isotropic Gs as well would be a defensible-looking edit that
        // makes "off" resemble what shipped instead of equalling it.
        assertTrue(eval.contains("Gs = c.g1v * ggxG1Aniso(dot(wi, c.t), dot(wi, c.b), ndl, ax, ay);"));
        assertTrue(eval.contains("Gs = c.g1v * ggxG1(ndl, c.rough);"),
                "the isotropic Smith term takes the UNfloored roughness, as it always has");

        assertTrue(core.contains("UNWEIGHTED_SPEC_ALPHA_FLOOR = 0.02"), "the constant still has a user");
        // The sun keeps it, and only where MIS is switched off — a light of zero angular size has no
        // finite density for anything to compete with, so there is nothing to weigh.
        assertTrue(rgen.contains("floorAlpha ? max(bc.alphaX, UNWEIGHTED_SPEC_ALPHA_FLOOR) : bc.alphaX"));
        assertTrue(rgen.contains("bool floorAlpha = !(misActive || exactSpecular);"));
    }

    /**
     * Iron law 8 for the isolation switch: OFF is the picture that shipped before S4b, not an approximation.
     *
     * <p>Three sites have to move together, and each of them still renders a lit world on its own. The
     * raygen's gate, the reservoir's weight and the target function's floor were one change; a switch that
     * reverts two of the three produces a state that has never existed, which is a worse A/B baseline than
     * no switch at all -- the comparison would attribute a difference to the weight that came from the
     * floor, and nothing would say so.
     *
     * <p>Polarity is asserted too. The bit is raised on the OFF setting so that a zero flags word is the
     * shipped picture; inverted, every code path that forgets to set it would silently ship the old
     * estimator.
     */
    @Test
    void offIsTheEstimatorThatPredatesS4bAtEveryOneOfItsThreeSites() throws IOException {
        String rgen = code(source("shaders/world/world.rgen.slang"));
        String lighting = code(source("shaders/world/lighting.slang"));
        String core = code(source("shaders/world/world_core.slang"));
        String composite = code(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));

        // 1. the raygen falls back to the 1/0 gate -- the same branch the proxied case already takes.
        assertTrue(rgen.contains("if (payloadEmitterProxied() || !emitterMisOn()) {"));
        // 2. the reservoir stops weighing.
        assertTrue(between(lighting, "public float3 shadeReservoir(", CLOSE)
                .contains("if (emitterMisOn() && !reservoirLightIsDynamic(s.lightIndex)) {"));
        // 3. the target function floors again. Asserted in theAlphaFloorTracksTheWeightExactly.
        assertTrue(between(lighting, "public float3 evalSampleContrib(", CLOSE)
                .contains("bool floorAlpha = !emitterMisOn();"));

        // One reader for all three, so the sites cannot come to different answers within a frame.
        assertTrue(core.contains("FLAG_EMITTER_MIS_OFF = 1u << 1u"));
        assertTrue(core.contains("(worldPush.flags & FLAG_EMITTER_MIS_OFF) == 0u"));

        // Raised on the OFF setting: a zero flags word must be the shipped picture.
        assertTrue(composite.contains("if (!FluoriteConfig.Rt.Bsdf.EMITTER_MIS_ENABLED.value()) {"),
                "inverted polarity would ship the pre-S4b estimator wherever the bit is not set");
        assertTrue(composite.contains("flags |= 0b10;"));
        assertTrue(FluoriteConfig.Rt.Bsdf.EMITTER_MIS_ENABLED.defaultValue(),
                "the default is the merged behaviour; OFF is the comparison, not the product");
    }

    /** Ten bits of tangent now, and the layout says where the eleventh went. */
    @Test
    void theTangentGaveUpABitAndTheLayoutSaysWhereItWent() throws IOException {
        String common = code(source("shaders/world/world_common.slang"));
        assertTrue(common.contains("PAYLOAD_TANGENT_SHIFT = 22u"));
        assertTrue(common.contains("PAYLOAD_TANGENT_MASK = 0x3FFu"));
        assertTrue(common.contains("PAYLOAD_TANGENT_STEPS = 1023.0"),
                "shift, mask and step count have to move together or the angle decodes wrong");
        assertTrue(common.contains("PAYLOAD_EMITTER_PROXIED = 1u << 21u"),
                "the freed bit, immediately below the tangent field");
    }

    private static String between(String haystack, String needle, String end) {
        int start = haystack.indexOf(needle);
        assertTrue(start >= 0, "not found: " + needle);
        int stop = haystack.indexOf(end, start);
        assertTrue(stop > start, "unterminated: " + needle);
        return haystack.substring(start, stop);
    }

    /** Strip // comments so an assertion cannot be satisfied by the prose that describes the bug. */
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
