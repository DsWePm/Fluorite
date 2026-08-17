package io.github.dswepm.fluorite.rt.material;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A held block's light comes from whichever atlas its ITEM model draws from.
 *
 * <p>Most light-emitting blocks reuse their block texture for the item, so for years every one of them
 * worked and the assumption that a held quad is a block-atlas quad was never contradicted. A lantern has a
 * bespoke {@code item/} texture, which put its quads on the items atlas — an atlas the material compile
 * never scanned, so no material was compiled for those sprites, the resolve fell through to a variant
 * built with {@code EmissionSummary.NONE}, and it became the only block in the game that did not light
 * whoever was holding it. Nothing errored at any point.
 *
 * <p>Three parts have to agree for that to work, and each fails silently on its own: the compile must SEE
 * the sprites, the emission analysis must PERMIT them, and the held-light observer must ACCEPT them.
 */
final class RtItemAtlasEmissionContractTest {

    /**
     * The compile scans both atlases.
     *
     * <p>Scanning is not the same as compiling: a sprite still has to earn a material by having an
     * {@code _s}/{@code _n} sibling or a proven place on something that emits, so an ordinary item costs a
     * map lookup and nothing downstream.
     */
    @Test
    void theMaterialCompileScansTheItemAtlasAsWellAsTheBlockAtlas() throws IOException {
        String materials = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/material/RtBlockMaterials.java"));
        assertTrue(materials.contains("atlasSprites(TextureAtlas.LOCATION_BLOCKS)"));
        assertTrue(materials.contains("atlasSprites(TextureAtlas.LOCATION_ITEMS)"),
                "a sprite this scan never sees gets no material, and no material means no emission");
    }

    /**
     * Item sprites are found by RESOLVING the item model, never by matching names.
     *
     * <p>A pack may retexture the item and not the block, or the reverse. Inferring one atlas's sprite from
     * the other's registry name would be wrong for exactly the packs that do it, and this codebase already
     * holds that line elsewhere — a held item qualifies by block semantics, never by registry name.
     */
    @Test
    void emittingItemSpritesAreDiscoveredByResolvingTheModel() throws IOException {
        String semantics = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/material/RtEmissionSemantics.java"));

        assertTrue(semantics.contains("item instanceof BlockItem blockItem"),
                "only a block's item form can inherit that block's light emission");
        assertTrue(semantics.contains("blockItem.getBlock().defaultBlockState().getLightEmission()"));
        assertTrue(semantics.contains("updateForTopItem(state, new ItemStack(item), ItemDisplayContext.GROUND"),
                "the model has to be resolved to learn which sprites it draws from");
        assertTrue(semantics.contains("fluorite$quads()"), "and its layers walked for their sprites");
        // The same collector both passes feed, so an item sprite and a block sprite are recorded
        // identically and permits() cannot come to different answers about them.
        assertTrue(semantics.contains("collectQuads(quads, light, sprites)"));

        // prepareQuadList() hands a writer an EMPTY list to fill; calling it to read would discard the
        // quads being asked about.
        assertFalse(semantics.contains("prepareQuadList"),
                "reading through prepareQuadList would clear what it is meant to report");
    }

    /**
     * The held-light observer accepts both atlases.
     *
     * <p>This is the gate that actually rejected the lantern, and it rejected all forty-two of its quads
     * before any material was consulted — so fixing the compile alone would have changed nothing.
     */
    @Test
    void theHeldLightObserverAcceptsItemAtlasQuads() throws IOException {
        String collector = code(source(
                "common/src/main/java/io/github/dswepm/fluorite/rt/entity/RtEntityCollectorBase.java"));
        String observer = between(collector, "private void recordHeldLightQuad(", "\n    }");

        assertTrue(observer.contains("TextureAtlas.LOCATION_BLOCKS.equals(sprite.atlasLocation())")
                        && observer.contains("TextureAtlas.LOCATION_ITEMS.equals(sprite.atlasLocation())"),
                "a held block's quads come from whichever atlas its item model uses");
        // Every gate still names itself. The diagnostic is what found this in one run rather than five,
        // and a gate that stops reporting is a gate that hides the next one of these.
        for (String reason : new String[] {"heldLightReject = \"quad is on neither",
                "heldLightReject = \"the resolved material variant is not emissive",
                "heldLightReject = \"emission factor is zero",
                "heldLightReject = \"the emissive coverage of this sprite is zero"}) {
            assertTrue(observer.contains(reason), "missing rejection reason: " + reason);
        }
        assertTrue(observer.contains("heldLightReject = null;"),
                "success must clear the reason, or every held light reports as a failure");
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
