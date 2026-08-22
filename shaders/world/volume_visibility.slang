// The bound volumetric visibility grid and the one correct way to read it. R/G are sun visibility and
// sky openness; BA are the visibility-weighted first moment of the sampled celestial square coordinates.
//
// Separate from the bake for the same reason atmosphere_lut is separate from atmosphere: the bake's
// descriptor set has exactly one storage image, a binding declared in a module reaches every importer,
// and giving the bake a set-0 binding its own layout does not contain is a validation error rather than
// a subtle one. The grid's DIMENSIONS are shared (they are the texture's), the BINDING is not.

import world_common; // WorldPush's shape
import world_core;   // the frame's WorldPush itself, which carries the grid's placement
import volume_hash;  // M25 cell addressing and the cell record

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
    /**
     * D199: the direction a volumetric ReSTIR cell settled on, and the sky radiance along it.
     *
     * <p>VALID IS FALSE ON EVERY PATH THAT DOES NOT SET IT, which is what keeps iron law 8 structural
     * here rather than careful. The scalar `sky` above is untouched and still correct on its own, so a
     * caller that ignores these three fields reproduces the shipped picture exactly -- and every caller
     * did, up to the commit that added them.
     *
     * <p>The pair exists because collapsing a reservoir to `sky` would discard the direction, and the
     * direction is what D198 spent a shadow ray to obtain. The phase term belongs at the caller, which
     * is the only place that knows the view direction it must be evaluated against.
     */
    public bool skyDirValid;
    public float3 skyDir;
    public float3 skyRadiance;
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
 * <p>WHAT IT COSTS, named rather than discovered later. Two things, both bounded:
 *
 * <ul>
 *   <li>Standing inside a cave mouth whose opening is further than the grid reaches, the boundary in
 *       that direction is roofed, so genuinely open sky beyond it is reported closed and distant outdoor
 *       fog darkens. Opposite direction to the error being removed, and far smaller.</li>
 *   <li>Above the grid nothing is clamped (see the +Y note below), so a sample more than half the grid's
 *       height overhead reads open. In a cavern taller than that, the air near its ceiling is lit. Same
 *       direction as the original defect, over a much smaller region, and it is the price of the deep
 *       water fix rather than an oversight.</li>
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
/**
 * Replace the scalar sky term with a cell's directional estimate, if this switch is on and the cell is
 * live. Silent on every other path, which is how the off state stays byte-identical.
 *
 * <p>Reads only. The cell is filled where the volume is marched, once per cell per frame rather than once
 * per sample -- the amortisation D198 depends on, and the reason a shadow ray in the target is affordable
 * at all.
 */
/**
 * What the read below actually saw, for debug view 28.
 *
 * <p>RECORDED BY THE READ ITSELF rather than re-derived by the view. The first three versions of this
 * diagnostic marched their own ray and looked up their own cells, and every one of them disagreed with the
 * shading about where to ask -- the queued origin collapses to the camera, then linear spacing started
 * beyond the room, and then the view painted before the shading it was describing had even run. A
 * diagnostic that performs its own lookup is testing its own lookup.
 *
 * <p>x < 0 means the read never ran here at all.
 */
static float3 gVolumeRestirProbe = float3(-1.0, 0.0, 0.0);

