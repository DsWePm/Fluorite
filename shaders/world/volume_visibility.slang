// The bound volumetric visibility grid and the one correct way to read it. SCALAR MODE (the shipped
// layout): R is sky openness -- the accumulated fraction of the upper hemisphere that reaches this cell
// -- and G is the sample count its running mean stands on, which only the bake reads. B and A are
// constants. DIRECTIONAL MODE (FLAG_SKY_FIELD_DIRECTIONAL, M28 S1): RGBA are four world-azimuth sector
// bins of that same fraction, and the count lives in the bake's meta images; volumeSkyOpenness returns
// their quarter-weighted sum, which is the same quantity, so scalar readers keep their published
// semantics without knowing the grid changed shape.
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

// Must match volume_visibility.comp.slang and RtSky.VIS_GRID_W/H/D.
public static const int VIS_GRID_W = 64;
public static const int VIS_GRID_H = 32;
public static const int VIS_GRID_D = 64;

/**
 * Bound by RtPipeline.setVolumeVisibilityGrid. Scalar mode: R sky openness, G the count's echo.
 * Directional mode: RGBA the four sector bins. See volumeSkyBins.
 *
 * <p>Sampled rather than storage, and that is the whole design: nearest fetches would draw the grid's own
 * cell boundaries into the fog as brightness steps, and a step where a gradient belongs is the failure
 * this line of work has hit repeatedly. Trilinear is also what makes a single binary ray per cell add up
 * to something continuous — the field it produces varies smoothly even though every sample in it is 0 or 1.
 */
[[vk::binding(16, 0)]] public Sampler3D volumeVisibilityGrid;

/**
 * The sky-visibility field at a point in the air, in whichever representation the grid currently
 * publishes: RGBA are four sector bins under FLAG_SKY_FIELD_DIRECTIONAL (M28 S1), or (openness, count
 * echo, 0, 1) in the shipped scalar mode. Callers that want ONE number take volumeSkyOpenness; the
 * marched ambient sky term takes this and weights each bin by its own sector radiance.
 *
 * <p>Outside the grid, the boundary's answer; only where no grid has been published at all does this
 * fall back to all-open.
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
 * <p>WHAT IT COSTS, named rather than discovered later. Standing inside a cave mouth whose opening is
 * further than the grid reaches, the boundary in that direction is roofed, so genuinely open sky beyond
 * it is reported closed and distant outdoor fog darkens. Opposite direction to the error being removed,
 * and far smaller. WITH FLAG_FOG_BEYOND_GRID_CLAMP the +Y face clamps too (see below), so the same
 * bounded cost exists overhead: a genuinely open column more than half the grid's height overhead reads
 * the top cell instead of 1.
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
 *
 * <p>THE MISS ANSWERS ARE ALL-OPEN IN BOTH MODES, and that is not an accident: four open bins
 * quarter-weighted sum to exactly the scalar 1.0, so every fallback below means the same number to a
 * scalar reader and to a sector reader.
 */
