// The bound volumetric visibility grid and the one correct way to read it. R is sky openness -- the
// accumulated fraction of the upper hemisphere that reaches this cell -- and G is the sample count its
// running mean stands on, which only the bake reads. B and A are constants.
//
// IT USED TO ANSWER ABOUT THE SUN TOO, in R, with the sampled celestial square coordinates in BA. That
// half lost in game to the one stochastic shadow ray a marched segment already casts, and it is gone;
// volume_visibility.comp.slang holds the reasoning. Sun visibility is a high-frequency quantity and a
// trilinear grid cannot carry an edge.
//
// Separate from the bake for the same reason atmosphere_lut is separate from atmosphere: the bake's
// descriptor set has exactly one storage image, a binding declared in a module reaches every importer,
// and giving the bake a set-0 binding its own layout does not contain is a validation error rather than
// a subtle one. The grid's DIMENSIONS are shared (they are the texture's), the BINDING is not.

import world_common; // WorldPush's shape
import world_core;   // the frame's WorldPush itself, which carries the grid's placement
// M27's field, for the one region this grid has no cells in: above its box. Safe to import HERE and
// nowhere lower, because it declares binding 30 and a binding declared in a module reaches every
// importer -- the note at the top of sky_light_field.slang and the one above are the same rule. This
// module's only importers are volume.slang and world.rgen.slang, and both already import it, so the
// set of shaders carrying binding 30 does not change.
import sky_light_field;

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
 * Sky openness at a point in the air: the fraction of the upper hemisphere that reaches it. Outside the
 * grid, the boundary's answer; only where no grid has been published at all does this fall back to 1.
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
 * <p>WHAT IT COSTS, named rather than discovered later. Two things, both bounded:
 *
 * <ul>
 *   <li>Standing inside a cave mouth whose opening is further than the grid reaches, the boundary in
 *       that direction is roofed, so genuinely open sky beyond it is reported closed and distant outdoor
 *       fog darkens. Opposite direction to the error being removed, and far smaller.</li>
 *   <li>Above the grid nothing is clamped (see the +Y note below), so a sample more than half the
 *       grid's height overhead used to read open. In a cavern taller than that, the air near its ceiling
 *       was lit — the same direction as the original defect, over a smaller region. <b>M27 closed
 *       that</b>: above the box this now reads Minecraft's sky light, which is a measurement of the
 *       point rather than a constant, and it agrees with the constant outdoors. What remains is the
 *       difference between a flood fill and a hemisphere integral, which is the same approximation the
 *       far region already runs on.</li>
 * </ul>
 *
 * <p>The no-grid fallback below is the one case this cannot help, because there is nothing to clamp to.
 * It is now reached only when VISIBILITY_CELL_SIZE is 0 — an explicit, informed opt out of the grid,
 * whose documented meaning is "the renderer's behaviour before this grid existed".
 *
 * <p>It used to be reached by turning volumetric FOG off, which stopped the bake as a side effect. This
 * function is not fog-only — the water medium's two enclosed sky terms read it too — so that made a fog
 * switch light up cave water, and the fallback carried the blame for a coupling made in RtComposite. The
 * bake now follows the grid's own knob instead (#41). Which leaves this fallback saying only what it
 * always should have: no grid was asked for, so there is no measurement to report.
 */