void volumeRestirSky(float3 p, inout VolumeVisibilitySample result) {
    if ((worldPush.environmentFlags & ENVIRONMENT_VOLUME_RESTIR) == 0u || worldPush.volumeGridSlots == 0u
            || worldPush.volumeGridAddr == 0) {
        gVolumeRestirProbe = float3(0.0, 0.0, 0.0); // black: switch off, or no table
        return;
    }
    float dist = length(p - worldPush.camOffset);
    int level = volumeCellLevel(dist, worldPush.volumeGridLodK);
    int3 coord = volumeCellCoord(p, volumeCellEdge(level));
    ConstPtr<VolumeCell> grid = ConstPtr<VolumeCell>(worldPush.volumeGridAddr);
    // The OTHER parity: the half completed last frame, which no thread is writing now. Reading the half
    // being filled is what made the first wiring flicker.
    uint base = volumeHalfBase(worldPush.volumeGridSlots, worldPush.frameIndex + 1u);
    int slot = volumeGridFind(grid, worldPush.volumeGridSlots, base, coord, level,
                              worldPush.frameIndex, worldPush.volumeGridEvictionFrames);
    if (slot < 0) {
        gVolumeRestirProbe = float3(1.0, 0.0, 1.0); // magenta: probed, found nothing
        return; // no cell yet: the scalar answer above stands, which is the shipped one
    }
    VolumeCell c = grid[slot];
    gVolumeRestirProbe = float3(saturate(c.stats.x / VOLUME_M_MAX),
                               volumeCellOpenness(c),
                               c.stats.x >= VOLUME_M_MATURE ? 1.0 : 0.0);

    // OPENNESS, NOT THE RESERVOIR'S EMPTINESS. This used to read W == 0 as "no sky reaches here", gated
    // on maturity, and once the froxel started feeding every cell every frame the cells matured at once
    // and the near field lost its fog along the visibility grid's box -- a rectangular seam in the air,
    // which is what a region boundary looks like when the two sides disagree about a quantity.
    //
    // The cell now carries the mean visibility directly, which is what `sky` has always meant, and it is
    // the same number the froxel reads. One quantity, one definition, both consumers.
    result.sky = volumeCellOpenness(c);
    if (c.dirW.w <= 0.0) {
        return; // no direction was ever selected: the scalar answer above is the whole answer
    }
    result.skyDirValid = true;
    result.skyDir = c.dirW.xyz;
    // leT already carries radiance times transmittance; W finishes the estimator. The phase belongs at
    // the caller, which is the only place that knows the view direction.
    result.skyRadiance = c.leT.xyz * c.dirW.w;
}

/**
 * Whether a point falls inside the cached visibility grid. THE ONE DEFINITION of M25's near/far split.
 *
 * <p>The deposit and the read must agree on it. When they did not -- the deposit testing its caller's own
 * region flag and the read testing the grid extent -- a cell could be written under one switch and looked
 * for under the other, so turning on a single switch produced cells nobody read.
 */
/**
 * A fully initialised sample meaning "nothing occludes anything here".
 *
 * <p>EXISTS BECAUSE PARTIAL INITIALISATION ALREADY SHIPPED A BUG. D199 widened this struct with three
 * fields, and a hand-built instance in volume.slang kept assigning only the original three. The bool that
 * selects the directional path was therefore uninitialised, and an uninitialised bool chooses a branch
 * per invocation while an uninitialised float3 becomes radiance -- black speckle everywhere, flickering,
 * indoors and outdoors, day and night, immune to every volumetric knob, and shifting with unrelated code
 * changes because that is what undefined values do.
 *
 * <p>The struct grew and one of its two construction sites did not. A factory is what makes the next
 * field addition safe: there is no way to build one of these and forget a member.
 */
public VolumeVisibilitySample volumeVisibilityUnoccluded() {
    VolumeVisibilitySample s;
    s.sun = 1.0;
    s.sky = 1.0;
    s.lightUv = float2(0.5, 0.5);
    s.skyDirValid = false;
    s.skyDir = float3(0.0, 1.0, 0.0);
    s.skyRadiance = float3(0.0, 0.0, 0.0);
    return s;
}

public bool volumeInsideVisibilityGrid(float3 p) {
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return false;
    }
    float3 extent = cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    float3 g = (p - worldPush.visGridOrigin.xyz) / extent;
    return g.y <= 1.0 && all(g >= float3(0.0, 0.0, 0.0)) && all(g <= float3(1.0, 1.0, 1.0));
}

public VolumeVisibilitySample sampleVolumeLightVisibility(float3 p) {
    VolumeVisibilitySample result = volumeVisibilityUnoccluded();
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
        // D194: above the grid the top cell is a LOWER bound on sky openness, and returning it as though
        // it were a measurement is what the FAR switch exists to replace. Clamp remains the off state.
        volumeRestirSky(p, result);
        return result;
    }
    // Clamped HERE rather than left to the sampler. The descriptor happens to carry CLAMP_TO_EDGE today,
    // so the fetch alone would do the right thing on the remaining axes -- but that address mode is
    // chosen in RtComposite, and a correctness property of this function must not depend on a decision
    // made in another file.
    float3 uvw = saturate(g);
    float4 v = volumeVisibilityGrid.SampleLevel(uvw, 0.0);
    result.sun = v.r;
    result.sky = v.g;
    result.lightUv = result.sun > 1.0e-5
            ? clamp(v.ba / result.sun, float2(0.0, 0.0), float2(1.0, 1.0))
            : float2(0.5, 0.5);
    volumeRestirSky(p, result);
    return result;
}

