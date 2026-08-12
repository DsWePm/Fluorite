package io.github.dswepm.fluorite.fabric;

import com.mojang.blaze3d.vertex.PoseStack;

import io.github.dswepm.fluorite.rt.RtFrameStats;
import io.github.dswepm.fluorite.rt.entity.RtEntityCollectorBase;

import net.fabricmc.fabric.api.client.renderer.v1.mesh.Mesh;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.MeshView;
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhase;

import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.feature.submit.SubmitNode;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.world.item.ItemDisplayContext;

import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Function;

/**
 * The three {@code SubmitNodeCollector} overloads that only exist as Fabric interface injections, plus
 * the mesh walk they share.
 *
 * <p>This could not be a shim over the shared collector, because the <em>live code path differs</em>
 * between loaders rather than just the API. FRAPI overwrites {@code BlockStateModelWrapper.update} so
 * that block-display models emit into the render state's mesh and vanilla {@code modelParts} stays
 * empty — submit then calls the injected overload instead of the vanilla one, and Fabric's own default
 * for it forwards only the (empty) parts list and drops the mesh. Without these overrides such models
 * capture zero quads. On a loader without that overwrite, {@code parts} is populated and the vanilla
 * overload in the base is the one that runs, so this class has no counterpart there.
 */
public final class FabricEntityCollector extends RtEntityCollectorBase {
	private final FabricQuadView view = new FabricQuadView();

	@Override
	public void submitBlockModel(PoseStack poseStack, Function<ChunkSectionLayer, RenderType> renderTypeByLayer,
			boolean hasTranslucency, List<BlockStateModelPart> parts, Mesh mesh,
			int[] tintLayers, int lightCoords, int overlayCoords, int outlineColor) {
		if (capture == null) {
			return;
		}
		if (!parts.isEmpty()) {
			submitBlockModel(poseStack, renderTypeByLayer.apply(ChunkSectionLayer.SOLID), parts, tintLayers,
					lightCoords, overlayCoords, outlineColor);
		}
		addMeshQuads(poseStack, mesh, tintLayers, false);
	}

	/** Fabric item models can carry a mesh besides (or instead of) vanilla baked quads; the injected
	 *  default drops it the same way the block-model overload does. */
	@Override
	public void submitItem(PoseStack poseStack, ItemDisplayContext displayContext, int lightCoords,
			int overlayCoords, int outlineColor, int[] tintLayers, List<BakedQuad> quads,
			MeshView mesh, ItemStackRenderState.FoilType foilType) {
		if (capture == null) {
			return;
		}
		beginSubmittedItem(displayContext);
		try {
			addQuads(poseStack.last().pose(), quads, tintLayers);
			addMeshQuads(poseStack, mesh, tintLayers, true);
		} finally {
			endSubmittedItem();
		}
	}

	@Override
	public <T extends SubmitNode> void submitCustom(SubmitRenderPhase<T> phase, T node) {
	}

	/** Capture a Fabric Renderer API mesh; each quad already carries final atlas UVs. */
	private void addMeshQuads(PoseStack poseStack, MeshView mesh, int[] tintLayers, boolean itemMesh) {
		if (mesh == null || mesh.size() == 0) {
			return;
		}
		Matrix4f pose = poseStack.last().pose();
		int idxStart = capturedIndexCount();
		long started = profileDynamicEntity ? RtFrameStats.FRAME.startStage() : 0L;
		try {
			capture.clearUvRemap();
			mesh.forEach(quad -> {
				view.wrap(quad);
				addQuadView(pose, view, tintLayers, itemMesh, null, null, null, 0f, 0f, 0f);
			});
		} finally {
			RtFrameStats.FRAME.endStage("entity.capture.submit.bakedQuads", started);
			countBakedOutput(idxStart);
		}
	}
}
