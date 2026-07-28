package dev.comfyfluffy.caustica.platform;

/**
 * Everything the shared code needs from the mod loader underneath it.
 *
 * <p>The surface is deliberately tiny. It carries no event layer: the three Fabric event registrations
 * the client used to make (client tick, client stopping, render-state invalidation) are all served by
 * mixins the mod already owns — {@code MinecraftMixin} on {@code Minecraft.tick}/{@code close} and
 * {@code LevelExtractorMixin} on {@code allChanged()} — so they work identically on both loaders with no
 * abstraction at all.
 *
 * <p>What remains is paths and, from M2, the geometry pipeline.
 */
public interface PlatformServices {
	PlatformPaths paths();

	/** How geometry gets out of Minecraft's models. The substantial half of this abstraction. */
	QuadPipeline quads();

	/** Loader name, stamped into the frame-stats CSV header so captures from the two are distinguishable. */
	String loaderName();
}