/** Compatibility view for callers that need only the two visibility scalars. */
public float2 sampleVolumeVisibility(float3 p) {
    VolumeVisibilitySample result = sampleVolumeLightVisibility(p);
    return float2(result.sun, result.sky);
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
    return sampleVolumeVisibility(gateP).y;
}

/**
 * Debug view 28: the state of the cells along this pixel's camera ray.
 *
 * <p>THE VIEW MAY ASK FOR ITSELF AGAIN, and the reason it may is new rather than a relapse. Every earlier
 * version failed because the sample point disagreed with the shading's -- only the marched path filled
 * cells, and it filled them sparsely and unpredictably. Now the froxel fills every cell in the frustum,
 * once per cell per frame, and the grid is world-anchored: "what is in the cell at this world point" has
 * one answer no matter who asks. The shading-recorded probe could only ever describe the MARCHED read,
 * which is a minority of the screen, so it reported "the read never ran here" over most of the picture
 * and said nothing at all about the froxel.
 *
 * <p>Geometric spacing, dense near the eye where the cells are small.
 */
public float3 volumeRestirDebugMarch(float3 ro, float3 rd, float cullDistance) {
    if ((worldPush.environmentFlags & ENVIRONMENT_VOLUME_RESTIR) == 0u
            || worldPush.volumeGridSlots == 0u || worldPush.volumeGridAddr == 0) {
        return float3(0.0, 0.0, 0.0); // black: the switch is off
    }
    uint slots = worldPush.volumeGridSlots;
    ConstPtr<VolumeCell> grid = ConstPtr<VolumeCell>(worldPush.volumeGridAddr);
    uint readBase = volumeHalfBase(slots, worldPush.frameIndex + 1u);
    uint writeBase = volumeHalfBase(slots, worldPush.frameIndex);

    // BOTH HALVES, REPORTED APART. Indoors this said "no cell anywhere" while the froxel was demonstrably
    // writing cells -- view 17 moves with the switch. That leaves two possibilities which one colour
    // cannot distinguish: the cells are written to a half nobody reads, or they are not written at all.
    // Cyan means the write half has this cell and the read half does not, which is a parity or timing
    // fault; magenta means neither has it, which is an addressing fault.
    float t = 1.0;
    for (uint step = 0u; step < 64u && t < cullDistance; ++step) {
        float3 p = ro + rd * t;
        int level = volumeCellLevel(length(p - worldPush.camOffset), worldPush.volumeGridLodK);
        float edge = volumeCellEdge(level);
        t += edge;
        int3 coord = volumeCellCoord(p, edge);
        int slot = volumeGridFind(grid, slots, readBase, coord, level,
                                  worldPush.frameIndex, worldPush.volumeGridEvictionFrames);
        if (slot >= 0) {
            VolumeCell c = grid[slot];
            // SCALED BELOW THE DIAGNOSTIC COLOURS ON PURPOSE. A sealed indoor cell is mature with zero
            // openness, which paints (1, 0, 1) -- the exact magenta that means "no cell found". The
            // correct reading and the failure reading were the same pixel value, so a working grid
            // indoors was indistinguishable from a broken one. Data lives under 0.8, faults sit at 1.0.
            // GREYSCALE FOR OPENNESS, GREEN WHILE IMMATURE. The previous key put openness in one channel
            // and maturity in another, so a sealed indoor cell -- mature, zero openness -- painted
            // (1, 0, 1): the same magenta that means "no cell found", scaled only in brightness. Asking
            // anyone to tell a correct reading from a failure by shade is not a diagnostic.
            //
            // Now the data channels move together and nothing in the data can land on a fault colour.
            float g = 0.15 + 0.65 * volumeCellOpenness(c);
            return c.stats.x >= VOLUME_M_MATURE
                    ? float3(g, g, g)
                    : float3(0.0, g, 0.0);
        }
        if (volumeGridFind(grid, slots, writeBase, coord, level,
                           worldPush.frameIndex, worldPush.volumeGridEvictionFrames) >= 0) {
            return float3(0.0, 1.0, 1.0); // cyan: written, but not where anyone reads
        }
    }
    return float3(1.0, 0.0, 1.0); // magenta: neither half holds a cell on this ray
}

/** Debug view 28 reads this after the shading that filled it. See gVolumeRestirProbe. */
public float3 volumeRestirProbeResult() {
    return gVolumeRestirProbe;
}
