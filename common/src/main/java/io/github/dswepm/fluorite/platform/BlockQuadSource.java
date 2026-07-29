package io.github.dswepm.fluorite.platform;

import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Predicate;

/**
 * Turns a block model into quads.
 *
 * <p>Thread-confined and reusable: the terrain mesher keeps one per worker thread in a {@link ThreadLocal}
 * so the emitter's buffers amortise across sections. Implementations may therefore hold mutable state, but
 * must not be shared between threads.
 *
 * <p>{@code cullTest} returns true when the nominal face should be discarded, matching the predicate the
 * Fabric Renderer API already takes.
 */
public interface BlockQuadSource {
	void emit(BlockStateModel model, BlockAndTintGetter view, BlockPos pos, BlockState state,
			RandomSource random, Predicate<Direction> cullTest, RtQuadSink sink);
}
