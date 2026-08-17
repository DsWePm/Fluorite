package io.github.dswepm.fluorite.rt.material;

import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.mixin.ItemLayerRenderStateAccessor;
import io.github.dswepm.fluorite.mixin.ItemStackRenderStateAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Resource-epoch proof that an atlas sprite is used by at least one light-emitting block state. */
public final class RtEmissionSemantics {
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final int VARIANT_PROBES = 8;

    private final Map<TextureAtlasSprite, Integer> maxStateLight;
    private final int emittingStates;
    private final int failedStates;

    private RtEmissionSemantics(Map<TextureAtlasSprite, Integer> maxStateLight,
                                int emittingStates, int failedStates) {
        this.maxStateLight = maxStateLight;
        this.emittingStates = emittingStates;
        this.failedStates = failedStates;
    }

    public static RtEmissionSemantics analyze() {
        IdentityHashMap<TextureAtlasSprite, Integer> sprites = new IdentityHashMap<>();
        int states = 0;
        int failures = 0;
        int blockSprites = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int light = state.getLightEmission();
                if (light <= 0 || state.getRenderShape() != RenderShape.MODEL) continue;
                states++;
                try {
                    collectState(state, light, sprites);
                } catch (Throwable throwable) {
                    failures++;
                    FluoriteMod.LOGGER.debug("Could not analyze emissive material sprites for {}", state, throwable);
                }
            }
            blockSprites = sprites.size();
        }
        int items = 0;
        for (Item item : BuiltInRegistries.ITEM) {
            if (!(item instanceof BlockItem blockItem)) continue;
            int light = blockItem.getBlock().defaultBlockState().getLightEmission();
            if (light <= 0) continue;
            items++;
            try {
                collectItem(item, light, sprites);
            } catch (Throwable throwable) {
                failures++;
                FluoriteMod.LOGGER.debug("Could not analyze emissive material sprites for item {}", item, throwable);
            }
        }
        Map<TextureAtlasSprite, Integer> frozen = Collections.unmodifiableMap(new IdentityHashMap<>(sprites));
        FluoriteMod.LOGGER.info(
                "RT emission semantics: emittingStates={}, emittingItems={}, sprites={} (+{} item-only), failedStates={}",
                states, items, frozen.size(), frozen.size() - blockSprites, failures);
        return new RtEmissionSemantics(frozen, states, failures);
    }

    /**
     * The sprites an emitting block's ITEM form is drawn from, which are not always its block form's.
     *
     * <p>Most light-emitting blocks reuse their block texture for the item, so this finds nothing new and
     * costs a model resolve. The ones that do not — a lantern has a bespoke {@code item/} texture — put
     * their held quads on the items atlas, and without this pass no material is ever compiled for those
     * sprites: the resolve falls through to a variant built with no emission, and the block becomes the
     * one in the game that fails to light whoever is holding it.
     *
     * <p>Resolved rather than name-matched. A pack is free to retexture the item and not the block, and
     * inferring one from the other's registry name would be wrong for exactly the packs that do.
     */
    private static void collectItem(Item item, int light,
                                    IdentityHashMap<TextureAtlasSprite, Integer> sprites) {
        ItemStackRenderState state = new ItemStackRenderState();
        // GROUND, not a hand context: the display context selects a TRANSFORM, and the transform is
        // irrelevant to which sprites the model uses. Asking for a hand would drag in the arm model.
        Minecraft.getInstance().getItemModelResolver()
                .updateForTopItem(state, new ItemStack(item), ItemDisplayContext.GROUND, null, null, 0);
        ItemStackRenderState.LayerRenderState[] layers = ((ItemStackRenderStateAccessor) state).fluorite$layers();
        int active = ((ItemStackRenderStateAccessor) state).fluorite$activeLayerCount();
        for (int i = 0; i < active && i < layers.length; i++) {
            ItemStackRenderState.LayerRenderState layer = layers[i];
            if (layer == null) continue;
            List<BakedQuad> quads = ((ItemLayerRenderStateAccessor) layer).fluorite$quads();
            if (quads != null) collectQuads(quads, light, sprites);
        }
    }

    public boolean permits(TextureAtlasSprite sprite) {
        return maxStateLight.containsKey(sprite);
    }

    public int maxStateLight(TextureAtlasSprite sprite) {
        return maxStateLight.getOrDefault(sprite, 0);
    }

    public int spriteCount() {
        return maxStateLight.size();
    }

    public int emittingStates() {
        return emittingStates;
    }

    public int failedStates() {
        return failedStates;
    }

    private static void collectState(BlockState state, int light,
                                     IdentityHashMap<TextureAtlasSprite, Integer> sprites) {
        BlockStateModel model = Minecraft.getInstance().getModelManager().getBlockStateModelSet().get(state);
        if (model == null) return;
        for (int probe = 0; probe < VARIANT_PROBES; probe++) {
            List<BlockStateModelPart> parts = new ArrayList<>();
            model.collectParts(RandomSource.create(mixSeed(state.hashCode(), probe)), parts);
            for (BlockStateModelPart part : parts) {
                collectQuads(part.getQuads(null), light, sprites);
                for (Direction direction : DIRECTIONS) collectQuads(part.getQuads(direction), light, sprites);
            }
        }
    }

    private static void collectQuads(List<? extends BakedQuad> quads,
                                     int light, IdentityHashMap<TextureAtlasSprite, Integer> sprites) {
        for (var quad : quads) {
            TextureAtlasSprite sprite = quad.materialInfo().sprite();
            if (sprite != null) sprites.merge(sprite, light, Math::max);
        }
    }

    private static long mixSeed(int stateHash, int probe) {
        long seed = Integer.toUnsignedLong(stateHash) ^ (0x9E3779B97F4A7C15L * (probe + 1L));
        seed ^= seed >>> 30;
        seed *= 0xBF58476D1CE4E5B9L;
        seed ^= seed >>> 27;
        return seed;
    }
}
