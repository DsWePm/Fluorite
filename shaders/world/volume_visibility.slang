// The bound volumetric visibility grid and the one correct way to read it.
//
// Separate from the bake for the same reason atmosphere_lut is separate from atmosphere: the bake's
// descriptor set has exactly one storage image, a binding declared in a module reaches every importer,
// and giving the bake a set-0 binding its own layout does not contain is a validation error rather than
// a subtle one. The grid's DIMENSIONS are shared (they are the texture's), the BINDING is not.

import world_common; // WorldPush's shape
import world_core;   // the frame's WorldPush itself, which carries the grid's placement

// Must match volume_visibility.comp.slang and RtSky.VIS_GRID_W/H/D.
public static const int VIS_GRID_W = 64;
public static const int VIS_GRID_H = 32;
public static const int VIS_GRID_D = 64;

/**
 * Bound by RtPipeline.setVolumeVisibilityGrid. R is sun visibility, G is sky openness.
 *
 * <p>Sampled rather than storage, and that is the whole design: nearest fetches would draw the grid's own
 * cell boundaries into the fog as brightness steps, and a step where a gradient belongs is the failure
 * this line of work has hit repeatedly. Trilinear is also what makes a single binary ray per cell add up
 * to something continuous — the field it produces varies smoothly even though every sample in it is 0 or 1.
 */
[[vk::binding(16, 0)]] public Sampler3D volumeVisibilityGrid;

/**
 * How wide the handover to the unshadowed model is at the grid's faces, in normalized units.
 *
 * The grid stops at its own boundary and beyond it there is no answer, so something has to happen there.
 * Cutting hard would put a visible shell around the camera at whatever radius the grid reaches — the fog
 * shadowed on one side of a plane and lit on the other, with the plane moving with the player. Fading to
 * "unshadowed" over the outermost cells replaces that edge with a gradient, and it fades toward the value
 * the renderer used everywhere before this grid existed, so the far field is unchanged rather than merely
 * different.
 */
static const float VIS_EDGE_FADE = 0.06;

/**
 * Sun visibility and sky openness at a point in the air. (1, 1) — fully lit — outside the grid, and
 * wherever no grid has been published.
 *
 * <p>Returning 1 rather than 0 outside is deliberate and is the safe direction: the renderer's behaviour
 * before this grid existed was "everything is lit", so a point the grid cannot answer for keeps exactly
 * the appearance it always had. The opposite default would black out the distance the moment a camera
 * moved, and it would do so silently.
 */
public float2 sampleVolumeVisibility(float3 p) {
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return float2(1.0, 1.0);
    }
    float3 extent = cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    // Cell (i,j,k) holds the answer for origin + (i+0.5)*cell, whose normalized coordinate is exactly
    // (i+0.5)/W. So this division IS the texture coordinate — no half-texel correction, because the bake
    // writes centres. If that convention ever changes, both sides move together or the field shifts.
    float3 uvw = (p - worldPush.visGridOrigin.xyz) / extent;
    if (any(uvw < float3(0.0, 0.0, 0.0)) || any(uvw > float3(1.0, 1.0, 1.0))) {
        return float2(1.0, 1.0);
    }
    float2 v = volumeVisibilityGrid.SampleLevel(uvw, 0.0).rg;
    float3 toFace = min(uvw, float3(1.0, 1.0, 1.0) - uvw);
    float edge = saturate(min(min(toFace.x, toFace.y), toFace.z) / VIS_EDGE_FADE);
    return lerp(float2(1.0, 1.0), v, edge);
}
