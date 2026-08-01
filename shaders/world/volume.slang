// Participating media along a segment: what the path picks up between two hits rather than at them.
// Depends on math, medium, trace, world_common and world_core.
//
// Two kinds of medium, kept deliberately apart:
//
//   ENCLOSED — water, glass, ice. Entered through geometry, pushed and popped on the MediumStack, and
//   homogeneous inside. The stack knows about these.
//
//   AMBIENT — the world's air: height fog now, noise fog and cloud layers later. Unbounded, with no
//   geometry to enter, and a pure function of world position and the dimension's preset. **These never
//   go on the stack.** That is the decision the rest of this file rests on: the stack keeps its
//   named-field, depth-2, no-dynamic-indexing shape (see medium.slang for why that matters in a
//   register-bound raygen), and an unbounded field that every path is inside at all times then costs
//   zero per-path state. Whether a path is in open air is the explicit Medium.ambient bit, never
//   inferred from ior == 1 && extinction == 0.

import math;
import medium;
import atmosphere;      // PLANET_R, ATMOS_VIEW_ALTITUDE, SUN_INTENSITY
import atmosphere_lut;  // sampleMultiScatter: the fog's isotropic source, same table the froxel reads
import trace;
import volume_visibility;
import world_common;
import world_core;

// ---- Sampling primitives.
//
// Neither is used by the analytic fog below, which integrates a segment in closed form rather than
// sampling within it. They are here because the random-walk subsurface work needs exactly these two and
// should share them rather than grow a second copy: one estimator for distances, one for directions.

/**
 * Samples the Henyey-Greenstein phase function about `wo`. The returned direction is distributed as the
 * phase function itself, so its sampling weight is 1.
 */
