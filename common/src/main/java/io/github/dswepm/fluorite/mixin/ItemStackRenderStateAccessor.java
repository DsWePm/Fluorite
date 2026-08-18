package io.github.dswepm.fluorite.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes the layers of a resolved item model so the RT material compile can find out which sprites an
 * ITEM is drawn from, the way {@code RtEmissionSemantics} already does for block states.
 *
 * <p>There is no other way to ask. A block state's sprites come out of the block model dispatcher, but an
 * item's come out of a render state that only ever exists to be submitted, and the layer array is private.
 * The alternative — matching item textures to blocks by registry name — is exactly what this codebase
 * forbids, and it would be wrong anyway for any pack that retextures one and not the other.
 *
 * <p>Why it matters: a light-emitting block whose item has a BESPOKE texture rather than a reused block
 * one puts its held quads on the items atlas, which the material compile never scanned, so the material
 * resolved to a fallback compiled with no emission at all. The lantern is that block, and it was the only
 * one in the game that did not light whoever held it.
 */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] fluorite$layers();

    @Accessor("activeLayerCount")
    int fluorite$activeLayerCount();
}
