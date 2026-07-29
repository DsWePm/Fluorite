package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.RtQuadView;
import dev.comfyfluffy.caustica.platform.SpriteLookup;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Entity-path sprite lookup: find the quad's atlas, then its finder, then search by UV.
 *
 * <p>The entity capture meets quads from several atlases, so unlike terrain it cannot hoist one finder
 * for the whole pass. This is the same per-quad work the collector did inline before the abstraction —
 * kept here rather than pushed onto the quad view because it is Fabric's problem specifically: vanilla
 * baked quads carry their sprite and have nothing to look up.
 */
final class FabricEntitySpriteLookup implements SpriteLookup {
	@Override
	public TextureAtlasSprite find(RtQuadView quad) {
		var delegate = ((FabricQuadView) quad).delegate();
		return Minecraft.getInstance().getAtlasManager()
				.getAtlasOrThrow(delegate.atlas().getId())
				.spriteFinder()
				.find(delegate);
	}
}
