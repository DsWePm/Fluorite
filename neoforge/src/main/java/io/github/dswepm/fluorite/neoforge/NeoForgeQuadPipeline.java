package io.github.dswepm.fluorite.neoforge;

import io.github.dswepm.fluorite.platform.BlockQuadSource;
import io.github.dswepm.fluorite.platform.QuadPipeline;
import io.github.dswepm.fluorite.platform.SpriteLookup;
import io.github.dswepm.fluorite.rt.entity.RtEntityCollectorBase;
import io.github.dswepm.fluorite.rt.terrain.RtSectionSnapshots;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;

final class NeoForgeQuadPipeline implements QuadPipeline {
	@Override
	public BlockQuadSource newBlockQuadSource() {
		return new NeoForgeBlockQuadSource();
	}

	@Override
	public SpriteLookup spriteLookup(TextureAtlas atlas) {
		return NeoForgeSpriteLookup.INSTANCE;
	}

	@Override
	public SpriteLookup entitySpriteLookup() {
		return NeoForgeSpriteLookup.INSTANCE;
	}

	@Override
	public RtSectionSnapshots.Region newRegion(ClientLevel level, int minSectionX, int minSectionY,
			int minSectionZ, Object[] sections) {
		// No subclass needed: the shared Region is complete here. The two methods the Fabric build has to
		// add are interface injections that do not exist on this loader.
		return new RtSectionSnapshots.Region(level, minSectionX, minSectionY, minSectionZ, sections);
	}

	@Override
	public RtEntityCollectorBase newEntityCollector() {
		return new NeoForgeEntityCollector();
	}
}
