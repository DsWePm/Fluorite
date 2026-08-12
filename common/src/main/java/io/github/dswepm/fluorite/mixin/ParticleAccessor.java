package io.github.dswepm.fluorite.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read-only particle state needed by the side-band M18 light collector. */
@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("xo") double fluorite$getPreviousX();
    @Accessor("yo") double fluorite$getPreviousY();
    @Accessor("zo") double fluorite$getPreviousZ();
    @Accessor("x") double fluorite$getX();
    @Accessor("y") double fluorite$getY();
    @Accessor("z") double fluorite$getZ();

    @Invoker("getLightCoords")
    int fluorite$invokeGetLightCoords(float partialTick);
}
