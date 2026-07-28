package dev.comfyfluffy.caustica.platform;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Resolves which atlas sprite a quad samples, from its UVs.
 *
 * <p>Fabric supplies this; without it an implementation has to build the same structure by hand — a
 * uniform grid over UV space listing the sprites whose rectangle covers each cell, then a short linear
 * scan of the cell the quad's UV centroid lands in. The sprite list is reachable through the existing
 * {@code TextureAtlasAccessor} mixin.
 *
 * <p>Instances are cached per atlas and invalidated on resource reload.
 */
public interface SpriteLookup {
	TextureAtlasSprite find(RtQuadView quad);
}
