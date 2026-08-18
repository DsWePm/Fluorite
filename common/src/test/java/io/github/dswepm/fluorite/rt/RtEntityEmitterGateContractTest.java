package io.github.dswepm.fluorite.rt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An emissive entity surface and its dynamic sphere proxy are one emitter, and must be counted once.
 *
 * <p>Terrain has had this since RIS landed: a prim in the light buffer sets a payload bit, and the raygen
 * drops its direct-hit emission off diffuse continuation rays because the previous vertex's RIS already
 * sampled it. Entities never reached that path — nothing put them in the light buffer — and the moment
 * M24 S3 began proposing their sphere proxies, every emissive entity surface started being counted twice:
 * once by RIS through the proxy, once by a bounce ray landing on the surface itself.
 *
 * <p>It shows as entities and held torches casting about twice the light they should, which is not
 * obviously wrong to look at, which is why it is a test.
 */
final class RtEntityEmitterGateContractTest {

    /**
     * Only quads that actually fed an accumulator are marked.
     *
     * <p>An entity-wide flag would be simpler and wrong in the harder direction: an emissive surface that
     * produced no sphere would have its emission suppressed with nothing sampling it in exchange, losing
     * the light outright. Per quad, set at the point the accumulator accepts it, keeps the flag and the
     * proxy in exact correspondence.
     */
    @Test
    void onlyQuadsThatFedASphereProxyAreMarked() throws IOException {
        String collector = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntityCollectorBase.java"));

        // Marked immediately before the accumulate, in both observers, so a quad rejected by any of the
        // earlier gates cannot carry the flag.
        assertEquals(2, count(collector, "markEmitterInLightBuffer(primStart);"),
                "both the held-light and flame observers must mark what they accept");
        assertTrue(collector.contains("activeHeldLight.addQuad("));
        assertTrue(collector.contains("flameLight.addQuad("));
        assertTrue(collector.contains("capture.orPrimFlagsFrom(primStart, RtEntityCapture.PRIM_EMITTER_IN_LIGHT_BUFFER)"));

        // Every feed site hands the observer the prim range it produced, or the mark lands nowhere.
        for (String call : new String[] {
                "recordHeldLightQuad(sprite, transmissive, tint, meshX, meshY, meshZ, primStart);",
                "recordHeldLightQuad(sprite, transmissive, color, meshX, meshY, meshZ, primStart);",
                "recordFlameLightQuad(sprite, meshX, meshY, meshZ, flamePrimStart);"}) {
            assertTrue(collector.contains(call), "feed site missing its prim range: " + call);
        }
        // And each range is taken BEFORE the quad it describes is appended. Presence is not enough: a
        // range read after the add covers nothing, marks nothing, and looks identical in a diff — which
        // is exactly what the first version of this assertion failed to notice.
        String needle = "= capture.primSize();";
        assertEquals(3, count(collector, needle), "three feed sites, three ranges");
        for (int at = collector.indexOf(needle); at >= 0; at = collector.indexOf(needle, at + 1)) {
            String rest = collector.substring(at + needle.length()).stripLeading();
            assertTrue(rest.startsWith("capture.add"),
                    "a prim range must be taken immediately before the quad it covers, not after");
        }
    }

    /** The flag reaches the shader through the bit terrain already uses, so the raygen gate is untouched. */
    @Test
    void theEntityFlagFeedsTheSamePayloadBitAsTerrain() throws IOException {
        String chit = code(source("shaders/world/world.rchit.slang"));
        String rgen = code(source("shaders/world/world.rgen.slang"));

        assertTrue(chit.contains("pr.flags & ENTITY_PRIM_EMITTER_IN_LIGHT_BUFFER"));
        assertTrue(chit.contains("pr.flags & TERRAIN_PRIM_IN_LIGHT_BUFFER"));
        assertTrue(chit.contains("payload.flags |= PAYLOAD_EMITTER_IN_LIST;"),
                "terrain raises it alone");
        assertTrue(chit.contains("payload.flags |= PAYLOAD_EMITTER_IN_LIST | PAYLOAD_EMITTER_PROXIED;"),
                "entities raise it together with the proxy tag, which is what tells the two apart");

        // S4 replaced the gate with three cases, and the PROXIED one is still the outright partition:
        // a sphere standing in for a mesh gives the two strategies different domains, so their densities
        // never meet and there is no weight to compute between them.
        assertTrue(rgen.contains("if (payloadEmitterProxied()) {"));
        assertTrue(rgen.contains("emitterShare = showCelestial ? 1.0 : 0.0;"),
                "a proxied emitter keeps the partition S4a introduced");
    }

    /**
     * The entity half is conditional on the proxies actually being proposed.
     *
     * <p>With the dynamic stratum off no sphere is ever sampled, so suppressing the surface would remove
     * that light and put nothing back. Terrain carries no such condition because its emitters live in the
     * buffer RIS always draws from.
     *
     * <p>Read from the push constant rather than WorldPush: the hit shader deliberately never dereferences
     * that struct, and one BDA load per hit to read a bit would cost more than the bit is worth.
     */
    @Test
    void entityEmittersAreGatedOnlyWhileTheirProxiesArePropopsed() throws IOException {
        String chit = code(source("shaders/world/world.rchit.slang"));
        assertTrue(chit.contains("pc.shadeFlags & SHADE_DYNAMIC_EMITTER_RIS"),
                "the entity flag alone is not enough; the stratum has to be on");

        String composite = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/RtComposite.java"));
        assertTrue(composite.contains("DYNAMIC_RIS_CANDIDATES.value() > 0"));
        assertTrue(composite.contains("flags |= 2;"), "SHADE_DYNAMIC_EMITTER_RIS");

        // The two sides of an ABI with no shared constant: one bit, declared twice.
        assertTrue(code(source("shaders/world/world_common.slang"))
                .contains("SHADE_DYNAMIC_EMITTER_RIS = 2u"));
        assertTrue(code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntityCapture.java"))
                .contains("PRIM_EMITTER_IN_LIGHT_BUFFER = 8"));
        assertTrue(code(source("shaders/world/world_common.slang"))
                .contains("ENTITY_PRIM_EMITTER_IN_LIGHT_BUFFER = 8u"));
    }

    private static int count(String haystack, String needle) {
        int total = 0;
        for (int at = haystack.indexOf(needle); at >= 0; at = haystack.indexOf(needle, at + 1)) {
            total++;
        }
        return total;
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
