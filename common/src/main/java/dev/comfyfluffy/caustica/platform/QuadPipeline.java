package dev.comfyfluffy.caustica.platform;

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

	/** Sprite lookup for an atlas. Implementations should cache per atlas and drop the cache on reload. */
	SpriteLookup spriteLookup(TextureAtlas atlas);

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
}
