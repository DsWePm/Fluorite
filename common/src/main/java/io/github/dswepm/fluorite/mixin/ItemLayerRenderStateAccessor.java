package io.github.dswepm.fluorite.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The quads of one resolved item layer.
 *
 * <p>The field rather than {@code prepareQuadList()}: that method exists to hand a writer an empty list to
 * fill, so calling it to READ would discard the very quads being asked about.
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerRenderStateAccessor {
    @Accessor("quads")
    List<BakedQuad> fluorite$quads();
}
