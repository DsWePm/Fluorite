package io.github.dswepm.fluorite.rt.entity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flame you see and the light it casts resolve one material.
 *
 * <p>They used to resolve two. The visible quad went through {@code setSpriteMaterial}, which hardcodes
 * {@code emitting = false}; the M18 side-band sphere light resolved {@code emitting = true} on purpose,
 * because the visible path was wrong and D98A scoped that milestone not to change the picture. Two
 * variant indices, two material ids, one sprite.
 *
 * <p>It was invisible on any pack whose fire sprite carries a LabPBR {@code _s}: {@code variantSummary}
 * keeps {@code entry.emissionSummary()} whenever {@code FEATURE_SPEC} is present, so both variants emit
 * and the split has no symptom. Without one, the non-emitting variant strips
 * {@code FEATURE_HEURISTIC_EMISSION} and compiles to {@code EmissionSummary.NONE} — the quad's emission
 * mask multiplies against zero strength, and a burning entity lights its surroundings with dark flames.
 *
 * <p>Which is the shape §8.9 forbids ReSTIR from being built over: selectable by NEE, not glowing when
 * looked at. Pinned here rather than left to the next reader to re-derive, because the failure needs a
 * particular resource pack to show at all.
 *
 * <p>Structural: resolving a material needs a live atlas and a material snapshot, neither of which the
 * JVM has. What it can check is that both paths still go through the one helper.
 */
final class RtFlameEmissionContractTest {
    @Test
    void theVisibleFlameAndItsLightProxyResolveTheSameMaterial() throws IOException {
        String collector = source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntityCollectorBase.java");

        // One helper, and it asks for the emitting variant -- the one the fire BLOCK resolves to, since
        // fire is a light-15 block and the block path derives emitting from state.getLightEmission().
        assertTrue(collector.contains("private static int flameMaterial(TextureAtlasSprite sprite)"));
        assertTrue(collector.contains(".resolve(sprite, RtMaterials.Profile.DEFAULT, false, true)"));

        // Both callers go through it: the two sprites of the visible quad, and the light proxy.
        assertTrue(collector.contains("int fire0Material = flameMaterial(fire0);"));
        assertTrue(collector.contains("int fire1Material = flameMaterial(fire1);"));
        assertTrue(collector.contains("int materialId = flameMaterial(sprite);"));

        // Exactly one place performs the resolve. A second one is how the two drifted apart before.
        assertEquals(1, count(collector, "Profile.DEFAULT, false, true)"),
                "the emitting fire material must be resolved in exactly one place");

        // And the visible flame must not go back through the generic sprite path, which hardcodes
        // emitting = false and is correct only for sprites whose block state does not emit.
        assertFalse(collector.contains("setSpriteMaterial(fire0"));
        assertFalse(collector.contains("setSpriteMaterial(fire1"));
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return Files.readString(root.resolve(relativePath));
    }
}
