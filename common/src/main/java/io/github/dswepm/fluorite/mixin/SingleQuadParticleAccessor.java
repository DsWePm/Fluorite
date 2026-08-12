package io.github.dswepm.fluorite.mixin;

import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Read-only sprite/tint state used to derive a particle light from its submitted appearance. */
@Mixin(SingleQuadParticle.class)
public interface SingleQuadParticleAccessor {
    @Accessor("sprite") TextureAtlasSprite fluorite$getSprite();
    @Accessor("rCol") float fluorite$getRed();
    @Accessor("gCol") float fluorite$getGreen();
    @Accessor("bCol") float fluorite$getBlue();
    @Accessor("alpha") float fluorite$getAlpha();
}
