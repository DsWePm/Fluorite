package dev.comfyfluffy.caustica.platform;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.resources.Identifier;

import org.jetbrains.annotations.Nullable;

/**
 * One quad, as the mesher reads it. Exactly the accessors the terrain and entity capture paths use — no
 * more, so that a second implementation has a closed set of things to be right about.
 *
 * <p>Instances are reused. Read what you need inside the sink call and do not retain the view.
 *
 * <p>Two of these do not survive a change of quad source and are the reason this interface exists at all
 * rather than the code just taking vanilla {@code BakedQuad}s:
 *
 * <ul>
 *   <li>{@link #chunkLayer()} is per-quad here. That is what lets one block model emit a SOLID base and a
 *       CUTOUT tinted overlay, and the coplanar-resolution pass keys on the distinction. Vanilla exposes
 *       render types per <em>part</em>, not per quad, so an implementation over baked quads has to
 *       propagate the part's layer down to every quad it emits.</li>
 *   <li>{@link #emissive()} has no vanilla equivalent whatsoever. Implementations without it return false
 *       and the caller falls back to the block's own light emission.</li>
 * </ul>
 */
public interface RtQuadView {
	float x(int vertexIndex);

	float y(int vertexIndex);

	float z(int vertexIndex);

	float u(int vertexIndex);

	float v(int vertexIndex);

	/** Packed ARGB vertex colour — authored albedo, before any biome tint is applied. */
	int color(int vertexIndex);

	/** Tint index, or negative when this quad takes no biome tint. */
	int tintIndex();

	/** SOLID / CUTOUT / TRANSLUCENT for this quad. See the class note. */
	ChunkSectionLayer chunkLayer();

	/** Whether the quad was authored as full-bright. False on sources that cannot express it. */
	boolean emissive();

	/** Which atlas this quad samples, so the caller can pick the right {@link SpriteLookup}. */
	Identifier atlasId();

	/**
	 * That atlas's texture location — a different identifier from {@link #atlasId()}, and the one the
	 * entity texture registry keys its bindless slots on.
	 */
	Identifier atlasTextureLocation();

	/** Entity path only: the item render type carrying this quad, when there is one. */
	@Nullable
	RenderType itemRenderType();
}
