package io.github.dswepm.fluorite.fabric;

import io.github.dswepm.fluorite.platform.BlockQuadSource;
import io.github.dswepm.fluorite.platform.RtQuadSink;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MutableQuadView;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Fabric Renderer API quad source: one reusable {@link QuadEmitter} feeding a reusable view.
 *
 * <p>Thread-confined, matching the contract — the terrain mesher keeps one per worker so the emitter's
 * buffers amortise across sections instead of re-growing per task.
 *
 * <p>{@code emitQuads} is an interface injection onto {@code BlockStateModel}, not a vanilla method. That
 * it compiles here and nowhere else is the whole point of this class.
 */
final class FabricBlockQuadSource implements BlockQuadSource {
	private final FabricQuadView view = new FabricQuadView();
	private final QuadEmitter emitter = Renderer.get().quadEmitter(this::onQuad);
	private RtQuadSink sink;

	private void onQuad(MutableQuadView quad) {
		view.wrap(quad);
		sink.quad(view);
	}

	@Override
	public void emit(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state,
			RandomSource random, Predicate<Direction> cullTest, RtQuadSink sink) {
		this.sink = sink;
		try {
			model.emitQuads(emitter, level, pos, state, random, cullTest);
		} finally {
			// Do not leave a finished job's sink reachable from a pooled per-thread source.
			this.sink = null;
		}
	}
}
