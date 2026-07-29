package dev.comfyfluffy.caustica.fabric;

import dev.comfyfluffy.caustica.platform.RtQuadView;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;

/**
 * Adapts a Fabric Renderer API quad to {@link RtQuadView}. Every accessor forwards directly — FRAPI is
 * where this interface's shape came from, so there is nothing to reconcile on this side.
 *
 * <p>Reused: {@link #wrap} retargets it per quad rather than allocating. {@link FabricSpriteLookup} pulls
 * the wrapped view back out, which is why {@link #delegate()} exists.
 */
final class FabricQuadView implements RtQuadView {
	private QuadView quad;

	void wrap(QuadView quad) {
		this.quad = quad;
	}

	QuadView delegate() {
		return quad;
	}

	@Override
	public float x(int vertexIndex) {
		return quad.x(vertexIndex);
	}

	@Override
	public float y(int vertexIndex) {
		return quad.y(vertexIndex);
	}

	@Override
	public float z(int vertexIndex) {
		return quad.z(vertexIndex);
	}

	@Override
	public float u(int vertexIndex) {
		return quad.u(vertexIndex);
	}

	@Override
	public float v(int vertexIndex) {
		return quad.v(vertexIndex);
	}

	@Override
	public int color(int vertexIndex) {
		return quad.color(vertexIndex);
	}

	@Override
	public int tintIndex() {
		return quad.tintIndex();
	}

	@Override
	public ChunkSectionLayer chunkLayer() {
		return quad.chunkLayer();
	}

	@Override
	public boolean emissive() {
		return quad.emissive();
	}

	@Override
	public Identifier atlasTextureLocation() {
		return quad.atlas().getTextureLocation();
	}

	@Override
	@Nullable
	public RenderType itemRenderType() {
		return quad.itemRenderType();
	}
}
