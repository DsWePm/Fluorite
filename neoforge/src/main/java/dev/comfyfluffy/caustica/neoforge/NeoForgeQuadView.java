package dev.comfyfluffy.caustica.neoforge;

import dev.comfyfluffy.caustica.platform.RtQuadView;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;
import org.joml.Vector3fc;

/**
 * Adapts a vanilla {@link BakedQuad} to {@link RtQuadView}.
 *
 * <p>This turned out to be a much thinner adapter than expected. The abstraction was designed around two
 * fears — that vanilla exposes render types per model part rather than per quad, and that it has no
 * per-quad emissive at all — and in 26.2 neither holds: {@code BakedQuad.MaterialInfo} carries
 * {@code layer()}, {@code lightEmission()}, {@code tintIndex()} and the {@code sprite()} itself, all per
 * quad. Nothing here has to be reconstructed or approximated.
 *
 * <p>Reused per quad; {@link #wrap} retargets it.
 */
final class NeoForgeQuadView implements RtQuadView {
	/** Vanilla baked quads carry no per-vertex colour, so albedo is unmodulated before the biome tint. */
	private static final int WHITE = 0xFFFFFFFF;

	private BakedQuad quad;

	void wrap(BakedQuad quad) {
		this.quad = quad;
	}

	TextureAtlasSprite sprite() {
		return quad.materialInfo().sprite();
	}

	@Override
	public float x(int vertexIndex) {
		return quad.position(vertexIndex).x();
	}

	@Override
	public float y(int vertexIndex) {
		return quad.position(vertexIndex).y();
	}

	@Override
	public float z(int vertexIndex) {
		return quad.position(vertexIndex).z();
	}

	@Override
	public float u(int vertexIndex) {
		// UVs are packed two floats to a long, u in the high word. Same unpacking RtEntityCapture already
		// does for the baked-quad entity path.
		return Float.intBitsToFloat((int) (quad.packedUV(vertexIndex) >>> 32));
	}

	@Override
	public float v(int vertexIndex) {
		return Float.intBitsToFloat((int) quad.packedUV(vertexIndex));
	}

	@Override
	public int color(int vertexIndex) {
		return WHITE;
	}

	@Override
	public int tintIndex() {
		return quad.materialInfo().tintIndex();
	}

	@Override
	public ChunkSectionLayer chunkLayer() {
		return quad.materialInfo().layer();
	}

	@Override
	public boolean emissive() {
		// Fabric's flag is "render this quad full-bright". Vanilla's nearest equivalent is a per-quad light
		// emission level, so treat a maxed-out one as the same thing. Anything below that falls through to
		// the block's own light emission at the call site, which is what an unset flag does on Fabric too.
		return quad.materialInfo().lightEmission() >= 15;
	}

	@Override
	public Identifier atlasTextureLocation() {
		TextureAtlasSprite sprite = quad.materialInfo().sprite();
		return sprite == null ? null : sprite.atlasLocation();
	}

	@Override
	@Nullable
	public RenderType itemRenderType() {
		return quad.materialInfo().itemRenderType();
	}
}
