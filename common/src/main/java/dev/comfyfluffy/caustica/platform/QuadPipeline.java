package dev.comfyfluffy.caustica.platform;

import dev.comfyfluffy.caustica.rt.entity.RtEntityCollectorBase;
import dev.comfyfluffy.caustica.rt.terrain.RtSectionSnapshots;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * How geometry gets out of Minecraft's models and into the mesher. The one part of the platform
 * abstraction with real substance behind it.
 */
public interface QuadPipeline {
	/**
	 * A quad source for the calling thread. The terrain mesher holds one per worker in a ThreadLocal, so
	 * implementations may keep reusable buffers but must not be shared across threads.
	 */
	BlockQuadSource newBlockQuadSource();

	/** Sprite lookup for one atlas. The terrain mesher resolves this once per dispatch and reuses it. */
	SpriteLookup spriteLookup(TextureAtlas atlas);

	/**
	 * Sprite lookup that works for a quad from any atlas, for the entity path — which meets quads from
	 * several atlases and so cannot hoist a single one.
	 *
	 * <p>Separate from {@link #spriteLookup} because the loaders answer it from opposite ends. Fabric has
	 * to find the atlas, then its finder, then search by UV. Vanilla baked quads carry the sprite on the
	 * quad, so there is nothing to search. Making the entity path name an atlas first would demand an
	 * identifier only one of them has.
	 */
	SpriteLookup entitySpriteLookup();

	/**
	 * Build the immutable 3×3×3 snapshot view a tessellation job reads.
	 *
	 * <p>This exists as a platform call for one method. Fabric's block-view API injects
	 * {@code getBiomeFabric} onto {@code BlockAndTintGetter}, and the model code reached through
	 * {@link BlockQuadSource} can call it on the view it is handed — so the Fabric build has to override
	 * it, and both the override and its body name an injected method that does not exist elsewhere. A
	 * subclass is the only way to add a method, hence a factory rather than a flag.
	 */
	RtSectionSnapshots.Region newRegion(ClientLevel level, int minSectionX, int minSectionY,
			int minSectionZ, Object[] sections);

	/**
	 * The entity capture collector.
	 *
	 * <p>Also a subclass rather than a flag, and for a sharper reason than the region: the <em>live code
	 * path</em> differs between loaders, not just the API. Fabric overwrites
	 * {@code BlockStateModelWrapper.update} so block-display models arrive as a mesh through an injected
	 * submit overload with an empty parts list; without that overwrite the same models arrive through the
	 * vanilla overload with parts populated. Both paths exist in the base; which one fires is decided by
	 * the loader, and only the injected overloads need adding.
	 */
	RtEntityCollectorBase newEntityCollector();
}