public float4 volumeSkyBins(float3 p) {
    // D78A: some authored dimensions deliberately use a tiny unoccluded environment floor. It is an
    // explicit non-physical readability light, so roofs must not gate it. Directional/local emitters
    // still cast their own shadow rays; only the preset's isotropic floor takes this fast path.
    if ((worldPush.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u) {
        return float4(1.0, 1.0, 1.0, 1.0);
    }
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return float4(1.0, 1.0, 1.0, 1.0);
    }
    float3 extent = cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    // Cell (i,j,k) holds the answer for origin + (i+0.5)*cell, whose normalized coordinate is exactly
    // (i+0.5)/W. So this division IS the texture coordinate — no half-texel correction, because the bake
    // writes centres. If that convention ever changes, both sides move together or the field shifts.
    float3 g = (p - worldPush.visGridOrigin.xyz) / extent;
    // ABOVE THE GRID is where the six faces used to disagree. D194 left +Y returning all-open
    // unconditionally, on two grounds: openness is monotone non-decreasing as you rise (the top cell is
    // a lower bound on everything above it, so clamping asserts a bound as a measurement, erring dark
    // with no ceiling on how wrong), and the underwater sky term samples half a block above the water
    // SURFACE -- deeper than half the grid's height, that query lands above the grid with the top cells
    // still underwater, so a clamp read "roofed" and the scattering vanished in a step at a fixed depth.
    //
    // Both grounds are now answered elsewhere, which is what makes the clamp affordable: the
    // deep-water half moved to the consumer that caused it (waterSurfaceSkyOpenness carries its own
    // above-grid exception), and the bound-as-measurement half is the same trade the sideways and
    // downward faces already made -- the boundary is the nearest thing actually measured, and in a
    // sealed cavern the top boundary is roofed, so erring dark there is erring CORRECT. The residual
    // cost is the named one: a genuinely open column more than half the grid's height overhead reads
    // the top cell. #73.
    //
    // The flag gate is the off-state contract: with it clear, all six faces keep exactly the shipped
    // answers, +Y included.
    if (g.y > 1.0 && (worldPush.flags & FLAG_FOG_BEYOND_GRID_CLAMP) == 0u) {
        // D194's shipped answer, kept verbatim while the clamp switch is off.
        return float4(1.0, 1.0, 1.0, 1.0);
    }
    // Clamped HERE rather than left to the sampler. The descriptor happens to carry CLAMP_TO_EDGE today,
    // so the fetch alone would do the right thing on the remaining axes -- but that address mode is
    // chosen in RtComposite, and a correctness property of this function must not depend on a decision
    // made in another file.
    float3 uvw = saturate(g);
    return saturate(volumeVisibilityGrid.SampleLevel(uvw, 0.0));
}

/**
 * The scalar form: the whole-hemisphere openness a reader with no use for direction wants.
 *
 * <p>Scalar mode (the flag clear) returns the grid's R, which IS that quantity -- the same fetch the
 * shipped path made, one channel of it. Directional mode returns the four bins' quarter-weighted sum,
 * which is the same cosine-weighted hemisphere fraction estimated sector by sector; the water, closed-
 * form and froxel readers go through here and keep their published semantics without knowing the grid
 * changed shape underneath them. Only the marched ambient sky term reads the bins themselves.
 */
public float volumeSkyOpenness(float3 p) {
    if ((worldPush.flags & FLAG_SKY_FIELD_DIRECTIONAL) != 0u) {
        return dot(volumeSkyBins(p), float4(0.25, 0.25, 0.25, 0.25));
    }
    return volumeSkyBins(p).r;
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
    // D194's exception, MOVED HERE from volumeSkyOpenness (M28, #73): this gate sits half a block above
    // the water SURFACE, so a camera deeper than the grid's vertical half-extent -- 16 blocks at the
    // default cell -- puts it ABOVE the grid while the top cells are still underwater. The grid's rays
    // treat that surface as an occluder like any other geometry, so letting this query take the clamped
    // top cell reads "roofed" and the deep-water scattering switches off in a step at a fixed depth,
    // which is exactly how the regression reported when the sideways clamp first landed.
    //
    // Above the box, the honest answer FOR WATER is the unoccluded one. Everywhere else the clamp now
    // applies. Note the early return is also bit-equal to the shipped path while the clamp switch is
    // off -- volumeSkyOpenness returned 1.0 for any above-grid query either way -- so the water rule
    // never has to know which mode it is in.
    float cell = worldPush.visGridOrigin.w;
    if (cell > 0.0) {
        float extentY = cell * float(VIS_GRID_H);
        if ((gateP.y - worldPush.visGridOrigin.y) / extentY > 1.0) {
            return 1.0;
        }
    }
    return volumeSkyOpenness(gateP);
}
