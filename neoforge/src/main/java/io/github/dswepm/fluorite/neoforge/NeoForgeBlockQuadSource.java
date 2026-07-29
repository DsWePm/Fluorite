package io.github.dswepm.fluorite.neoforge;

import io.github.dswepm.fluorite.platform.BlockQuadSource;
import io.github.dswepm.fluorite.platform.RtQuadSink;

import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Vanilla quad source: collect the model's parts, then walk each part's quad lists.
 *
 * <p>Two differences from the Fabric Renderer API path this has to make up for by hand.
 *
 * <p>{@code collectParts} takes only a random source — no view, position, state or cull predicate — so
 * face culling happens here rather than inside the model. A part exposes its quads in seven lists: one
 * per {@link Direction} for quads that a solid neighbour on that side would hide, and one under
 * {@code null} for quads that no neighbour can hide. The null list is therefore never culled, and the
 * six directional lists are skipped whole when the predicate rejects their direction. The predicate
 * keeps the Fabric convention: true means discard.
 *
 * <p>Thread-confined and reusable, matching the contract — the part list and the quad view are both
 * retained across calls so a worker meshing thousands of blocks does not allocate per block.
 */
final class NeoForgeBlockQuadSource implements BlockQuadSource {
	private static final Direction[] DIRECTIONS = Direction.values();

	private final List<BlockStateModelPart> parts = new ArrayList<>();
	private final NeoForgeQuadView view = new NeoForgeQuadView();

	@Override
	public void emit(BlockStateModel model, BlockAndTintGetter level, BlockPos pos, BlockState state,
			RandomSource random, Predicate<Direction> cullTest, RtQuadSink sink) {
		parts.clear();
		model.collectParts(random, parts);
		for (int p = 0; p < parts.size(); p++) {
			BlockStateModelPart part = parts.get(p);
			emitList(part.getQuads(null), sink);
			for (Direction direction : DIRECTIONS) {
				if (cullTest.test(direction)) {
					continue;
				}
				emitList(part.getQuads(direction), sink);
			}
		}
		// Not held across calls: the parts come from the model's own storage and outliving the call would
		// pin geometry for a block that has already been meshed.
		parts.clear();
	}

	private void emitList(List<BakedQuad> quads, RtQuadSink sink) {
		for (int i = 0; i < quads.size(); i++) {
			view.wrap(quads.get(i));
			sink.quad(view);
		}
	}
}
