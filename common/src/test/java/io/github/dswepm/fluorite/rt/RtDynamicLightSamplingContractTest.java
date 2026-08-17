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
 * Contracts for M24 S3 — M18's dynamic sphere emitters entering the RIS candidate set.
 *
 * <p>The records have existed since M18 and been read by nothing. Wiring them up crosses two boundaries
 * that both fail silently: a second light buffer whose indices mean something different, and a second
 * coordinate convention. Neither produces an error — they produce a lit picture with the light in the
 * wrong place, or a reservoir validated against a light it never came from.
 */
final class RtDynamicLightSamplingContractTest {

    /** End of a top-level Slang function body: a newline and the closing brace. */
    private static final String CLOSE = "\n}";

    /**
     * THE COORDINATE CONVENTION, which is the one that fails by rendering something plausible.
     *
     * <p>Every position in the static light buffer is in a terrain hierarchy's space and has
     * {@code lightRebase} added on the way out. The dynamic buffer is rebuilt from live entities every
     * frame and is authored in current rebased space already. Adding the offset compiles, runs, and puts
     * every entity's light a rebase distance from the entity — up to the rebase threshold away, which is
     * far enough to look like "dynamic lights don't work" rather than like an offset.
     */
    @Test
    void dynamicEmitterPositionsAreNeverRebasedAgain() throws IOException {
        String lighting = code(source("shaders/world/lighting.slang"));
        String restir = code(source("shaders/world/restir.slang"));
        String sample = between(lighting, "public bool sampleSphereEmitter(", CLOSE);
        String sphereCheck = between(restir, "bool storedSphereStillPresent(", CLOSE);

        assertFalse(sample.contains("lightRebase"),
                "the dynamic buffer is already in current rebased space");
        assertFalse(sphereCheck.contains("lightRebase"),
                "and the check that re-reads it has to agree, or every sphere fails containment");
        assertTrue(sample.contains("float3 toReceiver = receiver - light.pos;"));
        assertTrue(sphereCheck.contains("float3 d = p.lightPosArea.xyz - lg.pos;"));

        // The rectangle path must still rebase, or the two conventions have merely swapped.
        String ris = between(lighting, "public Reservoir risInitial(", CLOSE);
        assertTrue(ris.contains("sp = lg.pos + worldPush.lightRebase.xyz"),
                "static lights still live in hierarchy space");

        // And the declaration says so where someone adding the next buffer will read it.
        assertTrue(source("shaders/world/world_common.slang")
                        .contains("must NOT have lightRebase added"),
                "the convention belongs next to the address it applies to");
    }

    /**
     * Dynamic candidates are held back BEFORE the local/global split, and the mixture is rescaled.
     *
     * <p>Reserving candidates changes what fraction of the budget every other stratum describes. Leaving
     * {@code proposalPdf} unscaled would keep dividing by a density that claims the whole budget while
     * only part of it is drawn that way, which inflates every static light's weight by exactly the
     * fraction handed to entities — a brightening that follows the knob and looks like the entities are
     * what got brighter.
     *
     * <p>The two buffers hold disjoint shapes, so no candidate can be proposed by more than one stratum
     * and each pdf is its own stratum's term. That is what keeps this a scale factor rather than a sum.
     */
    @Test
    void reservingCandidatesRescalesEveryStratumsDensity() throws IOException {
        String ris = between(code(source("shaders/world/lighting.slang")), "public Reservoir risInitial(", CLOSE);

        assertTrue(ris.contains("uint staticCandidateCount = candidateCount - dynamicCandidateCount;"));
        assertTrue(ris.contains("? max(1u, (staticCandidateCount + 2u) / 4u) : staticCandidateCount;"),
                "the local/global split must divide what is LEFT, not the whole budget");
        assertTrue(ris.contains("float(localCandidateCount) / float(staticCandidateCount)"));
        assertTrue(ris.contains("* (float(staticCandidateCount) / float(candidateCount));"),
                "the static density must be scaled by the share actually drawn from it");
        assertTrue(ris.contains("sourcePdf = (float(dynamicCandidateCount) / float(candidateCount))"),
                "and the dynamic one by its own share");

        // One candidate held back at minimum, so a vertex never stops seeing the world it stands in.
        assertTrue(ris.contains("min(worldPush.dynamicRisCandidates, candidateCount - 1u)"));
        // Wave-coherent: both counts are frame constants, so every lane takes the same branch for a given
        // candidate index — the property the existing local/global split was built around.
        assertTrue(ris.contains("if (c < dynamicCandidateCount) {"));
    }

