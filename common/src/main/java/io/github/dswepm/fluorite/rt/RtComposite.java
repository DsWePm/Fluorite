package io.github.dswepm.fluorite.rt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import io.github.dswepm.fluorite.FluoriteConfig;
import io.github.dswepm.fluorite.FluoriteMod;
import io.github.dswepm.fluorite.client.FluoriteJitter;
import io.github.dswepm.fluorite.mixin.CommandEncoderAccessor;
import io.github.dswepm.fluorite.rt.gen.PackedPathSegmentData;
import io.github.dswepm.fluorite.rt.gen.WaterMediumProbeData;
import io.github.dswepm.fluorite.rt.gen.WorldPushConstantsData;
import io.github.dswepm.fluorite.rt.gen.WorldPushData;
import io.github.dswepm.fluorite.rt.gen.WorldPushData.BreakEntry;
import io.github.dswepm.fluorite.rt.gen.WorldPushData.Float2;
import io.github.dswepm.fluorite.rt.gen.WorldPushData.Float3;
import io.github.dswepm.fluorite.rt.gen.WorldPushData.Float4;
import io.github.dswepm.fluorite.rt.gen.WorldPushData.Int4;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.MoonPhase;
import net.minecraft.world.level.material.FluidState;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageBlit;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageMemoryBarrier2;
import org.lwjgl.vulkan.VkMemoryBarrier2;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import io.github.dswepm.fluorite.rt.accel.RtAccel;
import io.github.dswepm.fluorite.rt.accel.RtBuffer;
import io.github.dswepm.fluorite.rt.accel.RtImage;
import io.github.dswepm.fluorite.rt.entity.RtEntities;
import io.github.dswepm.fluorite.rt.entity.RtEntityTextures;
import io.github.dswepm.fluorite.rt.material.RtBlockMaterials;
import io.github.dswepm.fluorite.rt.material.RtEmissionSemantics;
import io.github.dswepm.fluorite.rt.material.RtMaterialOverrides;
import io.github.dswepm.fluorite.rt.material.RtMaterialRegistry;
import io.github.dswepm.fluorite.rt.pipeline.RtBloomFlarePipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtDepthOfFieldPipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtDisplayPipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtDlssFg;
import io.github.dswepm.fluorite.rt.pipeline.RtDlssRr;
import io.github.dswepm.fluorite.rt.overlay.RtWorldOverlay;
import io.github.dswepm.fluorite.rt.overlay.RtRainStreaks;
import io.github.dswepm.fluorite.rt.pipeline.RtHdrCompositePipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtLensPipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtSdrPresentPipeline;
import io.github.dswepm.fluorite.rt.pipeline.RtExposure;
import io.github.dswepm.fluorite.rt.pipeline.RtPipeline;
import io.github.dswepm.fluorite.rt.sky.RtSky;
import io.github.dswepm.fluorite.rt.sky.RtDimensionControls;
import io.github.dswepm.fluorite.rt.sky.RtEnvironmentTextures;
import io.github.dswepm.fluorite.rt.sky.RtHighCloudTextures;
import io.github.dswepm.fluorite.rt.sky.RtSkyPreset;
import io.github.dswepm.fluorite.rt.sky.RtSkyPresets;
import io.github.dswepm.fluorite.rt.terrain.RtTerrain;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * On-screen composite. Each frame, ray-trace into a render-res storage image (+ guide buffers), use
 * DLSS Ray Reconstruction to denoise and upscale it to display res, write that into a storage-capable
 * copy of the world color, and copy the result back to the world target at the
 * end-of-world seam. Gated by {@code -Dfluorite.rt=true}.
 *
 * <p>The path tracer and its guide buffers run at the configured render scale of display res with a per-frame
 * sub-pixel camera jitter; DLSS-RR ({@link RtDlssRr}) reconstructs the display-res image. With RR
 * disabled the trace runs at 1:1 and a linear blit stands in for the upscale (a raw, noisy reference).
 *
 * <p>Traces the extracted {@link RtTerrain} with perspective camera rays (camera matrices captured
 * each frame via {@link #captureFrame}); writes nothing until terrain is available.
 * Pipelines/SBT/descriptors are built once; sized images rebuilt on resize.
 */
public final class RtComposite {
    public static final RtComposite INSTANCE = new RtComposite();
    private static final int RAIN_EXPOSURE_REANCHOR_DISTANCE = 32;

    public static boolean enabled() {
        return FluoriteConfig.Rt.ENABLED.value();
    }

    // WorldPushData and its serializer are generated from Slang's reflected Std430DataLayout. Java never
    // owns or calculates a shader byte offset, struct size, array stride, or fixed-array capacity.
    private static final int WORLD_PUSH_SIZE = WorldPushData.BYTE_SIZE;
    // Real inline push constants (fast constant-bank reads), separate from the WorldPush BDA ring above.
    // Hot addresses/frameIndex and raygen's debugView avoid unnecessary global-memory dereferences;
    // WorldPushConstantsData is generated from the same Slang module and owns this second ABI as well.
    private static final int GUIDE_COUNT = 6; // RR guide buffers bound at world-pipeline bindings 3..8
    // Generated from the shader's own std430 layout rather than hand-copied, like every other ABI size
    // here. It was a literal 48 that nothing checked against the struct it describes.
    private static final long PATH_RECORD_BYTES = PackedPathSegmentData.BYTE_SIZE;
    // ---- Ambient participating medium.
    //
    // The active dimension preset supplies the baseline. Weather/time forcing is applied only when that
    // preset opts in, then these player settings act as global scale/bias controls.

    /** Reference density, height falloff, reference height and the distance past which fog stops. */
    private static Float4 fogParams(RtSkyPreset preset, RtDimensionControls dimension,
                                    RtEnvironmentForcing.Frame environment) {
        boolean on = FluoriteConfig.Rt.Volumetrics.ENABLED.value()
                && FluoriteConfig.Rt.Volumetrics.HEIGHT_FOG.value()
                && dimension.fogEnabled()
                && preset.fog().profile() != RtSkyPreset.FogProfile.OFF;
        // A density of zero is the disable path: volume.slang returns early on it, so switching fog off
        // costs one comparison per segment rather than a shader variant.
        float playerScale = dimension.resolveFogDensityScale(
                FluoriteConfig.Rt.Volumetrics.DENSITY_SCALE.value());
        float density = on ? preset.fog().density() * playerScale : 0f;
        density *= environment.fogDensityScale();
        float heightScale = preset.fog().profile() == RtSkyPreset.FogProfile.HOMOGENEOUS
                ? 0f
                : preset.fog().heightScale() * (FluoriteConfig.Rt.Volumetrics.HEIGHT_SCALE.value() / 48f);
        float heightBase = preset.fog().heightBase()
                + (FluoriteConfig.Rt.Volumetrics.HEIGHT_BASE.value() - 62f);
        float cull = preset.fog().cullDistance()
                * (FluoriteConfig.Rt.Volumetrics.CULL_DISTANCE.value() / 512f);
        return new Float4(density, heightScale, heightBase, Math.max(cull, fogStartDistance(preset)));
    }

    /**
      * Preset-authored per-channel extinction plus the player's globally scaled near cutoff in w.
      */
    private static Float4 fogExtinction(RtSkyPreset preset) {
        RtSkyPreset.Rgb extinction = preset.fog().extinction();
        return new Float4(extinction.r(), extinction.g(), extinction.b(), fogStartDistance(preset));
    }

    private static float fogStartDistance(RtSkyPreset preset) {
        // Additive relative to the old 16-block default, rather than multiplicative: a dimension whose
        // authored fog starts at the eye (Nether) must still let the player request a clear near field.
        return Math.max(0f, preset.fog().startDistance()
                + FluoriteConfig.Rt.Volumetrics.START_DISTANCE.value() - 16f);
    }

    private static int environmentFlags(RtSkyPreset preset, boolean fogActive,
                                        RtEnvironmentTextures.Entry environment) {
        int flags = 0;
        if (preset.fog().ambientVisibility() == RtSkyPreset.AmbientVisibility.UNOCCLUDED) {
            flags |= 1; // ENVIRONMENT_AMBIENT_UNOCCLUDED
        }
        if (fogActive && preset.fog().localLights()) {
            flags |= 1 << 1; // ENVIRONMENT_FROXEL_LOCAL_LIGHTS
        }
        if (environment != null) {
            flags |= (environment.layer() & 0xff) << 8;
        }
        return flags;
    }

    private static Float4 mediumSkyRadiance(RtSkyPreset preset, RtDimensionControls controls,
                                            RtEnvironmentTextures.Entry environment) {
        if (preset.skyProvider() == RtSkyPreset.SkyProvider.ENVIRONMENT && environment != null) {
            RtSkyPreset.Rgb mean = environment.meanRadiance();
            float scale = controls.environmentScale();
            return new Float4(mean.r() * scale, mean.g() * scale, mean.b() * scale, scale);
        }
        RtSkyPreset.Rgb ambient = preset.ambientRadiance();
        float scale = controls.ambientScale();
        return new Float4(ambient.r() * scale, ambient.g() * scale, ambient.b() * scale, 0f);
    }

    /**
     * Where the volumetric visibility grid sits this frame: its minimum corner in rebased blocks, and its
     * cell size in w. A cell size of zero is the disable path — sampleVolumeVisibility reports everything
     * lit, which reproduces the unshadowed fog exactly rather than approximately.
     *
     * <p><b>Snapped in ABSOLUTE world coordinates, then rebased.</b> Both halves of that matter. Snapping
     * is what keeps the sample lattice still while the player walks: an unsnapped grid would move
     * continuously with the camera, so every cell would cast its ray from a slightly different place each
     * frame and the fog would boil. Doing the snap before the rebase is what keeps the lattice still
     * across a terrain rebase too — rebasing first would snap to a grid that jumps whenever the origin
     * moves, which is a rare, large, and very hard-to-attribute flicker.
     *
     * <p>Centred on the camera, so the grid reaches half its extent in every direction. The camera sitting
     * at the centre rather than at a corner is what makes a turn free: the field behind the player is
     * already baked when they turn around.
     *
     * <p><b>The snap is to whole cells, which at the default cell size of 1 puts every cell centre at a
     * block centre.</b> That alignment is not incidental — it is what stops light leaking through a
     * one-block wall, because a cell that sits inside a solid block casts its rays from inside that block
     * and reads occluded, so it blocks the interpolation the way the block blocks the light. See
     * VISIBILITY_CELL_SIZE for the measurement that established it.
     */
    private static Float4 visibilityGridOrigin(double camX, double camY, double camZ, RtTerrain terrain) {
        float cell = FluoriteConfig.Rt.Volumetrics.VISIBILITY_CELL_SIZE.value();
        if (cell <= 0f || !FluoriteConfig.Rt.Volumetrics.ENABLED.value()) {
            return new Float4(0f, 0f, 0f, 0f);
        }
        double halfX = RtSky.VIS_GRID_W * 0.5 * cell;
        double halfY = RtSky.VIS_GRID_H * 0.5 * cell;
        double halfZ = RtSky.VIS_GRID_D * 0.5 * cell;
        double ox = Math.floor(camX / cell) * cell - halfX;
        double oy = Math.floor(camY / cell) * cell - halfY;
        double oz = Math.floor(camZ / cell) * cell - halfZ;
        return new Float4((float) (ox - terrain.blockX), (float) (oy - terrain.blockY),
                (float) (oz - terrain.blockZ), cell);
    }

    // ---- Volumetric clouds (M11). What the density field is allowed to be, and what the weather makes
    // of it. Everything here is authored per frame rather than compiled into cloud.slang, because the
    // vanilla weather system has to be able to move it.

    /**
     * Coverage bias, density scale, type bias and extinction — and where vanilla's weather enters.
     *
     * <p><b>Rain drives coverage, thunder drives type,</b> and those are two different axes on purpose.
     * Rain means the sky filled in; thunder means the clouds grew upward. A single "storminess" scalar
     * would tie them together and could never produce the two skies that actually differ — an overcast
     * drizzle, which is a flat sheet from horizon to horizon, and a thunderhead over an otherwise open
     * sky. Vanilla itself keeps them separate for the same reason (it can rain without thundering, and
     * its thunder level is only ever raised while it is raining), so this reads the pair rather than
     * collapsing it.
     *
     * <p>Both are ADDED to the noise fields rather than replacing them, so a storm arrives over a sky
     * still made of individual cells. Replacing would make every cloud identical the instant the weather
     * changed, which reads as a switch being thrown instead of as weather.
     *
     * <p>Rain also raises density, because the visible difference between a fair-weather sky and a rainy
     * one is not only how much of it is covered — it is that you can no longer see through any of it.
     *
     * <p>Interpolated at the frame's partial tick. Vanilla ramps these over many ticks, so the value is
     * already smooth; sampling it at the tick boundary instead would quantise a slow ramp to 20 Hz, which
     * is visible on a sky that covers the screen.
     *
     * @param environment the frame's single resolved weather sample
     */
    private static Float4 cloudParams(RtEnvironmentForcing.Frame environment) {
        float coverage = FluoriteConfig.Rt.Volumetrics.CLOUD_COVERAGE.value()
                + environment.cloudCoverageBias();
        float density = FluoriteConfig.Rt.Volumetrics.CLOUD_DENSITY.value()
                * environment.cloudDensityScale();
        float type = FluoriteConfig.Rt.Volumetrics.CLOUD_TYPE.value()
                + environment.cloudTypeBias();
        return new Float4(coverage, density, type,
                FluoriteConfig.Rt.Volumetrics.CLOUD_EXTINCTION.value());
    }

    /**
     * Where the deck sits and how big its features are: bottom altitude, thickness, base and detail size.
     *
     * <p>The bottom is a condensation altitude and therefore a plane — every cloud's flat base lands on
     * it, whatever its type. The thickness is what a cumulonimbus has to grow into, so it is deep, and it
     * costs march steps only where a tall cloud actually exists.
     */
    /**
     * The terrain rebase origin, so the clouds can be placed in the world rather than around the player.
     *
     * <p>Everything the ray tracer sees is relative to this, and it follows the camera — it is reset
     * whenever the camera drifts more than {@code terrain.rebase-distance-blocks} from it. The cloud
     * altitude is authored as an absolute height, so without this the deck sat at that height ABOVE THE
     * PLAYER: it climbed with them, could not be entered or flown above, and the whole pattern jumped
     * sideways at every rebase. Same reason {@code visibilityGridOrigin} snaps in absolute coordinates
     * before rebasing, and the same class of fault R18 exists to prevent.
     */
    /**
     * The wind's accumulated drift for one layer, in blocks.
     *
     * <p>Shared by all cloud representations and parameterised by their own speed and angle, because
     * high clouds sit kilometres higher, where they move faster and often from another
     * quarter, and two layers sliding past each other at different speeds is most of what makes a sky
     * read as deep rather than as one painted dome.
     *
     * <p>Game time rather than wall clock, so the sky stops when the game does. Kept in double to the
     * last moment: game time reaches millions of ticks on an old world, and the product with the speed
     * is what a float would start losing blocks off the end of.
     */
    private static double[] windDrift(RtEnvironmentForcing.Frame environment,
                                      float speed, float angleDegrees) {
        if (speed <= 0f || environment.gameSeconds() <= 0.0) {
            return new double[] {0.0, 0.0};
        }
        double angle = Math.toRadians(angleDegrees);
        return new double[] {Math.cos(angle) * speed * environment.gameSeconds(),
                Math.sin(angle) * speed * environment.gameSeconds()};
    }

    /**
     * Where the water simulation's domain sits, and how much of its slope reaches the shading.
     *
     * <p>A cell size of zero is the disable path — waterSimGrad returns before it reads anything, so the
     * procedural spectrum stands alone exactly as it did before this existed. That is what it returns
     * for now: the domain is placed and the field is stepped by the passes that follow this commit, and
     * a domain pointing at an unstepped field would be a flat contribution dressed up as a working one.
     */
    private Float4 waterSimDomain() {
        return waterDomain;
    }

    /**
     * The plane the simulation runs on, rebased, and how far off it a surface still counts as being on it.
     *
     * <p>The field is two-dimensional: it knows where it is in xz and nothing about y. Without this every
     * water surface sharing a column with the simulated one receives its ripples — the pool below a
     * waterfall answering a splash in the lake above it, in perfect time, having never been touched.
     *
     * <p>The tolerance is deliberately small and is NOT the probe's vertical reach. That setting says how
     * far to SEARCH for a plane; this says how close a surface has to be to the plane already chosen, and
     * a lake is flat — anything a metre off it is a different body of water. Faded rather than clipped so
     * that a flowing block at a shoreline does not pop.
     */
    private Float4 waterSimPlane(float stormSwellBias, float causticContrast) {
        if (waterDomain.z() <= 0f || Double.isNaN(waterSurfaceY)) {
            // Zero tolerance disables only the simulation-plane test. zw are unrelated resolved weather
            // values and must keep working when interactive ripples are off.
            return new Float4(0f, 0f, stormSwellBias, causticContrast);
        }
        return new Float4(waterPlaneRebasedY, WATER_PLANE_TOLERANCE,
                stormSwellBias, causticContrast);
    }

    /** A lake is flat; a surface further off the plane than this is a different lake. Blocks. */
    private static final float WATER_PLANE_TOLERANCE = 1.5f;

    /**
     * Place the simulation domain for this frame, and decide whether it moved far enough to re-anchor.
     *
     * <p>THE DOMAIN IS NOT RE-ANCHORED EVERY FRAME. It follows the player, but only in whole-cell jumps
     * and only once they have left a dead zone at its centre. Both halves earn their place: whole cells
     * are what keep the stored field aligned with the world, so a ripple stays where it was rather than
     * being resampled at a new phase and smeared into a streak (R22); the dead zone is what keeps the
     * obstacle rays from being recast every frame while someone walks, which is the whole of D40's
     * saving. Between re-anchors the domain is completely still and the simulation costs one dispatch.
     *
     * <p>Returns false when there is no water surface near the player, which disables the whole thing —
     * cell size zero, and the sampler returns before it reads.
     */
    /**
     * The surface of the water the player is actually touching, or NaN if they are not touching any.
     *
     * <p>Touching means the body is in water, or is standing on it. Both count: wading is contact, and
     * so is a boat, and so is standing on the ice-thin top of a pond looking down at it. The player's
     * own column is the only one examined, because contact is a local fact -- the outward search this
     * replaces existed to guess at water the player was NOT touching, which is precisely the guess that
     * kept picking the wrong lake.
     *
     * <p>Scanned OUTWARD from the player's feet rather than downward from above, so the surface returned
     * is the one they are in contact with rather than the highest one in the column. A pool on a ledge
     * overhead is not what you are standing in.
     */
    private static double waterContactSurface(ClientLevel level, BlockPos.MutableBlockPos probe,
                                              double camX, double camY, double camZ) {
        int px = (int) Math.floor(camX);
        int pz = (int) Math.floor(camZ);
        int feet = (int) Math.floor(camY);
        // A short reach: contact is by definition close. Two down covers standing on the surface with
        // the eye a block and a half above it; two up covers being submerged with the eye under.
        for (int d = 0; d <= WATER_CONTACT_REACH; d++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                int y = feet + sign * d;
                probe.set(px, y, pz);
                if (!level.getFluidState(probe).is(FluidTags.WATER)) {
                    continue;
                }
                // Walk up to the top of this body of water: the surface is the first cell above the
                // column that is not itself water.
                int top = y;
                while (top - y < WATER_CONTACT_CLIMB) {
                    probe.set(px, top + 1, pz);
                    if (!level.getFluidState(probe).is(FluidTags.WATER)) {
                        break;
                    }
                    top++;
                }
                return top + 1.0;
            }
            if (d == 0) {
                continue; // +0 and -0 are the same cell
            }
        }
        return Double.NaN;
    }

    private boolean placeWaterDomain(double camX, double camY, double camZ, ClientLevel level,
                                     RtTerrain terrain) {
        waterReanchor = false;
        if (level == null || !FluoriteConfig.Rt.Water.WATER_SIM.value()) {
            waterDomain = new Float4(0f, 0f, 0f, 0f);
            return false;
        }
        // WHICH SURFACE THE SIMULATION RUNS ON: latched by CONTACT, not chosen by proximity.
        //
        // Searching outward for the nearest surface and damping the result with hysteresis was two
        // mistakes wearing each other's clothes. The search could pick a surface the player has nothing
        // to do with, and the hysteresis -- there to stop the choice flickering -- is exactly what made
        // it stick to a lake you had already walked away from. Every attempt to tune one made the other
        // worse, because "nearest" changes continuously and a plane must not.
        //
        // Contact has neither problem. Touch water and that surface becomes the plane, at once, with no
        // dead zone to be on the wrong side of. Touch a different one and it switches, at once, for the
        // same reason. Nothing about it is gradual, so nothing about it can lag.
        //
        // Between contacts the last surface is KEPT rather than re-derived, which is what lets ripples
        // you left behind still be there when you climb out and look back. It is dropped only when it
        // falls outside the vertical reach, at which point there is no plane and no simulation.
        double surface = waterSurfaceY;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int reach = waterProbeReach();
        double contact = waterContactSurface(level, probe, camX, camY, camZ);
        if (!Double.isNaN(contact)) {
            surface = contact;
        } else if (!Double.isNaN(surface) && Math.abs(camY - surface) > reach) {
            surface = Double.NaN; // walked out of range of the last water touched
        }
        if (Double.isNaN(surface)) {
            waterDomain = new Float4(0f, 0f, 0f, 0f);
            waterSurfaceY = Double.NaN; // nothing in reach: do not keep a plane that is no longer there
            return false;
        }
        // Rebased here, where the terrain origin is in hand, because the shading compares it against a
        // rebased hit position and the two must be in one space.
        waterPlaneRebasedY = (float) (surface - terrain.blockY);

        float cell = waterCellSize();
        long centreCellX = (long) Math.floor(camX / cell);
        long centreCellZ = (long) Math.floor(camZ / cell);
        long wantX = centreCellX - RtSky.WATER_SIM_DIM / 2;
        long wantZ = centreCellZ - RtSky.WATER_SIM_DIM / 2;
        // In CELLS, from an authored distance in blocks. Generous by default: the obstacle mask is the
        // only thing a re-anchor costs, and a ripple is damped long before it reaches the domain edge,
        // so there is nothing to be gained by keeping the player pinned to the centre.
        long dead = (long) Math.max(1.0, FluoriteConfig.Rt.Water.WATER_SIM_REANCHOR.value() / cell);
        if (waterCellX == Long.MIN_VALUE
                || Math.abs(wantX - waterCellX) > dead || Math.abs(wantZ - waterCellZ) > dead
                || Math.abs(surface - waterSurfaceY) > 0.5) {
            waterCellX = wantX;
            waterCellZ = wantZ;
            waterSurfaceY = surface;
            waterReanchor = true;
        }
        // Rebuilt on a re-anchor, and retried until it actually reaches the GPU.
        if (waterReanchor || !waterMaskUploaded) {
            buildWaterObstacleMask(level, cell);
        }
        // IN THE SAME SPACE THE SHADING ASKS IN, which is not the rebased one. applyWaterWaves receives
        // hitPos.xz PLUS waterAnchor.xy, and that anchor is the rebase origin MASKED to 4096 -- the
        // procedural spectrum only needs world stability modulo its longest wavelength, so masking keeps
        // the coordinate small and is right for it. Handing this origin over un-masked put the two a
        // whole rebase apart, the uv landed outside [0,1], and waterSimGrad returned zero every time:
        // a field that simulated correctly and reached nothing.
        double originX = waterCellX * (double) cell - terrain.blockX + (terrain.blockX & WATER_ANCHOR_MASK);
        double originZ = waterCellZ * (double) cell - terrain.blockZ + (terrain.blockZ & WATER_ANCHOR_MASK);
        waterDomain = new Float4((float) originX, (float) originZ, cell,
                FluoriteConfig.Rt.Water.WATER_SIM_STRENGTH.value());
        return true;
    }

    /**
     * Ask the level which cells hold water, one byte each.
     *
     * <p>The block UNDER the surface, not at it: the surface height is the top face of the water block,
     * so the block that is or is not water sits one below. A cell is open where that block is water and
     * an obstacle everywhere else — which includes air, so a shoreline and a pier both reflect, and so
     * does the edge of the pond.
     *
     * <p>65k lookups, and only on a re-anchor: one frame's work every sixteen blocks of travel rather
     * than anything continuous. Into a loaded chunk a lookup is an array index; an unloaded one reads as
     * not-water, which makes the unloaded world a wall rather than a hole that swallows ripples.
     */
    private void buildWaterObstacleMask(ClientLevel level, float cell) {
        if (waterObstacleMask == null) {
            waterObstacleMask = new byte[RtSky.WATER_SIM_DIM * RtSky.WATER_SIM_DIM];
        }
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int below = (int) Math.floor(waterSurfaceY) - 1;
        for (int z = 0; z < RtSky.WATER_SIM_DIM; z++) {
            double worldZ = (waterCellZ + z + 0.5) * cell;
            for (int x = 0; x < RtSky.WATER_SIM_DIM; x++) {
                double worldX = (waterCellX + x + 0.5) * cell;
                probe.set((int) Math.floor(worldX), below, (int) Math.floor(worldZ));
                boolean open = level.getFluidState(probe).is(FluidTags.WATER);
                waterObstacleMask[z * RtSky.WATER_SIM_DIM + x] = open ? (byte) 255 : 0;
            }
        }
        // skyLuts does not exist on the first frames, and the first re-anchor is on the very first
        // frame. Without this retry the mask would be built once, dropped, and never rebuilt until the
        // player walked far enough to re-anchor -- with the image holding whatever it was allocated with
        // in the meantime.
        if (skyLuts != null) {
            skyLuts.uploadWaterObstacles(waterObstacleMask);
            waterMaskUploaded = true;
        } else {
            waterMaskUploaded = false;
        }
    }

    /**
     * The impulses this frame, from entities standing in or moving through the water.
     *
     * <p>CLAMPED, and that clamp is a stability guard rather than an art parameter: the solver is
     * explicit, so a displacement large enough against the cell size steepens the local slope until the
     * next step overshoots, and the overshoot compounds until the field explodes.
     *
     * <p>Only the nearest few, because only six fit in the push constants — and because that is also the
     * right answer visually. A crowd in the water makes one disturbed patch, not sixty ripples anyone
     * could tell apart.
     */
    private void collectWaterImpulses(double camX, double camZ, ClientLevel level) {
        waterImpulseCount = 0;
        java.util.Arrays.fill(waterImpulses, 0f);
        if (level == null || waterDomain.z() <= 0f) {
            return;
        }
        // Debug view 23 drives a test impulse at the domain centre. It is what makes the view able to
        // distinguish "nothing disturbs the field" from "the field cannot propagate" -- two states that
        // look identical on a flat pond and have completely different causes.
        if (FluoriteConfig.Rt.Composite.DEBUG_VIEW.value() == 23) {
            waterImpulses[0] = RtSky.WATER_SIM_DIM * 0.5f;
            waterImpulses[1] = RtSky.WATER_SIM_DIM * 0.5f;
            waterImpulses[2] = 3f;
            waterImpulses[3] = FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE.value()
                    * ((worldFrameCounter() & 31L) == 0L ? 1f : 0f);
            waterImpulseCount = 1;
        }
        float cell = waterDomain.z();
        float cap = FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE.value();
        double half = RtSky.WATER_SIM_DIM * 0.5 * cell;
        // Per-gate counters for the diagnostic below. EVERY GATE SEPARATELY, not one "nothing came out"
        // tally: the loop rejects for four different reasons and the whole question is which one, so a
        // combined count would reproduce exactly the ambiguity the diagnostic exists to remove. Same
        // lesson as debug view 22, which reported a verdict first and had to be changed to components.
        int seenEntities = 0;
        int seenInWater = 0;
        int seenInRange = 0;
        int seenNearSurface = 0;
        double fastest = -1.0;
        float impulseDepth = FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE_DEPTH.value();
        // The local player, tracked BY IDENTITY rather than inferred from the numbers. The first run of
        // this diagnostic left exactly one thing ambiguous: a value of 0.10 repeated 726 times is clearly
        // not a moving player, but "the player is absent" and "the player is standing still" produce the
        // same reading, and they have completely different fixes.
        Entity self = Minecraft.getInstance().player;
        boolean sawSelf = false;
        double selfSpeed = -1.0;
        for (Entity e : level.entitiesForRendering()) {
            seenEntities++;
            if (e == self) {
                sawSelf = true;
                selfSpeed = (Math.sqrt(e.getDeltaMovement().x * e.getDeltaMovement().x
                        + e.getDeltaMovement().z * e.getDeltaMovement().z)
                        + Math.abs(e.getDeltaMovement().y)) * 20.0;
            }
            if (waterImpulseCount >= RtSky.WATER_MAX_IMPULSES) {
                break;
            }
            if (!e.isInWater()) {
                continue;
            }
            seenInWater++;
            double dx = e.getX() - camX;
            double dz = e.getZ() - camZ;
            if (Math.abs(dx) > half || Math.abs(dz) > half) {
                continue;
            }
            seenInRange++;
            // How far under the surface it is, measured from its HIGHEST point: what matters is whether
            // any of it is near the water, not where its feet are. A surface wave's motion decays as
            // e^(-k*d), so something well below the surface is simply not coupled to it -- diving to the
            // bottom of a lake used to go on stamping ripples into the surface several blocks overhead.
            double submergence = waterSurfaceY - (e.getY() + e.getBbHeight());
            float depthFade = submergence <= 0.0
                    ? 1.0f
                    : (float) Math.max(0.0, 1.0 - submergence / Math.max(impulseDepth, 1.0e-3));
            if (depthFade <= 0.0f) {
                continue;
            }
            seenNearSurface++;
            // How hard it is moving through the surface. A still entity leaves the water alone; a
            // swimming one keeps feeding the field, which is what makes a wake rather than one splash.
            // BLOCKS PER SECOND. getDeltaMovement is per TICK, and forgetting that is a factor of twenty
            // -- a swim is about 0.1 per tick, which read as a speed of 0.1 gave an impulse of three
            // millimetres, a slope two orders of magnitude under the procedural spectrum's. Invisible,
            // and invisible in a way that looks exactly like nothing being injected at all.
            double speed = (Math.sqrt(e.getDeltaMovement().x * e.getDeltaMovement().x
                    + e.getDeltaMovement().z * e.getDeltaMovement().z)
                    + Math.abs(e.getDeltaMovement().y)) * 20.0;
            fastest = Math.max(fastest, speed);
            if (speed < 0.2) {
                continue;
            }
            // How wide a patch of water this thing disturbs, IN BLOCKS: its own width, floored so a
            // very thin entity still makes a dent you can see.
            double wantMetres = Math.max(e.getBbWidth(), WATER_IMPULSE_MIN_RADIUS)
                    * FluoriteConfig.Rt.Water.WATER_SIM_IMPULSE_SIZE.value();
            double wantCells = wantMetres / cell;
            // The grid cannot represent a bump narrower than a couple of cells -- a single-cell delta is
            // the grid's own Nyquist, which leapfrog carries forever as a checkerboard that never
            // propagates. So the radius is floored.
            //
            // BUT THE FLOOR IS PAID FOR IN AMPLITUDE, NOT IN WIDTH, and that is the fix. The floor used
            // to be the whole story, and because it is expressed in CELLS its size in the world moved
            // with the domain: at a 256-block range the cells are a metre across and a player's splash
            // grew from 0.6 m to 2 m wide. Widening the ripple range silently made every splash three
            // times bigger, which is not what that setting is for.
            //
            // Now a bump too fine for the grid is spread to the smallest width the grid can hold and its
            // height is scaled by the area ratio, so the volume of water moved is the same. Coarse grids
            // give a broader, shallower disturbance -- which is exactly what band-limiting a splash
            // should look like -- instead of a bigger one.
            float radius = (float) Math.max(2.0, wantCells);
            double volumeKeep = wantCells >= 2.0 ? 1.0 : (wantCells * wantCells) / 4.0;
            // Saturating at a walking pace, so a sprint does not simply scale the splash up without
            // limit -- past a point what changes about a wake is its shape, not its height, and the
            // clamp above this is a stability bound rather than a taste one.
            float amount = (float) (Math.min(speed / 4.0, 1.0) * volumeKeep) * cap * depthFade;
            int base = waterImpulseCount * 4;
            waterImpulses[base] = (float) ((e.getX() - waterCellX * (double) cell) / cell);
            waterImpulses[base + 1] = (float) ((e.getZ() - waterCellZ * (double) cell) / cell);
            waterImpulses[base + 2] = radius;
            waterImpulses[base + 3] = amount;
            waterImpulseCount++;
        }
        logImpulseGates(camX, camZ, cell, seenEntities, seenInWater, seenInRange, seenNearSurface,
                fastest, sawSelf, selfSpeed, self != null && self.isInWater());
    }

    /**
     * Why the entity impulse path produced what it produced, about once a second while debug view 23 is
     * up.
     *
     * <p>Reported because reading the code did not settle it. The path has four gates and a coordinate
     * mapping, every one of them looks correct in isolation, and the observable — no ripples — is the
     * same whichever one is at fault. So this names the gate instead: each count is the population that
     * SURVIVED that step, so the first number that collapses to zero is the culprit.
     *
     * <ul>
     *   <li>{@code entities}=0 — the render list is empty here, which would be a wrong level or a wrong
     *       call site rather than anything about water.</li>
     *   <li>{@code inWater}=0 — nothing reports {@code isInWater()}. If you are swimming when this
     *       prints, the local player is not in {@code entitiesForRendering()} at all.</li>
     *   <li>{@code inRange}=0 — the domain is not where the entities are; compare {@code camCell} against
     *       the grid's centre (128), since the player should sit almost exactly on it.</li>
     *   <li>{@code fastest} below 0.2 — the speed gate. The value is blocks per second after the ×20
     *       tick conversion, so a swim should read about 2.</li>
     *   <li>{@code emitted}=0 with all of the above healthy — the write itself, which would be the one
     *       thing left.</li>
     * </ul>
     */
    private void logImpulseGates(double camX, double camZ, float cell, int entities, int inWater,
                                 int inRange, int nearSurface, double fastest, boolean sawSelf,
                                 double selfSpeed, boolean selfInWater) {
        if (FluoriteConfig.Rt.Composite.DEBUG_VIEW.value() != 23 || (worldFrameCounter() % 60L) != 0L) {
            return;
        }
        FluoriteMod.LOGGER.info(
                "[water-sim] entities={} inWater={} inRange={} nearSurface={} fastest={} emitted={} "
                        + "| self: seen={} inWater={} speed={} "
                        + "| cell={} camCell=({}, {}) domainCell=({}, {}) surfaceY={}",
                entities, inWater, inRange, nearSurface,
                fastest < 0.0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", fastest),
                waterImpulseCount,
                sawSelf, selfInWater,
                selfSpeed < 0.0 ? "n/a" : String.format(java.util.Locale.ROOT, "%.2f", selfSpeed),
                String.format(java.util.Locale.ROOT, "%.3f", cell),
                String.format(java.util.Locale.ROOT, "%.1f", (camX - waterCellX * (double) cell) / cell),
                String.format(java.util.Locale.ROOT, "%.1f", (camZ - waterCellZ * (double) cell) / cell),
                waterCellX, waterCellZ,
                String.format(java.util.Locale.ROOT, "%.2f", waterSurfaceY));
    }

    /** y was the retired upper sheet's field span and is now unread; the shader holds detailScale at 1. */
    private static Float4 cloudCirrusShape() {
        return new Float4(FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_DIAMETER.value(),
                0f,
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_DENSITY.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_STRENGTH.value());
    }

    /** High-cloud world origin and deterministic patch-grid spacing. */
    private static Float4 cloudCirrusOrigin(RtTerrain terrain, RtEnvironmentForcing.Frame environment) {
        double[] drift = windDrift(environment,
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_WIND_SPEED.value(),
                FluoriteConfig.Rt.Volumetrics.cloudCirrusWindAngle());
        return new Float4((float) (terrain.blockX - drift[0]), terrain.blockY,
                (float) (terrain.blockZ - drift[1]),
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_PATCH_SPACING.value());
    }

    /**
     * Relative motion of the low cloudlets.
     *
     * <p>The layer origin already carries 100% of its wind drift; this is D161's 18% differential detail
     * motion on top. z carried the retired upper sheet's gain and is now zero, as w always was.
     */
    private static Float4 cloudEvolution(RtEnvironmentForcing.Frame environment) {
        double[] low = windDrift(environment,
                FluoriteConfig.Rt.Volumetrics.CLOUD_WIND_SPEED.value() * 0.18f,
                FluoriteConfig.Rt.Volumetrics.cloudWindAngle());
        return new Float4((float) -low[0], (float) -low[1], 0f, 0f);
    }

    /**
     * D173 low-cloud domain warp: amplitude, feature scale, and the warp's OWN wind drift.
     *
     * <p>The drift is the load-bearing part. The shape field already advects with the layer origin, so a
     * warp carried at the same speed would deform each cloud once and then translate it unchanged -- the
     * sky would move without ever growing. Advecting the warp at a DIFFERENT speed slides it through the
     * shape, so the displaced boundary is continuously re-formed and lobes appear and dissolve in place.
     *
     * <p>ITS OWN SPEED, NOT A FRACTION OF THE WIND, and this was got wrong first time round. The
     * differential-advection idea is borrowed from the low deck's detail offset, which takes 18% of the
     * wind -- and the record for that change already says it did not produce the growth it was aimed at.
     * Reusing the mechanism at 45% without checking the rate it produces repeated the same mistake: at the
     * shipped wind speed one warp feature took over eight minutes to pass through a cloud. It was
     * proportional to wind speed, and it was still invisible.
     *
     * <p>Clouds are reshaped by convection, not by advection: a still afternoon cumulus billows and
     * dissolves without going anywhere. A wind-proportional rate also freezes the whole sky the moment the
     * wind is set to zero, which is the opposite of what a calm day looks like.
     *
     * <p>The DIRECTION still follows the wind, because the turbulence really is carried along by it, and
     * that costs no second setting.
     */
    private static Float4 cloudWarp(RtEnvironmentForcing.Frame environment) {
        double[] drift = windDrift(environment,
                FluoriteConfig.Rt.Volumetrics.CLOUD_EVOLUTION_SPEED.value(),
                FluoriteConfig.Rt.Volumetrics.cloudWindAngle());
        return new Float4(FluoriteConfig.Rt.Volumetrics.CLOUD_WARP_AMOUNT.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_WARP_SCALE.value(),
                (float) -drift[0], (float) -drift[1]);
    }

    private static Float4 cloudRebase(RtTerrain terrain, RtEnvironmentForcing.Frame environment) {
        // The wind's accumulated drift, subtracted from the origin. Sampling a field at p + (origin -
        // drift) is the same thing as sampling a drifting field at p, and doing it this way means the
        // shader has no notion of wind at all: one addition it already performs to un-rebase the
        // coordinates now also advects them, so a cloud and its reflection cannot disagree about where
        // the field is.
        //
        // Only xz. The y lane is the pure rebase because cloudShellSpan measures the deck's altitude
        // from it, and a deck that drifted vertically would be a deck at the wrong height.
        double[] drift = windDrift(environment, FluoriteConfig.Rt.Volumetrics.CLOUD_WIND_SPEED.value(),
                FluoriteConfig.Rt.Volumetrics.cloudWindAngle());
        float driftX = (float) drift[0];
        float driftZ = (float) drift[1];
        return new Float4(terrain.blockX - driftX, terrain.blockY, terrain.blockZ - driftZ,
                FluoriteConfig.Rt.Volumetrics.CLOUD_FIELD_SCALE.value());
    }

    private static Float4 cloudShape() {
        return new Float4(FluoriteConfig.Rt.Volumetrics.CLOUD_ALTITUDE.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_THICKNESS.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_BASE_SCALE.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_DETAIL_SCALE.value());
    }

    /**
     * How the clouds are lit: phase asymmetry, single-scattering albedo, the self-shadow step count, and
     * the energy-matched isotropic diffusion source scale.
     *
     * <p>The albedo is the one with physical weight — the multiple-scattering term decays at
     * {@code sqrt(3*(1-albedo))} times the optical depth, so it alone decides how deep light reaches into
     * a cloud. The step count is the milestone's cost dial: those steps run per in-cloud sample, so they
     * multiply the expensive path rather than adding to it.
     */
    private static Float4 cloudLighting() {
        float phaseG = FluoriteConfig.Rt.Volumetrics.CLOUD_PHASE_G.value();
        float albedo = FluoriteConfig.Rt.Volumetrics.CLOUD_ALBEDO.value();
        return new Float4(phaseG,
                albedo,
                FluoriteConfig.Rt.Volumetrics.CLOUD_SUN_STEPS.value(),
                RtCloudLighting.diffuseSourceScale(albedo, phaseG));
    }

    /**
     * The high-cloud pair: bottom altitude, sheet separation/reference thickness, coverage and extinction.
     *
     * <p>Zero extinction is the disable path, the same pattern the fog's density uses — the shader
     * returns before intersecting either sheet, so switching high clouds off costs one comparison rather than a
     * shader variant.
     */
    private static Float4 cloudCirrus() {
        if (!FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS.value()) {
            return new Float4(0f, 0f, 0f, 0f);
        }
        return new Float4(FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_ALTITUDE.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_THICKNESS.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_COVERAGE.value(),
                FluoriteConfig.Rt.Volumetrics.CLOUD_CIRRUS_EXTINCTION.value());
    }

    /** Single-scattering albedo and the sun lobe's anisotropy. */
    private static Float4 fogScatter(RtSkyPreset preset) {
        float[] tint = FluoriteConfig.Rt.Volumetrics.scatterTintRgb();
        float scale = FluoriteConfig.Rt.Volumetrics.ALBEDO_SCALE.value();
        RtSkyPreset.Rgb albedo = preset.fog().albedo();
        // Named tint is a global hue shift relative to the legacy neutral choice. Dividing by that
        // neutral means the default is exactly identity while authored per-dimension RGB remains the base.
        float r = albedo.r() * tint[0] / 0.92f;
        float g = albedo.g() * tint[1] / 0.94f;
        float b = albedo.b() * tint[2] / 0.96f;
        // D13: this value is sigma_s / sigma_t, not an artistic radiance gain. Clamp at the ABI
        // boundary as well as in the setting so every shader consumer receives a conservative medium,
        // including callers assembled from a future preset whose tint accidentally exceeds one.
        return new Float4(Math.clamp(r * scale, 0.0f, 1.0f),
                Math.clamp(g * scale, 0.0f, 1.0f),
                Math.clamp(b * scale, 0.0f, 1.0f),
                Math.clamp(preset.fog().phaseG()
                        + FluoriteConfig.Rt.Volumetrics.PHASE_G.value() - 0.55f, -0.9f, 0.9f));
    }

    /**
     * Water's scattering coefficient and phase anisotropy.
     *
     * <p>One value for every water body. Per-biome character lives in the absorption, which the tint
     * still drives; keeping the scattering global is what lets the single-scattering albedo be recovered
     * in the shader as sigma_s/sigma_t from the extinction the wavefront record already carries — so this
     * costs no memory in the record, which had exactly one spare uint and now still does.
     */
    // Last biome tint reported, so the line below prints on change instead of every frame.
    //
    // Quantised hard, and rate limited on top, because "changed" turned out to mean "moved". The tint is
    // BiomeColors.getAverageWaterColor, which BLENDS over the surrounding area, so walking across a biome
    // boundary sweeps it continuously and every single frame carries a tint no frame has carried before.
    // The result was a per-frame LOGGER.info with string formatting on the render thread — and since the
    // whole diagnostic is gated on frameStats, it only ran while a performance capture was running. A
    // measurement instrument that costs render-thread time exactly when it is being measured is worse
    // than no instrument.
    private static final int WATER_TINT_LOG_BITS = 4;      // 16 levels per channel
    private static final long WATER_LOG_MIN_INTERVAL_NS = 1_000_000_000L;
    private int loggedWaterTint = Integer.MIN_VALUE;
    private long loggedWaterTintAt = Long.MIN_VALUE;

    /**
     * Report the water coefficients the shader will derive from this biome's tint.
     *
     * <p>The tint is the only per-biome input, and everything visible underwater comes out of it: swamp
     * water absorbs red and violet and reads green, ocean absorbs red and green and reads blue. What
     * this prints is the same arithmetic {@code waterAbsorption} and {@code waterExtinction} do in the
     * shader, so a colour that looks wrong in the water can be checked against the numbers rather than
     * guessed at — and the extinction is what tints everything seen through the water, so the two should
     * agree by eye.
     *
     * <p>Absorption is per channel and inverted from the tint: a low red tint means red is absorbed
     * fastest, which is why the tint's own colour and the water's apparent colour are not the same
     * thing. Extinction adds the scattered part on top, so it rises with turbidity while the ratio
     * between channels does not.
     *
     * <p>M16 moved the source to a GPU reduction of the sky-view LUT, so this CPU diagnostic deliberately
     * stops at coefficients and albedo. Reading the source back would either stall the frame or report a
     * stale value, and either would make a performance diagnostic less trustworthy than no number.
     */
    private void logWaterCoefficients(float r, float g, float b) {
        if (!FluoriteConfig.Rt.FrameStats.ENABLED.value()) {
            return;
        }
        int shift = 8 - WATER_TINT_LOG_BITS;
        int key = ((Math.round(r * 255f) >> shift) << (WATER_TINT_LOG_BITS * 2))
                | ((Math.round(g * 255f) >> shift) << WATER_TINT_LOG_BITS)
                | (Math.round(b * 255f) >> shift);
        long now = System.nanoTime();
        // The first report is unconditional. Timing it against a sentinel start value does not work:
        // nanoTime differences are the only meaningful quantity, and `now - Long.MIN_VALUE` overflows to
        // a large NEGATIVE number, which compares below any interval and silences the line forever. That
        // is exactly what happened — the instrument built to answer "is the source too bright" reported
        // nothing at all for a whole session, and silence looked identical to "nothing changed".
        boolean everLogged = loggedWaterTint != Integer.MIN_VALUE;
        if (everLogged && (key == loggedWaterTint || now - loggedWaterTintAt < WATER_LOG_MIN_INTERVAL_NS)) {
            return;
        }
        loggedWaterTint = key;
        loggedWaterTintAt = now;
        float[] s = FluoriteConfig.Rt.Water.scatteringRgb();
        float ar = WATER_ABSORB_FLOOR_R + WATER_DENSITY * (1f - Math.clamp(r, 0f, 1f));
        float ag = WATER_ABSORB_FLOOR_G + WATER_DENSITY * (1f - Math.clamp(g, 0f, 1f));
        float ab = WATER_ABSORB_FLOOR_B + WATER_DENSITY * (1f - Math.clamp(b, 0f, 1f));
        // sigma_t = sigma_a + sigma_s, and the albedo is sigma_s over that. Additive and with sigma_s
        // global, exactly as medium.slang has it.
        //
        // This printed the OTHER model until now — extinction as sigma_a*(1+k) and the albedo as k/(1+k),
        // which is sigma_s proportional to sigma_a. That was a real design for two commits and was thrown
        // out because a constant ratio is a grey albedo and every biome came out the same milky white; the
        // shader moved on and this did not. So the one line whose whole job is to be checked against the
        // shader has been reporting a rejected model's numbers, and reporting them for the model that
        // rejection was about. Scattering is printed alongside now, so the next drift shows up as an
        // arithmetic disagreement rather than as a plausible-looking wrong answer.
        float albR = s[0] / (ar + s[0]);
        float albG = s[1] / (ag + s[1]);
        float albB = s[2] / (ab + s[2]);
        FluoriteMod.LOGGER.info(
                "Water: tint=({}, {}, {}) absorption=({}, {}, {}) scattering=({}, {}, {})"
                        + " extinction=({}, {}, {}) scatterAlbedo=({}, {}, {})",
                fmt(r), fmt(g), fmt(b),
                fmt(ar), fmt(ag), fmt(ab),
                fmt(s[0]), fmt(s[1]), fmt(s[2]),
                fmt(ar + s[0]), fmt(ag + s[1]), fmt(ab + s[2]),
                fmt(albR), fmt(albG), fmt(albB));
    }

    private static String fmt(float v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    // Mirrors medium.slang's waterAbsorption. Duplicated deliberately and narrowly: this is a diagnostic
    // that exists to be compared against the shader, so it has to restate the shader's arithmetic rather
    // than share it. If they drift, the log stops being evidence — which is exactly what it is for.
    private static final float WATER_DENSITY = 0.1f;
    private static final float WATER_ABSORB_FLOOR_R = 0.015f;
    private static final float WATER_ABSORB_FLOOR_G = 0.010f;
    private static final float WATER_ABSORB_FLOOR_B = 0.008f;

    /**
     * Shout when the three answers about "is the camera in water" disagree with each other.
     *
     * <p>SILENT UNLESS SOMETHING IS ACTUALLY WRONG, which is what makes it usable against an
     * intermittent fault: it costs a handful of comparisons per frame and says nothing for hours, then
     * records the exact frame the contradiction appears. Reproducing an intermittent bug on demand is
     * expensive; having it write its own evidence down is not.
     *
     * <p>The three have to agree because the shading uses them for different jobs and assumes one world:
     * bit 0 decides which medium the camera ray STARTS in, the reference surface decides how DEEP every
     * scattering point is, and the camera's own height is the thing both are about. A submerged flag with
     * the surface below the eye means the ray starts in water while every depth reads negative -- no
     * absorption anywhere, which is over-bright, and an interface the integrator never enters, which is a
     * water surface that is not there.
     *
     * <p>This file already carries a long note about the last time these two were conflated (depth pinned
     * one block over the camera's head, reported as layered flicker on vertical movement), so the failure
     * mode is not hypothetical.
     */
    private void logWaterMediumContradiction(int flags, float surfaceY, double camY,
                                             int rebaseY, boolean simLive) {
        boolean submerged = (flags & 1) != 0;
        float camRebased = (float) (camY - rebaseY);
        boolean sentinel = surfaceY < -1.0e8f;
        String fault = null;
        if (submerged && sentinel) {
            fault = "submerged but no reference surface was found";
        } else if (submerged && surfaceY < camRebased - 0.01f) {
            fault = "submerged but the surface is BELOW the eye";
        } else if (!submerged && !sentinel && surfaceY > camRebased + 1.0f) {
            fault = "not submerged but the surface is well ABOVE the eye";
        } else if (Float.isNaN(surfaceY) || Double.isNaN(camY)) {
            fault = "NaN in the camera height or the reference surface";
        }
        if (fault == null) {
            waterMediumFaultFrames = 0;
            return;
        }
        // Log the first frame of a fault and then back off, so a persistent contradiction does not
        // become a persistent log.
        if (waterMediumFaultFrames == 0 || (waterMediumFaultFrames % 300) == 0) {
            FluoriteMod.LOGGER.warn(
                    "[water-medium] {} | submerged={} surfaceY(rebased)={} camY(rebased)={} "
                            + "rebaseY={} simLive={} frames={}",
                    fault, submerged,
                    String.format(java.util.Locale.ROOT, "%.3f", surfaceY),
                    String.format(java.util.Locale.ROOT, "%.3f", camRebased),
                    rebaseY, simLive, waterMediumFaultFrames);
        }
        waterMediumFaultFrames++;
    }

    /** How long the current water-medium contradiction has lasted, in frames. 0 = consistent. */
    private int waterMediumFaultFrames;

    /** Absorption dialled by hand, replacing the biome's, for art direction and for diagnosis. */
    private static Float4 waterAbsorbOverride() {
        if (!FluoriteConfig.Rt.Water.ABSORB_OVERRIDE.value()) {
            return new Float4(0f, 0f, 0f, 0f);
        }
        float[] a = FluoriteConfig.Rt.Water.absorptionRgb();
        return new Float4(a[0], a[1], a[2], 1f);
    }

    private static Float4 waterScatter(RtEnvironmentForcing.Frame environment) {
        float[] s = FluoriteConfig.Rt.Water.scatteringRgb();
        float scale = environment.waterScatterScale();
        return new Float4(s[0] * scale, s[1] * scale, s[2] * scale,
                FluoriteConfig.Rt.Water.PHASE_G.value());
    }

    /**
     * x is the wave field's amplitude (D49); yz retired by M16; w carries caustic dispersion.
     *
     * <p>The amplitude spends one of M16's dead slots rather than growing the pinned layout for a single
     * float. It scales the field's height and its slope by the same factor, because they are the same
     * function — the shading normal and the displaced geometry have to describe one surface.
     */
    /**
     * The wave field's shape: the second system's heading, the complexity ramp, the steepness scale.
     *
     * <p>The cross heading is resolved HERE, folded with the complexity, so that at complexity 0 it comes
     * out exactly equal to the primary wind and the shader's select changes nothing. That is what makes
     * the off state bit-identical rather than merely similar.
     */
    private static Float4 waterWaveShape(float waterStorm) {
        float complexity = FluoriteConfig.Rt.Water.WAVE_COMPLEXITY.value();
        double primary = Math.toRadians(FluoriteConfig.Rt.Water.waveWindAngle());
        double cross = primary
                + Math.toRadians(FluoriteConfig.Rt.Water.WAVE_CROSS_ANGLE.value()) * complexity;
        // Steepness, not just height. A storm sea is steeper, and that is the part that reads as
        // violent -- scaling the amplitude alone gives a bigger calm sea.
        return new Float4((float) Math.cos(cross), (float) Math.sin(cross), complexity,
                1f + waterStorm * 0.6f);
    }

    /** Gust patches (D52). Zero strength disables the whole term, including its noise fetches. */
    /** Domain warp (D53): the one thing measured to actually remove the lattice. */
    private static Float4 waterWaveWarp() {
        // CLAMPED SO THE WARP CANNOT FOLD. The map is p -> p + A*n(p/L), whose Jacobian is
        // I + (A/L)*grad(n). Once (A/L) grows enough, det J passes through zero and the map stops being
        // injective: two places map to one, and the field creases along the boundary between them. That
        // is the seam, and the rings that appear when the amplitude is pushed are the same fault seen
        // from inside.
        //
        // Measured rather than guessed: |grad n| tops out at 2.0 for this noise, and det J first goes
        // negative between A/L = 0.30 and 0.50. A quarter leaves det J above 0.35 everywhere, which is
        // a full factor of margin, and the authored range reached A/L = 2.0 -- det J = -12.8.
        float warpScale = FluoriteConfig.Rt.Water.WAVE_WARP_SCALE.value();
        float warp = Math.min(FluoriteConfig.Rt.Water.WAVE_WARP.value(), 0.25f * warpScale);
        int first = Math.round(FluoriteConfig.Rt.Water.WAVE_FIRST.value());
        int last = Math.max(first, Math.round(FluoriteConfig.Rt.Water.WAVE_LAST.value()));
        return new Float4(warp, warpScale,
                first * 16 + last,
                FluoriteConfig.Rt.Water.WAVE_BAND_LIMIT.value() ? 1f : 0f);
    }

    private static Float4 waterWaveGust(float waterStorm) {
        // Storms gust harder. Saturated because the modulation is a lerp toward the patch field and
        // going past 1 would start inverting quiet patches into loud ones.
        float gust = Math.min(1f, FluoriteConfig.Rt.Water.WAVE_GUST.value()
                * (1f + waterStorm * 0.8f));
        return new Float4(FluoriteConfig.Rt.Water.WAVE_GUST_SCALE.value(), gust,
                FluoriteConfig.Rt.Water.WAVE_GUST_SPEED.value(),
                FluoriteConfig.Rt.Water.WAVE_SPEED.value());
    }

    private static Float4 waterAux(float waterStorm) {
        return new Float4(FluoriteConfig.Rt.Water.WAVE_AMPLITUDE.value()
                        * (1f + waterStorm * 0.9f),
                // D72: never mutate wavelength against an absolute phase clock. Weather transfers
                // weight toward the existing long bands through waterSimPlane.z instead, so every
                // crest keeps its position while the sea state changes.
                FluoriteConfig.Rt.Water.WAVE_LENGTH.value(),
                (float) Math.toRadians(FluoriteConfig.Rt.Water.waveWindAngle()),
                FluoriteConfig.Rt.Water.CAUSTIC_DISPERSION.value());
    }

    /**
     * D72's water-only weather state. A unit clear-to-rain change takes twenty game seconds; changing
     * world resets to that world's current state, while /time jumps cannot fast-forward or reverse the
     * transition. Keeping this on the CPU preserves the existing shader ABI and removes a per-ray clock.
     */
    private float smoothWaterStorm(ClientLevel level, RtEnvironmentForcing.Frame environment) {
        float target = Math.max(environment.waterStorm(), 0f);
        double now = environment.gameSeconds();
        if (level != waterWeatherLevel || !Double.isFinite(waterWeatherSeconds)) {
            waterWeatherLevel = level;
            waterWeatherSeconds = now;
            smoothedWaterStorm = target;
            return smoothedWaterStorm;
        }
        double elapsed = now - waterWeatherSeconds;
        waterWeatherSeconds = now;
        // Normal frame deltas are about 0.05 s. Larger discontinuities are time commands, world catch-up
        // or suspension; none should teleport a visible sea state.
        if (elapsed > 0.0 && elapsed <= 1.0) {
            smoothedWaterStorm = RtEnvironmentForcing.moveTowards(
                    smoothedWaterStorm, target, (float) elapsed, 20f);
        }
        return smoothedWaterStorm;
    }

    /**
     * D106's two world-global water reservoirs. They retain weather history without a texture readback;
     * exposure and material response are multiplied later at each path vertex. A newly joined world has
     * no stored weather history, so it starts at the current rain fraction instead of visibly pretending
     * that an already-raining world has been bone dry for hours.
     */
    private RainSurfaceState smoothRainSurfaces(ClientLevel level, RtEnvironmentForcing.Frame environment,
                                                float dayFactor, Float4 finalCloudParams) {
        float rain = Math.clamp(environment.rain(), 0f, 1f);
        double now = environment.gameSeconds();
        if (level != rainSurfaceLevel || !Double.isFinite(rainSurfaceSeconds)) {
            rainSurfaceLevel = level;
            rainSurfaceSeconds = now;
            wetFilmStorage = rain;
            puddleStorage = rain;
            return new RainSurfaceState(wetFilmStorage, puddleStorage, rain, 0f, 1f);
        }
        double elapsed = now - rainSurfaceSeconds;
        rainSurfaceSeconds = now;
        // A command, catch-up or pause is not elapsed weather simulation. Consume only adjacent frames,
        // exactly like D72's sea state, so /time set cannot instantly dry a world.
        float drying = FluoriteConfig.Rt.Weather.DAYLIGHT_DRYING.value()
                ? RtEnvironmentForcing.dryingScale(dayFactor, finalCloudParams.y(), finalCloudParams.x())
                : 1f;
        float frameElapsed = elapsed > 0.0 && elapsed <= 1.0 ? (float) elapsed : 0f;
        if (frameElapsed > 0f) {
            wetFilmStorage = RtEnvironmentForcing.advanceRainStorage(wetFilmStorage, rain, (float) elapsed,
                    FluoriteConfig.Rt.Weather.WET_FILL_SECONDS.value(),
                    FluoriteConfig.Rt.Weather.WET_DRY_SECONDS.value(), drying);
            puddleStorage = RtEnvironmentForcing.advanceRainStorage(puddleStorage, rain, (float) elapsed,
                    FluoriteConfig.Rt.Weather.PUDDLE_FILL_SECONDS.value(),
                    FluoriteConfig.Rt.Weather.PUDDLE_DRY_SECONDS.value(), drying);
        }
        return new RainSurfaceState(wetFilmStorage, puddleStorage, rain, frameElapsed, drying);
    }

    private record RainSurfaceState(float wetFilm, float puddle, float rain,
                                    float elapsedSeconds, float dryingScale) {
    }

    /** CPU and shader views of the same snapped D105 exposure square. */
    private record RainExposureFrame(int originX, int originZ, int resolution,
                                     Float4 origin, Float4 direction, Float4 state,
                                     Float4 materialDefaults, Float4 surface, Float4 puddle,
                                     Float4 calibration0, Float4 calibration1) {
        private static RainExposureFrame off() {
            Float4 zero = new Float4(0f, 0f, 0f, 0f);
            return new RainExposureFrame(0, 0, 0, zero, zero, zero, zero, zero, zero, zero, zero);
        }
    }

    /**
     * Place the rain map in absolute coordinates, then rebase it. The map is centred on where the rain
     * ray through the camera intersects the top plane, not blindly on camera xz; otherwise an 8° slant
     * shifts the useful footprint sideways by dozens of blocks across the world's height.
     */
    private RainExposureFrame rainExposureFrame(double camX, double camY, double camZ,
                                                ClientLevel level, RtTerrain terrain,
                                                float animationTime,
                                                RainSurfaceState storage) {
        boolean needsWetSurface = FluoriteConfig.Rt.Weather.RAIN_SURFACES_ENABLED.value()
                && Math.max(storage.wetFilm(), storage.puddle()) > 1.0e-5f;
        boolean needsVisibleRain = FluoriteConfig.Rt.Weather.RAIN_PARTICLES_ENABLED.value()
                && FluoriteConfig.Rt.Weather.RAIN_STREAK_DENSITY.value() > 1.0e-5f
                && storage.rain() > 1.0e-5f;
        if (level == null || !(needsWetSurface || needsVisibleRain)) {
            return RainExposureFrame.off();
        }
        int resolution = FluoriteConfig.Rt.Weather.rainExposureResolution();
        double heading = Math.toRadians(FluoriteConfig.Rt.Composite.WIND_ANGLE.value());
        double slant = Math.toRadians(FluoriteConfig.Rt.Weather.RAIN_SLANT_DEGREES.value());
        float horizontal = (float) Math.sin(slant);
        float dirX = (float) (Math.cos(heading) * horizontal);
        float dirY = -(float) Math.cos(slant);
        float dirZ = (float) (Math.sin(heading) * horizontal);
        int topY = level.getMinY() + level.getHeight() + 1;
        int bottomY = level.getMinY() - 1;
        double cameraT = (camY - topY) / dirY;
        double cameraEntryX = camX - dirX * cameraT;
        double cameraEntryZ = camZ - dirZ * cameraT;
        // The precipitation cache is one cell per four blocks. Snapping the whole depth square to that
        // lattice avoids rebuilding 16k biome classifications for every single-block camera step.
        int desiredOriginX = (int) Math.floor(cameraEntryX / 4.0) * 4 - resolution / 2;
        int desiredOriginZ = (int) Math.floor(cameraEntryZ / 4.0) * 4 - resolution / 2;
        double anchoredCentreX = rainExposureAnchorOriginX + resolution * 0.5;
        double anchoredCentreZ = rainExposureAnchorOriginZ + resolution * 0.5;
        boolean reanchor = !rainExposureAnchorValid || rainExposureAnchorLevel != level
                || rainExposureAnchorResolution != resolution
                || Math.abs(cameraEntryX - anchoredCentreX) >= RAIN_EXPOSURE_REANCHOR_DISTANCE
                || Math.abs(cameraEntryZ - anchoredCentreZ) >= RAIN_EXPOSURE_REANCHOR_DISTANCE;
        if (reanchor) {
            rainExposureAnchorLevel = level;
            rainExposureAnchorResolution = resolution;
            rainExposureAnchorOriginX = desiredOriginX;
            rainExposureAnchorOriginZ = desiredOriginZ;
            rainExposureAnchorValid = true;
        }
        int originX = rainExposureAnchorOriginX;
        int originZ = rainExposureAnchorOriginZ;
        float maxT = (bottomY - topY) / dirY;
        Float4 origin = new Float4(originX - terrain.blockX, topY - terrain.blockY,
                originZ - terrain.blockZ, resolution);
        Float4 direction = new Float4(dirX, dirY, dirZ, maxT);
        Float4 state = new Float4(needsWetSurface ? storage.wetFilm() : 0f,
                needsWetSurface ? storage.puddle() : 0f, storage.rain(),
                (needsWetSurface || needsVisibleRain) ? 1f : 0f);
        Float4 defaults = new Float4(FluoriteConfig.Rt.Weather.DEFAULT_WET_ABSORPTION.value(),
                FluoriteConfig.Rt.Weather.DEFAULT_WET_DARKENING.value(),
                FluoriteConfig.Rt.Weather.DEFAULT_WET_FILM.value(),
                FluoriteConfig.Rt.Weather.DEFAULT_PUDDLE_AFFINITY.value());
        Float4 surface = new Float4(FluoriteConfig.Rt.Weather.WET_FILM_STRENGTH.value(),
                FluoriteConfig.Rt.Weather.WET_FILM_ROUGHNESS.value(),
                FluoriteConfig.Rt.Weather.PUDDLE_COVERAGE.value(),
                FluoriteConfig.Rt.Weather.PUDDLE_SCALE.value());
        Float4 puddle = new Float4(FluoriteConfig.Rt.Weather.PUDDLE_RIPPLE_STRENGTH.value(),
                FluoriteConfig.Rt.Weather.PUDDLES_ENABLED.value() ? 1f : 0f,
                FluoriteConfig.Rt.Weather.RAIN_RIPPLE_SIZE.value(),
                animationTime);
        Float4 calibration0 = new Float4(FluoriteConfig.Rt.Weather.WET_DARKENING_GAIN.value(),
                FluoriteConfig.Rt.Weather.WET_COAT_GAIN.value(),
                FluoriteConfig.Rt.Weather.PUDDLE_LAYER_GAIN.value(),
                FluoriteConfig.Rt.Weather.PUDDLE_ROUGHNESS.value());
        Float4 calibration1 = new Float4(FluoriteConfig.Rt.Weather.PUDDLE_EXTRA_DARKENING.value(),
                FluoriteConfig.Rt.Weather.PUDDLE_NORMAL_FLATTENING.value(),
                FluoriteConfig.Rt.Weather.RAIN_RIPPLE_WIDTH.value(),
                FluoriteConfig.Rt.Weather.WET_FILM_NORMAL_FLATTENING.value());
        return new RainExposureFrame(originX, originZ, resolution, origin, direction, state,
                defaults, surface, puddle, calibration0, calibration1);
    }

    /** M13 heterogeneous controls in the retired xyz lanes; w remains thin-shell thickness. */
    private static Float4 fogAux(RtSkyPreset preset, RtEnvironmentForcing.Frame environment) {
        float structure = preset.fog().noise() ? environment.fogStructureContrast() : 0f;
        return new Float4(structure,
                0.25f, FluoriteConfig.Rt.Volumetrics.fogNoiseMarchSteps(),
                FluoriteConfig.Rt.Bsdf.SUBSURFACE_THICKNESS.value());
    }

    /**
     * Stable absolute coordinates for the fog texture. D69 shares the world's weather heading while the
     * near-ground layer keeps its own angular offset and speed; it does not inherit either cloud layer's
     * velocity.
     * The shader sees terrain-rebased positions, so the terrain origin has to be added back here; without
     * it the whole density field would jump whenever the terrain rebase followed the camera.
     */
    private static Float4 fogNoiseOrigin(RtTerrain terrain, RtEnvironmentForcing.Frame environment) {
        double[] drift = windDrift(environment,
                FluoriteConfig.Rt.Volumetrics.FOG_NOISE_WIND_SPEED.value(),
                FluoriteConfig.Rt.Volumetrics.fogNoiseWindAngle());
        return new Float4((float) (terrain.blockX - drift[0]), terrain.blockY,
                (float) (terrain.blockZ - drift[1]),
                FluoriteConfig.Rt.Volumetrics.FOG_NOISE_FIELD_SCALE.value());
    }

    /**
     * How far up the water column the reference-surface scan will walk before giving up.
     *
     * <p>Generous rather than tuned: the loop stops at the first non-water block, so it only runs to this
     * limit for a camera genuinely that deep under water, and each step is one fluid-state lookup on the
     * client's own chunk cache. A camera in an ocean exits after a couple of steps.
     */
    private static final int WATER_SURFACE_SCAN_LIMIT = 512;

    /**
     * Shading switches the closest-hit reads. Inline in the push constant rather than in WorldPush
     * because the hit shader never dereferences that struct — one BDA load per hit to read a bit would
     * cost more than the feature it gates.
     */
    private static int shadeFlags() {
        int flags = 0;
        if (FluoriteConfig.Rt.Bsdf.SUBSURFACE_SOLID_LAYER.value()) {
            flags |= 1; // SHADE_SOLID_LAYER_SSS
        }
        return flags;
    }

    private static int debugView() {
        return FluoriteConfig.Rt.Composite.DEBUG_VIEW.value();
    }

    private static int spp() {
        return FluoriteConfig.Rt.Composite.SPP.value();
    }

    private static int maxBounces() {
        return FluoriteConfig.Rt.Composite.MAX_BOUNCES.value();
    }

    private static boolean waterWaves() {
        return FluoriteConfig.Rt.Composite.WATER_WAVES.value();
    }

    // Finite sun/moon angular sizes let NEE shadow rays sample the light disk (soft, contact-hardening
    // penumbrae). Radii in degrees; the real sun/moon are ~0.27°, but a touch larger reads pleasantly.
    private static final int WATER_ANCHOR_MASK = 4095;
    /**
     * The cell size the authored range implies: the grid is a fixed 256 cells wide (D39).
     *
     * <p>Through {@code simRangeBlocks} rather than the raw setting, so that turning the deformation on
     * pulls the ripple domain in to match it (D45) and the freed resolution goes where the geometry can
     * actually show it.
     */
    private static float waterCellSize() {
        return FluoriteConfig.Rt.Water.simRangeBlocks() / RtSky.WATER_SIM_DIM;
    }
    /** How far above and below the camera to look for the water surface the domain runs on. */
    private static int waterProbeReach() {
        return Math.round(FluoriteConfig.Rt.Water.WATER_SIM_HEIGHT.value());
    }
    /** Smallest patch an entity disturbs, in BLOCKS — so the size of a splash does not follow the grid. */
    private static final double WATER_IMPULSE_MIN_RADIUS = 0.5;
    /** How far from the feet still counts as touching water, in blocks. Contact is by definition close. */
    private static final int WATER_CONTACT_REACH = 2;
    /** How far up a water column the surface may be from the cell touched. Bounds a deep-ocean walk. */
    private static final int WATER_CONTACT_CLIMB = 32;
    // Where the water simulation's domain sits, in ABSOLUTE whole cells, and the surface it runs on.
    // Whole cells because a domain that slid continuously with the player would resample the height
    // field at a different phase every frame and smear every ripple into a streak (R22).
    private long waterCellX = Long.MIN_VALUE;
    private long waterCellZ;
    private double waterSurfaceY = Double.NaN;
    /** The same plane, rebased, which is the space the shading compares its hits in. */
    private float waterPlaneRebasedY;
    private Float4 waterDomain = new Float4(0f, 0f, 0f, 0f);
    private boolean waterReanchor;
    private final float[] waterImpulses = new float[RtSky.WATER_MAX_IMPULSES * 4];
    private int waterImpulseCount;
    private byte[] waterObstacleMask;
    private boolean waterMaskUploaded;

    /** Frames since start, for the debug view's periodic test impulse. */
    private long worldFrameCounter() {
        return frameCounter;
    }
    private static final Identifier SUN_ID = Identifier.withDefaultNamespace("sun");
    private static final Identifier[] MOON_IDS = createMoonIds();
    // Celestial rotation axis (the pole the sun/moon arc about): perpendicular to the east-west arc,
    // tilted by SUN_NOON_SOUTH_TILT. Pushed so the sky shader can build the sun/moon square's tangent
    // frame (right = travel direction) and wheel the starfield. = normalize(noonDir x sunriseDir).
    // Sign of the sub-pixel jitter as reported to DLSS-RR + applied to the primary ray, mirroring the
    // validated DLSS-SR convention (Vulkan flipped clip space wants Y negated).
    private static float jitterSignX() {
        return FluoriteConfig.Rt.Composite.JITTER_SIGN_X.value();
    }

    private static float jitterSignY() {
        return FluoriteConfig.Rt.Composite.JITTER_SIGN_Y.value();
    }

    private static float sunNoonTilt() {
        return FluoriteConfig.Rt.Composite.SUN_NOON_SOUTH_TILT.value();
    }

    private static float sunNoonY() {
        return Mth.cos(sunNoonTilt());
    }

    private static float sunNoonZ() {
        return Mth.sin(sunNoonTilt());
    }

    private static float celestialAxisY() {
        return -sunNoonZ();
    }

    private static float celestialAxisZ() {
        return sunNoonY();
    }

    // Monotonic per-composite frame counter used for cache eviction, shader sampling, and diagnostics.
    private static volatile long frameCounter;

    public static long frameCounter() {
        return frameCounter;
    }

    private RtPipeline worldPipeline;
    // Set at the HEAD of Minecraft.reloadResourcePacks() (mixin): a resource reload recreates the block
    // atlas + entity textures. We tear down the world pipeline there (drops all descriptor references) and
    // rebuild it once the NEW atlas is in place — detected by the atlas view handle changing away from
    // boundBlockAlbedoAtlasHandle to a fresh non-zero value (MC's deferred free keeps the old handle live for a few
    // frames, so "handle != 0" alone isn't enough to tell old from new).
    private volatile boolean reloadRebindRequested;
    // The block-atlas view handle currently bound into the world pipeline (set by bindWorldTextures).
    private long boundBlockAlbedoAtlasHandle;
    private int bindlessTextureCapacity;
    // True after the LabPBR atlases have been resolved/bound for the currently alive world pipeline.
    private boolean materialBindingsReady;
    // Set when a new material epoch is published. The first composite returns to vanilla so the next
    // client tick can apply RtTerrain's full-clear before any old-epoch primitive IDs are traced.
    private boolean materialEpochTraceGate;
    // World push data lives in a host-visible BDA ring; only the slot address and a small hot subset are
    // pushed inline (the full generated structure exceeds NVIDIA's 256-byte push-constant ceiling).
    // Exact graphics completion guards host writes; ring depth only avoids routine waits.
    private static final int PUSH_RING = 6;
    private PushSlot[] pushRing;
    private int pushSlot;
    // Device-side timing for the two trace dispatches. Shares the push ring's slot index and its graphics-use
    // wait, so a slot is only read once the work that wrote it has completed and the read never blocks.
    private static final int GPU_ZONE_TRACE_PRIMARY = 0;
    private static final int GPU_ZONE_TRACE_INDIRECT = 1;
    // The atmosphere's bakes, which sit BEFORE both traces and so fell inside neither zone. M10.4's
    // capture is what exposed the gap: gpu.traceIndirect fell 0.575 ms when the camera's prefix segment
    // stopped integrating analytically, and there was no number anywhere for what the froxel dispatch
    // that made it possible had cost. A saving measured against an unmeasured cost is not a net figure,
    // and M13 grows this dispatch rather than the traces, so the zone has to exist before then.
    private static final int GPU_ZONE_SKY_BAKE = 2;
    // The volumetric visibility grid, timed SEPARATELY from the bakes above it rather than inside them.
    // The combined zone measured 1.258 ms at 1080p once the grid was added, against 0.106 ms before it,
    // and there was no way to say how much of that twelvefold rise was the grid and how much was the
    // froxel's own growth to 64x36x64 with two rays a cell -- two changes that landed without a reading
    // between them. Deciding whether to spend more rays here needs the two numbers apart.
    private static final int GPU_ZONE_VIS_BAKE = 3;
    // The froxel, split out of GPU_ZONE_SKY_BAKE. That zone held the three sky tables AND the froxel, and
    // the combined 1.34 ms could not say which of them to spend effort on -- the froxel runs one thread
    // per COLUMN (2304 of them) while the visibility grid runs one per cell (131k) for a comparable ray
    // count and costs 0.072 ms. Eighteen times is a number worth acting on, but not before it is
    // attributed.
    private static final int GPU_ZONE_FROXEL_BAKE = 4;
    // The two water passes. Neither had been attributed: the deformation only ever had a CPU-side stage,
    // which times the thread recording the commands rather than the device doing the work -- for a
    // dispatch those differ by orders of magnitude, and iron rule 7 does not accept the former.
    //
    // The deformation's number is the one the milestone actually needs. It decides whether the cheaper
    // rebuild-only path is worth building, and whether the deformation range's quadratic cost bites.
    //
    // NOTE WHAT CANNOT BE A ZONE: the procedural wave spectrum. It is evaluated inline inside the raygen
    // shaders, and a timestamp brackets commands, not parts of a shader. Its cost is measured by the
    // isolation switch instead -- water.waves off against on, comparing gpu.tracePrimary and
    // gpu.traceIndirect in the same session -- which is the ratio method iron rule 7 asks for regardless.
    private static final int GPU_ZONE_WATER_SIM = 5;
    private static final int GPU_ZONE_WATER_DEFORM = 6;
    // The two acceleration-structure builds, both of which were measurement blind spots. Only their CPU
    // RECORD times existed (entity.blasRecord, frame.recordTlas), and those say how long it took to write
    // the commands, not how long the device took to run them.
    //
    // They are here because the water deformation's number cannot be judged without them. Water refits
    // four section BLASes for 0.27 ms while entities refit sixty for a comparable CPU cost, which points
    // at per-refit SIZE rather than count -- a terrain section carries all its stone, a mob carries a few
    // hundred triangles. That is an inference until the entity builds have a device number of their own.
    //
    // And the TLAS one bounds the fix. Splitting water into its own BLAS would cut the refit down to the
    // triangles that actually moved, at the price of one more instance per water-bearing section; without
    // a TLAS zone there is no way to tell whether that price is smaller than the saving.
    private static final int GPU_ZONE_ENTITY_BLAS = 7;
    private static final int GPU_ZONE_TLAS_BUILD = 8;
    /** D105's directional exposure map: comparable ray count to the high visibility grid, separate cost. */
    private static final int GPU_ZONE_RAIN_EXPOSURE = 9;
    private static final int GPU_ZONE_RAIN_STREAK = 10;
    private static final int GPU_ZONE_LENS_SPATIAL = 11;
    private static final int GPU_ZONE_DISPLAY_MAP = 12;
    private static final int GPU_ZONE_BLOOM_FLARE = 13;
    private RtGpuTimers gpuTimers;
    private RtDisplayPipeline displayPipeline;
    private RtLensPipeline lensPipeline;
    private RtDepthOfFieldPipeline depthOfFieldPipeline;
    private RtBloomFlarePipeline bloomFlarePipeline;
    private RtImage lensScratch;
    private RtImage lensFocus;
    private RtImage depthOfFieldCoc;
    private RtImage depthOfFieldNear;
    private RtImage depthOfFieldFar;
    private RtImage depthOfFieldTiles;
    private RtImage depthOfFieldDilatedTiles;
    private final RtImage[] bloomBright = new RtImage[RtBloomFlarePipeline.LEVEL_COUNT];
    private final RtImage[] bloomPyramid = new RtImage[RtBloomFlarePipeline.LEVEL_COUNT];
    private RtImage flareBokeh;
    private boolean lensFinalScratch;
    private final RtRainStreaks rainStreaks = new RtRainStreaks();
    private RtImage output;
    // Packed primary -> indirect continuations. Pass A is fixed at one sample and owns two records per
    // render pixel (base + optional transmission); Pass B resamples them at the configured SPP.
    private RtBuffer continuationQueue;
    private RtImage displayImage;
    // Parallel PQ-encoded ([0,1], ST.2084) HDR display image. Written alongside displayImage when HDR is
    // enabled. When the PQ swapchain is active, the combined UI overlay is composited over this image, then
    // this image is blitted straight to the swapchain.
    private RtImage hdrDisplayImage;
    // Set true after this frame's display dispatch wrote hdrDisplayImage (HDR enabled + RT ran); gates the
    // HDR present blit so a frame where RT did not run falls back to the vanilla SDR present.
    private boolean hdrWrittenThisFrame;
    // DLSS-FG "hudless" resource: a copy of the main render target before the combined UI overlay
    // composites back on top. Lazily allocated (only meaningful once FG + the UI overlay redirect are both
    // active), resized on demand.
    private RtImage fgHudlessImage;
    // Same idea as fgHudlessImage but for the HDR present path: a copy of hdrDisplayImage taken in
    // presentHdr right before its own combined-UI composite dispatch overwrites it in place (see
    // captureFgHdrHudless). Already PQ-encoded (same as hdrDisplayImage), so this is a plain image copy, not
    // a format conversion — DLSS-FG requires a display-ready EOTF-encoded [0,1] signal (its programming
    // guide explicitly disallows scRGB), and PQ is exactly that.
    private RtImage fgHdrHudlessImage;
    // Step C.2: composites the combined UI overlay over hdrDisplayImage at paper white, just before present.
    private RtHdrCompositePipeline hdrCompositePipeline;
    private long hdrUiSampler;

    private static final class PushSlot {
        final RtBuffer buffer;
        final RtBuffer waterProbe;
        final RtGpuExecutor.TrackedGraphicsUse graphicsUse = new RtGpuExecutor.TrackedGraphicsUse();
        boolean waterProbeArmed;
        RtTerrain.StreamingDiagnostics waterProbeTerrain;
        int waterProbeCpuFlags;

        PushSlot(RtBuffer buffer, RtBuffer waterProbe) {
            this.buffer = buffer;
            this.waterProbe = waterProbe;
        }
    }
    private static final long WATER_PROBE_LOG_INTERVAL_NS = 250_000_000L;
    private long waterProbeLoggedAt = Long.MIN_VALUE;

    /** Reads an old ring slot only after its graphics timeline has completed; this never stalls the GPU
     * that is rendering the current frame. */
    private void logWaterMediumProbe(PushSlot slot) {
        if (!slot.waterProbeArmed) {
            return;
        }
        slot.waterProbe.invalidate(0L, WaterMediumProbeData.BYTE_SIZE);
        long base = slot.waterProbe.mapped;
        int probeFlags = MemoryUtil.memGetInt(base + WaterMediumProbeData.FLAGS_OFFSET);
        if ((probeFlags & 1) == 0) {
            return;
        }
        long now = System.nanoTime();
        if (waterProbeLoggedAt != Long.MIN_VALUE && now - waterProbeLoggedAt < WATER_PROBE_LOG_INTERVAL_NS) {
            return;
        }
        waterProbeLoggedAt = now;

        int gpuFrame = MemoryUtil.memGetInt(base + WaterMediumProbeData.FRAME_INDEX_OFFSET);
        int pathFlags = MemoryUtil.memGetInt(base + WaterMediumProbeData.PATH_FLAGS_OFFSET);
        int debug = MemoryUtil.memGetInt(base + WaterMediumProbeData.DEBUG_VIEW_OFFSET);
        long ps = base + WaterMediumProbeData.PREFIX_SCATTER_LENGTH_OFFSET;
        long pt = base + WaterMediumProbeData.PREFIX_TRANSMITTANCE_OFFSET;
        long leaf = base + WaterMediumProbeData.LEAF_RADIANCE_OFFSET;
        long comp = base + WaterMediumProbeData.COMPOSITE_RADIANCE_OFFSET;
        long sky = base + WaterMediumProbeData.SKY_SOURCE_OPEN_OFFSET;
        long up = base + WaterMediumProbeData.UPWARD_WATER_OFFSET;
        long firstScatter = base + WaterMediumProbeData.FIRST_SEGMENT_SCATTER_OFFSET;
        long firstT = base + WaterMediumProbeData.FIRST_SEGMENT_T_OFFSET;
        long firstHit = base + WaterMediumProbeData.FIRST_HIT_OFFSET;
        long firstThroughput = base + WaterMediumProbeData.FIRST_THROUGHPUT_OFFSET;
        long firstWeightedScatter = base + WaterMediumProbeData.FIRST_WEIGHTED_SCATTER_OFFSET;
        RtTerrain.StreamingDiagnostics terrain = slot.waterProbeTerrain;
        String terrainText = terrain == null ? "unavailable" : String.format(java.util.Locale.ROOT,
                "resident=%d published=%d desired=%d empty=%d inFlight=%d missing=%d reextract=%d"
                        + " prepared=%d completed=%d instances=%d",
                terrain.resident(), terrain.published(), terrain.desired(), terrain.empty(),
                terrain.inFlight(), terrain.missing(), terrain.reextract(), terrain.prepared(),
                terrain.completed(), terrain.instances());
        FluoriteMod.LOGGER.info(
                "RT water-medium probe: gpuFrame={} debug={} probeFlags=0x{} cpuFlags=0x{} pathFlags=0x{}"
                        + " prefixLen={} prefixScatter=({},{},{}) prefixT=({},{},{}) prefixTLum={}"
                        + " leaf=({},{},{}) leafLum={} composite=({},{},{}) prefixFraction={}"
                        + " skySource=({},{},{}) skyOpen={} upWater={} skyDepth={} fallbackDepth={}"
                        + " surfaceY={} firstSegmentLen={} firstScatter=({},{},{}) firstT=({},{},{})"
                        + " firstTLum={} firstHitT={} firstMaterial={} firstEscaped={} firstMediumProfile={}"
                        + " firstThroughput=({},{},{}) firstThroughputLum={}"
                        + " firstWeightedScatter=({},{},{}) firstWeightedScatterLum={}"
                        + " terrain[{}]",
                gpuFrame, debug, Integer.toHexString(probeFlags), Integer.toHexString(slot.waterProbeCpuFlags),
                Integer.toHexString(pathFlags),
                fmt(MemoryUtil.memGetFloat(ps + 12)),
                fmt(MemoryUtil.memGetFloat(ps)), fmt(MemoryUtil.memGetFloat(ps + 4)), fmt(MemoryUtil.memGetFloat(ps + 8)),
                fmt(MemoryUtil.memGetFloat(pt)), fmt(MemoryUtil.memGetFloat(pt + 4)), fmt(MemoryUtil.memGetFloat(pt + 8)),
                fmt(MemoryUtil.memGetFloat(pt + 12)),
                fmt(MemoryUtil.memGetFloat(leaf)), fmt(MemoryUtil.memGetFloat(leaf + 4)), fmt(MemoryUtil.memGetFloat(leaf + 8)),
                fmt(MemoryUtil.memGetFloat(leaf + 12)),
                fmt(MemoryUtil.memGetFloat(comp)), fmt(MemoryUtil.memGetFloat(comp + 4)), fmt(MemoryUtil.memGetFloat(comp + 8)),
                fmt(MemoryUtil.memGetFloat(comp + 12)),
                fmt(MemoryUtil.memGetFloat(sky)), fmt(MemoryUtil.memGetFloat(sky + 4)), fmt(MemoryUtil.memGetFloat(sky + 8)),
                fmt(MemoryUtil.memGetFloat(sky + 12)),
                fmt(MemoryUtil.memGetFloat(up)), fmt(MemoryUtil.memGetFloat(up + 4)),
                fmt(MemoryUtil.memGetFloat(up + 8)), fmt(MemoryUtil.memGetFloat(up + 12)),
                fmt(MemoryUtil.memGetFloat(firstScatter + 12)),
                fmt(MemoryUtil.memGetFloat(firstScatter)), fmt(MemoryUtil.memGetFloat(firstScatter + 4)),
                fmt(MemoryUtil.memGetFloat(firstScatter + 8)),
                fmt(MemoryUtil.memGetFloat(firstT)), fmt(MemoryUtil.memGetFloat(firstT + 4)),
                fmt(MemoryUtil.memGetFloat(firstT + 8)), fmt(MemoryUtil.memGetFloat(firstT + 12)),
                fmt(MemoryUtil.memGetFloat(firstHit)), Math.round(MemoryUtil.memGetFloat(firstHit + 4)),
                Math.round(MemoryUtil.memGetFloat(firstHit + 8)),
                Math.round(MemoryUtil.memGetFloat(firstHit + 12)),
                fmt(MemoryUtil.memGetFloat(firstThroughput)),
                fmt(MemoryUtil.memGetFloat(firstThroughput + 4)),
                fmt(MemoryUtil.memGetFloat(firstThroughput + 8)),
                fmt(MemoryUtil.memGetFloat(firstThroughput + 12)),
                fmt(MemoryUtil.memGetFloat(firstWeightedScatter)),
                fmt(MemoryUtil.memGetFloat(firstWeightedScatter + 4)),
                fmt(MemoryUtil.memGetFloat(firstWeightedScatter + 8)),
                fmt(MemoryUtil.memGetFloat(firstWeightedScatter + 12)),
                terrainText);
    }
    // Menu/non-RT present: converts the SDR main target (sRGB) to PQ-encoded at paper white so menus,
    // the title panorama and the loading screen present correctly to the PQ swapchain instead of being
    // raw-copied (misdisplayed). Lazily created; the image is sized to the swapchain.
    private RtSdrPresentPipeline sdrPresentPipeline;
    private RtImage sdrPresentImage;
    // DLSS Frame Generation: per-generated-frame interpolated output images (backbuffer size/format), and
    // the jitter-free reprojection matrices derived from the MV view-projections each frame. In HDR mode
    // these hold DLSSG's raw PQ-encoded output, which is blitted straight to the (PQ) swapchain — no decode
    // needed since the swapchain itself is PQ-native.
    private RtImage[] fgInterp = new RtImage[0];
    private int fgInterpW = -1;
    private int fgInterpH = -1;
    private int fgInterpFormat = Integer.MIN_VALUE;
    private boolean fgReset = true;
    private final Matrix4f fgClipToPrev = new Matrix4f();
    private final Matrix4f fgPrevToClip = new Matrix4f();
    private final Matrix4f fgMatTmp = new Matrix4f();
    // Guide buffers (first-hit attributes for DLSS-RR): normal+roughness, albedo, hardware reversed-Z
    // depth, motion, specular albedo, and reflection motion.
    private RtImage gNormal;
    private RtImage gAlbedo;
    private RtImage gDepth;
    private RtImage gMotion;
    private RtImage gSpecAlbedo;
    private RtImage gSpecMotion;
    // Display-res RT image the display mapper reads: DLSS-RR writes it (render -> display denoise+upscale), or a
    // linear blit of `output` fills it when RR is off/unavailable (the no-RR reference / fallback).
    private RtImage rrOutput;
    private final RtExposure exposure = new RtExposure();

    // Trace + guide buffers run at render res; composite (display-mapping) runs at display res.
    private int displayW = -1;
    private int displayH = -1;
    private int renderW = -1;
    private int renderH = -1;
    // What ensureOutput last sized the render/guide images for, so a quality change (or RR being
    // toggled) at a fixed window size is noticed even though displayW/displayH didn't change.
    private boolean renderSizeRrEnabled;
    private int renderSizeRrQuality = Integer.MIN_VALUE;

    // Motion-vector reprojection state: the previous frame's camera-relative view-projection and
    // camera position, read into the push constant each frame then advanced at frame end.
    private final Matrix4f mvPrevProjView = new Matrix4f();
    private final Matrix4f mvCurProjView = new Matrix4f();
    private final Matrix4f mvPushMatrix = new Matrix4f();
    private final Matrix4f frameInvViewProj = new Matrix4f();
    private final Matrix4f frameInvProjection = new Matrix4f();
    private final BlockPos.MutableBlockPos cameraBlockPos = new BlockPos.MutableBlockPos();
    /** Scratch for the reference-surface walk; never escapes the frame that uses it. */
    private final BlockPos.MutableBlockPos surfaceScan = new BlockPos.MutableBlockPos();
    private double mvPrevCamX;
    private double mvPrevCamY;
    private double mvPrevCamZ;
    private float mvCamDeltaX;
    private float mvCamDeltaY;
    private float mvCamDeltaZ;
    private boolean mvHasPrev;
    private float previousWaterWaveTime;
    private boolean waterWaveTimeValid;
    private ClientLevel waterWeatherLevel;
    private double waterWeatherSeconds = Double.NaN;
    private float smoothedWaterStorm;
    private ClientLevel rainSurfaceLevel;
    private double rainSurfaceSeconds = Double.NaN;
    private float wetFilmStorage;
    private float puddleStorage;
    private ClientLevel rainExposureAnchorLevel;
    private int rainExposureAnchorResolution;
    private int rainExposureAnchorOriginX;
    private int rainExposureAnchorOriginZ;
    private boolean rainExposureAnchorValid;
    private long atlasSampler;
    private long lutSampler;
    private long tilingSampler;
    private long environmentSampler;
    private long environmentTransferSampler;
    private long highCloudPatchSampler;
    // The atmosphere's precomputed tables (M10). Baked once, then sampled by world.rmiss and world.rgen.
    private RtSky skyLuts;
    // Reloaded through the same resource-manager seam as material overrides. A missing entry is not an
    // error: RtSkyPresets deliberately resolves it to the complete atmosphere.
    private RtSkyPresets skyPresets = RtSkyPresets.EMPTY;
    private RtEnvironmentTextures environmentTextures;
    private RtHighCloudTextures highCloudTextures;
    private boolean failed;
    private boolean loggedActive;

    // Camera captured each frame from GameRenderer (unjittered level projection + camera rotation + pos).
    private final Matrix4f frameProjection = new Matrix4f();
    private final Matrix4f frameViewRotation = new Matrix4f();
    private double camX;
    private double camY;
    private double camZ;
    private boolean frameCaptured;
    private long celestialUvAtlasHandle;
    private int celestialUvMoonPhase = -1;
    private float sunU0;
    private float sunV0;
    private float sunU1 = 1f;
    private float sunV1 = 1f;
    private float moonU0;
    private float moonV0;
    private float moonU1 = 1f;
    private float moonV1 = 1f;

    // Per-frame TLAS resources, rebuilt in place from a small ring of persistent slots (see
    // RtAccel.TlasRing — replaces the old create-and-defer-destroy-per-frame churn whose VMA slow path
    // showed up as rare multi-ms prepareTlas spikes).
    private final RtAccel.TlasRing tlasRing = new RtAccel.TlasRing();

    // This frame's TLAS handle, published after prepareTlas so the world-overlay pass (block outline's
    // rayQueryEXT occlusion test) can bind the exact same acceleration structure the primary trace used —
    // same-queue submission order (RtWorldOverlay's transient buffer runs later, same graphics queue)
    // makes the TLAS build's writes visible without an extra semaphore, matching every other overlay
    // feature's reliance on in-order queue execution for this frame's world content.
    private volatile long currentTlasHandle;
    private RtGpuExecutor.GraphicsUse pendingGraphicsUse;

    private RtComposite() {
    }

    /** This frame's TLAS handle (0 if none built yet), for {@code io.github.dswepm.fluorite.rt.overlay} occlusion queries. */
    public long currentTlasHandle() {
        return currentTlasHandle;
    }

    private static Identifier[] createMoonIds() {
        MoonPhase[] phases = MoonPhase.values();
        Identifier[] ids = new Identifier[phases.length];
        for (int i = 0; i < phases.length; i++) {
            ids[i] = Identifier.withDefaultNamespace("moon/" + phases[i].getSerializedName());
        }
        return ids;
    }

    public boolean hasFailed() {
        return this.failed;
    }

    /**
     * Whether the current frame must retain vanilla world rendering while RT resource state converges.
     *
     * <p>The composite still runs at the normal seam so it can consume the one-frame epoch gate or observe
     * the newly uploaded atlas. This method only prevents {@code LevelRenderer} from being cancelled before
     * a deliberately transient {@link #composite} return. Such a return is not a renderer failure and must
     * not trip {@code VanillaRenderController}'s permanent safety latch.</p>
     */
    public boolean requiresVanillaWorldFallback() {
        // Pipeline creation publishes a new material epoch and deliberately makes composite() return
        // false once so RtTerrain can apply the matching full clear. Keep vanilla alive for that bring-up
        // frame; otherwise LevelRenderer is cancelled before composite() discovers it must fall back and
        // VanillaRenderController permanently latches the resulting missing replacement frame.
        if (worldPipeline == null || !materialBindingsReady) {
            return true;
        }
        if (materialEpochTraceGate) {
            return true;
        }
        if (RtEntityTextures.maxTextures() > bindlessTextureCapacity) {
            return true;
        }
        if (reloadRebindRequested) {
            long atlas = blockAlbedoAtlasView();
            return atlas == 0L || atlas == boundBlockAlbedoAtlasHandle;
        }
        return false;
    }

    /**
     * Clear the failure latch on an explicit render-state invalidation (F3+A, dimension change) so RT
     * re-arms after a transient error instead of staying on vanilla until restart. A deterministic
     * failure just latches again on the next frame (bounded log spam: one error line per invalidation).
     */
    public void resetFailureLatch() {
        if (failed) {
            failed = false;
            FluoriteMod.LOGGER.info("RT failure latch cleared by render-state invalidation; retrying RT");
        }
    }

    /** Capture the frame's camera for the next composite. Called from GameRendererMixin. */
    public void captureFrame(Matrix4f projection, Matrix4fc viewRotation, double cameraX, double cameraY, double cameraZ) {
        frameProjection.set(projection);
        frameViewRotation.set(viewRotation);
        camX = cameraX;
        camY = cameraY;
        camZ = cameraZ;
        frameCaptured = true;
    }

    /**
     * The frame's forward camera-relative view-projection (jitter-free), exactly what {@code world.rgen}
     * traced with — overlay raster passes ({@code io.github.dswepm.fluorite.rt.overlay}) reuse it so their content lands
     * pixel-exact on the RT image. Valid after {@code updateMotion} ran this frame; do not mutate.
     */
    public Matrix4fc currentViewProjection() {
        return mvCurProjView;
    }

    /**
     * Reset per-frame present state at the very start of {@link net.minecraft.client.renderer.GameRenderer}
     * render (before any RT work). Critical for menu/no-world frames: {@link #composite()} is only called
     * while a level is rendering ({@code WorldRenderScaler} opens its window in {@code renderLevel}), so on
     * menu frames {@code composite} never runs and {@code hdrWrittenThisFrame} would otherwise keep its stale
     * {@code true} from the last world frame — presenting a black/stale HDR image behind the menu. Clearing it
     * here every frame makes {@link #isHdrPresentActive()} false on menu frames so the SDR convert-present path
     * runs instead.
     */
    public void beginFrame() {
        if (pendingGraphicsUse != null) {
            throw new IllegalStateException("Previous RT graphics use was never completed");
        }
        RtFrameStats.FRAME.beginIfInactive();
        hdrWrittenThisFrame = false;
    }

    /** This frame's completion token, valid until {@link #finishGraphicsUse()} signals it. */
    public RtGpuExecutor.GraphicsUse currentGraphicsUse() {
        RenderSystem.assertOnRenderThread();
        return pendingGraphicsUse;
    }

    /** Signal this RT frame's shared completion token after its final TLAS consumer (world overlay). */
    public void finishGraphicsUse() {
        RtGpuExecutor.GraphicsUse graphicsUse = pendingGraphicsUse;
        if (graphicsUse == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            throw new IllegalStateException("RT context disappeared before graphics use completed");
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice()
                .createCommandEncoder()).fluorite$getBackend();
        ctx.gpuExecutor().endGraphicsUse(encoder, graphicsUse);
        pendingGraphicsUse = null;
    }

    public void endFrame() {
        RtFrameStats.FRAME.end();
    }

    public boolean composite(GpuTexture nativeColor, int width, int height) {
        frameCounter++; // global frame serial used by remaining per-frame/entity rings and diagnostics
        VulkanDiagnostics.setInFlight("graphics-latest", "frame=" + frameCounter + " size=" + width + "x" + height);
        hdrWrittenThisFrame = false; // set true again below once this frame's HDR display image is written
        if (failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null) {
            return false;
        }
        ctx.gpuExecutor().throwIfFailed();
        // Count-bounded terrain streaming (dispatch/drain/build kick) runs here once per render frame — before
        // the ready gate below, because it is what MAKES terrain ready during the initial fill.
        try {
            RtTerrain.frame(ctx);
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            FluoriteMod.LOGGER.error("RT terrain streaming failed; reverting to vanilla path", t);
            return false;
        }
        if (RtTerrain.currentOrNull() == null || !frameCaptured || Minecraft.getInstance().level == null) {
            // No world this frame (incl. after quitting to the title — terrain residency + frameCaptured can
            // linger until an explicit invalidate, which would otherwise present a stale/empty HDR image as a
            // black menu background). Skip RT so the present path falls back to vanilla SDR / the PQ SDR
            // convert path, which shows the menu + panorama correctly.
            return false;
        }
        try {
            if (displayPipeline == null) {
                displayPipeline = RtDisplayPipeline.create(ctx);
            }
            // A resource reload re-stitches the block atlas. We've already torn down the world pipeline
            // (onResourceReloadStart) so nothing references the old atlas, but MC's deferred free keeps the
            // old view handle live for a few frames, then swaps in the new atlas (whose GPU upload may lag,
            // leaving the handle 0 transiently). Skip RT — vanilla renders — until the handle becomes a
            // fresh, non-zero value different from what we last bound; only then rebuild against it.
            if (reloadRebindRequested) {
                long atlas = blockAlbedoAtlasView();
                if (atlas == 0L || atlas == boundBlockAlbedoAtlasHandle) {
                    return false;
                }
            }
            ensureOutput(ctx, width, height);
            // Cheap idempotent check every frame (not just on resize): if the exposure mode is switched
            // manual -> auto at runtime (video settings), the auto-mode histogram/state/pipeline must be
            // allocated before recordFrame's exposure.record() below needs them, or it throws.
            exposure.ensureResources(ctx);
            ensureLensResources(ctx);
            refreshPipelineShapeIfNeeded(ctx);
            RtPipeline active = ensureWorld(ctx);
            if (materialEpochTraceGate) {
                materialEpochTraceGate = false;
                return false;
            }
            refreshMaterialBindingsIfNeeded(ctx);
            updateMotion();
            recordFrame(ctx, active, nativeColor);
            if (!loggedActive) {
                loggedActive = true;
                FluoriteMod.LOGGER.info("RT composite active (terrain): {}x{}, RT output replaces the world target", width, height);
            }
            return true;
        } catch (Throwable t) {
            ctx.gpuExecutor().throwIfFailed();
            failed = true;
            FluoriteMod.LOGGER.error("RT composite failed; reverting to vanilla path", t);
            return false;
        }
    }

    /**
     * Bring the world pipeline + LabPBR atlases up as soon as we're in a world and the block atlas is
     * loaded — <em>before</em> terrain tessellates — so the immutable material snapshot is available to
     * the first worker section. Driven from the client tick ahead of {@link RtTerrain#update}. No-op once
     * the pipeline exists, while a reload rebuild is pending (the reload path rebuilds against the new
     * atlas), or until we're in a world with the atlas ready. The heavy {@code _s}/{@code _n} atlases are
     * deliberately not built at the menu — only once a world is entered.
     */
    public void ensureResourcesReady(RtContext ctx) {
        if (failed || worldPipeline != null || reloadRebindRequested) {
            return;
        }
        if (Minecraft.getInstance().level == null || blockAlbedoAtlasView() == 0L) {
            return;
        }
        try {
            ensureWorld(ctx);
        } catch (Throwable t) {
            failed = true;
            FluoriteMod.LOGGER.error("RT resource bring-up failed; reverting to vanilla path", t);
        }
    }

    private RtPipeline ensureWorld(RtContext ctx) {
        if (worldPipeline == null) {
            bindlessTextureCapacity = RtEntityTextures.maxTextures();
            worldPipeline = RtPipeline.create(ctx, new String[]{
                            RtDeviceBringup.worldPrimaryRaygenShader(),
                            RtDeviceBringup.worldRaygenShader()},
                    new String[]{"world.rmiss.spv", "world_guide.rmiss.spv"},
                    "world.rchit.spv", "world.rahit.spv",
                    WorldPushConstantsData.BYTE_SIZE, true, GUIDE_COUNT, bindlessTextureCapacity, true);
            // Per-frame world data lives in this BDA ring; the pipeline pushes its address and hot fields.
            if (pushRing == null) {
                pushRing = new PushSlot[PUSH_RING];
                for (int i = 0; i < PUSH_RING; i++) {
                    pushRing[i] = new PushSlot(
                            ctx.createBuffer(WORLD_PUSH_SIZE,
                                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt world push " + i),
                            ctx.createBuffer(WaterMediumProbeData.BYTE_SIZE,
                                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, "rt water medium probe " + i));
                }
            }
            if (gpuTimers == null) {
                gpuTimers = RtGpuTimers.create(ctx, PUSH_RING, "gpu.tracePrimary", "gpu.traceIndirect",
                        "gpu.skyBake", "gpu.visBake", "gpu.froxelBake",
                        "gpu.waterSim", "gpu.waterDeform",
                        "gpu.entityBlas", "gpu.tlasBuild", "gpu.rainExposure", "gpu.rainStreak",
                        "gpu.lensSpatial", "gpu.displayMap", "gpu.bloomFlare");
            }
            if (output != null) {
                worldPipeline.setStorageImage(output.view);
                bindGuideImages();
            }
            bindWorldTextures(ctx);
            reloadRebindRequested = false;
        }
        // The TLAS is rebuilt and bound per frame in recordFrame since dynamic entity content animates
        // the instance set every frame.
        return worldPipeline;
    }

    private void refreshPipelineShapeIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        int desiredBindlessCapacity = RtEntityTextures.maxTextures();
        if (desiredBindlessCapacity <= bindlessTextureCapacity) {
            return;
        }
        ctx.waitIdle();
        worldPipeline.destroy();
        worldPipeline = null;
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
    }

    /**
     * Resolve + bind every world-pipeline texture: the block atlas (binding 2 + bindless fallback slot 0)
     * and the canonical material page bundles in reserved bindless slots. Shared by first creation and
     * the post-reload rebind. Resets the entity bindless registry, recreates material pages, builds
     * the shared material registry, and invalidates old-epoch geometry before tracing resumes.
     */
    private void bindWorldTextures(RtContext ctx) {
        long sampler = atlasSampler(ctx);
        long atlasView = blockAlbedoAtlasView();
        boundBlockAlbedoAtlasHandle = atlasView; // remember what we bound so a reload can detect the new atlas
        worldPipeline.setBlockAlbedoAtlas(atlasView, sampler);
        // Bindless slot 0 = fallback texture (the block atlas) so an entity whose texture can't be
        // resolved samples something defined rather than an unbound (partially-bound) descriptor.
        RtBlockMaterials.INSTANCE.reset();
        RtMaterialOverrides materialOverrides = RtMaterialOverrides.load();
        skyPresets = RtSkyPresets.load();
        if (environmentTextures != null) {
            environmentTextures.destroy();
        }
        environmentTextures = RtEnvironmentTextures.load(ctx, skyPresets);
        if (highCloudTextures != null) {
            highCloudTextures.destroy();
        }
        highCloudTextures = RtHighCloudTextures.load(ctx);
        RtEmissionSemantics emissionSemantics = RtEmissionSemantics.analyze();
        RtBlockMaterials.INSTANCE.prepareAll(ctx, bindlessTextureCapacity, emissionSemantics, materialOverrides);
        RtEntityTextures.INSTANCE.reset(bindlessTextureCapacity);
        worldPipeline.setEntityAlbedoTexture(0, atlasView, sampler);
        RtBlockMaterials.INSTANCE.bindPages(worldPipeline, sampler);
        RtMaterialRegistry.INSTANCE.rebuild(ctx, RtBlockMaterials.INSTANCE, materialOverrides);
        materialBindingsReady = true;
        // Sky rewrite: bind the vanilla celestials atlas (sun + moon phases) for world.rmiss. The view
        // handle is stable across frames; the shader only samples it inside the sun/moon discs (sky
        // directions), so the block-atlas fallback is never read if the celestials atlas isn't ready.
        long celView = celestialsAtlasView();
        if (worldPipeline.hasSkyAtlas()) {
            worldPipeline.setSkyAtlas(celView != 0L ? celView : atlasView, sampler);
        }
        // The atmosphere's tables, bound HERE — the one path that runs both on first creation and on the
        // post-reload rebind. They were previously bound from the bake, which happens exactly once ever;
        // a pipeline rebuilt after that got an unbound binding, and sampling an unbound descriptor
        // returned zero, so the sun and moon discs went black while the sunlight stayed right.
        if (worldPipeline.hasTransmittanceLut()) {
            if (skyLuts == null) {
                skyLuts = RtSky.create(ctx);
            }
            worldPipeline.setTransmittanceLut(skyLuts.transmittanceView(), lutSampler(ctx));
            worldPipeline.setMultiScatterLut(skyLuts.multiScatterView(), lutSampler(ctx));
            worldPipeline.setSkyViewLuts(skyLuts.skyViewRayleighView(), skyLuts.skyViewMieView(),
                    skyLuts.skyViewMultiView(), lutSampler(ctx));
            worldPipeline.setAerialPerspectiveLut(skyLuts.aerialPerspectiveView(), lutSampler(ctx));
            worldPipeline.setVolumeVisibilityGrid(skyLuts.visibilityGridView(), lutSampler(ctx));
            // NOT the LUT sampler. Every table above is a parameterisation over [0,1] and must clamp;
            // cloud and fog noise are sampled at WORLD COORDINATES divided by a feature size, which
            // leaves that range immediately and has to wrap. See tilingSampler.
            worldPipeline.setCloudNoise(skyLuts.cloudNoiseView(), tilingSampler(ctx));
            // Same tiling sampler as the volume: the weather map is read by dividing world
            // coordinates by an authored span and letting it repeat, exactly as the slices did.
            worldPipeline.setCloudWeather(skyLuts.cloudWeatherView(), tilingSampler(ctx));
            worldPipeline.setCloudWarp(skyLuts.cloudWarpView(), tilingSampler(ctx));
            worldPipeline.setFogNoise(skyLuts.fogNoiseView(), tilingSampler(ctx));
            // Clamped, not repeating: the height field is a finite domain that follows the player, and
            // wrapping it would put the far shore's ripples on the near one. The sampler's clamp is a
            // backstop only -- waterSimGrad rejects out-of-domain coordinates before it reads.
            worldPipeline.setWaterSimHeight(skyLuts.waterHeightView(), lutSampler(ctx));
            worldPipeline.setRainExposureDepth(skyLuts.rainExposureDepthView(), lutSampler(ctx));
            worldPipeline.setRainWetHistory(
                    skyLuts.rainWetHistoryView(), skyLuts.rainWetHistorySampler());
            worldPipeline.setEnvironmentRadiance(
                    environmentTextures.radianceView(), environmentSampler(ctx));
            worldPipeline.setEnvironmentTransfer(
                    environmentTextures.transferView(), environmentTransferSampler(ctx));
            worldPipeline.setEnvironmentDiskEntry(
                    environmentTextures.diskEntryView(), environmentTransferSampler(ctx));
            worldPipeline.setEnvironmentDiskExit(
                    environmentTextures.diskExitView(), environmentTransferSampler(ctx));
            worldPipeline.setHighCloudPatches(
                    highCloudTextures.patchView(), highCloudPatchSampler(ctx));
        }
        setCelestialUvAtlas(celView);
        // Atlas UVs and material IDs are one resource epoch. Drop old terrain as a unit rather than
        // incrementally displaying old UVs/IDs against the new atlas/table.
        RtTerrain.requestFullClear();
        materialEpochTraceGate = true;
    }

    private void refreshMaterialBindingsIfNeeded(RtContext ctx) {
        if (worldPipeline == null || reloadRebindRequested) {
            return;
        }
        if (!materialBindingsReady) {
            bindWorldTextures(ctx);
        }
    }

    /** Vulkan image-view of the vanilla celestials atlas (sun + moon-phase sprites), or 0 if unavailable. */
    private static long celestialsAtlasView() {
        try {
            GpuTextureView view = Minecraft.getInstance().getAtlasManager()
                    .getAtlasOrThrow(AtlasIds.CELESTIALS).getTextureView();
            return vkImageView(view);
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Hooked at the HEAD of {@link net.minecraft.client.Minecraft#reloadResourcePacks()} (mixin). A
     * resource reload re-stitches the block atlas (and reloads entity textures): MC frees the old GPU
     * images via its deferred destruction queue, which refuses while any descriptor set still references
     * them ("in use by VkDescriptorSet" → device lost). So we drain in-flight frames and then <b>destroy
     * the world pipeline outright</b> — dropping every descriptor reference (block atlas binding 2 +
     * bindless set) — so MC can free its textures cleanly. The pipeline is cheap to rebuild (no terrain
     * re-upload); {@code ensureWorld} recreates it on the first world frame after the reload, once the new
     * atlas is ready (gated in {@link #composite}). The new material epoch clears terrain before trace.
     */
    public void onResourceReloadStart() {
        reloadRebindRequested = true;
        materialBindingsReady = false;
        setCelestialUvAtlas(0L);
        RtEntities.INSTANCE.onResourceReload();
        RtContext ctx = RtContext.currentOrNull();
        if (ctx != null) {
            ctx.waitIdle();
            if (worldPipeline != null) {
                worldPipeline.destroy();
                worldPipeline = null;
                bindlessTextureCapacity = 0;
            }
            if (environmentTextures != null) {
                environmentTextures.destroy();
                environmentTextures = null;
            }
            if (highCloudTextures != null) {
                highCloudTextures.destroy();
                highCloudTextures = null;
            }
            RtMaterialRegistry.INSTANCE.destroy();
        }
    }

    /** Bind the guide buffers into the world pipeline's extra storage-image slots. */
    private void bindGuideImages() {
        if (worldPipeline == null || gNormal == null) {
            return;
        }
        worldPipeline.setExtraStorageImage(0, gNormal.view);
        worldPipeline.setExtraStorageImage(1, gAlbedo.view);
        worldPipeline.setExtraStorageImage(2, gDepth.view);
        worldPipeline.setExtraStorageImage(3, gMotion.view);
        worldPipeline.setExtraStorageImage(4, gSpecAlbedo.view);
        worldPipeline.setExtraStorageImage(5, gSpecMotion.view);
    }

    private void destroyGuideImages() {
        if (gNormal != null) {
            gNormal.destroy();
            gNormal = null;
        }
        if (gAlbedo != null) {
            gAlbedo.destroy();
            gAlbedo = null;
        }
        if (gDepth != null) {
            gDepth.destroy();
            gDepth = null;
        }
        if (gMotion != null) {
            gMotion.destroy();
            gMotion = null;
        }
        if (gSpecAlbedo != null) {
            gSpecAlbedo.destroy();
            gSpecAlbedo = null;
        }
        if (gSpecMotion != null) {
            gSpecMotion.destroy();
            gSpecMotion = null;
        }
        if (rrOutput != null) {
            rrOutput.destroy();
            rrOutput = null;
        }
    }

    private void ensureOutput(RtContext ctx, int width, int height) {
        boolean rrEnabled = RtDlssRr.enabled();
        int rrQuality = rrEnabled ? RtDlssRr.quality() : Integer.MIN_VALUE;
        if (output != null && continuationQueue != null
                && displayImage != null && hdrDisplayImage != null && rrOutput != null && exposure.ready()
                && displayW == width && displayH == height
                && renderSizeRrEnabled == rrEnabled && renderSizeRrQuality == rrQuality) {
            return;
        }
        ctx.waitIdle(); // resize is rare; no in-flight frame may use the old image/descriptor
        destroyLensImages();
        if (displayImage != null) {
            displayImage.destroy();
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
        }
        if (output != null) {
            output.destroy();
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();

        displayW = width;
        displayH = height;
        // The path tracer + its guide buffers run at render res; DLSS-RR (or a fallback blit) upscales
        // to display res. With RR off there is no reconstruction pass, so trace at 1:1 for a faithful reference.
        // With RR on, ask NGX what render resolution its chosen quality mode actually expects rather
        // than assuming a fixed ratio: different quality modes (and driver versions) use different
        // ratios, and DLSSD's own optimal-settings query is the source of truth for what it will accept.
        int[] optimal = rrEnabled ? RtDlssRr.INSTANCE.queryOptimalRenderSize(width, height) : null;
        renderW = optimal != null ? optimal[0] : width;
        renderH = optimal != null ? optimal[1] : height;
        renderSizeRrEnabled = rrEnabled;
        renderSizeRrQuality = rrQuality;

        // RT traces into an HDR (R16G16B16A16_SFLOAT) target so radiance > 1 survives to the display
        // mapping seam. displayImage stays R8G8B8A8 to match the main target it is copied into
        // (vkCmdCopyImage requires texel-size-compatible formats).
        output = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "trace color " + renderW + "x" + renderH);
        long pixelRecords = Math.multiplyExact((long) renderW, (long) renderH);
        long continuationBytes = Math.multiplyExact(
                Math.multiplyExact(pixelRecords, 2L), PATH_RECORD_BYTES);
        continuationQueue = ctx.createBuffer(continuationBytes,
                VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                "path continuation queue " + renderW + "x" + renderH + "x2");
        displayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R8G8B8A8_UNORM, "RT display image " + width + "x" + height);
        // PQ-encoded ([0,1], ST.2084) HDR display image, written in parallel by display.comp when HDR mode is active.
        hdrDisplayImage = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "RT HDR display image " + width + "x" + height);
        // Guide buffers match the trace (render) resolution; DLSS-RR consumes them at render res.
        gNormal = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide normal roughness " + renderW + "x" + renderH);
        gAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide diffuse albedo " + renderW + "x" + renderH);
        gDepth = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R32_SFLOAT,
                "guide reversed-Z depth " + renderW + "x" + renderH);
        gMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide motion " + renderW + "x" + renderH);
        gSpecAlbedo = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT, "guide specular albedo " + renderW + "x" + renderH);
        gSpecMotion = ctx.createStorageImage(renderW, renderH, VK10.VK_FORMAT_R16G16_SFLOAT, "guide specular motion " + renderW + "x" + renderH);
        // Display-res RT image the display mapper reads. Always present (DLSS-RR target, or blit-upscale fallback).
        rrOutput = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                "DLSS-RR output " + width + "x" + height, VK10.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        exposure.ensureResources(ctx);

        mvHasPrev = false; // recreated images -> first MV frame is zero
        waterWaveTimeValid = false;
        if (worldPipeline != null) {
            worldPipeline.setStorageImage(output.view);
            bindGuideImages();
        }
        displayPipeline.setImages(displayImage.view, rrOutput.view, exposure.image().view, hdrDisplayImage.view);
    }

    /**
     * Lazily own the one full-resolution post-RR ping-pong target plus effect-specific compact images.
     * Switching between an odd and even number of scene-linear writes changes which image the display
     * descriptor reads, so that rare setting transition waits for old frames before updating it.
     */
    private void ensureLensResources(RtContext ctx) {
        boolean diagnosticsBypass = debugView() != 0;
        boolean motion = !diagnosticsBypass
                && FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_ENABLED.value();
        boolean depthOfField = !diagnosticsBypass
                && FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_ENABLED.value();
        boolean bloom = !diagnosticsBypass
                && FluoriteConfig.Rt.PostProcessing.BLOOM_ENABLED.value();
        boolean lensFlare = !diagnosticsBypass
                && FluoriteConfig.Rt.PostProcessing.LENS_FLARE_ENABLED.value();
        boolean opticalHighlights = bloom || lensFlare;
        boolean sceneLinearPost = motion || depthOfField || opticalHighlights;
        if (!sceneLinearPost) {
            if (lensScratch != null) {
                ctx.waitIdle();
                destroyLensImages();
            }
            displayPipeline.setImages(displayImage.view, rrOutput.view,
                    exposure.image().view, hdrDisplayImage.view);
            lensFinalScratch = false;
            return;
        }

        int sceneLinearWrites = (motion ? 1 : 0) + (depthOfField ? 1 : 0)
                + (opticalHighlights ? 1 : 0);
        boolean desiredFinalScratch = (sceneLinearWrites & 1) != 0;
        if (lensScratch == null) {
            ctx.waitIdle();
            lensScratch = ctx.createStorageImage(displayW, displayH,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "lens scene-linear scratch " + displayW + "x" + displayH);
        } else if (lensFinalScratch != desiredFinalScratch) {
            ctx.waitIdle();
        }

        if ((motion || depthOfField) && lensFocus == null) {
            ctx.waitIdle();
            if (lensPipeline == null) lensPipeline = RtLensPipeline.create(ctx);
            lensFocus = ctx.createStorageImage(1, 1, VK10.VK_FORMAT_R32_SFLOAT,
                    "lens auto-focus history");
            lensPipeline.setImages(rrOutput.view, lensScratch.view, gDepth.view, gMotion.view, lensFocus.view);
        }

        if (depthOfField && depthOfFieldCoc == null) {
            ctx.waitIdle();
            if (depthOfFieldPipeline == null) depthOfFieldPipeline = RtDepthOfFieldPipeline.create(ctx);
            int halfWidth = (displayW + 1) / 2;
            int halfHeight = (displayH + 1) / 2;
            int tileWidth = (displayW + 15) / 16;
            int tileHeight = (displayH + 15) / 16;
            depthOfFieldCoc = ctx.createStorageImage(displayW, displayH,
                    VK10.VK_FORMAT_R16_SFLOAT,
                    "depth of field signed CoC " + displayW + "x" + displayH);
            depthOfFieldNear = ctx.createStorageImage(halfWidth, halfHeight,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "depth of field near layer " + halfWidth + "x" + halfHeight);
            depthOfFieldFar = ctx.createStorageImage(halfWidth, halfHeight,
                    VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "depth of field far layer " + halfWidth + "x" + halfHeight);
            depthOfFieldTiles = ctx.createStorageImage(tileWidth, tileHeight,
                    VK10.VK_FORMAT_R16G16_SFLOAT,
                    "depth of field tile extrema " + tileWidth + "x" + tileHeight);
            depthOfFieldDilatedTiles = ctx.createStorageImage(tileWidth, tileHeight,
                    VK10.VK_FORMAT_R16G16_SFLOAT,
                    "depth of field dilated tile extrema " + tileWidth + "x" + tileHeight);
            depthOfFieldPipeline.setImages(rrOutput.view, lensScratch.view, gDepth.view, lensFocus.view,
                    depthOfFieldCoc.view, depthOfFieldNear.view, depthOfFieldFar.view,
                    depthOfFieldTiles.view, depthOfFieldDilatedTiles.view);
        } else if (!depthOfField && depthOfFieldCoc != null) {
            ctx.waitIdle();
            destroyDepthOfFieldImages();
        }

        boolean bloomFlareRebind = false;
        if (opticalHighlights && bloomBright[0] == null) {
            ctx.waitIdle();
            if (bloomFlarePipeline == null) bloomFlarePipeline = RtBloomFlarePipeline.create(ctx);
            for (int level = 0; level < RtBloomFlarePipeline.LEVEL_COUNT; level++) {
                int levelWidth = RtBloomFlarePipeline.levelSize(displayW, level);
                int levelHeight = RtBloomFlarePipeline.levelSize(displayH, level);
                bloomBright[level] = ctx.createStorageImage(levelWidth, levelHeight,
                        VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "HDR bright pyramid " + level + " " + levelWidth + "x" + levelHeight);
                bloomPyramid[level] = ctx.createStorageImage(levelWidth, levelHeight,
                        VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                        "bloom accumulation " + level + " " + levelWidth + "x" + levelHeight);
            }
            bloomFlareRebind = true;
        } else if (!opticalHighlights && bloomBright[0] != null) {
            ctx.waitIdle();
            destroyBloomFlareImages();
        }

        if (lensFlare && flareBokeh == null) {
            ctx.waitIdle();
            int width = RtBloomFlarePipeline.levelSize(displayW, 1);
            int height = RtBloomFlarePipeline.levelSize(displayH, 1);
            flareBokeh = ctx.createStorageImage(width, height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "pentagonal lens flare bokeh " + width + "x" + height);
            bloomFlareRebind = true;
        } else if (!lensFlare && flareBokeh != null) {
            ctx.waitIdle();
            if (bloomFlarePipeline != null) bloomFlarePipeline.invalidateImages();
            flareBokeh.destroy();
            flareBokeh = null;
            bloomFlareRebind = true;
        }

        if (opticalHighlights && bloomFlareRebind) {
            long[] brightViews = new long[RtBloomFlarePipeline.LEVEL_COUNT];
            long[] pyramidViews = new long[RtBloomFlarePipeline.LEVEL_COUNT];
            for (int level = 0; level < RtBloomFlarePipeline.LEVEL_COUNT; level++) {
                brightViews[level] = bloomBright[level].view;
                pyramidViews[level] = bloomPyramid[level].view;
            }
            // Bloom-only still needs every statically declared descriptor valid; its quarter-resolution
            // accumulation image is a legal unused fallback for the absent flare-only target.
            long flareView = flareBokeh != null ? flareBokeh.view : bloomPyramid[1].view;
            bloomFlarePipeline.setImages(rrOutput.view, lensScratch.view, exposure.image().view,
                    brightViews, pyramidViews, flareView);
        }

        RtImage finalScene = desiredFinalScratch ? lensScratch : rrOutput;
        displayPipeline.setImages(displayImage.view, finalScene.view,
                exposure.image().view, hdrDisplayImage.view);
        lensFinalScratch = desiredFinalScratch;
    }

    private void destroyLensImages() {
        destroyDepthOfFieldImages();
        destroyBloomFlareImages();
        if (lensScratch != null) {
            lensScratch.destroy();
            lensScratch = null;
        }
        if (lensFocus != null) {
            lensFocus.destroy();
            lensFocus = null;
        }
        lensFinalScratch = false;
    }

    private void destroyBloomFlareImages() {
        if (bloomFlarePipeline != null) bloomFlarePipeline.invalidateImages();
        if (flareBokeh != null) {
            flareBokeh.destroy();
            flareBokeh = null;
        }
        for (int level = 0; level < RtBloomFlarePipeline.LEVEL_COUNT; level++) {
            if (bloomBright[level] != null) {
                bloomBright[level].destroy();
                bloomBright[level] = null;
            }
            if (bloomPyramid[level] != null) {
                bloomPyramid[level].destroy();
                bloomPyramid[level] = null;
            }
        }
    }

    private void destroyDepthOfFieldImages() {
        if (depthOfFieldCoc != null) {
            depthOfFieldCoc.destroy();
            depthOfFieldCoc = null;
        }
        if (depthOfFieldNear != null) {
            depthOfFieldNear.destroy();
            depthOfFieldNear = null;
        }
        if (depthOfFieldFar != null) {
            depthOfFieldFar.destroy();
            depthOfFieldFar = null;
        }
        if (depthOfFieldTiles != null) {
            depthOfFieldTiles.destroy();
            depthOfFieldTiles = null;
        }
        if (depthOfFieldDilatedTiles != null) {
            depthOfFieldDilatedTiles.destroy();
            depthOfFieldDilatedTiles = null;
        }
    }

    private static RtDisplayPipeline.CreativeGrade creativeGradeSettings() {
        return new RtDisplayPipeline.CreativeGrade(
                FluoriteConfig.Rt.PostProcessing.COLOR_GRADING_ENABLED.value(),
                FluoriteConfig.Rt.PostProcessing.TEMPERATURE_K.value(),
                FluoriteConfig.Rt.PostProcessing.TINT.value(),
                FluoriteConfig.Rt.PostProcessing.CONTRAST.value(),
                FluoriteConfig.Rt.PostProcessing.SATURATION.value(),
                FluoriteConfig.Rt.PostProcessing.HUE_DEGREES.value(),
                tonalGrade(FluoriteConfig.Rt.PostProcessing.SHADOW_EXPOSURE_EV,
                        FluoriteConfig.Rt.PostProcessing.SHADOW_RED_EV,
                        FluoriteConfig.Rt.PostProcessing.SHADOW_GREEN_EV,
                        FluoriteConfig.Rt.PostProcessing.SHADOW_BLUE_EV,
                        FluoriteConfig.Rt.PostProcessing.SHADOW_SATURATION,
                        FluoriteConfig.Rt.PostProcessing.SHADOW_CONTRAST),
                tonalGrade(FluoriteConfig.Rt.PostProcessing.MID_EXPOSURE_EV,
                        FluoriteConfig.Rt.PostProcessing.MID_RED_EV,
                        FluoriteConfig.Rt.PostProcessing.MID_GREEN_EV,
                        FluoriteConfig.Rt.PostProcessing.MID_BLUE_EV,
                        FluoriteConfig.Rt.PostProcessing.MID_SATURATION,
                        FluoriteConfig.Rt.PostProcessing.MID_CONTRAST),
                tonalGrade(FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_EXPOSURE_EV,
                        FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_RED_EV,
                        FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_GREEN_EV,
                        FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_BLUE_EV,
                        FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_SATURATION,
                        FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_CONTRAST),
                FluoriteConfig.Rt.PostProcessing.SHADOW_BOUNDARY_EV.value(),
                FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_BOUNDARY_EV.value());
    }

    private static RtDisplayPipeline.TonalGrade tonalGrade(FluoriteConfig.FloatSetting exposure,
                                                            FluoriteConfig.FloatSetting red,
                                                            FluoriteConfig.FloatSetting green,
                                                            FluoriteConfig.FloatSetting blue,
                                                            FluoriteConfig.FloatSetting saturation,
                                                            FluoriteConfig.FloatSetting contrast) {
        return new RtDisplayPipeline.TonalGrade(exposure.value(), red.value(), green.value(), blue.value(),
                saturation.value(), contrast.value());
    }

    private static RtDisplayPipeline.FilmGrain filmGrainSettings() {
        return new RtDisplayPipeline.FilmGrain(
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_ENABLED.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_INTENSITY.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_SIZE.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_CHROMATIC.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_SHADOWS.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_MIDTONES.value(),
                FluoriteConfig.Rt.PostProcessing.FILM_GRAIN_HIGHLIGHTS.value());
    }

    /**
     * Compute this frame's motion-vector push data: the matrix that projects a current world point
     * into the previous frame's clip space, plus the per-frame camera translation. On the first frame
     * (or after a reset) push the current view-projection with zero delta so MVs come out zero.
     */
    private void updateMotion() {
        mvCurProjView.set(frameProjection).mul(frameViewRotation);
        if (mvHasPrev) {
            mvPushMatrix.set(mvPrevProjView);
            mvCamDeltaX = (float) (camX - mvPrevCamX);
            mvCamDeltaY = (float) (camY - mvPrevCamY);
            mvCamDeltaZ = (float) (camZ - mvPrevCamZ);
        } else {
            mvPushMatrix.set(mvCurProjView);
            mvCamDeltaX = 0f;
            mvCamDeltaY = 0f;
            mvCamDeltaZ = 0f;
        }
        mvPrevProjView.set(mvCurProjView);
        mvPrevCamX = camX;
        mvPrevCamY = camY;
        mvPrevCamZ = camZ;
        mvHasPrev = true;
    }

    private void recordFrame(RtContext ctx, RtPipeline active, GpuTexture nativeColor) {
        long dstImage = vkImage(nativeColor);
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).fluorite$getBackend();
        RtGpuExecutor gpuExecutor = ctx.gpuExecutor();
        // Reserve the graphics-use value that guards this frame's reusable TLAS and entity resources.
        RtGpuExecutor.GraphicsUse graphicsUse = gpuExecutor.beginGraphicsUse(encoder);
        RtGpuExecutor.GraphicsUseWaiter graphicsUseWaiter = gpuExecutor.graphicsUseWaiter();
        pendingGraphicsUse = graphicsUse;
        RtEntities.FrameEntities frameEntities = null;
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_COMMAND_BUFFER, cmd.address(), "composite command buffer");
        int debugView = debugView();
        RtTerrain terrain = RtTerrain.currentOrNull();
        try (MemoryStack stack = MemoryStack.stackPush(); RtDebugLabels.Scope frameLabel = RtDebugLabels.scope(ctx, cmd, "composite frame")) {
            // RR drives the upscale: trace + jitter at render res, DLSS-RR denoises+upscales to display.
            // Jitter is suppressed for the no-RR reference and for the debug guide views (raw inspection).
            boolean rrPath = RtDlssRr.enabled() && debugView == 0;
            float jitterX = 0f;
            float jitterY = 0f;
            if (rrPath) {
                FluoriteJitter.INSTANCE.prepare(renderW, renderH, displayW);
                jitterX = FluoriteJitter.INSTANCE.jitterPixelsX() * jitterSignX();
                jitterY = FluoriteJitter.INSTANCE.jitterPixelsY() * jitterSignY();
            }

            boolean rrDone = false;
            // Select the next BDA ring slot; the generated WorldPushData serializer fills it once all
            // frame-derived values (including entity addresses and block-breaking entries) are known.
            pushSlot = (pushSlot + 1) % PUSH_RING;
            PushSlot selectedPushSlot = pushRing[pushSlot];
            graphicsUseWaiter.await(selectedPushSlot.graphicsUse);
            logWaterMediumProbe(selectedPushSlot);
            if (gpuTimers != null) {
                // The await above is what makes this safe: this slot's timestamps are from PUSH_RING frames
                // ago and the GPU has finished with them, so reading costs nothing and resetting cannot
                // race the device still writing.
                gpuTimers.resolve(ctx, pushSlot);
                gpuTimers.beginFrame(cmd, pushSlot);
            }
            selectedPushSlot.graphicsUse.mark(graphicsUse);
            RtBuffer pushBuf = selectedPushSlot.buffer;
            ByteBuffer push = MemoryUtil.memByteBuffer(pushBuf.mapped, WORLD_PUSH_SIZE);
            frameInvViewProj.set(frameProjection).mul(frameViewRotation).invert();
            frameInvProjection.set(frameProjection).invert();
            // flags: camera-in-water (so the path tracer starts in the water medium when the eye is
            // submerged, fixing the air→water first-segment orientation) + W1 wave normals. Bit 1 used to
            // gate a Lambertian fallback BRDF that nothing ever turned off; the GGX path is unconditional
            // now, so that bit is unused rather than reassigned, to avoid a stale reader elsewhere.
            // Bit 12 hides the first-person body from secondary rays; see below and trace.slang.
            int flags = 0;
            // Far below any world, so the shader reads "not submerged" without a second flag to check.
            float waterSurfaceY = -1.0e9f;
            var level = Minecraft.getInstance().level;
            Identifier dimensionId = level == null ? null : level.dimension().identifier();
            RtSkyPreset skyPreset = dimensionId == null
                    ? RtSkyPreset.FULL_ATMOSPHERE
                    : skyPresets.forDimension(dimensionId);
            RtDimensionControls dimensionControls = RtDimensionControls.forDimension(dimensionId);
            RtEnvironmentTextures.Entry environmentEntry = environmentTextures == null
                    ? null : environmentTextures.entry(skyPreset);
            if (skyPreset.skyProvider() == RtSkyPreset.SkyProvider.ENVIRONMENT
                    && environmentEntry == null) {
                // A malformed or missing resource disables only its own dimension. The complete
                // atmosphere is the established safe fallback and must not inherit End-only controls.
                skyPreset = RtSkyPreset.FULL_ATMOSPHERE;
                dimensionControls = RtDimensionControls.DEFAULT;
            }
            if (level != null) {
                cameraBlockPos.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
                // Height-aware, mirroring vanilla's own Camera.getFluidInCamera(): a plain block-granular
                // test wrongly flags the eye submerged anywhere in a water column's top block, even well
                // above its actual surface (shallow/flowing water, or standing with your head just over a
                // source block).
                FluidState fs = level.getFluidState(cameraBlockPos);
                if (fs.is(FluidTags.WATER)) {
                    // The SUBMERGED test uses the camera's own block, which is what vanilla's
                    // Camera.getFluidInCamera does and is right for "which medium does the camera ray
                    // start in".
                    float ownTop = cameraBlockPos.getY() + fs.getHeight(level, cameraBlockPos);
                    if (camY < ownTop) {
                        flags |= 0b01;
                    }
                    // The REFERENCE SURFACE is a different question and used to be answered with the same
                    // number, which was wrong in a way that hid behind the shape of the bug it caused.
                    //
                    // getHeight returns 1.0 for a water block with more water above it, so deep under an
                    // ocean the "surface" came out as floor(camY) + 1 -- pinned one block over the
                    // camera's head, wherever the camera went. Two consequences, both observed: water
                    // thirty blocks down was lit as though it sat at the surface, because its depth came
                    // out as about one block; and floor(camY) steps by one every time the camera crosses a
                    // block boundary, so the whole volume's brightness jumped in one-block layers as the
                    // player swam up or down. That was reported as layered flicker on vertical movement,
                    // and it survived silencing the sky term, which correctly ruled out the sky-light grid.
                    //
                    // Walk up to the real top of the column instead. Depth is a property of the water
                    // body, so the answer must not move when the camera does -- that stillness is the
                    // whole fix, and it is why a scan is worth a few dozen block lookups a frame.
                    surfaceScan.set(cameraBlockPos);
                    float surface = ownTop;
                    for (int step = 0; step < WATER_SURFACE_SCAN_LIMIT; step++) {
                        surfaceScan.setY(surfaceScan.getY() + 1);
                        FluidState above = level.getFluidState(surfaceScan);
                        if (!above.is(FluidTags.WATER)) {
                            break;
                        }
                        surface = surfaceScan.getY() + above.getHeight(level, surfaceScan);
                    }
                    // Written whether or not the EYE is under it, and that separation is the whole point.
                    //
                    // Enclosed single scattering measures how deep a scattering point is in order to know
                    // how much light ever reached it, and the reference surface used to be published only
                    // while submerged. Surfacing therefore replaced every depth in the scene with zero:
                    // exp(-sigma * 0) = 1, so every water segment — including one thirty blocks down —
                    // was lit as though it sat at the surface. That is a step change in the whole volume
                    // at the instant the eye crosses, which is exactly how it was reported (scattering
                    // present above one precise height and absent below it, in every view direction).
                    // The submerged FLAG still gates what it always did: which medium the camera ray
                    // starts in. Depth is a property of the water, not of where the eye happens to be.
                    waterSurfaceY = surface - terrain.blockY;
                }
            }
            // Hide the first-person body from secondary rays while its owner is in the water at all
            // (trace.slang FLAG_HIDE_SELF).
            //
            // The camera's own block is the wrong test and was tried first: standing waist-deep, the eye is
            // a block and a half above the water and sits in AIR, so the flag never fired for exactly the
            // case that asked for it. What matters is where the BODY is, because the body is what shows up
            // sliced at the waterline in the surface reflection a metre in front of you — vanilla's first
            // person draws no body at all, so half of one is read as a glitch rather than as seeing
            // yourself. Standing beside a pond keeps the reflection, which is the case worth having.
            Entity cameraOwner = Minecraft.getInstance().getCameraEntity();
            if (cameraOwner != null && cameraOwner.isInWater()) {
                flags |= 0b1000000000000;
            }
            if (waterWaves()) {
                flags |= 0b10000; // W1: animated water wave normals
            }
            if (FluoriteConfig.Rt.Bsdf.MIS_ENABLED.value()) {
                flags |= 0b100000; // weight the sun's two estimators instead of summing them
            }
            if (FluoriteConfig.Rt.Bsdf.ANISOTROPY_ENABLED.value()) {
                flags |= 0b1000000; // stretch the specular lobe along the surface tangent
            }
            // Subsurface mode in bits 7-8 and the walk's event budget in bits 9-11. Packed rather than
            // given lanes of their own: WorldPush is read once per path, but every field added to it is
            // paid by the layout test, the generated record and everyone reading the struct.
            flags |= (FluoriteConfig.Rt.Bsdf.subsurfaceModeId() & 0b11) << 7;
            flags |= (Math.clamp(FluoriteConfig.Rt.Bsdf.SUBSURFACE_MAX_EVENTS.value(), 0, 7)) << 9;
            // Bits 13-14: which source feeds the water's single scattering. A measurement switch —
            // see FluoriteConfig.Rt.Water.SCATTER_SOURCE.
            flags |= (FluoriteConfig.Rt.Water.scatterSourceId() & 0b11) << 13;
            if (!FluoriteConfig.Rt.Water.SUN_SHADOW.value()) {
                flags |= 0b1000000000000000; // bit 15: take the sun as unoccluded (isolation switch)
            }
            // Bits 16-17: which of the fog's two in-scatter machines are live. The froxel answers for the
            // camera prefix and the closed form answers for every bounce; they are summed on screen, so
            // this is the only way to ask which of them a brightness came from. See
            // FluoriteConfig.Rt.Volumetrics.SEGMENT_SOURCE.
            flags |= (FluoriteConfig.Rt.Volumetrics.segmentSourceId() & 0b11) << 16;
            // Bits 18-22: the cap on how many visibility sub-steps a marched segment may take.
            flags |= (FluoriteConfig.Rt.Volumetrics.visibilityMaxSteps() & 0b11111) << 18;
            // Bits 23-25: jittered shadow rays for the fog's sun term (0 = read the grid instead).
            flags |= (FluoriteConfig.Rt.Volumetrics.sunShadowRays() & 0b111) << 23;
            if (FluoriteConfig.Rt.Volumetrics.MULTI_SCATTER.value()) {
                // Bit 26: the source decays at the diffusion rate rather than the beam's.
                flags |= 1 << 26;
            }
            if (skyPreset.cloudsEnabled() && FluoriteConfig.Rt.Volumetrics.CLOUDS.value()) {
                flags |= 1 << 30; // volumetric clouds (M11)
                // Bits 2-3: how much march a ray that is not the first of its path may spend. A cost
                // dial only — every ray intersects the same world-anchored shells from its own origin,
                // so this cannot move a cloud in a reflection relative to the one overhead.
                flags |= (FluoriteConfig.Rt.Volumetrics.cloudSecondaryId() & 0b11) << 2;
                if (FluoriteConfig.Rt.Volumetrics.CLOUD_MULTI_SCATTER.value()) {
                    // Bit 29: the diffusion term that keeps a thick cloud's interior bright. Nested under
                    // the clouds themselves rather than independent, so the off state of the pair is one
                    // state rather than two that differ only in dead work.
                    flags |= 1 << 29;
                }
            }
            if (FluoriteConfig.Rt.Volumetrics.SCATTER_VERTEX.value()) {
                flags |= 1 << 27; // sample one scattering event per segment (M17)
                if (FluoriteConfig.Rt.Volumetrics.VOLUME_EMITTER_NEE.value()) {
                    // Bit 28: emitters light the medium at that event. Requires the event, so it is
                    // nested rather than independent -- the shader would have nowhere to put it.
                    flags |= 1 << 28;
                }
            }

            // W1/W2 water parameters: camera-biome tint plus wrapped animation time. Per-water-body tint
            // comes from the primitive; this is the fallback for a camera already inside the medium.
            float wtr = 0.25f, wtg = 0.46f, wtb = 0.9f; // neutral ocean-ish default if no level/biome
            if (level != null) {
                int wc = BiomeColors.getAverageWaterColor(level, cameraBlockPos);
                wtr = ((wc >> 16) & 0xFF) / 255f;
                wtg = ((wc >> 8) & 0xFF) / 255f;
                wtb = (wc & 0xFF) / 255f;
            }
            float waterWaveTime = (float) (System.nanoTime() / 1.0e9 % 3600.0);
            float waterWaveDelta = waterWaveTime - previousWaterWaveTime;
            // A first frame, long pause, or one-hour phase wrap has no adjacent wave frame to reproject.
            // Use the current phase so the reflection MV is neutral instead of manufacturing a huge jump.
            float priorWaterWaveTime = waterWaveTimeValid
                    && waterWaveDelta >= 0f && waterWaveDelta <= 0.25f
                    ? previousWaterWaveTime : waterWaveTime;
            previousWaterWaveTime = waterWaveTime;
            waterWaveTimeValid = true;
            Float4 waterParams = new Float4(wtr, wtg, wtb, waterWaveTime);
            // W1 wave-domain anchor: the terrain rebase origin reduced mod 4096 (kept small for shader
            // float precision). hitPos.xz (rebased) + anchor reconstructs a world-pinned coordinate, so the
            // ripple pattern stays fixed in the world as the player moves and the rebase origin shifts.
            // Move the deformation region before the domain, so a section that crosses the boundary is
            // marked for re-extraction this frame rather than one behind.
            RtTerrain.updateDeformAnchor(camX, camY, camZ,
                    FluoriteConfig.Rt.Water.WATER_SIM.value()
                            && FluoriteConfig.Rt.Water.WATER_DEFORM.value(),
                    Math.round(FluoriteConfig.Rt.Water.WATER_DEFORM_RANGE.value()));
            boolean waterSimLive = placeWaterDomain(camX, camY, camZ, level, terrain);
            if (waterSimLive) {
                collectWaterImpulses(camX, camZ, level);
            }
            logWaterMediumContradiction(flags, waterSurfaceY, camY, terrain.blockY, waterSimLive);
            Float4 waterAnchor = new Float4(terrain.blockX & WATER_ANCHOR_MASK,
                    terrain.blockZ & WATER_ANCHOR_MASK, priorWaterWaveTime, waterSurfaceY);

            // Rebuild the TLAS this frame from static section instances merged with dynamic entity
            // instances, bind it into the pipeline's descriptor ring, record the build, then barrier so
            // the trace sees the finished TLAS. Section BLASes are already built (async, by RtTerrain);
            // only the cheap instance-level TLAS is rebuilt per frame. Retired terrain geometry/table
            // generations are reclaimed by graphics-timeline completion.
            // Entity BLASes are built inline below and merged into the per-frame TLAS. geomTableAddr
            // feeds the hit shader entity path (per-prim normal/tint) and motion vectors.
            RtEntities.FrameEntities fe = RtEntities.INSTANCE.beginFrame(ctx, terrain.staticInstances(),
                    terrain.blockX, terrain.blockY, terrain.blockZ, camX, camY, camZ, frameProjection, frameViewRotation);
            frameEntities = fe;
            boolean waterProbeEnabled = FluoriteConfig.Rt.Diagnostics.WATER_MEDIUM_TRACE.value();
            selectedPushSlot.waterProbeArmed = waterProbeEnabled;
            selectedPushSlot.waterProbeTerrain = terrain.streamingDiagnostics();
            selectedPushSlot.waterProbeCpuFlags = flags;
            if (waterProbeEnabled) {
                // A valid bit from an older use of this slot must not masquerade as a current sample if a
                // trace aborts before the centre pixel writes. The four-byte flush is harmless on coherent
                // memory and correctly aligned by VMA otherwise.
                MemoryUtil.memPutInt(selectedPushSlot.waterProbe.mapped + WaterMediumProbeData.FLAGS_OFFSET, 0);
                selectedPushSlot.waterProbe.flush(WaterMediumProbeData.FLAGS_OFFSET, Integer.BYTES);
            }
            // Block-breaking overlay: resolves each destroy-stage RenderType's texture into the
            // SAME bindless entity-texture array (destroy_stage_N.png is a standalone Sampler0 texture,
            // not a block-atlas sprite — see ModelBakery.BREAKING_LOCATIONS/DESTROY_TYPES), so any newly
            // resolved slot rides along with the uploadPending() call right below.
            BreakEntry[] breaking = breakingEntries(terrain);
            // One immutable weather/time read for every medium. The shader receives only the resolved
            // physical values, so this does not grow WorldPush or add a weather branch to any hot path.
            RtEnvironmentForcing.Frame environment = skyPreset.weatherEnabled()
                    ? RtEnvironmentForcing.capture(level)
                    : RtEnvironmentForcing.captureClock(level);
            SkyPush sky = skyPush(skyPreset, dimensionControls, environment.gameSeconds());
            Float4 resolvedFogParams = fogParams(skyPreset, dimensionControls, environment);
            Float4 resolvedCloudParams = cloudParams(environment);
            RainSurfaceState rainSurface = smoothRainSurfaces(
                    level, environment, sky.sunDir().w(), resolvedCloudParams);
            RainExposureFrame rainExposure = rainExposureFrame(
                    camX, camY, camZ, level, terrain, waterWaveTime, rainSurface);
            float waterStorm = smoothWaterStorm(level, environment);
            float stormSwellBias = Math.clamp(waterStorm, 0f, 1f)
                    * FluoriteConfig.Rt.Weather.WATER_STORM_SWELL_BIAS.value();
            // Coefficients remain CPU-visible; M16's source is reduced on the GPU later and is deliberately
            // not read back into this frame-stats diagnostic (see logWaterCoefficients).
            if (level != null) {
                logWaterCoefficients(wtr, wtg, wtb);
            }
            new WorldPushData(
                    frameInvViewProj,
                    new Float3((float) (camX - terrain.blockX), (float) (camY - terrain.blockY),
                            (float) (camZ - terrain.blockZ)),
                    (int) frameCounter,
                    mvPushMatrix,
                    new Float3(mvCamDeltaX, mvCamDeltaY, mvCamDeltaZ),
                    spp(),
                    new Float2(jitterX, jitterY),
                    flags,
                    maxBounces(),
                    sky.sunDir(),
                    sky.lightDir(),
                    sky.lightRadiance(),
                    sky.moonDir(),
                    sky.celestial(),
                    sky.sunUv(),
                    sky.moonUv(),
                    waterParams,
                    waterAnchor,
                    mvCurProjView,
                    breaking.length,
                    breaking,
                    // RIS emitter NEE: candidate count (0 = emitter NEE off; the shader also requires
                    // lightCount > 0, so an empty buffer degrades to legacy gather). The light buffer
                    // device addresses themselves are pc.light*Addr — every 64-bit address lives in the
                    // push-constant block now, not here.
                    new Float4(terrain.lightRebaseOffsetX(), terrain.lightRebaseOffsetY(),
                            terrain.lightRebaseOffsetZ(), terrain.lightInvGlobalPowerSum()),
                    new Float4(terrain.lightGridOriginX(), terrain.lightGridOriginY(), terrain.lightGridOriginZ(), 16f),
                    new Int4(terrain.lightGridDimX(), terrain.lightGridDimY(), terrain.lightGridDimZ(), 0),
                    terrain.lightCount(),
                    FluoriteConfig.Rt.Lights.RIS_CANDIDATES.value(),
                    skyPreset.skyProvider().shaderId(),
                    environmentFlags(skyPreset, resolvedFogParams.x() > 0f, environmentEntry),
                    resolvedFogParams,
                    fogExtinction(skyPreset),
                    fogScatter(skyPreset),
                    fogAux(skyPreset, environment),
                    fogNoiseOrigin(terrain, environment),
                    // Atmosphere mode overwrites this via sky_medium_reduce later in the command buffer.
                    // Other providers own it directly, so skipping that reduction preserves their
                    // authored environment source rather than stale atmospheric state.
                    mediumSkyRadiance(skyPreset, dimensionControls, environmentEntry),
                    waterScatter(environment),
                    waterAbsorbOverride(),
                    waterAux(waterStorm),
                    visibilityGridOrigin(camX, camY, camZ, terrain),
                    resolvedCloudParams,
                    cloudShape(),
                    cloudRebase(terrain, environment),
                    cloudLighting(),
                    cloudCirrus(),
                    cloudCirrusShape(),
                    cloudCirrusOrigin(terrain, environment),
                    cloudEvolution(environment),
                    // ORDER MATTERS AND NOTHING CHECKS IT FOR YOU. WorldPushData is generated from the
                    // shader's reflection, so its constructor is POSITIONAL and follows world_common's
                    // declaration order -- waterSimDomain, then the two wave lanes, then the plane.
                    // Getting it wrong compiles, runs, and silently feeds every lane the one beside it.
                    waterSimDomain(),
                    waterWaveShape(waterStorm),
                    waterWaveGust(waterStorm),
                    waterWaveWarp(),
                    waterSimPlane(stormSwellBias, environment.waterCausticContrast()),
                    rainExposure.origin(),
                    rainExposure.direction(),
                    rainExposure.state(),
                    rainExposure.materialDefaults(),
                    rainExposure.surface(),
                    rainExposure.puddle(),
                    rainExposure.calibration0(),
                    rainExposure.calibration1(),
                    cloudWarp(environment)
            ).write(push);
            pushBuf.flush(0L, WORLD_PUSH_SIZE);
            // Upload any entity textures registered this frame into the bindless set before the trace.
            RtEntityTextures.INSTANCE.uploadPending(active, atlasSampler(ctx));
            // Build the entity BLAS, the TLAS that references it and the terrain BLAS, then the trace.
            // Barriers separate each stage; the graphics-use timeline guards resource reuse.
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_ENTITY_BLAS);
            }
            if (!fe.blas().isEmpty()) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("entity.blasRecord")) {
                    RtAccel.recordBlasBuilds(ctx, cmd, fe.blas());
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack); // entity BLAS writes visible to the TLAS build
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_ENTITY_BLAS);
            }
            // Water geometry, before the TLAS is built over the bounds it produces. Guarded on the
            // domain being live: with no plane there is nothing to displace onto.
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_WATER_DEFORM);
            }
            // deformBuiltIn() is the snapshot from the last terrain load; the live setting can still
            // switch the pass OFF instantly, which is always safe and free. It cannot switch it ON,
            // because nothing resident was built to deform -- that is what the reload is for.
            if (RtTerrain.deformBuiltIn() && FluoriteConfig.Rt.Water.WATER_DEFORM.value()
                    && waterSimLive) {
                try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("water.deform")) {
                    float range = FluoriteConfig.Rt.Water.WATER_DEFORM_RANGE.value();
                    // Section-local xz reaches the wave domain by adding the section origin, dropping the
                    // terrain rebase and adding back the masked anchor -- the same three-step the shading
                    // does, assembled here because this is where all three are known (F21).
                    double waveOffsetX = -terrain.blockX + (terrain.blockX & WATER_ANCHOR_MASK);
                    double waveOffsetZ = -terrain.blockZ + (terrain.blockZ & WATER_ANCHOR_MASK);
                    terrain.recordWaterDeform(ctx, cmd, skyLuts, pushBuf.deviceAddress,
                            waveOffsetX, waveOffsetZ,
                            (float) (camX + waveOffsetX), (float) (camZ + waveOffsetZ),
                            range * 0.75f, range, 1.0f);
                }
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_WATER_DEFORM);
            }
            RtAccel.PreparedTlas frameTlas;
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.prepareTlas")) {
                frameTlas = RtAccel.prepareTlas(ctx, fe.baseInstances(), fe.dynamicInstances(), tlasRing,
                        graphicsUse);
            }
            active.setTlas(frameTlas.accel.handle, graphicsUse, graphicsUseWaiter);
            // recordRainHistory will publish the opposite ping-pong image before the trace; bind that
            // exact target into the descriptor slot setTlas just proved idle.
            active.setCurrentRainWetHistory(
                    skyLuts.rainWetHistoryView(skyLuts.rainWetHistoryTargetIndex()),
                    skyLuts.rainWetHistorySampler());
            currentTlasHandle = frameTlas.accel.handle;
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_TLAS_BUILD);
            }
            try (RtFrameStats.Scope ignored = RtFrameStats.FRAME.stage("frame.recordTlas")) {
                RtAccel.recordTlasBuild(ctx, cmd, frameTlas);
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_TLAS_BUILD);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // TLAS build visible to the trace

            // The atmosphere's tables. recordBakeIfNeeded is a no-op after the first frame — nothing in
            // the transmittance table depends on the time of day, since the sun's position enters where
            // it is sampled rather than where it is baked. Bound only once the bake has been recorded, so
            // the descriptor never names an image with undefined contents.
            if (skyLuts == null) {
                skyLuts = RtSky.create(ctx);
            }
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_SKY_BAKE);
            }
            boolean atmosphereProvider = skyPreset.skyProvider() == RtSkyPreset.SkyProvider.ATMOSPHERE;
            // The once-only method currently owns atmosphere tables plus both static noise fields. A
            // local-ambient resource-pack dimension may legally request clouds or structured fog, so it
            // must initialise those textures even though it will never sample the atmosphere tables.
            // The built-in Nether requests neither and therefore still skips this whole bake.
            boolean needsStaticSkyAssets = atmosphereProvider
                    || skyPreset.cloudsEnabled() || skyPreset.fog().noise();
            if (needsStaticSkyAssets && skyLuts.recordBakeIfNeeded(cmd)) {
                // A later compute/trace in this command buffer samples at least one image just written.
                // On every frame after the first this is a no-op; on the first, the barrier prevents a
                // valid preset from reading undefined atmosphere or noise texels.
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }
            // The sky-view table, every frame. Its two inputs do not depend on the time of day and are
            // baked once; this one does, because the sun's direction enters where it is BAKED rather than
            // where it is sampled. Recorded unconditionally rather than on a change test: the sun moves
            // every tick anyway, and a test that let one stale frame through would show as the sky
            // lagging the sun, which is exactly the kind of fault nobody attributes correctly later.
            if (atmosphereProvider) {
                skyLuts.recordSkyViewBake(cmd, sky.sunDir().x(), sky.sunDir().y(), sky.sunDir().z(),
                        FluoriteConfig.Rt.Sky.skyTint(), FluoriteConfig.Rt.Sky.SKY_INTENSITY.value(),
                        pushBuf.deviceAddress);
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_SKY_BAKE);
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_FROXEL_BAKE);
            }
            // The froxel, after the sky-view table and after the push buffer it reads has been written.
            // It follows the CAMERA as well as the sun, so per-frame is not a choice here at all.
            skyLuts.recordFroxelBake(cmd, pushBuf.deviceAddress,
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), frameTlas.accel.handle, graphicsUse);
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_FROXEL_BAKE);
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_VIS_BAKE);
            }
            // The volumetric visibility grid (M13.2), which the froxel does NOT read — it casts its own
            // rays, at its own view-adaptive sample positions, and is far denser near the eye than any
            // uniform world grid could be. This one exists for the segments the froxel cannot cover: every
            // bounce, whose fog was completely unshadowed until now.
            //
            // Its own timing zone. Sharing the froxel's would keep the one question this dispatch raises
            // unanswerable: it and the froxel each cast a few hundred thousand rays a frame, they can be
            // resized independently, and a single number covering both cannot say which resize to make.
            //
            // Gated on the same condition visibilityGridOrigin() publishes: with cell size 0 the origin's
            // w is 0, every sampler returns unoccluded, and the bake used to run anyway — 131k rays all
            // cast from the degenerate origin, paid every frame to fill a grid nobody would read. The
            // timer zone stays unconditional so gpu.visBake reads ~0 rather than going stale.
            if (FluoriteConfig.Rt.Volumetrics.VISIBILITY_CELL_SIZE.value() > 0f
                    && FluoriteConfig.Rt.Volumetrics.ENABLED.value()
                    && skyPreset.fog().ambientVisibility() != RtSkyPreset.AmbientVisibility.UNOCCLUDED) {
                skyLuts.recordVisibilityBake(cmd, pushBuf.deviceAddress, frameTlas.accel.handle, graphicsUse);
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_VIS_BAKE);
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_RAIN_EXPOSURE);
            }
            if (rainExposure.resolution() > 0) {
                // The anchor remains stable around the player and recentres in 32-block steps. The CPU
                // cache slides its overlap then; the compute pass keeps near tiles hot and fills far tiles
                // progressively before every path vertex consumes the shared directional depth.
                try (RtFrameStats.Scope ignoredStats =
                             RtFrameStats.FRAME.stage("rain.exposureCache")) {
                    skyLuts.updateRainPrecipitation(level, rainExposure.originX(), rainExposure.originZ(),
                            rainExposure.resolution(), pushSlot);
                }
                skyLuts.recordRainExposureBake(cmd, pushBuf.deviceAddress, terrain.tableAddress(),
                        frameTlas.accel.handle, rainExposure.originX(), rainExposure.originZ(),
                        rainExposure.resolution(), rainExposure.direction().x(),
                        rainExposure.direction().y(), rainExposure.direction().z(), pushSlot, graphicsUse);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                skyLuts.recordRainHistory(cmd, pushBuf.deviceAddress,
                        rainExposure.originX(), rainExposure.originZ(), rainExposure.resolution(),
                        rainSurface.elapsedSeconds(),
                        FluoriteConfig.Rt.Weather.WET_FILL_SECONDS.value(),
                        FluoriteConfig.Rt.Weather.WET_DRY_SECONDS.value(),
                        FluoriteConfig.Rt.Weather.PUDDLE_FILL_SECONDS.value(),
                        FluoriteConfig.Rt.Weather.PUDDLE_DRY_SECONDS.value(),
                        rainSurface.dryingScale());
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_RAIN_EXPOSURE);
            }
            // OUTSIDE the visibility gate, which is where it used to be and had no business being. The
            // water solver was nested inside `VISIBILITY_CELL_SIZE > 0 && Volumetrics.ENABLED`, so
            // turning the fog off silently stopped the ripples -- two settings with nothing to do with
            // each other, coupled by nothing but where a brace happened to fall, and no way to tell from
            // the symptom that the fog switch was responsible.
            // Unconditional around the gated work, so the column reads ~0 rather than going stale --
            // the same reasoning the visibility bake's zone is documented with.
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_WATER_SIM);
            }
            if (waterSimLive) {
                // (c*dt/dx)^2, clamped to the CFL limit HERE, where the timestep and the cell size
                // are both known. Past c*dt/dx = 1/sqrt(2) explicit leapfrog does not lose accuracy,
                // it amplifies every step and the field explodes inside a second -- so this is a
                // hard bound on an authored value, not a taste adjustment.
                float dt = 1f / 60f;
                float courant = FluoriteConfig.Rt.Water.WATER_SIM_SPEED.value() * dt
                        / waterCellSize();
                courant = Math.min(courant, 0.70f);
                skyLuts.recordWaterSim(cmd, frameTlas.accel.handle, graphicsUse,
                        waterDomain.x(), waterDomain.y(), waterCellSize(),
                        (float) (waterSurfaceY - terrain.blockY),
                        courant * courant,
                        FluoriteConfig.Rt.Water.WATER_SIM_DAMPING.value(),
                        RtSky.WATER_SIM_DIM * 0.10f,
                        waterImpulses, waterImpulseCount, waterReanchor,
                        // The domain's absolute cell origin, so the solver can resolve a
                        // re-anchor by shifting its reads instead of losing the field.
                        waterCellX, waterCellZ);
            }
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_WATER_SIM);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // bakes visible to the trace's sampling

            // Push the BDA ring slot's address plus the small hot subset used directly by the shaders.
            // Every 64-bit device address the trace needs lives here, not behind worldPushAddr: the
            // section/entity/material tables are read from world.rahit/world.rchit, which never load
            // WorldPush at all, and the RIS light buffers are read from world.rgen's hot inner loop, so
            // none of them should cost an extra BDA dereference to find.
            ByteBuffer pushConstants = stack.malloc(WorldPushConstantsData.BYTE_SIZE);
            new WorldPushConstantsData(pushBuf.deviceAddress, terrain.tableAddress(), fe.geomTableAddr(),
                    RtMaterialRegistry.INSTANCE.tableAddress(),
                    RtMaterialRegistry.INSTANCE.extensionTableAddress(),
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), continuationQueue.deviceAddress,
                    waterProbeEnabled ? selectedPushSlot.waterProbe.deviceAddress : 0L,
                    (int) frameCounter, debugView, shadeFlags()).write(pushConstants);
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world primary trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.tracePrimary")) {
                if (gpuTimers != null) {
                    gpuTimers.begin(cmd, pushSlot, GPU_ZONE_TRACE_PRIMARY);
                }
                active.trace(cmd, renderW, renderH, pushConstants, 0);
                if (gpuTimers != null) {
                    gpuTimers.end(cmd, pushSlot, GPU_ZONE_TRACE_PRIMARY);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // continuation/guide writes visible to pass B
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "world indirect trace");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.traceIndirect")) {
                if (gpuTimers != null) {
                    gpuTimers.begin(cmd, pushSlot, GPU_ZONE_TRACE_INDIRECT);
                }
                active.trace(cmd, renderW, renderH, pushConstants, 1);
                if (gpuTimers != null) {
                    gpuTimers.end(cmd, pushSlot, GPU_ZONE_TRACE_INDIRECT);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // RT writes visible to DLSS reads
            // DLSS-RR denoise + upscale. The RT pass wrote noisy color (render res) + guides;
            // RR reads them and writes the display-res denoised result straight into rrOutput.
            if (rrPath && RtDlssRr.INSTANCE.ensureFeature(cmd.address(), renderW, renderH, displayW, displayH)) {
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "DLSS-RR evaluate");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.dlssRr")) {
                    rrDone = RtDlssRr.INSTANCE.evaluate(cmd.address(), output, gDepth, gMotion, gAlbedo,
                            gSpecAlbedo, gNormal, gSpecMotion, rrOutput, renderW, renderH, displayW, displayH,
                            -jitterX, -jitterY, frameViewRotation, frameProjection);
                }
            }

            // When DLSS-RR did not produce the display-res image (disabled, debug view, or a runtime
            // failure), bring the render-res trace up to display res with a linear blit so the display mapper
            // always has a display-res RT image. With RR off render == display, so this is a 1:1 copy.
            if (!rrDone) {
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "fallback upscale");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.upscale")) {
                    blitUpscale(cmd, stack, output, rrOutput);
                }
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rrOutput visible to the HDR rain pass

            int rainStreakCount = 0;
            float rainStreakDensity = FluoriteConfig.Rt.Weather.RAIN_STREAK_DENSITY.value();
            if (FluoriteConfig.Rt.Weather.RAIN_PARTICLES_ENABLED.value()) {
                rainStreakCount = Math.round(FluoriteConfig.Rt.Weather.rainStreakBudget()
                        * rainStreakDensity * rainSurface.rain());
            }
            RtFrameStats.FRAME.count("rainStreakInstances", rainStreakCount);
            if (gpuTimers != null) {
                gpuTimers.begin(cmd, pushSlot, GPU_ZONE_RAIN_STREAK);
            }
            rainStreaks.record(ctx, cmd, rrOutput.view, gDepth.view, skyLuts.rainExposureDepthView(),
                    skyLuts.transmittanceView(), skyLuts.transmittanceSampler(),
                    frameTlas.accel.handle, displayW, displayH,
                    pushBuf.deviceAddress, (int) frameCounter, rainStreakCount,
                    FluoriteConfig.Rt.Weather.RAIN_STREAK_SPEED.value(),
                    FluoriteConfig.Rt.Weather.RAIN_STREAK_LENGTH.value(), rainStreakDensity,
                    terrain.lightBufferAddress(), terrain.lightAliasBufferAddress(),
                    terrain.lightLocalAliasBufferAddress(), terrain.lightGridCellBufferAddress(),
                    terrain.lightGridSpanBufferAddress(), graphicsUse);
            if (gpuTimers != null) {
                gpuTimers.end(cmd, pushSlot, GPU_ZONE_RAIN_STREAK);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // rain colour writes visible to exposure

            // Auto-exposure meters rrOutput (the post-RR, denoised/converged image), not the raw
            // pre-RR trace: RR has no notion of exposure (DLSS-RR Integration Guide §3.7 — ignore
            // exposure/auto-exposure/sharpness entirely for RR), so this is purely our own metering
            // choice, independent of RR's pipeline placement. Metering the noisy pre-RR buffer made
            // the histogram's log-luminance average biased by Monte-Carlo noise (Jensen's inequality
            // on the concave log()), so the computed exposure drifted with SPP; rrOutput is stable
            // regardless of SPP, keeping exposure consistent.
            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "exposure");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.exposure")) {
                exposure.record(ctx, cmd, stack, rrOutput);
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack); // exposure image visible to the display mapper

            boolean lensDiagnosticsBypass = debugView != 0;
            boolean motionBlur = !lensDiagnosticsBypass
                    && FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_ENABLED.value();
            boolean depthOfField = !lensDiagnosticsBypass
                    && FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_ENABLED.value();
            boolean bloom = !lensDiagnosticsBypass
                    && FluoriteConfig.Rt.PostProcessing.BLOOM_ENABLED.value();
            boolean lensFlare = !lensDiagnosticsBypass
                    && FluoriteConfig.Rt.PostProcessing.LENS_FLARE_ENABLED.value();
            boolean sourceIsScratch = false;
            if (motionBlur || depthOfField) {
                if (lensPipeline == null || lensScratch == null || lensFocus == null) {
                    throw new IllegalStateException("spatial lens effects enabled without resources");
                }
                if (depthOfField && (depthOfFieldPipeline == null || depthOfFieldCoc == null
                        || depthOfFieldNear == null || depthOfFieldFar == null
                        || depthOfFieldTiles == null || depthOfFieldDilatedTiles == null)) {
                    throw new IllegalStateException("depth of field enabled without split-layer resources");
                }
                if (gpuTimers != null) gpuTimers.begin(cmd, pushSlot, GPU_ZONE_LENS_SPATIAL);
                try (RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.lensSpatial")) {
                    float focalLengthMm = Math.max(1.0f, 18.0f * Math.abs(frameProjection.m00()));
                    if (motionBlur) {
                        lensPipeline.motionBlur(cmd, RtLensPipeline.RR_TO_SCRATCH,
                                frameInvProjection, displayW, displayH, renderW, renderH,
                                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_SAMPLES.value(),
                                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_SHUTTER_ANGLE.value(),
                                FluoriteConfig.Rt.PostProcessing.MOTION_BLUR_MAX_RADIUS.value(),
                                focalLengthMm);
                        sourceIsScratch = true;
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    }
                    boolean autoFocus = FluoriteConfig.Rt.PostProcessing.automaticFocus();
                    if (depthOfField) {
                        if (autoFocus) {
                            lensPipeline.updateAutoFocus(cmd, frameInvProjection,
                                    displayW, displayH, renderW, renderH, focalLengthMm);
                            VulkanCommandEncoder.memoryBarrier(cmd, stack);
                        }
                        depthOfFieldPipeline.record(cmd,
                                sourceIsScratch ? RtLensPipeline.SCRATCH_TO_RR
                                        : RtLensPipeline.RR_TO_SCRATCH,
                                frameInvProjection, displayW, displayH, renderW, renderH,
                                autoFocus,
                                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_FOCUS_DISTANCE.value(),
                                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_F_STOP.value(),
                                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_MAX_RADIUS.value(),
                                focalLengthMm,
                                FluoriteConfig.Rt.PostProcessing.DEPTH_OF_FIELD_APERTURE_BLADES.value());
                        sourceIsScratch = !sourceIsScratch;
                        VulkanCommandEncoder.memoryBarrier(cmd, stack);
                    }
                }
                if (gpuTimers != null) gpuTimers.end(cmd, pushSlot, GPU_ZONE_LENS_SPATIAL);
            }

            if (bloom || lensFlare) {
                if (bloomFlarePipeline == null || lensScratch == null || bloomBright[0] == null
                        || bloomPyramid[0] == null) {
                    throw new IllegalStateException("bloom/lens flare enabled without HDR pyramid resources");
                }
                if (gpuTimers != null) gpuTimers.begin(cmd, pushSlot, GPU_ZONE_BLOOM_FLARE);
                try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "HDR bloom and lens flare");
                     RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.bloomFlare")) {
                    bloomFlarePipeline.record(cmd,
                            sourceIsScratch ? RtLensPipeline.SCRATCH_TO_RR : RtLensPipeline.RR_TO_SCRATCH,
                            displayW, displayH, bloom, lensFlare,
                            FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_FILTER_THRESHOLD.value(),
                            FluoriteConfig.Rt.PostProcessing.HIGHLIGHT_FILTER_SOFT_KNEE.value(),
                            FluoriteConfig.Rt.PostProcessing.BLOOM_INTENSITY.value(),
                            FluoriteConfig.Rt.PostProcessing.BLOOM_RADIUS.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_INTENSITY.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_GHOSTS.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_HALO.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_STREAKS.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_THRESHOLD.value(),
                            FluoriteConfig.Rt.PostProcessing.LENS_FLARE_BOKEH_SIZE.value());
                    sourceIsScratch = !sourceIsScratch;
                }
                if (gpuTimers != null) gpuTimers.end(cmd, pushSlot, GPU_ZONE_BLOOM_FLARE);
                VulkanCommandEncoder.memoryBarrier(cmd, stack);
            }

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "map RT to display");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.displayMap")) {
                if (gpuTimers != null) gpuTimers.begin(cmd, pushSlot, GPU_ZONE_DISPLAY_MAP);
                displayPipeline.dispatch(cmd, displayW, displayH, FluoriteConfig.Rt.Hdr.enabled(),
                        FluoriteConfig.Rt.PostProcessing.outputTransformMode(),
                        FluoriteConfig.Rt.PostProcessing.acesHdrPresetNits(),
                        FluoriteConfig.Rt.Hdr.paperWhiteNits(), FluoriteConfig.Rt.Hdr.headroom(),
                        creativeGradeSettings(), filmGrainSettings(), (int) frameCounter,
                        !lensDiagnosticsBypass
                                && FluoriteConfig.Rt.PostProcessing.LENS_DISTORTION_ENABLED.value(),
                        FluoriteConfig.Rt.PostProcessing.LENS_DISTORTION_STRENGTH.value(),
                        !lensDiagnosticsBypass
                                && FluoriteConfig.Rt.PostProcessing.CHROMATIC_ABERRATION_ENABLED.value(),
                        FluoriteConfig.Rt.PostProcessing.CHROMATIC_ABERRATION_STRENGTH.value(),
                        !lensDiagnosticsBypass && FluoriteConfig.Rt.PostProcessing.VIGNETTE_ENABLED.value(),
                        FluoriteConfig.Rt.PostProcessing.VIGNETTE_INTENSITY.value(),
                        FluoriteConfig.Rt.PostProcessing.VIGNETTE_START.value(),
                        FluoriteConfig.Rt.PostProcessing.VIGNETTE_SOFTNESS.value());
                if (gpuTimers != null) gpuTimers.end(cmd, pushSlot, GPU_ZONE_DISPLAY_MAP);
            }
            hdrWrittenThisFrame = FluoriteConfig.Rt.Hdr.enabled();
            VulkanCommandEncoder.memoryBarrier(cmd, stack);

            try (RtDebugLabels.Scope ignored = RtDebugLabels.scope(ctx, cmd, "copy composite to main target");
                 RtFrameStats.Scope ignoredStats = RtFrameStats.FRAME.stage("frame.copyOutput")) {
                VK10.vkCmdCopyImage(cmd, displayImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                        dstImage, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, displayW, displayH));
            }
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(rt composite) failed");
        }
        encoder.execute(cmd); // deferred into the frame's submission — correct for per-frame work
        // Do not attach a merely reserved token: failed recording may never signal it. Once execute succeeds,
        // every owner in this frame's manifest is protected through the final overlay consumer.
        RtEntities.INSTANCE.markGraphicsUse(frameEntities, graphicsUse);
    }

    /**
     * Block-breaking overlay: mirrors vanilla's {@code ClientLevel.destructionProgress()} (populated
     * by network packets, independent of the cancelled {@code LevelRenderer.render()} — see
     * [[rt-native-overlay-tier1]]) into the push's {@code breaking[]} list, so {@code world.rchit} can blend
     * the matching destroy-stage crack texture into a hit terrain block's albedo. Each block's own
     * destroy-stage texture ({@code minecraft:textures/block/destroy_stage_N.png}, resolved via
     * {@link ModelBakery#DESTROY_TYPES}) is a standalone {@code Sampler0} texture, not a block-atlas sprite,
     * so it rides the same bindless entity-texture array as entity textures ({@link RtEntityTextures}).
     */
    private BreakEntry[] breakingEntries(RtTerrain terrain) {
        BreakEntry[] result = new BreakEntry[WorldPushData.BREAKING_CAPACITY];
        int count = 0;
        var level = Minecraft.getInstance().level;
        if (level != null) {
            for (var entry : level.destructionProgress().long2ObjectEntrySet()) {
                if (count >= result.length) {
                    break;
                }
                var progresses = entry.getValue();
                if (progresses == null || progresses.isEmpty()) {
                    continue;
                }
                int stage = Mth.clamp(progresses.last().getProgress(), 0, 9);
                BlockPos pos = BlockPos.of(entry.getLongKey());
                int slot = RtEntityTextures.INSTANCE.slotFor(ModelBakery.DESTROY_TYPES.get(stage));
                result[count++] = new BreakEntry(new Int4(
                        pos.getX() - terrain.blockX,
                        pos.getY() - terrain.blockY,
                        pos.getZ() - terrain.blockZ,
                        slot));
            }
        }
        return count == result.length ? result : java.util.Arrays.copyOf(result, count);
    }

    private record SkyPush(Float4 sunDir, Float4 lightDir, Float4 lightRadiance, Float4 moonDir,
                           Float4 celestial, Float4 sunUv, Float4 moonUv) {}

    private record CelestialUv(Float4 sun, Float4 moon) {}

    /** Provider-specific celestial/environment source encoded in the shared WorldPush lanes. */
    private SkyPush skyPush(RtSkyPreset preset, RtDimensionControls controls, double gameSeconds) {
        if (preset.skyProvider() == RtSkyPreset.SkyProvider.ENVIRONMENT) {
            RtSkyPreset.Environment environment = preset.environment();
            RtSkyPreset.Vec3 direction = environment.direction();
            RtSkyPreset.Vec3 up = environment.up();
            Float4 zeroUv = new Float4(0f, 0f, 0f, 0f);
            float diskScale = controls.diskScale();
            float diskPhase = wrappedRadians(gameSeconds * 0.5);
            float environmentAngle = wrappedRadians(gameSeconds
                    * Math.toRadians(controls.environmentRotationSpeed()));
            return new SkyPush(
                    new Float4(direction.x(), direction.y(), direction.z(), diskPhase),
                    new Float4(direction.x(), direction.y(), direction.z(), environment.lightHalfAngle()),
                    new Float4(diskScale, diskScale, diskScale, controls.diskOuterRadius()),
                    new Float4(-direction.x(), -direction.y(), -direction.z(), controls.diskThickness()),
                    new Float4(up.x(), up.y(), up.z(), environmentAngle),
                    zeroUv, zeroUv);
        }
        if (preset.skyProvider() == RtSkyPreset.SkyProvider.LOCAL_AMBIENT) {
            Float4 zeroUv = new Float4(0f, 0f, 0f, 0f);
            return new SkyPush(
                    new Float4(0f, 1f, 0f, 0f),
                    new Float4(0f, 1f, 0f, 0f),
                    new Float4(0f, 0f, 0f, 0f),
                    new Float4(0f, -1f, 0f, 0f),
                    new Float4(0f, 1f, 0f, 0f),
                    zeroUv, zeroUv);
        }
        return skyPush();
    }

    private static float wrappedRadians(double angle) {
        double wrapped = angle % (Math.PI * 2.0);
        if (wrapped < 0.0) wrapped += Math.PI * 2.0;
        return (float) wrapped;
    }

    /**
     * Derive the celestial light from Minecraft's time of day as typed values for {@link WorldPushData}.
     * Celestial angles come from the camera's {@link EnvironmentAttributeProbe} (partial-tick
     * interpolated). {@code fluorite.rt.sunNoonSouthDeg} tilts the east-west arc toward south (+Z) at
     * noon.
     */
    private SkyPush skyPush() {
        float sunX, sunY, sunZ, dayFactor, lx, ly, lz, rr, rg, rb, lightRadius;
        float moonX, moonY, moonZ, moonPhase, starAngle, starBrightness;
        Minecraft mc = Minecraft.getInstance();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        var probe = mc.gameRenderer.mainCamera().attributeProbe();
        float sunAngle = probe.getValue(EnvironmentAttributes.SUN_ANGLE, partial) * (float) (Math.PI / 180.0);
        float moonAngle = probe.getValue(EnvironmentAttributes.MOON_ANGLE, partial) * (float) (Math.PI / 180.0);
        float sunNoon = Mth.cos(sunAngle);
        sunX = -Mth.sin(sunAngle); sunY = sunNoonY() * sunNoon; sunZ = sunNoonZ() * sunNoon;
        float moonNoon = Mth.cos(moonAngle);
        moonX = -Mth.sin(moonAngle); moonY = sunNoonY() * moonNoon; moonZ = sunNoonZ() * moonNoon;
        moonPhase = probe.getValue(EnvironmentAttributes.MOON_PHASE, partial).index(); // 0 full .. 4 new
        // Stars: use Minecraft's actual celestial rotation + brightness (the same values vanilla's
        // SkyRenderer uses), so the starfield wheels about the celestial pole tied to world time and
        // fades in/out at dusk/dawn exactly like vanilla. STAR_ANGLE is in degrees -> radians.
        starAngle = probe.getValue(EnvironmentAttributes.STAR_ANGLE, partial) * (float) (Math.PI / 180.0);
        starBrightness = probe.getValue(EnvironmentAttributes.STAR_BRIGHTNESS, partial);
        dayFactor = smoothstep(-0.08f, 0.10f, sunY);
        // What leaves here is the light's UNDYED peak: the atmosphere it crosses is applied on the GPU,
        // from the transmittance table, by dyeCelestialLight at each stage's entry point. This used to be
        // an 8-step march ported into Java (atmosphereTransmittance, now deleted) purely so the sun's
        // disc, the sky gradient and the light on terrain could not disagree about a sunset. They still
        // cannot; there is now one implementation instead of two kept in step by hand.
        if (sunY > -0.05f) {
            // Sun stays the NEE light through the whole sunset. Its colour and fall-off ARE the
            // atmosphere's transmittance, so it whitens overhead and reddens into the horizon on exactly
            // the curve the visible sky follows. The old hand-tuned warmth ramp switched to the moon at
            // sunY == 0 while the sun was still at ~16% strength, which read as a hard light pop; the
            // transmittance is already near zero at the horizon and this short fade carries the remainder
            // to exactly zero before the moon takes over.
            float fade = smoothstep(-0.05f, 0.005f, sunY);
            float sunPeak = 21.0f;
            lx = sunX; ly = sunY; lz = sunZ;
            rr = sunPeak * fade;
            rg = sunPeak * fade;
            rb = sunPeak * fade;
            lightRadius = FluoriteConfig.Rt.Composite.SUN_ANGULAR_RADIUS.value();
        } else {
            // Moon: dim cool light, ramping up from zero at the sun→moon handoff (sunY = -0.05, where
            // the sun fade also reaches zero) so the switch is invisible. Scaled by the lit fraction so
            // a new moon gives near-zero moonlight. The warm-amber-when-low tint comes from the same
            // table on the GPU — lightDir is the moon's direction in this branch, so the dye follows.
            float moonStrength = smoothstep(0.04f, 0.22f, -sunY);
            float litFraction = 1.0f - Math.abs(moonPhase - 4.0f) / 4.0f; // 0 new .. 1 full
            float moonPeak = 0.20f * (0.15f + 0.85f * litFraction);
            lx = moonX; ly = moonY; lz = moonZ;
            rr = 0.30f * moonPeak * moonStrength;
            rg = 0.36f * moonPeak * moonStrength;
            rb = 0.55f * moonPeak * moonStrength;
            lightRadius = FluoriteConfig.Rt.Composite.MOON_ANGULAR_RADIUS.value();
        }
        // Art direction on the celestial light, applied to the peak before the GPU dyes it with the
        // atmosphere. Both defaults are the identity, so a fresh install is the physics and nothing else.
        // Here rather than in the shader because this is exactly the quantity the shader is handed: put it
        // downstream and it would have to be applied at all eight sites that read lightRadiance.
        float[] tint = FluoriteConfig.Rt.Sky.sunTint();
        float artScale = FluoriteConfig.Rt.Sky.SUN_INTENSITY.value();
        rr *= artScale * tint[0];
        rg *= artScale * tint[1];
        rb *= artScale * tint[2];
        CelestialUv uv = celestialUv(moonPhase);
        return new SkyPush(
                new Float4(sunX, sunY, sunZ, dayFactor),
                new Float4(lx, ly, lz, lightRadius),
                new Float4(rr, rg, rb, starBrightness),
                new Float4(moonX, moonY, moonZ, moonPhase),
                new Float4(0f, celestialAxisY(), celestialAxisZ(), starAngle),
                uv.sun(),
                uv.moon());
    }

    /**
     * Push the celestials-atlas UV rects (u0,v0,u1,v1) for the sun sprite and the current moon-phase
     * sprite, so world.rmiss can sample the real vanilla textures on the discs. Atlas-not-ready (early
     * boot / no resources) leaves full-range UVs and the shader's block-atlas fallback covers it.
     */
    private CelestialUv celestialUv(float moonPhaseIndex) {
        if (celestialUvAtlasHandle == 0L) {
            setCelestialUvAtlas(celestialsAtlasView());
        }
        int phase = Math.clamp((int) moonPhaseIndex, 0, MOON_IDS.length - 1);
        if (phase != celestialUvMoonPhase) {
            refreshCelestialUvCache(phase);
        }
        return new CelestialUv(
                new Float4(sunU0, sunV0, sunU1, sunV1),
                new Float4(moonU0, moonV0, moonU1, moonV1));
    }

    private void setCelestialUvAtlas(long atlasHandle) {
        if (celestialUvAtlasHandle == atlasHandle) {
            return;
        }
        celestialUvAtlasHandle = atlasHandle;
        celestialUvMoonPhase = -1;
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
    }

    private void refreshCelestialUvCache(int moonPhase) {
        sunU0 = 0f; sunV0 = 0f; sunU1 = 1f; sunV1 = 1f;
        moonU0 = 0f; moonV0 = 0f; moonU1 = 1f; moonV1 = 1f;
        try {
            if (celestialUvAtlasHandle != 0L) {
                TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.CELESTIALS);
                TextureAtlasSprite sun = atlas.getSprite(SUN_ID);
                sunU0 = sun.getU0(); sunV0 = sun.getV0(); sunU1 = sun.getU1(); sunV1 = sun.getV1();
                TextureAtlasSprite moon = atlas.getSprite(MOON_IDS[moonPhase]);
                moonU0 = moon.getU0(); moonV0 = moon.getV0(); moonU1 = moon.getU1(); moonV1 = moon.getV1();
            }
        } catch (Exception ignored) {
            // celestials atlas not yet loaded — keep full-range UVs (fallback texture is the block atlas)
        }
        celestialUvMoonPhase = moonPhase;
    }

    /** Hermite smoothstep matching GLSL semantics (0 below edge0, 1 above edge1). */
    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Math.clamp((x - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    public void destroy() {
        // Teardown runs after the device is idle, so the TLAS ring's slots are no longer in flight and
        // can be freed immediately. That wait is FluoriteLifecycle.shutdown's first act — it was named
        // here as something CLIENT_STOPPING already did for years before anyone checked, and it did not.
        tlasRing.destroy();
        if (gpuTimers != null) {
            gpuTimers.destroy(RtContext.get());
            gpuTimers = null;
        }
        if (RtDlssRr.enabled()) {
            RtDlssRr.INSTANCE.destroy();
        }
        destroyLensImages();
        if (lensPipeline != null) {
            lensPipeline.destroy();
            lensPipeline = null;
        }
        if (depthOfFieldPipeline != null) {
            depthOfFieldPipeline.destroy();
            depthOfFieldPipeline = null;
        }
        if (bloomFlarePipeline != null) {
            bloomFlarePipeline.destroy();
            bloomFlarePipeline = null;
        }
        if (displayImage != null) {
            displayImage.destroy();
            displayImage = null;
        }
        if (hdrDisplayImage != null) {
            hdrDisplayImage.destroy();
            hdrDisplayImage = null;
        }
        if (fgHudlessImage != null) {
            fgHudlessImage.destroy();
            fgHudlessImage = null;
        }
        if (fgHdrHudlessImage != null) {
            fgHdrHudlessImage.destroy();
            fgHdrHudlessImage = null;
        }
        RtWorldOverlay.INSTANCE.destroy(); // overlay features/pipelines/scratch live on the same device lifetime
        rainStreaks.destroy();
        if (output != null) {
            output.destroy();
            output = null;
        }
        if (continuationQueue != null) {
            continuationQueue.destroy();
            continuationQueue = null;
        }
        destroyGuideImages();
        exposure.destroy();
        if (displayPipeline != null) {
            displayPipeline.destroy();
            displayPipeline = null;
        }
        if (hdrCompositePipeline != null) {
            hdrCompositePipeline.destroy();
            hdrCompositePipeline = null;
        }
        if (hdrUiSampler != 0L) {
            RtContext hdrCtx = RtContext.currentOrNull();
            if (hdrCtx != null) {
                VK10.vkDestroySampler(hdrCtx.vk(), hdrUiSampler, null);
            }
            hdrUiSampler = 0L;
        }
        if (sdrPresentPipeline != null) {
            sdrPresentPipeline.destroy();
            sdrPresentPipeline = null;
        }
        if (sdrPresentImage != null) {
            sdrPresentImage.destroy();
            sdrPresentImage = null;
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[0];
        fgInterpW = -1;
        fgInterpH = -1;
        fgInterpFormat = Integer.MIN_VALUE;
        if (worldPipeline != null) {
            worldPipeline.destroy();
            worldPipeline = null;
        }
        if (environmentTextures != null) {
            environmentTextures.destroy();
            environmentTextures = null;
        }
        if (highCloudTextures != null) {
            highCloudTextures.destroy();
            highCloudTextures = null;
        }
        bindlessTextureCapacity = 0;
        materialBindingsReady = false;
        materialEpochTraceGate = false;
        RtMaterialRegistry.INSTANCE.destroy();
        if (pushRing != null) {
            for (PushSlot slot : pushRing) {
                if (slot != null) {
                    slot.buffer.destroy();
                    slot.waterProbe.destroy();
                }
            }
            pushRing = null;
        }
        if (atlasSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), atlasSampler, null);
            }
            atlasSampler = 0L;
        }
        if (tilingSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), tilingSampler, null);
            }
            tilingSampler = 0L;
        }
        if (environmentSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), environmentSampler, null);
            }
            environmentSampler = 0L;
        }
        if (environmentTransferSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), environmentTransferSampler, null);
            }
            environmentTransferSampler = 0L;
        }
        if (highCloudPatchSampler != 0L) {
            RtContext ctx = RtContext.currentOrNull();
            if (ctx != null) {
                VK10.vkDestroySampler(ctx.vk(), highCloudPatchSampler, null);
            }
            highCloudPatchSampler = 0L;
        }
    }

    /** Sprite-array sampling clamps to one selected layer; mip filtering removes distant shimmer. */
    private long highCloudPatchSampler(RtContext ctx) {
        if (highCloudPatchSampler == 0L) {
            highCloudPatchSampler = createHighCloudSampler(ctx, VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
                    10f, "high-cloud patch sampler");
        }
        return highCloudPatchSampler;
    }

    private long createHighCloudSampler(RtContext ctx, int addressMode, float maxLod, String label) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                    .addressModeU(addressMode).addressModeV(addressMode).addressModeW(addressMode)
                    .minLod(0f).maxLod(maxLod);
            LongBuffer p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkCreateSampler(" + label + ") failed");
            }
            long sampler = p.get(0);
            RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, sampler, label);
            return sampler;
        }
    }

    /** Equirectangular U wraps; polar V clamps. Linear mip filtering antialiases star reflections. */
    private long environmentSampler(RtContext ctx) {
        if (environmentSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0f).maxLod(12f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(environment) failed");
                }
                environmentSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, environmentSampler,
                        "environment sampler");
            }
        }
        return environmentSampler;
    }

    /** Nearest classification preserves escape/disk/capture topology in the packed Kerr transfer map. */
    private long environmentTransferSampler(RtContext ctx) {
        if (environmentTransferSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0f).maxLod(0f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(environment transfer) failed");
                }
                environmentTransferSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, environmentTransferSampler,
                        "environment transfer sampler");
            }
        }
        return environmentTransferSampler;
    }

    /**
     * Sampler for the atmosphere LUTs: LINEAR + CLAMP_TO_EDGE, which the block-atlas sampler is not.
     *
     * <p>Both differences matter and neither is a preference. NEAREST would draw the 256x64 table's texel
     * grid as banding across the sky — and the axis it bands along is the horizon gradient the table
     * exists to get right. REPEAT would wrap the zenith round to the ground: the parameterisation puts
     * the extremes of the sky at u = 0 and u = 1, so the one place wrapping is visible is the one place
     * the answer changes fastest.
     */
    private long lutSampler(RtContext ctx) {
        if (lutSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .minLod(0f).maxLod(0f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(atmosphere LUT) failed");
                }
                lutSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, lutSampler, "atmosphere LUT sampler");
            }
        }
        return lutSampler;
    }

    /**
     * A REPEAT sampler, for volumes read at world coordinates rather than at a parameterisation.
     *
     * <p>Separate from {@link #lutSampler} because the two requirements are opposites and neither can
     * serve the other. Every atmosphere table is a function of angles and altitudes mapped onto [0,1],
     * where wrapping would join the zenith to the horizon; the cloud noise is a tileable volume sampled
     * at {@code worldPosition / featureSize}, which leaves [0,1] within one feature and has to wrap.
     *
     * <p><b>This is what the noise bake's tiling exists for.</b> cloud_noise.comp.slang hashes its cells
     * on coordinates wrapped to each octave's own period specifically so the volume repeats seamlessly;
     * that work only pays off through an address mode that repeats. Bound with the clamping LUT sampler
     * instead, the whole visible sky read a single clamped corner of the volume, so coverage came out
     * near-constant and {@code shape + coverage - 1} never rose above zero — no clouds at all, at any
     * setting.
     */
    private long tilingSampler(RtContext ctx) {
        if (tilingSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_LINEAR).minFilter(VK10.VK_FILTER_LINEAR)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(0f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(tiling volume) failed");
                }
                tilingSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, tilingSampler, "tiling volume sampler");
            }
        }
        return tilingSampler;
    }

    private long atlasSampler(RtContext ctx) {
        if (atlasSampler == 0L) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                        .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                        .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR)
                        .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT)
                        .minLod(0f).maxLod(16f);
                LongBuffer p = stack.mallocLong(1);
                if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                    throw new IllegalStateException("vkCreateSampler(block atlas) failed");
                }
                atlasSampler = p.get(0);
                RtDebugLabels.name(ctx, VK10.VK_OBJECT_TYPE_SAMPLER, atlasSampler, "block atlas sampler");
            }
        }
        return atlasSampler;
    }

    private static long blockAlbedoAtlasView() {
        GpuTextureView view = Minecraft.getInstance().getTextureManager()
                .getTexture(TextureAtlas.LOCATION_BLOCKS).getTextureView();
        return vkImageView(view);
    }

    private static long vkImageView(GpuTextureView view) {
        if (view instanceof VulkanGpuTextureView vulkanView) {
            return vulkanView.vkImageView();
        }
        throw new IllegalStateException("cannot resolve VkImageView for " + view);
    }

    private static long vkImage(GpuTexture texture) {
        if (texture instanceof VulkanGpuTexture vulkanTexture) {
            return vulkanTexture.vkImage();
        }
        throw new IllegalStateException("cannot resolve VkImage for " + texture);
    }

    private static VkImageCopy.Buffer copyRegion(MemoryStack stack, int width, int height) {
        VkImageCopy.Buffer region = VkImageCopy.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).extent().set(width, height, 1);
        return region;
    }

    /** Whether the HDR present path (HDR image + combined UI -> PQ swapchain) should replace the vanilla SDR blit. */
    public boolean isHdrPresentActive() {
        return FluoriteConfig.Rt.Hdr.enabled()
                && hdrWrittenThisFrame
                && hdrDisplayImage != null;
    }

    /**
     * DLSS-FG: the PQ-encoded HDR backbuffer (view/image), valid only right after {@link #presentHdr} has run
     * this frame (it's the same image {@code presentHdr} just composited UI into and blitted to the
     * swapchain) — used as the interpolation source for HDR frame generation instead of the SDR main target.
     * Already display-ready PQ, so it's fed to DLSSG directly with no extra encode step. 0 if HDR isn't
     * active this frame.
     */
    public long hdrBackbufferView() {
        return hdrDisplayImage != null ? hdrDisplayImage.view : 0L;
    }

    public long hdrBackbufferImage() {
        return hdrDisplayImage != null ? hdrDisplayImage.image : 0L;
    }

    /**
     * Blit this frame's PQ-encoded HDR image straight into the swapchain image, replacing Minecraft's SDR
     * blit. Replicates {@code VulkanGpuSurface.blitFromTexture}'s barrier + acquire-wait/present-signal
     * sequence with the HDR {@link RtImage} as the (GENERAL-layout) source; an added memory barrier makes the
     * display-compute writes visible to the blit read. The SDR main target is bypassed; the combined UI image
     * is blended over the HDR image here at paper white before the swapchain blit. The magic stage/access
     * values mirror vanilla {@code blitFromTexture} exactly. Y is flipped to match the vanilla swapchain blit.
     */
    public void presentHdr(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH, long acquireSem, long presentSem) {
        RtImage src = hdrDisplayImage;
        int copyW = Math.min(swapW, src.width);
        int copyH = Math.min(swapH, src.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // DLSS-FG "hudless" capture: hdrDisplayImage right now holds the RT world before the combined
            // UI overlay is blended in. Snapshot it before that composite overwrites it in place, mirroring
            // captureFgHudless's SDR pattern (pre-UI copy) but reusing this frame's already-open command
            // buffer.
            if (RtDlssFg.enabled()) {
                captureFgHdrHudless(cmd, stack, src);
            }

            // Step C.2: composite the combined UI overlay over the HDR world image (in place) at paper white,
            // before the swapchain blit. The overlay is an MC render target kept in GENERAL layout, sampled by
            // the compute pass. A memory barrier first makes the overlay writes + the world HDR writes visible
            // to the compute; the dep1 barrier below (ALL writes -> transfer read) then covers the compute's
            // HDR write for the blit.
            long overlayView = RtUiOverlay.populatedThisFrame() ? RtUiOverlay.overlayColorView() : 0L;
            if (overlayView != 0L) {
                ensureHdrUiResources();
                if (hdrCompositePipeline != null) {
                    VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
                    pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
                    VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
                    KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);
                    hdrCompositePipeline.setImages(hdrDisplayImage.view, overlayView, hdrUiSampler);
                    hdrCompositePipeline.dispatch(cmd, src.width, src.height, FluoriteConfig.Rt.Hdr.paperWhiteNits());
                }
                RtUiOverlay.markConsumed();
            }
            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the HDR compute writes visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit HDR (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(hdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
    }

    /** Lazily create the HDR UI-composite compute pipeline + its nearest/clamp sampler (first HDR present). */
    private void ensureHdrUiResources() {
        if (hdrCompositePipeline != null) {
            return;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return;
        }
        hdrCompositePipeline = RtHdrCompositePipeline.create(ctx);
    }

    /** Ensure the shared nearest/clamp sampler used to sample SDR/overlay targets in the present compute. */
    private boolean ensureUiSampler(RtContext ctx) {
        if (hdrUiSampler != 0L) {
            return true;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo sci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK10.VK_FILTER_NEAREST).minFilter(VK10.VK_FILTER_NEAREST)
                    .mipmapMode(VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
            var p = stack.mallocLong(1);
            if (VK10.vkCreateSampler(ctx.vk(), sci, null, p) != VK10.VK_SUCCESS) {
                return false;
            }
            hdrUiSampler = p.get(0);
        }
        return true;
    }

    /**
     * Whether a non-RT frame (menu, title panorama, loading screen) should be SDR-&gt;PQ converted for
     * present instead of vanilla's raw SDR blit. True when the PQ swapchain is active but this frame did
     * not produce an HDR image ({@link #isHdrPresentActive()} false).
     */
    public boolean isPqSdrPresentActive() {
        return FluoriteConfig.Rt.Hdr.enabled()
                && !isHdrPresentActive();
    }

    /**
     * Present a non-RT (menu/loading) frame to the PQ swapchain: convert the SDR main target (sRGB-encoded
     * rgba8, GENERAL layout, already holding the composited panorama + UI) to PQ-encoded at paper white via
     * a compute pass into {@link #sdrPresentImage}, then blit that into the swapchain. Mirrors
     * {@link #presentHdr} barrier-for-barrier; returns false (keep vanilla SDR blit) if resources are
     * unavailable.
     */
    public boolean presentSdrToPq(VulkanCommandEncoder enc, long swapchainImage, int swapW, int swapH,
            long sdrMainView, long acquireSem, long presentSem) {
        if (sdrMainView == 0L || failed) {
            return false;
        }
        RtContext ctx = RtContext.get();
        if (ctx == null || !ensureUiSampler(ctx)) {
            return false;
        }
        if (sdrPresentPipeline == null) {
            sdrPresentPipeline = RtSdrPresentPipeline.create(ctx);
        }
        if (sdrPresentImage == null || sdrPresentImage.width != swapW || sdrPresentImage.height != swapH) {
            if (sdrPresentImage != null) {
                ctx.waitIdle(); // see ensureOutput: a resize must not free what an in-flight frame reads
                sdrPresentImage.destroy();
            }
            sdrPresentImage = ctx.createStorageImage(swapW, swapH, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "RT SDR->PQ present image " + swapW + "x" + swapH);
        }
        RtImage dst = sdrPresentImage;
        int copyW = Math.min(swapW, dst.width);
        int copyH = Math.min(swapH, dst.height);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();

            // Make the prior GUI/overlay writes to the SDR main target visible to the compute sample.
            VkMemoryBarrier2.Buffer pre = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            pre.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(2048L).dstAccessMask(98304L);
            VkDependencyInfo preDep = VkDependencyInfo.calloc(stack).sType$Default().pMemoryBarriers(pre);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, preDep);

            sdrPresentPipeline.setImages(dst.view, sdrMainView, hdrUiSampler);
            sdrPresentPipeline.dispatch(cmd, dst.width, dst.height, FluoriteConfig.Rt.Hdr.paperWhiteNits());

            // Swapchain UNDEFINED -> TRANSFER_DST, plus make the compute write visible to the blit read.
            VkImageMemoryBarrier2.Buffer toDst = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toDst.get(0).srcStageMask(0L).srcAccessMask(0L).dstStageMask(4096L).dstAccessMask(4096L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_UNDEFINED).newLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toDst.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer srcVis = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            srcVis.get(0).srcStageMask(65536L).srcAccessMask(65536L).dstStageMask(4096L).dstAccessMask(2048L);
            VkDependencyInfo dep1 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toDst).pMemoryBarriers(srcVis);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep1);

            // Blit converted PQ image (GENERAL) -> swapchain (TRANSFER_DST), Y-flipped like vanilla.
            VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
            region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.get(0).srcOffsets(1).set(copyW, copyH, 1); // srcOffsets[0] = (0,0,0) from calloc
            region.get(0).dstOffsets(0).set(0, copyH, 0);
            region.get(0).dstOffsets(1).set(copyW, 0, 1);
            VK10.vkCmdBlitImage(cmd, dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, swapchainImage,
                    VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region, VK10.VK_FILTER_NEAREST);

            // Swapchain TRANSFER_DST -> PRESENT_SRC_KHR (1000001002).
            VkImageMemoryBarrier2.Buffer toPresent = VkImageMemoryBarrier2.calloc(1, stack).sType$Default();
            toPresent.get(0).srcStageMask(4096L).srcAccessMask(4096L).dstStageMask(65536L).dstAccessMask(0L)
                    .oldLayout(VK10.VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL).newLayout(1000001002)
                    .srcQueueFamilyIndex(-1).dstQueueFamilyIndex(-1).image(swapchainImage);
            toPresent.get(0).subresourceRange().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VkMemoryBarrier2.Buffer mem2 = VkMemoryBarrier2.calloc(1, stack).sType$Default();
            mem2.get(0).srcStageMask(4096L).srcAccessMask(2048L).dstStageMask(65536L).dstAccessMask(98304L);
            VkDependencyInfo dep2 = VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(toPresent).pMemoryBarriers(mem2);
            KHRSynchronization2.vkCmdPipelineBarrier2KHR(cmd, dep2);

            if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
                throw new IllegalStateException("vkEndCommandBuffer(sdr present) failed");
            }
            enc.waitSemaphore(acquireSem, 0L, 65536L);
            enc.execute(cmd);
            enc.signalSemaphore(presentSem, 0L, 4096L);
        }
        return true;
    }

    /**
     * Linear-filtered blit of the full render-res image into the full display-res image. Used as the
     * non-RR / fallback upscale so display mapping always sees a display-res RT image; a no-op stretch when
     * the two are the same size (RR disabled -> render == display).
     */
    private static void blitUpscale(VkCommandBuffer cmd, MemoryStack stack, RtImage src, RtImage dst) {
        VkImageBlit.Buffer region = VkImageBlit.calloc(1, stack);
        region.get(0).srcSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).dstSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
        region.get(0).srcOffsets(1).set(src.width, src.height, 1); // srcOffsets[0] zeroed by calloc
        region.get(0).dstOffsets(1).set(dst.width, dst.height, 1);
        VK10.vkCmdBlitImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                dst.image, VK10.VK_IMAGE_LAYOUT_GENERAL, region, VK10.VK_FILTER_LINEAR);
    }

    /**
     * DLSS Frame Generation quality: capture a copy of {@code main} (the main render target) into
     * {@link #fgHudlessImage} for {@link #fgInterpolate} to feed DLSSG as the "hudless" resource. Call from
     * {@code GameRendererMixin} right after {@code GuiRenderer.render()} but BEFORE
     * {@link RtUiOverlay#compositeIfUsed()} — at that point, when the UI overlay redirect is active, {@code
     * main} still has no combined UI baked in (world overlays, hand/screen effects and GUI went to the
     * overlay target instead). No-op (and {@link #fgInterpolate} passes 0/0/0 for hudless, same as always)
     * unless both FG and the UI overlay redirect are active — capturing this without the redirect would just
     * copy the ALREADY-composited backbuffer, which is useless as a distinct hudless input.
     */
    public void captureFgHudless(RenderTarget main) {
        if (!RtDlssFg.enabled() || !RtUiOverlay.enabled() || main == null || main.getColorTexture() == null) {
            return;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        long srcImage;
        try {
            srcImage = vkImage(main.getColorTexture());
        } catch (IllegalStateException e) {
            return; // not a Vulkan-backed texture (shouldn't happen on this backend)
        }
        if (fgHudlessImage == null || fgHudlessImage.width != main.width || fgHudlessImage.height != main.height) {
            if (fgHudlessImage != null) {
                ctx.waitIdle(); // see ensureOutput: a resize must not free what an in-flight frame reads
                fgHudlessImage.destroy();
            }
            fgHudlessImage = ctx.createStorageImage(main.width, main.height, VK10.VK_FORMAT_R8G8B8A8_UNORM,
                    "FG hudless capture " + main.width + "x" + main.height);
        }
        var encoder = (VulkanCommandEncoder) ((CommandEncoderAccessor) RenderSystem.getDevice().createCommandEncoder()).fluorite$getBackend();
        VkCommandBuffer cmd = encoder.allocateAndBeginTransientCommandBuffer();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Make writes into `main` visible to the copy (the combined UI has not touched `main` yet this
            // frame — it went to the UI overlay target instead).
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
            VK10.vkCmdCopyImage(cmd, srcImage, VK10.VK_IMAGE_LAYOUT_GENERAL,
                    fgHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, main.width, main.height));
            VulkanCommandEncoder.memoryBarrier(cmd, stack);
        }
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg hudless capture) failed");
        }
        encoder.execute(cmd);
    }

    /**
     * HDR counterpart of {@link #captureFgHudless} — copies {@code src} (this frame's {@code hdrDisplayImage},
     * before the combined UI overlay is blended in) into {@link #fgHdrHudlessImage} for {@link
     * #fgInterpolate}'s HDR path to feed DLSSG as the "hudless" resource. A plain copy, not a format
     * conversion: both images are
     * already PQ-encoded (the display-ready EOTF-encoded [0,1] signal DLSS-FG's programming guide requires),
     * so no encode step is needed. Called from {@link #presentHdr} using its already-open {@code cmd}/
     * {@code stack}, right before that method's own combined-UI composite dispatch overwrites
     * {@code hdrDisplayImage} in place — same "capture before the UI gets baked back in" timing as the SDR
     * version, just within a single method instead of split across a mixin hook.
     */
    private void captureFgHdrHudless(VkCommandBuffer cmd, MemoryStack stack, RtImage src) {
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return;
        }
        if (fgHdrHudlessImage == null || fgHdrHudlessImage.width != src.width || fgHdrHudlessImage.height != src.height) {
            if (fgHdrHudlessImage != null) {
                ctx.waitIdle(); // see ensureOutput: a resize must not free what an in-flight frame reads
                fgHdrHudlessImage.destroy();
            }
            fgHdrHudlessImage = ctx.createStorageImage(src.width, src.height, VK10.VK_FORMAT_R16G16B16A16_SFLOAT,
                    "FG HDR hudless capture (PQ) " + src.width + "x" + src.height);
        }
        // Make composite()'s writes to hdrDisplayImage (an earlier submit this frame) visible to this copy;
        // the copy's write is then made visible to the UI-composite dispatch that follows (and to DLSSG's
        // read, in a later command buffer) by the same idiom.
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
        VK10.vkCmdCopyImage(cmd, src.image, VK10.VK_IMAGE_LAYOUT_GENERAL,
                fgHdrHudlessImage.image, VK10.VK_IMAGE_LAYOUT_GENERAL, copyRegion(stack, src.width, src.height));
        VulkanCommandEncoder.memoryBarrier(cmd, stack);
    }

    /**
     * DLSS Frame Generation: record the DLSSG evaluate for generated frame {@code index} of {@code count}
     * (backbuffer = the final frame; HW depth = {@code gDepth}; motion = {@code gMotion}) into Minecraft's
     * command encoder, returning the interpolated output image (backbuffer size) for {@link RtFramePresenter}
     * to blit into a generated swapchain image. On {@code index == 1} it ensures the feature (created in its
     * own synchronous submit), the per-index output images, and the jitter-free reprojection matrices.
     * Returns {@code null} (caller falls back to duplicating the real frame for this one frame, no session
     * impact) when there's simply no captured RT frame to interpolate from right now — routine and expected
     * on menu/loading/transition frames, since {@link RtFramePresenter#isActive} only gates on being in a
     * world, not on RT having actually produced a frame this tick. Throws instead for failures that should
     * never happen once RT is actively producing frames (DLSSG feature creation failing, an out-of-range
     * index, the evaluate itself failing) — the caller treats those as fatal and disables FG for the
     * session, same as any other FG present-record failure, rather than silently degrading to duplicated
     * (non-interpolated) frames forever with no visible sign anything is wrong. Rotation-only matrices;
     * camera translation is carried by the mvecs (cameraMotionIncluded).
     *
     * <p>{@code hdrBackbuffer} selects the HDR path. Per the DLSS-FG programming guide's HDR section, scRGB is
     * explicitly unsupported as a DLSS-FG input ("not suitable as inputs to DLSS-FG" — it wants a
     * display-ready, EOTF-encoded [0,1] signal, recommending HDR10/ST.2084) — since the renderer's whole HDR
     * pipeline is natively PQ-encoded, every image fed to {@code RtDlssFg.evaluate} in HDR mode is already in
     * that format with no extra conversion needed: the backbuffer is the raw {@code backbufferView}/
     * {@code backbufferImage} the caller passed in ({@link #hdrBackbufferView()}, already PQ + UI-composited
     * by {@link #presentHdr}); the hudless resource is {@link #fgHdrHudlessImage} (copied by {@link
     * #presentHdr} <em>before</em> its own UI composite ran, mirroring {@link #captureFgHudless}'s pre-UI
     * timing); and DLSSG's own (also PQ-encoded) output is returned as-is, since the swapchain itself is
     * PQ-native and can blit it directly. The UI resource itself needs no HDR-specific handling — it's the
     * same combined {@link RtUiOverlay} texture used by both present paths (only the *compositing* math that
     * consumes it differs, done separately by {@code presentHdr}/{@code RtUiOverlay}, not here).
     */
    public RtImage fgInterpolate(VulkanCommandEncoder enc, long backbufferView, long backbufferImage,
            int swapW, int swapH, int index, int count, boolean hdrBackbuffer) {
        if (failed || gDepth == null || gMotion == null || !frameCaptured) {
            return null;
        }
        RtContext ctx = RtContext.currentOrNull();
        if (ctx == null) {
            return null;
        }
        final int fmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        if (index == 1) {
            if (!ensureFgFeature(ctx, swapW, swapH, renderW, renderH, fmt)) {
                throw new IllegalStateException("DLSSG feature not ready (ensureFgFeature failed)");
            }
            ensureFgInterp(ctx, count, swapW, swapH, fmt);
            // clipToPrevClip = prevVP * inverse(curVP); prevClipToClip = curVP * inverse(prevVP). Both from
            // the (rotation-only, camera-relative) MV view-projections, so jitter-free.
            fgMatTmp.set(mvCurProjView).invert();
            fgClipToPrev.set(mvPrevProjView).mul(fgMatTmp);
            fgMatTmp.set(mvPrevProjView).invert();
            fgPrevToClip.set(mvCurProjView).mul(fgMatTmp);
        }
        if (index < 1 || index > fgInterp.length || fgInterp[index - 1] == null) {
            throw new IllegalStateException(
                    "fgInterpolate index " + index + " out of range for fgInterp[" + fgInterp.length + "]");
        }
        RtImage out = fgInterp[index - 1];
        // Only feed hudless/ui when they exist AND match this frame's backbuffer size — a stale or mismatched
        // size (e.g. mid-resize) is worse than skipping, so fall back to 0/0/0 (DLSSG just does without).
        RtImage hudlessSrc = hdrBackbuffer ? fgHdrHudlessImage : fgHudlessImage;
        boolean hudlessReady = hudlessSrc != null && hudlessSrc.width == swapW && hudlessSrc.height == swapH;
        long hudlessView = hudlessReady ? hudlessSrc.view : 0L;
        long hudlessImg = hudlessReady ? hudlessSrc.image : 0L;
        int hudlessFmt = hdrBackbuffer ? VK10.VK_FORMAT_R16G16B16A16_SFLOAT : VK10.VK_FORMAT_R8G8B8A8_UNORM;
        boolean uiReady = RtUiOverlay.overlayWidth() == swapW && RtUiOverlay.overlayHeight() == swapH
                && RtUiOverlay.overlayColorView() != 0L && RtUiOverlay.overlayColorImage() != 0L;
        long uiView = uiReady ? RtUiOverlay.overlayColorView() : 0L;
        long uiImg = uiReady ? RtUiOverlay.overlayColorImage() : 0L;

        VkCommandBuffer cmd = enc.allocateAndBeginTransientCommandBuffer();
        boolean ok = RtDlssFg.INSTANCE.evaluate(cmd.address(),
                backbufferView, backbufferImage, fmt,
                gDepth.view, gDepth.image, VK10.VK_FORMAT_R32_SFLOAT,
                gMotion.view, gMotion.image, VK10.VK_FORMAT_R16G16_SFLOAT,
                hudlessView, hudlessImg, hudlessReady ? hudlessFmt : 0,
                uiView, uiImg, uiReady ? VK10.VK_FORMAT_R8G8B8A8_UNORM : 0,
                out.view, out.image, fmt,
                swapW, swapH, renderW, renderH, count, index, 1.0f, 1.0f,
                true /* depthInverted (reversed-Z) */, hdrBackbuffer /* colorBuffersHDR */,
                true /* cameraMotionIncluded (in mvecs) */, fgReset,
                fgClipToPrev, fgPrevToClip);
        if (VK10.vkEndCommandBuffer(cmd) != VK10.VK_SUCCESS) {
            throw new IllegalStateException("vkEndCommandBuffer(fg interpolate) failed");
        }
        fgReset = false;
        if (!ok) {
            throw new IllegalStateException("ngxshim_evaluate_dlssg failed (RtDlssFg.evaluate returned false)");
        }
        enc.execute(cmd);
        return out;
    }

    private boolean ensureFgFeature(RtContext ctx, int w, int h, int rw, int rh, int fmt) {
        if (RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt)) {
            return true;
        }
        // Create the feature in its own submit + wait (not folded into MC's frame submit).
        ctx.submitSync(c -> RtDlssFg.INSTANCE.ensureFeature(c.address(), w, h, rw, rh, fmt));
        fgReset = true; // fresh feature has no temporal history
        return RtDlssFg.INSTANCE.featureReadyFor(w, h, rw, rh, fmt);
    }

    private void ensureFgInterp(RtContext ctx, int count, int w, int h, int fmt) {
        if (fgInterp.length == count && fgInterpW == w && fgInterpH == h && fgInterpFormat == fmt
                && (count == 0 || fgInterp[0] != null)) {
            return;
        }
        boolean freeing = false;
        for (RtImage img : fgInterp) {
            freeing |= img != null;
        }
        if (freeing) {
            ctx.waitIdle(); // see ensureOutput: a resize must not free what an in-flight frame reads
        }
        for (RtImage img : fgInterp) {
            if (img != null) {
                img.destroy();
            }
        }
        fgInterp = new RtImage[count];
        for (int i = 0; i < count; i++) {
            fgInterp[i] = ctx.createStorageImage(w, h, fmt, "FG interp " + i + " " + w + "x" + h);
        }
        fgInterpW = w;
        fgInterpH = h;
        fgInterpFormat = fmt;
    }
}