public float volumeSkyOpenness(float3 p) {
    // D78A: some authored dimensions deliberately use a tiny unoccluded environment floor. It is an
    // explicit non-physical readability light, so roofs must not gate it. Directional/local emitters
    // still cast their own shadow rays; only the preset's isotropic floor takes this fast path.
    if ((worldPush.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u) {
        return 1.0;
    }
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return 1.0;
    }
    float3 extent = cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    // Cell (i,j,k) holds the answer for origin + (i+0.5)*cell, whose normalized coordinate is exactly
    // (i+0.5)/W. So this division IS the texture coordinate — no half-texel correction, because the bake
    // writes centres. If that convention ever changes, both sides move together or the field shifts.
    float3 g = (p - worldPush.visGridOrigin.xyz) / extent;
    // ABOVE THE GRID IS THE ONE DIRECTION THAT MUST NOT CLAMP, and the reason is a property of the
    // quantity rather than a special case: sky openness is monotone non-decreasing as you rise. Going up
    // you clear occluders; you never acquire one. So the top cell is a LOWER BOUND on everything above
    // it, and clamping to it would assert a bound as though it were a measurement -- erring dark, with
    // no ceiling on how wrong.
    //
    // That is not hypothetical. The underwater sky term samples half a block above the water SURFACE, so
    // once the camera is deeper than the grid's vertical half-extent -- 16 blocks at the default cell --
    // that query lands above the grid. The top cell there is still underwater, and this grid's zenith ray
    // treats a water surface as an occluder like any other geometry, so clamping answered "roofed" and
    // the scattering vanished. As a step, at a fixed depth, which is exactly how it was reported.
    //
    // Sideways and downward do clamp: laterally the world plausibly continues as it does here, and below
    // the grid the bottom cell is an UPPER bound, so clamping there errs bright but bounded.
    if (g.y > 1.0) {
        // D194 SAID "RETURN 1.0", AND ITS REASON HAS SINCE EXPIRED. The argument was that the top cell
        // is a lower bound rather than a measurement, so reporting it would dress a bound up as a
        // reading -- true, and it left the unoccluded constant as the only remaining answer BECAUSE
        // THERE WAS NO DATA UP THERE. M27 put data up there. Its field spans the whole world height, so
        // a point above this box is inside it, and reading a third field is neither clamping to a
        // boundary cell nor asserting a bound: it is a different measurement of the same point.
        //
        // WHAT IT WAS COSTING. Every air sample more than half the grid's height above the camera --
        // 16 blocks at the default cell -- was told the sky was wide open however much rock was over
        // it. That is #41's original defect surviving in a smaller region, and it is position
        // dependent by construction: the ceiling rides on the camera, so descending pulls rock that was
        // "above the box" into it and the fog overhead goes from fully lit to measured. Fog brightness
        // that changes because the viewer moved is what sent this milestone looking in the first place.
        //
        // OUTDOORS IS UNCHANGED, which is what makes this safe to take: open sky is sky light 15, which
        // is 1.0, exactly the constant this replaces. Only a roofed sample moves, and it moves darker.
        // The deep-water case that motivated D194 is also unchanged -- waterSurfaceSkyOpenness asks from
        // just above the surface, and open water's surface is 15.
        //
        // The curve stays off this path on purpose. FAR_FOG_SKY_LIGHT_CURVE is applied by the fog to the
        // far region, never inside a field reader, so that the debug views keep showing what Minecraft
        // stores rather than what the dial is set to; that contract is pinned by
        // RtSkyLightFogContractTest. This reader is one of those views' sources.
        return (worldPush.environmentFlags & ENVIRONMENT_FAR_FOG_SKY_LIGHT) != 0u
                ? skyLightAt(p)
                : 1.0;
    }
    // Clamped HERE rather than left to the sampler. The descriptor happens to carry CLAMP_TO_EDGE today,
    // so the fetch alone would do the right thing on the remaining axes -- but that address mode is
    // chosen in RtComposite, and a correctness property of this function must not depend on a decision
    // made in another file.
    float3 uvw = saturate(g);
    return saturate(volumeVisibilityGrid.SampleLevel(uvw, 0.0).r);
}

/**
 * A hand's breadth. Lifts a sample from inside the water to just above its surface.
 *
 * <p>Named because the number is load-bearing and shared. Too small and float error at the interface can
 * leave the sample on the wrong side of it; too large and the answer stops being about this point.
 */
public static const float WATER_SKY_GATE_LIFT = 0.5;

/**
 * Sky openness for a point inside water — asked from just above the surface, not from the point itself.
 *
 * <p><b>Why not from the point.</b> This grid's zenith ray treats a water surface as an occluder like any
 * other geometry, so asked from underwater it answers "roofed" everywhere and the scattering vanishes.
 * Asked from a hand's breadth above the surface it answers the question actually being posed: how much
 * sky is above this column of water. That is also why the query needs no instance mask for water.
 *
 * <p><b>Why openness comes from the grid while the DEPTH comes from a ray.</b> The two are not
 * interchangeable and the split is deliberate: depth wants a ray because a ray measures a length, and
 * openness wants the grid because the grid interpolates. "How much sky is up there" is a quantity that
 * must never step, and every attempt to answer it with a single binary ray has put a step where a
 * gradient belongs. `skyDepth` is the caller's measured distance to the surface, from its own ray.
 *
 * <p><b>Why this is a function.</b> It was written out three times — twice in volume.slang for the
 * stratified and scatter-vertex paths, once in world.rgen.slang for the water medium probe — each with
 * its own copy of the paragraph above. Three copies of one expression is three chances to diverge, and
 * divergence here is silent because each copy is defensible read alone. The probe copy mattered most:
 * its whole job is to report what the shading does, so a gate point that drifts from the shading's makes
 * the probe report a measurement nobody takes. Its up-RAY stays independent, which is the part that was
 * deliberately duplicated (see the note at its call site); only this arithmetic is shared.
 */
public float waterSurfaceSkyOpenness(float3 p, float skyDepth) {
    float3 gateP = float3(p.x, p.y + skyDepth + WATER_SKY_GATE_LIFT, p.z);
    return volumeSkyOpenness(gateP);
}