    /**
     * An index alone stopped being enough the moment there were two buffers.
     *
     * <p>Index 7 names a different light in each, and the identity check exists precisely to catch a
     * reservoir whose light is no longer the light it came from. Without a tag it would re-read the wrong
     * buffer, compare against an unrelated emitter, and usually reject — but sometimes accept, which is
     * the failure the check was written to prevent, reintroduced by the fix for something else.
     */
    @Test
    void aStoredLightIndexSaysWhichBufferItCameFrom() throws IOException {
        String lighting = code(source("shaders/world/lighting.slang"));
        String restir = code(source("shaders/world/restir.slang"));

        assertTrue(lighting.contains("RESERVOIR_DYNAMIC_LIGHT_BIT = 0x80000000u"));
        assertTrue(lighting.contains("li |= RESERVOIR_DYNAMIC_LIGHT_BIT;"),
                "a dynamic candidate must tag its index when it wins the reservoir");

        String check = between(restir, "public bool storedLightStillPresent(", CLOSE);
        assertTrue(check.contains("uint index = reservoirLightSlot(stored);"),
                "the tag must be stripped before either buffer is indexed");
        assertTrue(check.contains("if (reservoirLightIsDynamic(stored)) {"));
        assertTrue(check.contains("index >= worldPush.dynamicLightCount"));
        assertTrue(check.contains("ConstPtr<Light>(worldPush.dynamicLightAddr)[index]"));
        // Shape disagreement rejects on both paths: a slot that changed shape is not the stored light.
        assertTrue(check.contains("if (!lightIsSphere(lg)) {"));
        assertTrue(check.contains("if (lightIsSphere(lg)) {"));
        // The sentinel must not be mistaken for a tagged index — it sets that bit and every other one.
        assertTrue(lighting.contains("index != RESERVOIR_NO_LIGHT && (index & RESERVOIR_DYNAMIC_LIGHT_BIT)"));

        String sphereCheck = between(restir, "bool storedSphereStillPresent(", CLOSE);
        assertTrue(sphereCheck.contains("lightRadiance(lg)"), "radiance must be compared");
        assertTrue(sphereCheck.contains("lightSphereRadius(lg)"), "and the radius, through the stored area");
        assertTrue(sphereCheck.contains("abs(dot(d, d) - radius * radius)"),
                "the stored point must still be ON the sphere — the only test a light that MOVED fails");
    }

    /**
     * The visible hemisphere, in the area measure the target function already speaks.
     *
     * <p>Sampling the whole sphere would put half of every dynamic candidate on the far side, where the
     * emitter cosine zeroes it — inside the loop that measurement identified as the expensive one. The
     * hemisphere wastes none and needs no second pdf path, because uniform over 2*pi*r^2 with density
     * 1/(2*pi*r^2) is exactly what evalSampleContrib's area argument means.
     */
    @Test
    void sphereSamplingCoversTheFacingHalfInTheAreaMeasure() throws IOException {
        String sample = between(code(source("shaders/world/lighting.slang")),
                "public bool sampleSphereEmitter(", CLOSE);

        assertTrue(sample.contains("area = 6.28318530718 * radius * radius;"),
                "2*pi*r^2 — the hemisphere, matching what is actually sampled");
        // z uniform in [0,1] is what makes it uniform in AREA rather than crowding the pole; a cosine or
        // an angle-uniform draw would need a different density and would bias the estimate as written.
        assertTrue(sample.contains("float z = rndf(seed);"));
        assertTrue(sample.contains("float r = sqrt(max(0.0, 1.0 - z * z));"));
        assertTrue(sample.contains("sp = light.pos + lnrm * radius;"),
                "the point goes on the surface, so the stored area pins the radius");
        // A receiver inside the emitter has no facing half; there is nothing to sample and no axis to
        // build one around, and normalising that would hand NaN to the target function.
        assertTrue(sample.contains("dist2 <= radius * radius"));
    }

    /**
     * Off is off (iron law 8), and it is off bit for bit.
     *
     * <p>With no candidates reserved, {@code staticCandidateCount} is the whole budget, the split is the
     * one that shipped, and the static density is multiplied by 1.0 — which IEEE leaves exactly alone.
     * The random stream is untouched too: the dynamic branch consumes nothing when it never runs.
     */
    @Test
    void zeroDynamicCandidatesIsTheBehaviourThatPredatesThem() throws IOException {
        String ris = between(code(source("shaders/world/lighting.slang")), "public Reservoir risInitial(", CLOSE);

        // The stratum is skipped entirely when there is nothing to draw from, so an empty buffer cannot
        // consume candidates and hand back nothing.
        assertTrue(ris.contains("worldPush.dynamicLightCount > 0u && worldPush.dynamicLightAddr != 0"));
        assertTrue(ris.contains(": 0u;"), "no dynamic lights means no dynamic stratum");

        assertEquals(0, FluoriteConfig.Rt.Composite.DYNAMIC_RIS_CANDIDATES.defaultValue().intValue(),
                "shipped off, or the M18 picture is not reproducible");

        // And the publish side must not reserve candidates against an empty buffer either, which would
        // read as "RIS got worse" rather than as "the setting did nothing".
        assertTrue(source("common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java")
                        .contains("fe.dynamicLightCount() > 0"),
                "the knob must be gated on there being something to propose");
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
