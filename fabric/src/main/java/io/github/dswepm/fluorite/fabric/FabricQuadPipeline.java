package io.github.dswepm.fluorite.fabric;

import io.github.dswepm.fluorite.platform.BlockQuadSource;
import io.github.dswepm.fluorite.platform.QuadPipeline;
import io.github.dswepm.fluorite.platform.SpriteLookup;
import io.github.dswepm.fluorite.rt.entity.RtEntityCollectorBase;
import io.github.dswepm.fluorite.rt.terrain.RtSectionSnapshots;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;

final class FabricQuadPipeline implements QuadPipeline {
	@Override
	public BlockQuadSource newBlockQuadSource() {
		return new FabricBlockQuadSource();
	}

	@Override
	public SpriteLookup spriteLookup(TextureAtlas atlas) {
		// Fabric caches the finder on the atlas itself and rebuilds it on reload, so there is nothing to
		// cache here — the call is what the terrain dispatch used to make directly.
		return new FabricSpriteLookup(atlas.spriteFinder());
	}

	@Override
	public SpriteLookup entitySpriteLookup() {
		return new FabricEntitySpriteLookup();
	}

	@Override
	public RtSectionSnapshots.Region newRegion(ClientLevel level, int minSectionX, int minSectionY,
			int minSectionZ, Object[] sections) {
		return new FabricRegion(level, minSectionX, minSectionY, minSectionZ, sections);
	}

	@Override
	public RtEntityCollectorBase newEntityCollector() {
		return new FabricEntityCollector();
	}
}