public float3 samplePhaseHG(float3 wo, float g, inout uint seed) {
    float u1 = rndf(seed);
    float u2 = rndf(seed);
    float cosT;
    if (abs(g) < 1.0e-3) {
        cosT = 1.0 - 2.0 * u1; // isotropic; the general form below is 0/0 here
    } else {
        float s = (1.0 - g * g) / (1.0 - g + 2.0 * g * u1);
        cosT = (1.0 + g * g - s * s) / (2.0 * g);
    }
    cosT = clamp(cosT, -1.0, 1.0);
    float sinT = sqrt(max(0.0, 1.0 - cosT * cosT));
    float phi = 2.0 * PI * u2;
    // Same canonical frame construction cosineDir uses, for the same reason: any orthonormal basis will
    // do, and picking it the same way keeps the two readable side by side.
    float3 t = normalize(abs(wo.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(wo, t));
    t = cross(b, wo);
    return normalize(sinT * cos(phi) * t + sinT * sin(phi) * b + cosT * wo);
}

/** Samples a free-flight distance through extinction `sigmaT`, with its pdf. */
public float sampleFreeFlight(float sigmaT, inout uint seed, out float pdf) {
    if (sigmaT <= 0.0) {
        pdf = 0.0;
        return 1.0e30;
    }
    float t = -log(max(1.0e-7, 1.0 - rndf(seed))) / sigmaT;
    pdf = sigmaT * exp(-sigmaT * t);
    return t;
}

/**
 * Phase function evaluation.
 *
 * Delegates to math.slang's hg() rather than restating it — the surface subsurface term already uses
 * that one, and two copies of a phase function drift apart.
 */
public float evalPhase(float cosT, float g) {
    return hg(cosT, g);
}

// ---- The ambient field.
//
// fogParams     x density at the reference height, y height scale, z reference height, w cull distance
// fogExtinction xyz per-channel extinction tint, w near cutoff in blocks
// fogScatter    xyz single-scattering albedo, w Henyey-Greenstein g for the sun lobe
// fogAmbient    xyz isotropic source radiance (the sky's contribution to what the fog is lit by)

/**
 * Integral of the height profile exp(-max(h,0)/H) over a segment, where h runs linearly from hA to hB.
 *
 * The profile SATURATES below the reference height rather than continuing to grow. That is not a
 * refinement, it is what makes this usable: an unbounded exp(-(y-y0)/H) integrated over a long downward
 * ray produces optical depths in the hundreds — from 200 blocks up, a ray that misses geometry and runs
 * the full cull distance downward reached tau ~ 186, and the whole view went opaque the moment the camera
 * climbed. Real fog has a bottom. With this, tau can never exceed sigma0 * cullDistance.
 *
 * Analytic in every case. exp(-h/H) integrates to (H/s)(exp(-hA/H) - exp(-hB/H)) for slope s, the
 * saturated part is just its length, and a segment that crosses the reference height is split at the
 * crossing.
 */
float ambientProfileIntegral(float hA, float hB, float len, float H) {
    if (len <= 0.0) {
        return 0.0;
    }
    float dh = hB - hA;
    if (abs(dh) < 1.0e-5) {
        return exp(-max(hA, 0.0) / H) * len; // effectively level: one height for the whole segment
    }
    float s = dh / len;
    if (hA >= 0.0 && hB >= 0.0) {
        return (H / s) * (exp(-hA / H) - exp(-hB / H));
    }
    if (hA <= 0.0 && hB <= 0.0) {
        return len; // entirely inside the saturated layer
    }
    float tCross = -hA / s; // distance along the segment where the reference height is crossed
    if (hA < 0.0) {
        return tCross + (H / s) * (1.0 - exp(-hB / H)); // saturated, then thinning
    }
    return (H / s) * (exp(-hA / H) - 1.0) + (len - tCross); // thinning, then saturated
}

/**
 * Optical depth of the ambient medium over [start, end] along a ray, before the per-channel tint.
 *
 * `start` is the near cutoff: fog does not begin at the eye, so a window of clear air in front of the
 * camera keeps the near field readable and stops the whole screen from being veiled at ground level.
 */
public float ambientHeightIntegral(float3 ro, float3 rd, float start, float end) {
    float sigma0 = worldPush.fogParams.x;
    if (sigma0 <= 0.0 || end <= start) {
        return 0.0;
    }
    float scale = max(worldPush.fogParams.y, 1.0e-3);
    float y0 = worldPush.fogParams.z;
    return sigma0 * ambientProfileIntegral(ro.y + rd.y * start - y0,
                                           ro.y + rd.y * end - y0,
                                           end - start, scale);
}

/** The [start, end] window a segment of length `tEnd` actually accumulates fog over. */
public float2 ambientWindow(float tEnd) {
    float start = min(worldPush.fogExtinction.w, tEnd);
    float end = min(tEnd, worldPush.fogParams.w);
    return float2(start, max(start, end));
}

/** Transmittance of the ambient medium over a segment. Shadow rays go through visibilityThroughAmbient. */
public float3 ambientTransmittance(float3 ro, float3 rd, float dist) {
    float2 window = ambientWindow(dist);
    float tau = ambientHeightIntegral(ro, rd, window.x, window.y);
    if (tau <= 0.0) {
        return float3(1.0, 1.0, 1.0);
    }
    return exp(-worldPush.fogExtinction.xyz * tau);
}

/**
 * A shadow ray, with the ambient field's absorption folded into what it reports.
 *
 * The multiply happens here rather than inside visibility() because the shadow payload must not grow:
 * SER spills every live value at ReorderThread, so a wider payload is paid twice on that path (see
 * trace_ser.slang). One wrapper rather than three call sites, so a fourth shadow ray cannot quietly skip
 * it — that is the whole reason this exists as a function.
 *
 * Approximate for a shading point inside an enclosed medium: the ray leaves the water partway along and
 * the ambient field only applies after that, which this does not model. Such points are already deep in
 * the water's own absorption, so the error lands where almost nothing is visible anyway.
 */
public VisibilityResult visibilityThroughAmbient(MediumStack stack, float3 origin, float3 dir, float tmax) {
    VisibilityResult result = visibility(origin, dir, tmax);
    if (stack.current.ambient) {
        result.transmittance *= ambientTransmittance(origin, dir, tmax);
    }
    return result;
}

/** What a segment did to the path: what survives it, and what it added. */
public struct SegmentIntegral {
    public float3 transmittance;
    public float3 inScatter;
};

/**
 * Hard ceiling on the sub-steps a segment may split its ambient in-scatter into. The live cap is
 * worldPush.flags bits 18-22 (Rt.Volumetrics.VISIBILITY_MAX_STEPS); this only bounds the loop so the
 * compiler has a constant trip count to work with and a bad config cannot unroll a raygen into oblivion.
 */
static const int AMBIENT_VIS_STEP_LIMIT = 31;

/**
 * How many sub-steps to take across `span` blocks of grid, and it is derived rather than fixed.
 *
 * The step length wants to be the grid's CELL SIZE. Finer tells you nothing the grid can represent —
 * every value in it is an interpolation between cell centres — and coarser throws away resolution the
 * bake's rays already paid for. A fixed count could only be right at one span: six steps is finer than a
 * cell across a room and five blocks a step across a long escape through a window, and it is the long one
 * that carries a light shaft.
 *
 * So short segments get fewer steps than the old fixed six, not more, and only long ones cost anything —
 * which is the opposite of what raising a fixed count would have done.
 */
int ambientVisSteps(float span, float cell) {
    int cap = int(max((worldPush.flags >> 18u) & 31u, 1u));
    float wanted = ceil(span / max(cell, 0.25));
    return int(clamp(wanted, 1.0, float(min(cap, AMBIENT_VIS_STEP_LIMIT))));
}

/** In-flight state of the ambient integral: what it has gathered, and what survives to the next step. */
struct AmbientAccum {
    float3 inScatter;
    float3 transmittance;
};

/**
 * One sub-step of the ambient in-scatter, with a fixed visibility over it.
 *
 * The same closed form the whole segment used to run, applied to a piece of it. That is what makes this
 * split free of consequence where nothing is shadowed: with visibility 1 throughout, the pieces telescope
 * — sum of T(i-1) * (1 - t(i)) is T(0) - T(n) — and the height integral is additive over abutting windows,
 * so the total comes back to S * (1 - exp(-sigma * tau)) exactly. A change that is provably a no-op when
 * the new input is absent is a change whose effect on screen can only be the new input.
 */
void ambientStep(inout AmbientAccum acc, float3 ro, float3 rd, float tA, float tB,
                 float3 sunSource, float3 skySource, float2 vis) {
    if (tB <= tA) {
        return;
    }
    float tau = ambientHeightIntegral(ro, rd, tA, tB);
    if (tau <= 0.0) {
        return;
    }
    float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
    float3 source = sunSource * vis.x + skySource * vis.y;
    acc.inScatter += acc.transmittance * worldPush.fogScatter.xyz * source
            * (float3(1.0, 1.0, 1.0) - stepT);
    acc.transmittance *= stepT;
}

/**
 * The sun's contribution to a marched segment, with visibility from RAYS instead of from the grid.
 *
 * <p><b>Why the grid is not enough, measured rather than argued.</b> Debug view 19 plots sun visibility
 * along a scanline twice, once from the grid and once from a ray. Inside a room the grid reaches zero in
 * shadow correctly but only reaches 0.5 where the truth is 1 — the peak is halved — and its edge is a
 * ramp where the truth is a step. The mechanism is structural, not a tuning failure: trilinear blends the
 * eight nearest cells, and indoors every sample is within one cell of a wall cell whose value is zero, so
 * the lit peak is averaged down no matter how the grid is tuned. A voxel grid's visibility is band-limited
 * to its cell size, and a light shaft is exactly the high-frequency part it cannot carry.
 *
 * <p><b>Why this is not the estimator M9 measured and rejected.</b> That one cast rays at FIXED optical
 * depths with no per-frame decorrelation, so every frame produced the same wrong binary answer and applied
 * it to a whole segment — a BIAS, and a visible one: an occlusion boundary flipped the screen at once.
 * This one jitters within each stratum from the path's own RNG, so the error is zero-mean noise that
 * converges, which is the signal DLSS-RR is built to remove. The difference is falsifiable rather than
 * rhetorical: freeze temporal accumulation and this must look NOISY, not blocky. If it looks blocky, the
 * jitter is not reaching it and this is M9 again.
 *
 * <p>Stratified over the segment with each stratum carrying its exact closed-form weight, so the total
 * matches the unshadowed integral wherever visibility is uniform — the same property that made the grid
 * path a provable no-op when the grid was absent.
 */
float3 sunInScatterStochastic(float3 ro, float3 rd, float2 window, float3 sunSource,
                              int rays, inout uint seed) {
    float3 gathered = float3(0.0, 0.0, 0.0);
    float3 transmittance = float3(1.0, 1.0, 1.0);
    for (int i = 0; i < rays; ++i) {
        float tA = lerp(window.x, window.y, float(i) / float(rays));
        float tB = lerp(window.x, window.y, float(i + 1) / float(rays));
        float tau = ambientHeightIntegral(ro, rd, tA, tB);
        if (tau <= 0.0) {
            continue;
        }
        float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
        // Jittered inside the stratum. This one line is the whole difference from M9's estimator.
        float3 p = ro + rd * lerp(tA, tB, rndf(seed));
        float3 vis = visibilityMasked(CULL_SECONDARY_NO_SELF, p,
                                      worldPush.lightDir.xyz, RAY_FAR).transmittance;
        gathered += transmittance * worldPush.fogScatter.xyz * sunSource * vis
                * (float3(1.0, 1.0, 1.0) - stepT);
        transmittance *= stepT;
    }
    return gathered;
}

/**
 * Where this ray enters and leaves the visibility grid's box, as distances along it. An empty span
 * (x >= y) when the ray misses the box or no grid is published.
 *
 * A slab test, and it is an optimisation rather than a correctness requirement — sampleVolumeVisibility
 * already returns "lit" for a point outside the grid, so a span that is slightly wrong only wastes or
 * misplaces samples. That property is deliberate: this is the kind of arithmetic that is easy to get
 * subtly wrong, and it should not be able to put light where there is none.
 */
float2 visGridSpan(float3 ro, float3 rd) {
    float cell = worldPush.visGridOrigin.w;
    if (cell <= 0.0) {
        return float2(0.0, -1.0);
    }
    float3 lo = worldPush.visGridOrigin.xyz;
    float3 hi = lo + cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    // Near-zero components are floored rather than divided by. The branchless slab test's usual argument
    // is that 1/0 gives an infinity the min/max handle correctly — true, right up until the ray origin
    // lies exactly in a slab's plane, where the infinity meets a zero numerator and produces a NaN that
    // then propagates silently through every min and max downstream. Flooring the direction instead gives
    // a very large finite t of the correct sign, which is what the infinity was standing in for anyway:
    // a ray parallel to a slab either never leaves it or is never in it, and both come out right.
    float3 safeRd = select(abs(rd) < float3(1.0e-8, 1.0e-8, 1.0e-8),
                           float3(1.0e-8, 1.0e-8, 1.0e-8), rd);
    float3 invD = float3(1.0, 1.0, 1.0) / safeRd;
    float3 t0 = (lo - ro) * invD;
    float3 t1 = (hi - ro) * invD;
    float3 tMin = min(t0, t1);
    float3 tMax = max(t0, t1);
    float enter = max(max(tMin.x, tMin.y), max(tMin.z, 0.0));
    float exit = min(min(tMax.x, tMax.y), tMax.z);
    return float2(enter, exit);
}

/**
 * Integrates both media over one segment. This is the single hook that replaces
 * `throughput *= exp(-extinction * t)` in the raygens. Callers do:
 *
 *     L += throughput * seg.inScatter;
 *     throughput *= seg.transmittance;
 *
 * in that order. The in-scatter is what the segment added to the radiance arriving along it, so it is
 * weighted by the throughput reaching the segment, not by the throughput leaving it.
 *
 * Analytic. For a source radiance that is constant along the segment, the single-scattering integral
 * collapses exactly to albedo * Ls * (1 - T) — and height fog keeps that identity, because all the height
 * variation lives inside the optical depth. So this costs no marching, no extra rays and no new path
 * vertices.
 *
 * <p><b>Occlusion arrives as a lookup, not as a ray.</b> The ambient source is no longer constant along
 * the segment: it is scaled by the visibility grid, which is sampled at a few points over the stretch the
 * grid covers and left at 1 everywhere else. Still no rays here — the rays were spent once per world-space
 * cell in volume_visibility.comp, which is the whole reason a grid was built instead of a shadow ray being
 * added to this function. See that shader for what the three cheaper alternatives were and why each fails.
 *
 * <p>The transmittance is untouched by any of it and is still one closed form over the whole window. What
 * a segment HIDES was never in question.
 */
/**
 * Phase function for an enclosed medium: mostly forward Mie, with a small Rayleigh component.
 *
 * The Rayleigh term is what makes water go blue with depth rather than merely darker — short wavelengths
 * keep being redirected while long ones are absorbed, so the light that reaches the eye from deep water
 * has been turned around more times AND is bluer for it.
 *
 * The structure is HPWater's; the constants are not, and must not be. Its HenyeyPhase omits the 1/(4 PI)
 * normalisation, so every parameter in that project is tuned around a factor of ~4 PI of energy error —
 * transplanting its numbers onto a normalised phase function comes out about twelve times too bright.
 * See R20. math.slang's hg() is normalised, so this is a straight convex combination.
 */
public static const float ENCLOSED_RAYLEIGH_FRACTION = 0.05;

/**
 * Effective anisotropy after the scattering the single-scattering integral does not model.
 *
 * A strongly forward phase function is right for ONE scattering event. Light that reaches the eye from
 * deep in a highly scattering medium has been redirected many times, and each event multiplies the mean
 * cosine of the distribution by g — so after n events the surviving directionality is g^n. A medium with
 * single-scattering albedo w scatters on average 1/(1-w) times before absorption ends the walk, which
 * gives an effective anisotropy of
 *
 *   g_eff = g^(1/(1 - w))
 *
 * and that is the whole term: one pow, no marching, no new rays. It falls out of the same similarity
 * argument that underlies reduced scattering coefficients, so it costs nothing to justify either.
 *
 * What it fixes is the look the water had while the albedo sat near 1: a medium that scatters almost
 * everything and yet keeps a sharp forward lobe reads as a glossy sheet rather than as depth, because
 * every ray leaving a point agrees about where the light came from. At w = 0.95 this drives g = 0.75
 * down to 0.75^20, which is 0.003 — isotropic, which is what a nearly conservative medium actually is.
 *
 * HPWater blends toward isotropic on the same reasoning and reaches it through a fitted smoothstep on
 * the albedo; this is derived instead, and shares no constants with it.
 */
float effectiveAnisotropy(float g, float3 scatterAlbedo) {
    float w = clamp(luminance(scatterAlbedo), 0.0, 0.98);
    return sign(g) * pow(abs(g), 1.0 / (1.0 - w));
}

public float enclosedPhase(float cosT, float g, float3 scatterAlbedo) {
    float rayleigh = (3.0 / (16.0 * PI)) * (1.0 + cosT * cosT);
    return lerp(hg(cosT, effectiveAnisotropy(g, scatterAlbedo)), rayleigh, ENCLOSED_RAYLEIGH_FRACTION);
}

/**
 * integral of exp(-sigma_t * max(d0 - descentPerT * t, 0) * pathScale) * exp(-sigma_t * t) dt over [0, T].
 *
 * Split out because the sun and the sky differ only in how long their path to a given depth is: the sun
 * arrives at an angle and travels depth / sin(elevation), the sky arrives overhead and travels depth.
 * Same closed form, one parameter apart. `descentPerT` is how fast DEPTH falls per unit t, which is the
 * ray's own rd.y for both of them; the light's slant is entirely in `pathScale`.
 *
 * The `max(..., 0)` on depth is the whole reason this has two pieces. Without it a climbing ray's depth
 * goes negative past the surface and exp(-sigma d) silently becomes exp(+sigma |d|), amplifying the
 * source without bound. That was previously headed off by clamping the exponent `a` to zero instead —
 * which stopped the growth but replaced it with something worse and quieter: a clamped `a` is exactly
 * zero, the 0/0 branch then returns the SEGMENT LENGTH, and for a ray that hit nothing that length is
 * RAY_FAR = 10000. So the case meant to be capped became thousands of times the source instead. Clamping
 * the depth caps it at the cause and is also the more correct model: above the surface there is no water
 * left to attenuate the light, so it arrives whole and only the view path is still attenuating.
 */
float3 depthAttenuatedIntegral(float3 sigmaT, float d0, float descentPerT, float pathScale, float tEnd) {
    // Where the ray reaches depth 0. Only a climbing ray gets there; a level or descending one never
    // does, and its whole segment takes the submerged branch.
    float tSurface = descentPerT > 1.0e-6 ? d0 / descentPerT : 1.0e30;
    float tSub = clamp(tSurface, 0.0, tEnd);

    // Submerged stretch. Both exponents are linear in t, so the product stays closed form. `a` may be
    // positive here (climbing faster than the light descends) but only over [0, tSurface], where the
    // growth is exactly cancelled by `head` — at t = tSurface the two collapse to exp(-sigma_t*tSurface),
    // the plain view attenuation, which is also where the second piece picks up. Continuous, and bounded.
    float3 head = exp(-sigmaT * (d0 * pathScale));
    float3 a = sigmaT * (descentPerT * pathScale - 1.0);
    float3 span;
    // a -> 0 is a ray climbing at exactly the rate the light descends, where the exponential is flat and
    // the closed form is 0/0. The limit is the length of the stretch.
    [unroll]
    for (int c = 0; c < 3; ++c) {
        float ac = a[c];
        span[c] = abs(ac) < 1.0e-5 ? tSub : (exp(ac * tSub) - 1.0) / ac;
    }
    float3 total = head * span;

    // Above the surface: source unattenuated, view path still attenuating.
    if (tEnd > tSub) {
        float3 safeSigma = max(sigmaT, float3(1.0e-6, 1.0e-6, 1.0e-6));
        total += (exp(-safeSigma * tSub) - exp(-safeSigma * tEnd)) / safeSigma;
    }
    return total;
}

// Sun-visibility strata for the enclosed single-scattering estimator: shadow rays at view-path optical
// depths 0, TAU_STEP, 2*TAU_STEP. Three cover 1 - exp(-3.75) = 97.6% of the integrand's mass; strata
// beyond the segment's end cast nothing, so only long segments through murky water pay all three.
public static const int WATER_SUN_VIS_STRATA = 3;
public static const float WATER_SUN_VIS_TAU_STEP = 1.25;

/**
 * How open to the sky the water at `p` is, from the coarse CPU-built grid: 0 sealed cave, 1 open ocean.
 *
 * The value is vanilla's own sky light, averaged over the water blocks of an 8-block cell (see
 * RtSkyLightGrid). Two properties make that the right quantity for the ambient source and are worth
 * naming because neither is obvious:
 *
 *   - Sky light is a property of the COLUMN above a point, not of the sun's direction. A hill's shadow
 *     leaves it at 15 while a cave roof zeroes it — which is exactly the split the ambient term needs,
 *     and exactly the one its shadowless constant source could not express: any constant is
 *     simultaneously too bright for the cave and too dark for the shadowed pond.
 *
 *   - Vanilla attenuates sky light through water block by block, so it fades over ~15 blocks of depth
 *     on its own. That overlaps with the analytic exp(-sigma*depth) this term already carries, and the
 *     overlap is ACCEPTED deliberately rather than corrected for: the doubled falloff errs darker with
 *     depth, deep water reading dark is the behaviour the murk rework was asked for, and the correction
 *     (dividing one falloff back out) would put a division between two nearly-zero quantities in every
 *     deep pixel.
 *
 * Trilinear over the byte cells: the grid is 8-block-coarse, and nearest sampling would draw its cell
 * boundaries as visible brightness steps in the water — this whole line of work exists because a step
 * where a gradient belongs reads as a bug.
 */
float skyLightCell(int3 c, int3 dims) {
    c = clamp(c, int3(0, 0, 0), dims - int3(1, 1, 1));
    uint idx = (uint(c.z) * uint(dims.y) + uint(c.y)) * uint(dims.x) + uint(c.x);
    uint word = ConstPtr<uint>(pc.skyLightAddr)[idx >> 2];
    return float((word >> ((idx & 3u) * 8u)) & 0xFFu) * (1.0 / 255.0);
}

public float skyLightFactor(float3 p) {
    if (pc.skyLightAddr == 0) {
        return 1.0; // no grid published (menu worlds, first frames): keep the constant-source behaviour
    }
    int3 dims = worldPush.skyLightDims.xyz;
    float3 g = (p - worldPush.skyLightOrigin.xyz) / max(worldPush.skyLightOrigin.w, 1.0e-3)
            - float3(0.5, 0.5, 0.5);
    float3 fl = floor(g);
    float3 f = g - fl;
    int3 base = int3(fl);
    float c00 = lerp(skyLightCell(base, dims),
                     skyLightCell(base + int3(1, 0, 0), dims), f.x);
    float c10 = lerp(skyLightCell(base + int3(0, 1, 0), dims),
                     skyLightCell(base + int3(1, 1, 0), dims), f.x);
    float c01 = lerp(skyLightCell(base + int3(0, 0, 1), dims),
                     skyLightCell(base + int3(1, 0, 1), dims), f.x);
    float c11 = lerp(skyLightCell(base + int3(0, 1, 1), dims),
                     skyLightCell(base + int3(1, 1, 1), dims), f.x);
    return lerp(lerp(c00, c10, f.y), lerp(c01, c11, f.y), f.z);
}

/**
 * Single scattering inside an enclosed medium, with the source attenuated by the medium above it.
 *
 * The attenuation of the SOURCE is the whole difference between a volume and a coat of paint. Integrate
 * a constant source and every ray converges to albedo * source, which lands on each underwater surface
 * as one flat pale wash — the light does not get dimmer as it goes down, so nothing reads as depth. What
 * makes water look like water is that a photon arriving at a point three metres down has already
 * crossed three metres of water to get there.
 *
 * So the source carries exp(-sigma_t * depth / sin(elevation)) — the light's own path from the surface
 * down to wherever it scatters — and because that depth varies linearly along the ray, the whole
 * integral stays closed form. For a segment of length T starting at depth d0:
 *
 *   I = albedo * sigma_t * S * exp(-sigma_t * d0 / sunY) * (exp(a T) - 1) / a
 *   a = sigma_t * (rd.y / sunY - 1)
 *
 * where the two exponentials inside are the light's path down and the view path back out. `a` is
 * negative for anything but a ray climbing faster than the light descends, so the term saturates rather
 * than growing — deep water reaches a bounded glow instead of an unbounded one.
 *
 * This is HPWater's structure — analytic single scattering, spectral sigma, forward phase — with the
 * light attenuation that makes it volumetric. None of its constants: see enclosedPhase and R20.
 *
 * The reference surface is the camera's own water body. A different pool at a different height gets the
 * wrong depth, which is a real limitation and a cheap one to accept: the body the camera is in is the
 * one filling the screen. When the camera is not in water at all, the segment's own start is the
 * surface crossing, which is exactly right for looking down into a pond.
 */
float3 enclosedSingleScatter(Medium medium, float3 albedo, float3 ro, float3 rd, float tEnd,
                             inout uint seed) {
    float3 sigmaT = medium.extinction;
    float surfaceY = worldPush.waterAnchor.w;
    // Submerged: depth is measured from the pushed surface. Otherwise this segment began where the ray
    // crossed into the water, so it starts at the surface by construction.
    float d0 = surfaceY > -1.0e8 ? max(0.0, surfaceY - ro.y) : 0.0;

    float3 total = float3(0.0, 0.0, 0.0);

    // Which sources are live. A measurement switch (worldPush.flags bits 13-14, Rt.Water.SCATTER_SOURCE):
    // the two terms have independent occlusion models, so isolating one is the only way to attribute a
    // misbehaviour to it rather than to their sum.
    uint scatterSource = (worldPush.flags >> 13u) & 3u;
    bool sunSourceOn = scatterSource == 0u || scatterSource == 1u;
    bool skySourceOn = scatterSource == 0u || scatterSource == 2u;

    // The sun, reaching depth d after travelling d / cos(refracted zenith angle) through the water.
    //
    // NOT d / sin(elevation). That is the path through AIR, and light entering water bends toward the
    // vertical: Snell with n = 1.333 maps the whole sky onto a cone of half-angle 48.6 degrees inside
    // the water (the same critical angle that makes Snell's window), so the in-water path is at most
    // 1/cos(48.6) = 1.51 times the depth NO MATTER how low the sun sits. The air form is unbounded — at
    // sin(elevation) = 0.2 it claimed 5x the depth against a true 1.48x, and it diverges at sunset — so
    // the sun term died within a block or two of the surface and left a bright band hugging the surface
    // with darkness immediately under it. A bounded path is what turns that edge into a falloff.
    float sunY = worldPush.lightDir.y;
    if (sunSourceOn && sunY > 1.0e-3) {
        float sinAir = sqrt(max(0.0, 1.0 - sunY * sunY));
        float sinWater = sinAir / WATER_IOR;
        // The 1e-4 floor is unreachable given the critical angle bounds cosWater below at ~0.66; it is
        // there so a future IOR change cannot turn this into a division by zero silently.
        float cosWater = sqrt(max(1.0e-4, 1.0 - sinWater * sinWater));
        float sunPathScale = 1.0 / cosWater;
        // The PHASE still uses the unrefracted direction. The scattering angle ought to be measured
        // against the refracted beam too, but that would move the underwater sun halo, which currently
        // reads correctly — a separate change to make deliberately rather than as a side effect of
        // fixing the path length.
        float cosT = dot(rd, worldPush.lightDir.xyz);
        // rd.y, not rd.y/sunY. `descentPerT` is how fast the ray's DEPTH falls per unit t, which is the
        // ray's own vertical component and has nothing to do with where the sun is; the sun's slant is
        // `pathScale`, and depthAttenuatedIntegral already multiplies the two. Passing rd.y/sunY divided
        // by sin(elevation) twice, so a low sun squared its own slant. The sky term below always passed
        // rd.y; the two never agreed.
        float3 phaseGain = worldPush.lightRadiance.xyz
                * (4.0 * PI * enclosedPhase(cosT, worldPush.waterScatter.w, albedo));

        // Shadow rays at FIXED OPTICAL DEPTHS along the segment, weighting each stratum by exactly the
        // share of the closed-form integral it covers — F(edge[i+1]) - F(edge[i]) with F the same
        // depthAttenuatedIntegral the one-ray version used, so the total is unchanged where visibility
        // is uniform and the estimator stays exact, not an approximation of the old one.
        //
        // Why more than one ray: a single ray from the segment origin makes sun visibility ONE value for
        // the whole segment — and for the camera segment, one value for the whole screen. Any occlusion
        // boundary the camera crosses then flips the entire screen's scattering at once, which is
        // exactly what was observed: swimming above/below one precise height toggled the murk
        // everywhere, in every direction, as a step. Occlusion now varies along each ray, so it varies
        // across the screen, and the camera crossing a shadow boundary moves through it in stages.
        //
        // Why the strata sit at fixed optical depths (tau = 0, 1.25, 2.5 along the view path) rather
        // than at fractions of the segment: fractions of tEnd would make the sample POSITIONS depend on
        // segment length, and segment length is whatever the ray happened to hit — neighbours
        // disagreeing that way is what made the fog's sun halo blotchy, and is why the one-ray version
        // deliberately cast from the origin. Fixed depths keep neighbouring pixels sampling the same
        // world locations. The first stratum's ray still leaves from `ro` itself, preserving the old
        // behaviour as the first term. Strata past the segment's end carry zero weight and cast no ray,
        // so short segments still pay one ray; only long segments through murky water pay all three.
        //
        // Still CULL_SECONDARY_NO_SELF: the first ray's origin IS the camera for the camera segment,
        // which sits inside the first-person head mesh, and this remains the only shadow ray in the
        // renderer cast from the eye.
        float sigmaBar = max(luminance(sigmaT), 1.0e-4);
        float3 sunTerm = float3(0.0, 0.0, 0.0);
        float3 integralToPrevEdge = float3(0.0, 0.0, 0.0);
        for (int s = 0; s < WATER_SUN_VIS_STRATA; ++s) {
            float edgeNear = (WATER_SUN_VIS_TAU_STEP * float(s)) / sigmaBar;
            if (edgeNear >= tEnd) {
                break;
            }
            float edgeFar = s == WATER_SUN_VIS_STRATA - 1
                    ? tEnd
                    : min((WATER_SUN_VIS_TAU_STEP * float(s + 1)) / sigmaBar, tEnd);
            float3 integralToFarEdge = depthAttenuatedIntegral(sigmaT, d0, rd.y, sunPathScale, edgeFar);
            float3 weight = max(integralToFarEdge - integralToPrevEdge, float3(0.0, 0.0, 0.0));
            integralToPrevEdge = integralToFarEdge;
            if (all(weight <= 1.0e-8)) {
                continue;
            }
            // Bit 15: skip the shadow ray and take the sun as unoccluded. Splits this term into its two
            // moving parts — occlusion, and the depth integral weighting it — so which one misbehaves is
            // measured rather than argued.
            // JITTERED inside the stratum, not pinned to its near edge.
            //
            // The edge was deterministic, so every frame asked the same question at the same place and got
            // the same binary answer; walking an occlusion boundary past it flipped a whole stratum at
            // once, and that is what read as the water's scattering flickering with movement. Confirmed
            // not to be the fog before this was touched: silencing BOTH of the fog's in-scatter machines
            // (SEGMENT_SOURCE = none) left the flicker exactly as it was, and water media are never
            // ambient so they never sample the visibility grid at all.
            //
            // This does NOT undo the reason the edges were fixed. That reason was that sample positions
            // must not depend on SEGMENT LENGTH -- a ray stopping at 30 blocks and its neighbour running
            // to the cull distance would sample different world locations, which came out as blotches
            // correlated with the geometry. A jitter depends on a random number instead, so it produces
            // uncorrelated noise rather than structure, and noise is what the denoiser is for.
            //
            // Same shape M13.3 measured and shipped for the fog's sun term (see sunInScatterStochastic),
            // including its falsification test: with the denoiser off this must look NOISY, not blocky.
            // The stratum weights were already exact, so this changes where the question is asked and
            // nothing about what the answer is worth.
            float3 vis = (worldPush.flags & 32768u) != 0u
                    ? float3(1.0, 1.0, 1.0)
                    : visibilityMasked(CULL_SECONDARY_NO_SELF,
                                       ro + rd * lerp(edgeNear, edgeFar, rndf(seed)),
                                       worldPush.lightDir.xyz, RAY_FAR).transmittance;
            sunTerm += vis * weight;
        }
        total += phaseGain * sunTerm;
    }

    // Sky light, arriving from overhead, so its path to depth d is d. The water's own source, not the
    // fog's (see worldPush.waterAmbient) — and gated by the sky-light grid, which is what makes an
    // underwater cave dark while shadowed surface water keeps its glow: vanilla sky light is a property
    // of the column above a point, not of the sun's direction, so a hill's shadow does not remove it but
    // a cave roof does. That split is exactly the one a constant source could not express.
    if (skySourceOn) {
        total += worldPush.waterAmbient.xyz * skyLightFactor(ro)
                * depthAttenuatedIntegral(sigmaT, d0, rd.y, 1.0, tEnd);
    }

    return albedo * sigmaT * total;
}

public SegmentIntegral integrateSegment(MediumStack stack, float3 ro, float3 rd, float tEnd,
                                        uint seed) {
    SegmentIntegral seg;
    seg.transmittance = float3(1.0, 1.0, 1.0);
    seg.inScatter = float3(0.0, 0.0, 0.0);

    // Enclosed medium: homogeneous over the segment. Air's extinction is zero, so the ordinary case
    // costs one comparison.
    if (any(stack.current.extinction > 0.0)) {
        float3 enclosedT = exp(-stack.current.extinction * tEnd);
        seg.transmittance = enclosedT;

        float3 scatterAlbedo = mediumScatterAlbedo(stack.current);
        if (any(scatterAlbedo > 0.0)) {
            // Its own rehash of the caller's seed, distinct from the fog's below, so the water's
            // stratum jitter and the fog's cannot draw the same numbers on the same pixel.
            uint waterSeed = seed ^ 0x85EBCA6Bu;
            waterSeed = pcg(waterSeed);
            seg.inScatter += enclosedSingleScatter(stack.current, scatterAlbedo, ro, rd, tEnd,
                                                   waterSeed);
        }
    }

    // A path inside water is inside water — not inside water *and* the world's fog.
    if (stack.current.ambient) {
        float2 window = ambientWindow(tEnd);
        float tau = ambientHeightIntegral(ro, rd, window.x, window.y);
        if (tau > 0.0) {
            float3 fogT = exp(-worldPush.fogExtinction.xyz * tau);
            seg.transmittance *= fogT;
            // The sunlight lighting the fog has itself crossed the fog to get here. Without that term
            // a low sun grew a halo that brightened with exactly the depth that ought to be
            // extinguishing it.
            //
            // Measured from the segment's ORIGIN, not from a point inside it. Sampling the midpoint made
            // this depend on segment length, and segment length is whatever the ray happened to hit: a
            // ray stopping at 30 blocks and its neighbour escaping to the cull distance sampled the sun's
            // transmittance an order of magnitude apart, so the halo came out blotchy across neighbouring
            // pixels and unstable as the camera moved. Every ray leaving one point now agrees.
            float3 sunTransmittance = ambientTransmittance(ro, worldPush.lightDir.xyz, RAY_FAR);
            // 4*PI undoes hg()'s sphere normalisation: the phase function is a density over directions,
            // and what is wanted here is its gain relative to isotropic.
            // The source, split into the two halves the visibility grid answers for SEPARATELY.
            //
            // The sun's lobe is gated by whether the sun is reachable. The other two are gated by whether
            // the SKY is, which is a different question and deliberately so: a hillside's shadow must not
            // drain the ambient light out of the fog standing in it, because what removes ambient light is
            // a roof, not a hill. The froxel splits its source the same way, and the two agreeing matters
            // — they are added together on screen, so a disagreement between them would show up as a seam
            // at whatever depth the camera's prefix segment happens to end.
            //
            // <b>Character for character the froxel's source</b> (sky_froxel.comp.slang), and that is the
            // point rather than a coincidence: the two are ADDED on screen, one covering the camera's
            // prefix segment and the other covering every bounce, so any difference between them appears
            // as a seam at whatever depth the primary ray happened to stop.
            //
            // The ad-hoc "extinguished light returns as glow" term that used to sit here is gone. It
            // returned 0.35x the sun's RADIANCE isotropically and grew with density, so at density 10 it
            // reached 0.35x while the sun's own sideways lobe was 0.16x — a flat wash twice as bright as
            // the thing it was supposed to be softening, which is why the light shaft vanished from the
            // side and the water blew out. What replaces it is the multiple-scattering table, which is
            // the same quantity computed rather than guessed.
            //
            // Named limitation, so it is not rediscovered as a bug: that table is the ATMOSPHERE's
            // multiple scattering, over kilometres. The local fog's own multiple scattering — what makes
            // real pea soup glow white — is not modelled by anything here now. A single-scattering
            // renderer cannot have it, and a constant standing in for it is what this change removed.
            float3 sunSource = worldPush.lightRadiance.xyz * sunTransmittance
                    * (4.0 * PI * evalPhase(dot(rd, worldPush.lightDir.xyz), worldPush.fogScatter.w));
            float atmosR = PLANET_R + ATMOS_VIEW_ALTITUDE;
            float3 skySource = worldPush.fogAmbient.xyz * sunTransmittance
                    + sampleMultiScatter(atmosR, clamp(worldPush.lightDir.y, -1.0, 1.0)) * SUN_INTENSITY;
            // Bit 16 (Rt.Volumetrics.SEGMENT_SOURCE): silence the fog in-scatter on MARCHED segments.
            //
            // Gating here rather than at the call sites is what makes the switch mean exactly "every
            // segment the froxel does not cover". The camera prefix takes the froxel whenever this branch
            // would have run at all — the one case it does not, a submerged camera, has ambient false and
            // never reaches this line — so the segments left running it are precisely the bounces.
            //
            // Only the emission moves. seg.transmittance was set above and stays, so this separates "the
            // fog is too bright" from "the fog is too thick" instead of confusing the two.
            // Bits 23-25: how many jittered shadow rays the SUN term gets. Zero keeps it on the grid.
            // The sky term stays on the grid either way — it is genuinely low frequency (a room is dark,
            // a hillside is not) and needs no edge, which is the whole reason the two are split.
            uint sunRays = (worldPush.flags >> 23u) & 7u;
            float3 gridSunSource = sunRays > 0u ? float3(0.0, 0.0, 0.0) : sunSource;
            if (((worldPush.flags >> 16u) & 1u) == 0u) {
                // Three regions, because the grid has a boundary and pretending otherwise is what puts a
                // shell around the camera: the stretch before the ray enters the grid, the stretch inside
                // it where visibility is sampled, and the stretch beyond. The two outer ones run the
                // closed form with visibility 1 — exactly what this line did before the grid existed —
                // and the inner one is the only place anything new happens.
                float2 span = visGridSpan(ro, rd);
                float tEnter = clamp(span.x, window.x, window.y);
                float tExit = clamp(max(span.y, span.x), tEnter, window.y);

                AmbientAccum acc;
                acc.inScatter = float3(0.0, 0.0, 0.0);
                acc.transmittance = float3(1.0, 1.0, 1.0);
                ambientStep(acc, ro, rd, window.x, tEnter, gridSunSource, skySource, float2(1.0, 1.0));
                // Guarded, not just relying on ambientStep's own zero-length early-out: the SAMPLE happens
                // before that check, so a segment that never touches the grid would still pay six 3D
                // texture fetches to be told six times that it is outside. Most bounce segments in an open
                // scene are exactly that.
                if (tExit > tEnter) {
                    int steps = ambientVisSteps(tExit - tEnter, worldPush.visGridOrigin.w);
                    for (int i = 0; i < steps; ++i) {
                        float tA = lerp(tEnter, tExit, float(i) / float(steps));
                        float tB = lerp(tEnter, tExit, float(i + 1) / float(steps));
                        // Sampled at the sub-step's MIDPOINT, so the value describes the stretch it weights
                        // rather than one of its ends. Sampling an end biases the whole segment toward
                        // whichever side of a shadow boundary that end happened to fall on, which is the
                        // one-coin-flip failure this grid exists to avoid — at a smaller scale, but the same
                        // shape.
                        float2 vis = sampleVolumeVisibility(ro + rd * ((tA + tB) * 0.5));
                        ambientStep(acc, ro, rd, tA, tB, gridSunSource, skySource, vis);
                    }
                }
                ambientStep(acc, ro, rd, tExit, window.y, gridSunSource, skySource, float2(1.0, 1.0));
                seg.inScatter = acc.inScatter;
                if (sunRays > 0u) {
                    // Taken BY VALUE and rehashed rather than advanced in place. The callers include the
                    // camera prefix and the debug views, which have no path RNG stream to advance, and
                    // threading an inout through them would make every future caller carry one. The
                    // rehash is what keeps this sample from correlating with whatever the path draws next
                    // from the same seed.
                    uint fogSeed = seed ^ 0x9E3779B9u; // pcg takes an inout, so it needs a variable
                    fogSeed = pcg(fogSeed);
                    seg.inScatter += sunInScatterStochastic(ro, rd, window, sunSource,
                                                            int(sunRays), fogSeed);
                }
            }
        }
    }
    return seg;
}
