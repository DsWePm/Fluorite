package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * M29's hand lighting reuses the world's shading machinery through a SECOND pipeline, and reuse across
 * a pipeline boundary is exactly where silent drift lives: the push shape must stay the RT pipeline's
 * (the world modules resolve the shared {@code worldPush} global through it), the bindless binding
 * numbers must stay the entity path's (slot IDs mean textures), the vanilla suppression must ride the
 * collector swap (so an off state is the shipped path byte for byte), and the recorded second spelling
 * of the canonical-page decode must move with rchit's constants when those change.
 *
 * <p>All source-level, per the repo's registry lessons: the failure modes here are invisible at runtime
 * (wrong reuse looks like a dark hand; a missed suppression looks like vanilla+RT double hands) and no
 * automated test runs a game.
 */
final class RtHandLitContractTest {

    @Test
    void theHandSubmitIsCapturedBySwappingTheCollector() throws IOException {
        String mixin = source("common/src/main/java/io/github/dswepm/fluorite/mixin/GameRendererMixin.java");
        // The suppression IS the collector swap: vanilla's storage never receives the hand nodes.
        assertTrue(mixin.contains("RtHandCapture.INSTANCE().collector()"),
                "hand submit must route into RtHandCapture's collector or the vanilla hand draws double");
        assertTrue(mixin.contains("RtHandCapture.INSTANCE().beginSubmit()"));
        assertTrue(mixin.contains("RtHandCapture.INSTANCE().endSubmit("));
        // The capture must consume the camera's world->view rotation, or every world-space light lands
        // rotated with the view.
        assertTrue(mixin.contains("viewRotationMatrix"));
        // And the projection capture hooks renderLevel's SECOND getBuffer (the hand/3D-HUD projection).
        assertTrue(mixin.contains("ordinal = 1"));
        // Per-frame reset: a stale projection from a menu frame must never project a gameplay hand.
        assertTrue(mixin.contains("RtHandCapture.INSTANCE().beginFrame()"));
        // The draw happens inside the redirect window, where the overlay is the live target.
        assertTrue(mixin.contains("RtHandFeature.INSTANCE().draw()"));
    }

    @Test
    void theFragmentSharesTheRtPushAndBindingSpace() throws IOException {
        String frag = source("shaders/overlay/hand_lit.frag.slang");
        // The world modules resolve worldPush from the RT push shape; a private push here would break
        // every import silently at the first missing binding.
        assertTrue(frag.contains("import world_core;"));
        assertTrue(frag.contains("pc.worldPushAddr"));
        // Bindless arrays keep the entity path's set-1 binding numbers, so slot IDs mean the same
        // textures the descriptor mirror wrote.
        assertTrue(frag.contains("[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];"));
        assertTrue(frag.contains("[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];"));
        assertTrue(frag.contains("[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];"));
        assertTrue(frag.contains("[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];"));
        // The sky term is the one shared consumer, not a second spelling of the sector arithmetic.
        assertTrue(frag.contains("volumeSkySurfaceIrradiance"));
        String visibility = source("shaders/world/volume_visibility.slang");
        assertTrue(visibility.contains("public float3 volumeSkySurfaceIrradiance(float3 p, float3 n)"));
    }

    @Test
    void theRecordedSecondSpellingTracksRchitsDecode() throws IOException {
        String frag = source("shaders/overlay/hand_lit.frag.slang");
        String rchit = source("shaders/world/world.rchit.slang");
        // The canonical-page decode is duplicated by parallel-track necessity (rchit is M30's file this
        // milestone); the two spellings must move together. These are the lanes whose meaning silently
        // changed once already (the JSON-over-texture precedence), so pin them against rchit's text.
        for (String constant : new String[] {
                "MATERIAL_FEATURE_ROUGHNESS_AUTHORED", "MATERIAL_FEATURE_METALNESS_AUTHORED",
                "MATERIAL_EMISSION_STRENGTH_SHIFT", "MATERIAL_MAX_EMISSION_STRENGTH"}) {
            assertTrue(frag.contains(constant), "hand decode missing " + constant);
            assertTrue(rchit.contains(constant), "rchit decode missing " + constant);
        }
        // The glint constants were copied, not imported: values must match rchit's definitions.
        for (String constant : new String[] {
                "GLINT_TINT", "GLINT_PHASE_AXIS", "GLINT_TINT_WEIGHT", "GLINT_EMISSION", "GLINT_RATE"}) {
            assertTrue(frag.contains(constant));
            assertTrue(rchit.contains(constant));
        }
        assertTrue(frag.contains("KNOWN SECOND SPELLING"));
    }

    @Test
    void theAttributionColumnsAreRegistered() throws IOException {
        // D218's rule: a FRAME.count name that is not in the registry throws at runtime, mid-frame, on
        // the first frame the switch flips. The registry test scans the call sites; this pins the pair
        // from the other end, so removing a call without removing its registration also fails.
        String stats = source("common/src/main/java/io/github/dswepm/fluorite/rt/RtFrameStats.java");
        assertTrue(stats.contains("\"handQuads\""));
        assertTrue(stats.contains("\"handDraws\""));
        String capture = source("common/src/main/java/io/github/dswepm/fluo\u0072ite/rt/entity/RtHandCapture.java");
        assertTrue(capture.contains("FRAME.count(\"handQuads\""));
        String feature = source("common/src/main/java/io/github/dswepm/fluorite/rt/overlay/RtHandFeature.java");
        assertTrue(feature.contains("FRAME.count(\"handDraws\""));
    }

    @Test
    void theSwitchDefaultsOffAndGatesTheWholePath() throws IOException {
        String config = source("common/src/main/java/io/github/dswepm/fluorite/FluoriteConfig.java");
        // Off = the shipped hand, untouched: the gate sits at capture, so an off state submits nothing.
        assertTrue(config.contains("bool(\"fluorite.rt.composite.handRtLighting\", \"composite.hand-rt-lighting\", false)"));
        String feature = source("common/src/main/java/io/github/dswepm/fluorite/rt/overlay/RtHandFeature.java");
        assertTrue(feature.contains("FluoriteConfig.Rt.Composite.HAND_RT_LIGHTING.value()"));
        assertTrue(feature.contains("RtComposite.INSTANCE.currentTlasHandle() != 0L"));
    }

    private static String source(String relativePath) throws IOException {
        java.nio.file.Path root = java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from " + System.getProperty("user.dir"));
        }
        return String.join("\n", java.nio.file.Files.readAllLines(root.resolve(relativePath)));
    }
}
