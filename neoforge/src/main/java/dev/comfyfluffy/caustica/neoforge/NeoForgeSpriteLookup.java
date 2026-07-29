package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.platform.RtQuadView;
import dev.comfyfluffy.caustica.platform.SpriteLookup;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * There is nothing to look up: a vanilla baked quad already names its sprite.
 *
 * <p>This was budgeted as the largest new piece of work in the port — Fabric supplies a UV-space search
 * structure that vanilla was assumed not to have, so the plan called for rebuilding it: a uniform grid
 * over UV space, a per-cell candidate list, a linear scan of the cell a quad's centroid lands in, and a
 * round-trip unit test over a synthetic atlas. In 26.2 {@code BakedQuad.MaterialInfo} carries the sprite,
 * so none of that is needed.
 *
 * <p>The same instance serves both the per-atlas and the entity-path lookups, since neither consults the
 * atlas it was asked about.
 */
final class NeoForgeSpriteLookup implements SpriteLookup {
	static final NeoForgeSpriteLookup INSTANCE = new NeoForgeSpriteLookup();

	private NeoForgeSpriteLookup() {
	}

	@Override
	public TextureAtlasSprite find(RtQuadView quad) {
		return ((NeoForgeQuadView) quad).sprite();
	}
}
