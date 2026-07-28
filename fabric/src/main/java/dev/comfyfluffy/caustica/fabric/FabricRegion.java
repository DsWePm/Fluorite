package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.rt.terrain.RtSectionSnapshots;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

/**
 * The snapshot view, plus the one method that only exists on Fabric.
 *
 * <p>{@code getBiomeFabric} is injected onto {@code BlockAndTintGetter} by fabric-block-view-api-v2, and
 * the model code reached through {@code emitQuads} can call it on the view it is handed. Both the
 * override and its body name injected members, so neither can live in shared code — and a method cannot
 * be added by configuration, only by a subclass. Hence this class and the factory that builds it.
 *
 * <p>It appears in no import grep of the shared sources, which is why the loader-agnostic check reads
 * bytecode instead.
 */
final class FabricRegion extends RtSectionSnapshots.Region {
	FabricRegion(ClientLevel level, int minSectionX, int minSectionY, int minSectionZ, Object[] sections) {
		super(level, minSectionX, minSectionY, minSectionZ, sections);
	}

	@Override
	public Holder<Biome> getBiomeFabric(BlockPos pos) {
		return level.getBiomeFabric(pos);
	}
}
