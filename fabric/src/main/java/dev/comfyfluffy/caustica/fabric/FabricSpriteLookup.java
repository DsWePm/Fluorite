package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.RtQuadView;
import dev.comfyfluffy.caustica.platform.SpriteLookup;

import net.fabricmc.fabric.api.client.renderer.v1.sprite.SpriteFinder;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Fabric already builds this structure for us; the lookup is a straight delegation.
 *
 * <p>The cast is safe by construction: every {@link RtQuadView} reaching a Fabric {@link SpriteLookup}
 * came out of {@link FabricBlockQuadSource} or the Fabric entity collector, both of which only ever hand
 * out {@link FabricQuadView}. {@code SpriteFinder} needs the FRAPI quad itself, so the wrapper has to be
 * unwrapped rather than re-implemented over the interface.
 */
record FabricSpriteLookup(SpriteFinder finder) implements SpriteLookup {
	@Override
	public TextureAtlasSprite find(RtQuadView quad) {
		return finder.find(((FabricQuadView) quad).delegate());
	}
}
