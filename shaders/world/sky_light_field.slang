// M27: Minecraft's own sky light, read as a toroidal field.
//
// WHY THIS EXISTS. The ray-traced visibility grid reaches about 32 blocks. Past it the marched fog had
// no data at all: it was handed a fully open sky, which lit distant cave fog (#41), and clamping to the
// grid's boundary cell instead was tried and rejected in game because 100 blocks away that cell is a
// measurement of somewhere else -- it darkened distant fog OUTDOORS too.
//
// WHY IT COSTS NO RAYS. Growing the visibility grid to this reach would cast its one ray per cell over
// 56 million cells instead of 131 thousand, roughly 11 ms a frame extrapolated from the 0.042 ms that
// grid measures today. Minecraft has already run this flood fill and keeps it current. Nothing here
// computes sky light; the CPU side moves it and this reads it.
//
// WHAT IT IS NOT. Sky light is a FLOOD FILL, not a hemisphere integral: it falls one step per block from
// an opening, so a cave ten blocks in from a lit mouth reads 5 where the fraction of sky actually
// reaching it is nearer zero, and a chamber with a vertical shaft to the surface reads 0 where a
// hemisphere integral would find sky. It answers a different question from the visibility grid, and
// that is why it is used where the grid has nothing to say rather than in place of it.
//
// ITS OWN BINDING, and this module must therefore be imported only by shaders whose set has it. A
// binding declared in a module reaches every importer; the froxel compute set does not contain binding
// 30 and importing this there is a validation error rather than a subtle one. Same rule, same reason,
// as the note at the top of volume_visibility.slang.

import world_common; // WorldPush's shape
import world_core;   // the frame's WorldPush itself, which carries the field's placement

/** Must match RtSkyLightField.DIM. Blocks per axis, and the modulus the horizontal axes wrap on. */
public static const float SKY_LIGHT_DIM = 384.0;

/**
 * Bound by RtPipeline.setSkyLightField, with U and W repeating and V clamped.
 *
 * <p>The address modes are the addressing scheme rather than a taste: horizontally the field is a torus,
 * so a texel holds whichever world column currently in the window is congruent to it, and REPEAT is what
 * turns a world coordinate into that texel. Vertically it is not -- the field spans the entire world
 * height, so past the build limit the honest answer is the top plane's, which is what CLAMP gives.
 */
[[vk::binding(30, 0)]] public Sampler3D skyLightGrid;

/**
 * Sky exposure at a point, as Minecraft computed it, or 1 where there is nothing to read.
 *
 * <p>The argument is in the shader's usual rebased space; the offsets that turn it back into a world
 * coordinate ride in WorldPush.skyLightField, already reduced so that adding them cannot lose precision.
 *
 * <p>ONE FILTERED FETCH, which is the whole reason the data lives in an image rather than a buffer. The
 * fog march asks this per step; a manually filtered buffer read would be eight dependent loads in the
 * place M26 spent a milestone removing three.
 */
public float skyLightAt(float3 p) {
    if (worldPush.skyLightField.w <= 0.0) {
        return 1.0;
    }
    float3 uvw;
    uvw.x = (p.x + worldPush.skyLightField.x) * (1.0 / SKY_LIGHT_DIM);
    uvw.y = (p.y + worldPush.skyLightField.y) * (1.0 / SKY_LIGHT_DIM);
    uvw.z = (p.z + worldPush.skyLightField.z) * (1.0 / SKY_LIGHT_DIM);
    return saturate(skyLightGrid.SampleLevel(uvw, 0.0).r);
}
