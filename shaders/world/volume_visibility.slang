// The bound volumetric visibility grid and the one correct way to read it. R/G are sun visibility and
// sky openness; BA are the visibility-weighted first moment of the sampled celestial square coordinates.
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

public struct VolumeVisibilitySample {
    public float sun;
    public float sky;
    public float2 lightUv;
};

/**
 * Sun visibility and sky openness at a point in the air. Outside the grid, the boundary's answer; only
 * where no grid has been published at all does this fall back to (1, 1).
 *
 * <p>OUTSIDE THE GRID IT CLAMPS, and that replaces two decisions this file used to record — a reset to
 * "fully lit" beyond the faces, and a fade toward it over the outermost 6% to hide the seam that reset
 * created. Neither is being ignored; both were compensating for the same thing, and clamping removes it.
 *
 * <ul>
 *   <li>The reset's stated reason was that 1 is the safe direction, because the renderer's behaviour
 *       before this grid existed was "everything is lit", and the opposite default would silently black
 *       out the distance. Clamping is <b>not</b> that opposite default. It is "the nearest answer we
 *       actually measured", which outdoors IS 1 — so the case that argument was protecting keeps exactly
 *       the appearance it had.</li>
 *   <li>The fade's stated reason was that cutting hard would put a visible shell around the camera, fog
 *       shadowed on one side of a plane and lit on the other, with the plane following the player. That
 *       discontinuity was <b>manufactured by the reset</b>: the grid's value at its face and 1 are two
 *       different numbers. Clamping is continuous across the boundary by construction, so there is no
 *       longer a seam for a gradient to hide, and the fade had itself become a defect — it brightened
 *       the answer as a sample approached the faces, which underground is the wrong way.</li>
 * </ul>
 *
 * <p>WHAT THIS FIXES. The grid spans 64x32x64 cells, so at the default one-block cell it reaches about
 * 32 blocks horizontally and 16 vertically. In a cave, every fog and underwater sample past that was
 * being told the sky was wide open, which is why distant cave fog glowed while nearby fog was correct.
 * Clamping asks the boundary instead, and in a cave the boundary is roofed.
 *
 * <p>WHAT IT COSTS, named rather than discovered later: standing inside a cave mouth whose opening is
 * further than the grid reaches, the boundary in that direction is roofed, so genuinely open sky beyond
 * it is reported closed and distant outdoor fog is darkened. That is an error in the opposite direction
 * from the one being removed, and a far smaller one.
 *
 * <p>The no-grid fallback below is the one case this cannot help, because there is nothing to clamp to.
 * It is reached when volumetric fog is switched off, which also stops the bake — and the water medium
 * reads this same function, so switching fog off currently lights cave water. That coupling is a
 * separate fix; see issue #41.
 */
public VolumeVisibilitySample sampleVolumeLightVisibility(float3 p) {
    VolumeVisibilitySample result;
    result.sun = 1.0;
    result.sky = 1.0;
    result.lightUv = float2(0.5, 0.5);
    // D78A: some authored dimensions deliberately use a tiny unoccluded environment floor. It is an
    // explicit non-physical readability light, so roofs must not gate it. Directional/local emitters
    // still cast their own shadow rays; only the preset's isotropic floor takes this fast path.
    if ((worldPush.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u) {
        return result;
    }
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return result;
    }
    float3 extent = cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    // Cell (i,j,k) holds the answer for origin + (i+0.5)*cell, whose normalized coordinate is exactly
    // (i+0.5)/W. So this division IS the texture coordinate — no half-texel correction, because the bake
    // writes centres. If that convention ever changes, both sides move together or the field shifts.
    // Clamped HERE rather than left to the sampler. The descriptor happens to carry CLAMP_TO_EDGE today,
    // so the fetch alone would do the right thing -- but that address mode is chosen in RtComposite, and
    // a correctness property of this function must not depend on a decision made in another file.
    float3 uvw = saturate((p - worldPush.visGridOrigin.xyz) / extent);
    float4 v = volumeVisibilityGrid.SampleLevel(uvw, 0.0);
    result.sun = v.r;
    result.sky = v.g;
    result.lightUv = result.sun > 1.0e-5
            ? clamp(v.ba / result.sun, float2(0.0, 0.0), float2(1.0, 1.0))
            : float2(0.5, 0.5);
    return result;
}

/** Compatibility view for callers that need only the two visibility scalars. */
public float2 sampleVolumeVisibility(float3 p) {
    VolumeVisibilitySample result = sampleVolumeLightVisibility(p);
    return float2(result.sun, result.sky);
}
