// Participating media along a segment: what the path picks up between two hits rather than at them.
// Depends on math, medium, trace, world_common and world_core.
//
// Two kinds of medium, kept deliberately apart:
//
//   ENCLOSED — water, glass, ice. Entered through geometry, pushed and popped on the MediumStack, and
//   homogeneous inside. The stack knows about these.
//
//   AMBIENT — the world's air: analytic height fog or its mean-one heterogeneous form. Unbounded, with no
//   geometry to enter, and a pure function of world position and the dimension's preset. **These never
//   go on the stack.** That is the decision the rest of this file rests on: the stack keeps its
//   named-field, depth-2, no-dynamic-indexing shape (see medium.slang for why that matters in a
//   register-bound raygen), and an unbounded field that every path is inside at all times then costs
//   zero per-path state. Whether a path is in open air is the explicit MEDIUM_FLAG_AMBIENT bit, never
//   inferred from ior == 1 && extinction == 0.

import math;
import medium;
import trace;
import atmosphere_lut;
import environment;
import volume_source;
import volume_visibility;
import volume_hash;
import world_common;
import world_core;

// M13 packed base/detail density field. Binding 19 deliberately follows the long-lived water binding 18
// so adding heterogeneous fog does not renumber a descriptor read by closest-hit.
[[vk::binding(19, 0)]] Sampler3D fogNoise;

// ---- Sampling primitives.
//
// Neither is used by either ambient-fog estimator below. They are here because the random-walk
// subsurface work needs exactly these two and
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

// ---- The ambient field.
//
// fogParams     x density at the reference height, y height scale, z reference height, w cull distance
// fogExtinction xyz per-channel extinction tint, w near cutoff in blocks
// fogScatter    xyz single-scattering albedo, w Henyey-Greenstein g for the sun lobe
// mediumSkyRadiance xyz shared phase-integrated sky-view source for air and enclosed media

/** CPU resolves the structure switch to contrast zero: exact analytic fog, before any texture fetch. */
bool ambientNoiseOn() {
    return volumeFogNoiseOn(worldPush.fogParams, worldPush.fogAmbient);
}

float ambientNoiseMultiplier(float3 p, float footprint) {
    if (!ambientNoiseOn()) {
        return 1.0;
    }
    float2 packed = fogNoise.SampleLevel(
            volumeFogNoiseUv(worldPush.fogNoiseOrigin, p), 0.0).rg;
    return volumeFogNoiseMultiplier(packed, worldPush.fogAmbient,
                                    worldPush.fogNoiseOrigin.w, footprint);
}

/** Debug 25 uses the exact final multiplier function consumed by normal transport. */
public float ambientNoiseDebugMultiplier(float4 sample) {
    return volumeFogNoiseResolvedMultiplier(sample.xy, sample.zw, worldPush.fogAmbient);
}

float ambientNoiseDensity(float3 p, float footprint) {
    return volumeFogHeightDensity(worldPush.fogParams, p)
            * ambientNoiseMultiplier(p, footprint);
}

/** Numerical optical depth for a standalone ray such as a sun or emitter path. */
float ambientNoiseOpticalDepth(float3 ro, float3 rd, float start, float end) {
    float span = end - start;
    if (span <= 0.0 || worldPush.fogParams.x <= 0.0) {
        return 0.0;
    }
    int steps = volumeFogMarchSteps(span, worldPush.fogAmbient,
                                    worldPush.fogNoiseOrigin.w);
    float dt = span / float(max(steps, 1));
    float tau = 0.0;
    [loop]
    for (int i = 0; i < steps; ++i) {
        float t = start + (float(i) + 0.5) * dt;
        tau += ambientNoiseDensity(ro + rd * t, dt) * dt;
    }
    return tau;
}

/**
 * Optical depth of the ambient medium over [start, end] along a ray, before the per-channel tint.
 *
 * `start` is the near cutoff: fog does not begin at the eye, so a window of clear air in front of the
 * camera keeps the near field readable and stops the whole screen from being veiled at ground level.
 */
public float ambientHeightIntegral(float3 ro, float3 rd, float start, float end) {
    return volumeAmbientOpticalDepth(worldPush.fogParams, ro, rd, start, end);
}

/** The [start, end] window a segment of length `tEnd` actually accumulates fog over. */
public float2 ambientWindow(float tEnd) {
    float start = min(worldPush.fogExtinction.w, tEnd);
    float end = min(tEnd, worldPush.fogParams.w);
    return float2(start, max(start, end));
}

/**
 * Transmittance of the ambient medium over a segment, at a caller-chosen extinction.
 *
 * <p>Split out because the fog's SOURCE and the fog's VIEW path decay at different rates once multiple
 * scattering is accounted for — see diffuseAttenuation. Everything that asks "what does this ray lose on
 * its way here" wants the full extinction and should call ambientTransmittance; only the sun's own path
 * to a scattering point wants the diffusion rate.
 */
public float3 ambientTransmittanceWith(float3 sigma, float3 ro, float3 rd, float dist) {
    if (ambientNoiseOn()) {
        float start = min(worldPush.fogExtinction.w, dist);
        float end = min(dist, worldPush.fogParams.w);
        float tau = ambientNoiseOpticalDepth(ro, rd, start, max(start, end));
        return tau > 0.0 ? exp(-sigma * tau) : float3(1.0, 1.0, 1.0);
    }
    return volumeAmbientTransmittance(worldPush.fogParams, worldPush.fogExtinction,
                                      sigma, ro, rd, dist);
}

/** Transmittance of the ambient medium over a segment. Shadow rays go through visibilityThroughAmbient. */
public float3 ambientTransmittance(float3 ro, float3 rd, float dist) {
    return ambientTransmittanceWith(worldPush.fogExtinction.xyz, ro, rd, dist);
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
public VisibilityResult visibilityThroughAmbient(float3 mediumExtinction, uint mediumFlags,
                                                  float3 origin, float3 dir, float tmax) {
    VisibilityResult result = visibility(origin, dir, tmax);
    if (mediumFlagsAmbient(mediumFlags)) {
        result.transmittance *= ambientTransmittance(origin, dir, tmax);
    }
    return result;
}

/**
 * Fill one cell: draw candidates over the upper hemisphere, measure each with a shadow ray, resample.
 *
 * <p>ONE THREAD PER CELL PER FRAME, ELECTED WITH AN ATOMIC. The first wiring used a plain read of the
 * stamp followed by a write, and that enforced nothing: every thread landing in a cell reads the stamp
 * before any write lands, so all of them passed the check, all deposited, and all wrote. The rays became
 * per-sample -- voiding D198's economics entirely -- and the concurrent stores tore the 64-byte record,
 * which is what the screen was showing. A check-then-act is not a rule.
 *
 * <p>With the election in place the ray really is per cell per frame: 436k live cells against 5.8-11.5M
 * volume samples means 13-26 samples share every ray drawn here, which is the whole reason a shadow ray
 * in the target is affordable.
 *
 * <p>Writes THIS parity and seeds from the OTHER, so nothing reads a half being written.
 */
void volumeRestirDeposit(float3 p, float3 mediumExtinction, uint mediumFlags) {
    // THE CELL'S CONTENT MUST NOT DEPEND ON WHICH THREAD WON THE ELECTION, and the winner is arbitrary
    // and different every frame. Taking the caller's local sky source made the stored radiance jump
    // frame to frame -- flicker, from the same root as the seed, which was fixed one round earlier
    // without following the reasoning through to the data. The frame's medium sky radiance is the same
    // value for every thread, which is what makes the cell stable.
    //
    // The region is decided the same way the READ decides it, by one shared predicate, so a cell is
    // never written under one switch and looked for under the other.
    uint switchBit = volumeInsideVisibilityGrid(p)
            ? ENVIRONMENT_VOLUME_RESTIR_NEAR : ENVIRONMENT_VOLUME_RESTIR_FAR;
    float3 skyRadiance = worldPush.mediumSkyRadiance.xyz;
    if ((worldPush.environmentFlags & switchBit) == 0u || worldPush.volumeGridSlots == 0u
            || worldPush.volumeGridAddr == 0) {
        return;
    }
    uint slots = worldPush.volumeGridSlots;
    uint frame = worldPush.frameIndex;
    float dist = length(p - worldPush.camOffset);
    int level = volumeCellLevel(dist, worldPush.volumeGridLodK);
    int3 coord = volumeCellCoord(p, volumeCellEdge(level));
    DevicePtr<VolumeCell> grid = DevicePtr<VolumeCell>(worldPush.volumeGridAddr);
    ConstPtr<VolumeCell> gridRead = ConstPtr<VolumeCell>(worldPush.volumeGridAddr);

    uint writeBase = volumeHalfBase(slots, frame);
    int slot = volumeGridClaim(gridRead, slots, writeBase, coord, level, frame,
                               worldPush.volumeGridEvictionFrames);
    if (slot < 0) {
        return; // probe window full of live strangers; contributing nothing beats evicting one
    }
    // THE ELECTION. Exactly one thread sees a stamp other than this frame and proceeds; every other
    // thread that lands in this cell this frame loses the exchange and returns without tracing anything.
    uint previous;
    InterlockedExchange(grid[slot].ident.y, frame, previous);
    if (previous == frame) {
        return;
    }

    // Continue the cell from the OTHER half, which nothing is writing this frame. A cell with no history
    // there starts empty rather than inheriting whatever the slot held.
    uint readBase = volumeHalfBase(slots, frame + 1u);
    int prior = volumeGridFind(gridRead, slots, readBase, coord, level, frame,
                               worldPush.volumeGridEvictionFrames);
    VolumeCell c = prior >= 0 ? gridRead[prior] : volumeCellEmpty();

    // Seeded from the CELL, not the pixel: which thread won the election is arbitrary, so a pixel-derived
    // stream would make the cell's content depend on that arbitrary choice and shimmer between frames.
    uint seed = volumeCellKey(coord, level) ^ (frame * 2654435761u);
    uint candidates = max(worldPush.volumeGridCandidates, 1u);
    for (uint i = 0u; i < candidates; ++i) {
        // Uniform over the UPPER hemisphere. The lower half has no sky to find, and folding rather than
        // drawing over the whole sphere keeps the pdf honest at half the candidates.
        float u1 = rndf(seed);
        float u2 = rndf(seed);
        float r = sqrt(max(0.0, 1.0 - u1 * u1));
        float phi = 6.28318530718 * u2;
        float3 dir = float3(r * cos(phi), u1, r * sin(phi));
        VisibilityResult shadow = visibilityThroughAmbient(mediumExtinction, mediumFlags,
                                                           p, dir, RAY_FAR);
        volumeCellUpdate(c, dir, skyRadiance * shadow.transmittance, 0.15915494309, seed);
    }
    volumeCellClaimStamp(c, coord, level, frame);
    grid[slot] = c;
}


/** What a segment did to the path: what survives it, and what it added. */
public struct SegmentIntegral {
    public float3 transmittance;
    public float3 inScatter;
    // M17: the segment's sampled scattering event, for the estimators that cannot run here. Emitter NEE
    // needs the light grid, and lighting.slang imports THIS module -- the dependency runs one way only,
    // so the vertex is handed out and the caller, which sees both, does that half. Zero weight means no
    // event was sampled: the mode is off, or the segment scatters nothing.
    public float3 scatterPos;
    public float3 scatterWeight;
};

// ---- Shared volume sun-visibility sampler (M15.1).
//
// Both estimators ask the same question through this module: choose a jittered point in a stratum,
// trace toward that medium's sun direction with the same cull mask, and return both surface
// transmittance and the water distance carried by the shadow payload. The caller still owns the exact
// integral weight because homogeneous water and exponential-height fog have different analytic forms.
public static const float SUN_VIS_TAU_STEP = 1.25;

struct VolumeSunShadowSample {
    float t;
    float3 transmittance;
    float waterHitT;
};

/** Fixed-optical-depth stratum for a homogeneous segment. Empty once tNear reaches tEnd. */
float2 homogeneousSunVisStratum(int index, int cap, float sigmaBar, float tEnd) {
    float safeSigma = max(sigmaBar, 1.0e-4);
    float tNear = (SUN_VIS_TAU_STEP * float(index)) / safeSigma;
    if (tNear >= tEnd) {
        return float2(tEnd, tEnd);
    }
    float tFar = index == cap - 1
            ? tEnd
            : min((SUN_VIS_TAU_STEP * float(index + 1)) / safeSigma, tEnd);
    return float2(tNear, tFar);
}

/**
 * Distance whose scalar ambient optical depth from window.x is targetTau.
 *
 * The height integral is analytic, but its inverse has three cases around the saturated lower layer.
 * Eight bisection steps keep that case split out of the hot interface and locate the shadow sample to
 * < 1/256 of the window. This runs only for optional extra rays: a one-ray setting takes the whole
 * window directly and does no search.
 */
float ambientDistanceAtOpticalDepth(float3 ro, float3 rd, float2 window,
                                    float sigmaBar, float targetTau) {
    float lo = window.x;
    float hi = window.y;
    [unroll]
    for (int i = 0; i < 8; ++i) {
        float mid = (lo + hi) * 0.5;
        float tau = sigmaBar * ambientHeightIntegral(ro, rd, window.x, mid);
        if (tau < targetTau) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return (lo + hi) * 0.5;
}

/** Stateful schedule so one stratum's far boundary is reused as the next one's near boundary. */
struct AmbientSunVisSchedule {
    float sigmaBar;
    float totalBaseTau;
    float totalTau;
    float tNear;
};

AmbientSunVisSchedule makeAmbientSunVisSchedule(float3 ro, float3 rd, float2 window) {
    AmbientSunVisSchedule schedule;
    schedule.sigmaBar = max(luminance(worldPush.fogExtinction.xyz), 1.0e-4);
    schedule.totalBaseTau = ambientHeightIntegral(ro, rd, window.x, window.y);
    schedule.totalTau = schedule.sigmaBar * schedule.totalBaseTau;
    schedule.tNear = window.x;
    return schedule;
}

/** Next fixed-optical-depth stratum for the exponential-height ambient field. */
float2 nextAmbientSunVisStratum(int index, int cap, float3 ro, float3 rd, float2 window,
                                inout AmbientSunVisSchedule schedule) {
    float tauNear = SUN_VIS_TAU_STEP * float(index);
    if (tauNear >= schedule.totalTau) {
        return float2(window.y, window.y);
    }
    float tauFar = index == cap - 1
            ? schedule.totalTau
            : min(tauNear + SUN_VIS_TAU_STEP, schedule.totalTau);
    float tFar = tauFar >= schedule.totalTau
            ? window.y
            : ambientDistanceAtOpticalDepth(ro, rd, window, schedule.sigmaBar, tauFar);
    float2 stratum = float2(schedule.tNear, tFar);
    schedule.tNear = tFar;
    return stratum;
}

/**
 * One shared shadow-ray operation at a caller-selected position/direction. `useTransmittance=false`
 * still traces: enclosed water needs waterHitT even when its diagnostic switch disables occlusion.
 */
VolumeSunShadowSample traceVolumeSunShadow(float3 ro, float3 rd, float sampleT,
                                            float3 sunDir, bool useTransmittance) {
    VolumeSunShadowSample sample;
    sample.t = sampleT;
    if (worldPush.skyProvider != SKY_PROVIDER_ATMOSPHERE) {
        sample.transmittance = float3(1.0, 1.0, 1.0);
        sample.waterHitT = -1.0;
        return sample;
    }
    VisibilityResult shadow = visibilityMasked(CULL_SHADOW_NO_SELF, ro + rd * sample.t,
                                                 sunDir, RAY_FAR);
    sample.transmittance = useTransmittance
            ? shadow.transmittance
            : float3(1.0, 1.0, 1.0);
    sample.waterHitT = shadow.waterHitT;
    return sample;
}

// ---- The single scattering vertex (M17).
//
// One point per segment, drawn with probability proportional to sigma_t * T -- the density of scattering
// events along the ray. Where the closed form evaluates albedo * S * (1 - T) for a source it must assume
// CONSTANT, this evaluates S once at a point drawn from that same distribution, and the two agree in
// expectation exactly: with S constant and unoccluded, E[weight * S] = albedo * S * (1 - T). That
// identity is what gives the switch a meaning -- off must return the published picture, and on must
// change only what a constant source could never have expressed.
//
// Why a point at all, when the closed form is exact for what it models: a point can be ASKED things a
// segment cannot. Which emitters are nearby and in what direction, and how much sky reaches THIS depth
// rather than the depth the segment happened to start at, are functions of position, and a segment has
// no position to offer them.

/** Bit 27 (Rt.Volumetrics.SCATTER_VERTEX): sample one scattering vertex instead of assuming the source. */
bool scatterVertexOn() {
    return (worldPush.flags & (1u << 27u)) != 0u;
}

/** Bit 28: let block emitters light that event. Nested under bit 27, which supplies the event. */
public bool volumeEmitterNeeOn() {
    return (worldPush.flags & (1u << 28u)) != 0u;
}

public struct ScatterVertex {
    public float3 pos;
    // Multiply a source radiance evaluated at pos by this to get the segment's in-scatter from it.
    // Zero means the segment scatters nothing, which is also how every caller skips the estimator.
    public float3 weight;
};

ScatterVertex noScatterVertex() {
    ScatterVertex vertex;
    vertex.pos = float3(0.0, 0.0, 0.0);
    vertex.weight = float3(0.0, 0.0, 0.0);
    return vertex;
}

/**
 * Optical depth of the sampled event, drawn from the truncated exponential over [0, tauTotal].
 *
 * Truncated rather than open-ended because the segment ends: a draw past its end would describe an event
 * that happens somewhere this segment does not own. `survive` = 1 - exp(-tauTotal) is both the
 * normalisation and, once the spectral correction below cancels, the closed form's own (1 - T).
 */
float sampleScatterTau(float tauTotal, inout uint seed) {
    float survive = 1.0 - exp(-tauTotal);
    return -log(max(1.0 - rndf(seed) * survive, 1.0e-7));
}

/**
 * f/pdf for the sampled event, shared by both media.
 *
 * Both integrands become the same expression after changing variables to the density-integrated depth:
 * homogeneous water has density 1 so that depth IS distance, and the height field folds its profile into
 * exactly that integral. So one weight serves both and only the tau -> position mapping differs, which
 * is the same split the sun-shadow strata already use.
 *
 * The scalar sigmaBar drives the pdf while the RGB coefficients drive f, and the ratio between them IS
 * the spectral correction -- a strongly coloured medium stays unbiased without sampling three times. For
 * a grey medium the two cancel and this collapses to albedo * (1 - T), the closed form exactly.
 */
float3 scatterVertexWeight(float3 sigmaT, float3 albedo, float sigmaBar, float tauScalar, float tauTotal) {
    float survive = 1.0 - exp(-tauTotal);
    float tauBase = tauScalar / max(sigmaBar, 1.0e-4);
    float pdf = max(sigmaBar, 1.0e-4) * exp(-tauScalar);
    return albedo * sigmaT * exp(-sigmaT * tauBase) * (survive / max(pdf, 1.0e-12));
}

/** Scattering vertex of a homogeneous segment: tau maps to distance by a constant. */
ScatterVertex sampleHomogeneousScatterVertex(float3 sigmaT, float3 albedo, float3 ro, float3 rd,
                                             float tEnd, inout uint seed) {
    float sigmaBar = max(luminance(sigmaT), 1.0e-4);
    float tauTotal = sigmaBar * tEnd;
    if (tauTotal <= 1.0e-6) {
        return noScatterVertex();
    }
    float tauScalar = sampleScatterTau(tauTotal, seed);
    ScatterVertex vertex;
    vertex.pos = ro + rd * min(tauScalar / sigmaBar, tEnd);
    vertex.weight = scatterVertexWeight(sigmaT, albedo, sigmaBar, tauScalar, tauTotal);
    return vertex;
}

/**
 * Scattering vertex of the height field: tau maps to distance through the same bisection the strata use.
 *
 * The bisection is the only extra arithmetic the ambient case pays over the homogeneous one, and it runs
 * once per segment rather than once per stratum.
 */
ScatterVertex sampleAmbientScatterVertex(float3 sigmaT, float3 albedo, float3 ro, float3 rd,
                                         float2 window, float baseTau, inout uint seed) {
    float sigmaBar = max(luminance(sigmaT), 1.0e-4);
    float tauTotal = sigmaBar * baseTau;
    if (tauTotal <= 1.0e-6) {
        return noScatterVertex();
    }
    float tauScalar = sampleScatterTau(tauTotal, seed);
    ScatterVertex vertex;
    vertex.pos = ro + rd * ambientDistanceAtOpticalDepth(ro, rd, window, sigmaBar, tauScalar);
    vertex.weight = scatterVertexWeight(sigmaT, albedo, sigmaBar, tauScalar, tauTotal);
    return vertex;
}

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
                 float3 scatterAlbedo, float3 sunSource, float3 skySource, float2 vis) {
    if (tB <= tA) {
        return;
    }
    float tau = ambientHeightIntegral(ro, rd, tA, tB);
    if (tau <= 0.0) {
        return;
    }
    float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
    float3 source = sunSource * vis.x + skySource * vis.y;
    acc.inScatter += acc.transmittance * scatterAlbedo * source
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
 * This one jitters within each stratum from the path's own RNG, so the old coherent step becomes temporal
 * noise that DLSS-RR can reduce. It is deliberately still a stratified approximation, not an unbiased
 * Monte Carlo estimator: the sample is uniform in distance while the layer carries a closed-form optical
 * weight. A strict f/pdf correction belongs to M17 because it changes brightness and noise, not to this
 * neutral interface refactor. The useful falsification test survives: freeze temporal accumulation and
 * this must look NOISY, not blocky. Blocky means the jitter is not reaching it and this is M9 again.
 *
 * <p>Stratified in optical depth; each layer carries its exact unoccluded view-path weight. The visibility
 * sample stands in for that layer's weighted visibility, which is the named approximation above.
 */
float3 sampledAmbientSunSource(float3 ro, float3 viewDir, float3 lightDir,
                               float phaseG, float3 sunSigma) {
    if (worldPush.skyProvider != SKY_PROVIDER_ATMOSPHERE) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 sampledIrradiance = sampleProviderCelestialIrradiance(
            worldPush, undyedCelestialIrradiance, lightDir);
    float3 localFogT = ambientTransmittanceWith(sunSigma, ro, lightDir, RAY_FAR);
    return evaluateAmbientRadianceSource(
            viewDir, lightDir, phaseG, sampledIrradiance,
            localFogT, float3(0.0, 0.0, 0.0)).sun;
}

float3 sunInScatterStochastic(float3 ro, float3 rd, float2 window, float3 scatterAlbedo,
                               float phaseG, float3 sunSigma, int rays, inout uint seed) {
    float3 gathered = float3(0.0, 0.0, 0.0);
    float3 transmittance = float3(1.0, 1.0, 1.0);
    AmbientSunVisSchedule schedule = makeAmbientSunVisSchedule(ro, rd, window);
    for (int i = 0; i < rays; ++i) {
        float2 stratum = nextAmbientSunVisStratum(i, rays, ro, rd, window, schedule);
        float tA = stratum.x;
        float tB = stratum.y;
        if (tB <= tA) {
            break;
        }
        float tau = rays == 1
                ? schedule.totalBaseTau
                : ambientHeightIntegral(ro, rd, tA, tB);
        if (tau <= 0.0) {
            continue;
        }
        float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
        float sampleT = lerp(tA, tB, rndf(seed));
        SquareLightSample lightSample = sampleSquareLight(
                worldPush.lightDir.xyz, worldPush.celestial.xyz, worldPush.lightDir.w, seed);
        float3 lightDir = lightSample.direction;
        float3 sunSource = sampledAmbientSunSource(ro, rd, lightDir, phaseG, sunSigma);
        VolumeSunShadowSample shadow = traceVolumeSunShadow(
                ro, rd, sampleT, lightDir, true);
        gathered += transmittance * scatterAlbedo * sunSource * shadow.transmittance
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

// ---- M13 residual: one canonical numerical partition for a noisy ambient segment.
//
// The old height field is analytic and additive under arbitrary splits. Midpoint quadrature is not: if
// transmittance were integrated over the whole segment while visibility split in-scatter into different
// pieces, the two could sample different densities and violate the segment energy contract. This layout
// is therefore built once and reused by total tau, in-scatter and the M17 event inversion. The visibility
// box may add cells inside its own span, but both passes see the same three regions and the same samples.

struct NoisyAmbientLayout {
    float4 edge; // window start, visibility enter, visibility exit, window end
    int3 steps;  // before, inside, after
};

int noisyRegionSteps(float span, float nominalStep) {
    return span > 0.0 ? max(int(ceil(span / max(nominalStep, 1.0e-4))), 1) : 0;
}

NoisyAmbientLayout makeNoisyAmbientLayout(float3 ro, float3 rd, float2 window) {
    NoisyAmbientLayout layout;
    float totalSpan = max(window.y - window.x, 0.0);
    int baseSteps = volumeFogMarchSteps(totalSpan, worldPush.fogAmbient,
                                        worldPush.fogNoiseOrigin.w);
    float nominalStep = totalSpan / float(max(baseSteps, 1));

    float2 grid = visGridSpan(ro, rd);
    bool intersectsGrid = grid.y > grid.x;
    float enter = intersectsGrid ? clamp(grid.x, window.x, window.y) : window.x;
    float exit = intersectsGrid ? clamp(max(grid.y, grid.x), enter, window.y) : enter;
    layout.edge = float4(window.x, enter, exit, window.y);
    layout.steps.x = noisyRegionSteps(enter - window.x, nominalStep);
    layout.steps.y = noisyRegionSteps(exit - enter, nominalStep);
    if (exit > enter) {
        layout.steps.y = max(layout.steps.y,
                ambientVisSteps(exit - enter, worldPush.visGridOrigin.w));
    }
    layout.steps.z = noisyRegionSteps(window.y - exit, nominalStep);
    int cap = volumeFogMarchCap(worldPush.fogAmbient);
    layout.steps = min(layout.steps, int3(cap, cap, cap));
    return layout;
}

float noisyAmbientRegionTau(float3 ro, float3 rd, float a, float b, int steps) {
    if (steps <= 0 || b <= a) {
        return 0.0;
    }
    float dt = (b - a) / float(steps);
    float tau = 0.0;
    [loop]
    for (int i = 0; i < steps; ++i) {
        float t = a + (float(i) + 0.5) * dt;
        tau += ambientNoiseDensity(ro + rd * t, dt) * dt;
    }
    return tau;
}

float noisyAmbientLayoutTau(float3 ro, float3 rd, NoisyAmbientLayout layout) {
    return noisyAmbientRegionTau(ro, rd, layout.edge.x, layout.edge.y, layout.steps.x)
         + noisyAmbientRegionTau(ro, rd, layout.edge.y, layout.edge.z, layout.steps.y)
         + noisyAmbientRegionTau(ro, rd, layout.edge.z, layout.edge.w, layout.steps.z);
}

/** Find a density-integrated depth in one canonical region. */
bool noisyDistanceInRegion(float3 ro, float3 rd, float a, float b, int steps,
                           float targetBaseTau, inout float cumulative, out float distance) {
    distance = b;
    if (steps <= 0 || b <= a) {
        return false;
    }
    float dt = (b - a) / float(steps);
    [loop]
    for (int i = 0; i < steps; ++i) {
        float tA = a + float(i) * dt;
        float tMid = tA + dt * 0.5;
        float density = ambientNoiseDensity(ro + rd * tMid, dt);
        float cellTau = density * dt;
        if (targetBaseTau <= cumulative + cellTau && cellTau > 0.0) {
            distance = tA + clamp((targetBaseTau - cumulative) / density, 0.0, dt);
            return true;
        }
        cumulative += cellTau;
    }
    return false;
}

float noisyDistanceAtBaseTau(float3 ro, float3 rd, NoisyAmbientLayout layout,
                             float targetBaseTau) {
    float cumulative = 0.0;
    float distance;
    if (noisyDistanceInRegion(ro, rd, layout.edge.x, layout.edge.y, layout.steps.x,
                              targetBaseTau, cumulative, distance)) {
        return distance;
    }
    if (noisyDistanceInRegion(ro, rd, layout.edge.y, layout.edge.z, layout.steps.y,
                              targetBaseTau, cumulative, distance)) {
        return distance;
    }
    if (noisyDistanceInRegion(ro, rd, layout.edge.z, layout.edge.w, layout.steps.z,
                              targetBaseTau, cumulative, distance)) {
        return distance;
    }
    return layout.edge.w;
}

ScatterVertex sampleNoisyAmbientScatterVertex(float3 sigmaT, float3 albedo,
                                               float3 ro, float3 rd,
                                               NoisyAmbientLayout layout, float baseTau,
                                               inout uint seed) {
    float sigmaBar = max(luminance(sigmaT), 1.0e-4);
    float tauTotal = sigmaBar * baseTau;
    if (tauTotal <= 1.0e-6) {
        return noScatterVertex();
    }
    float tauScalar = sampleScatterTau(tauTotal, seed);
    ScatterVertex vertex;
    vertex.pos = ro + rd * noisyDistanceAtBaseTau(
            ro, rd, layout, tauScalar / sigmaBar);
    vertex.weight = scatterVertexWeight(sigmaT, albedo, sigmaBar, tauScalar, tauTotal);
    return vertex;
}

/** One region of the canonical view march; gridRegion changes visibility, never density sampling. */
void integrateNoisyAmbientRegion(inout AmbientAccum acc, float3 ro, float3 rd,
                                 float a, float b, int steps, bool gridRegion,
                                 float3 scatterAlbedo, float3 skySource,
                                 float phaseG, float3 sunSigma, uint sunRays,
                                 bool sourceOn) {
    if (steps <= 0 || b <= a) {
        return;
    }
    float dt = (b - a) / float(steps);
    [loop]
    for (int i = 0; i < steps; ++i) {
        float t = a + (float(i) + 0.5) * dt;
        float3 p = ro + rd * t;
        float tau = ambientNoiseDensity(p, dt) * dt;
        if (tau <= 0.0) {
            continue;
        }
        float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
        if (sourceOn) {
            VolumeVisibilitySample visSample;
            visSample.sun = 1.0;
            visSample.sky = 1.0;
            visSample.lightUv = float2(0.5, 0.5);
            if (gridRegion) {
                visSample = sampleVolumeLightVisibility(p);
            }
            volumeRestirDeposit(p, worldPush.fogExtinction.xyz, MEDIUM_FLAG_AMBIENT);

            // D199: with a live cell the sky term is directional, so the phase belongs here -- this is
            // the only place that knows rd. Without one this is verbatim the shipped line.
            float3 source = visSample.skyDirValid
                    ? visSample.skyRadiance * enclosedPhase(dot(rd, visSample.skyDir), phaseG, scatterAlbedo)
                    : skySource * visSample.sky;
            if (sunRays == 0u && worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE) {
                float3 lightDir = gridRegion
                        ? sampleSquareLightDirection(worldPush.lightDir.xyz,
                                worldPush.celestial.xyz, worldPush.lightDir.w,
                                visSample.lightUv)
                        : worldPush.lightDir.xyz;
                // Unlike the analytic constant-source adapter, a numerical step has a position. Use it:
                // dense pockets then attenuate the sunlight that reaches deeper pockets instead of only
                // modulating what the camera sees.
                source += sampledAmbientSunSource(p, rd, lightDir, phaseG, sunSigma)
                        * visSample.sun;
            }
            acc.inScatter += acc.transmittance * scatterAlbedo * source
                    * (float3(1.0, 1.0, 1.0) - stepT);
        }
        acc.transmittance *= stepT;
    }
}

/** Stratified direct-sun estimator for the approved noisy field when M17's one-event mode is off. */
float3 noisyAmbientSunInScatter(float3 ro, float3 rd, NoisyAmbientLayout layout,
                                float baseTau, float3 scatterAlbedo,
                                float phaseG, float3 sunSigma, int rays,
                                inout uint seed) {
    float3 sigmaT = worldPush.fogExtinction.xyz;
    float sigmaBar = max(luminance(sigmaT), 1.0e-4);
    float totalScalarTau = sigmaBar * baseTau;
    float3 gathered = float3(0.0, 0.0, 0.0);
    for (int i = 0; i < rays; ++i) {
        float tauNear = SUN_VIS_TAU_STEP * float(i);
        if (tauNear >= totalScalarTau) {
            break;
        }
        float tauFar = i == rays - 1
                ? totalScalarTau
                : min(tauNear + SUN_VIS_TAU_STEP, totalScalarTau);
        float sampledTau = lerp(tauNear, tauFar, rndf(seed));
        float sampleT = noisyDistanceAtBaseTau(ro, rd, layout, sampledTau / sigmaBar);
        float3 p = ro + rd * sampleT;
        SquareLightSample lightSample = sampleSquareLight(
                worldPush.lightDir.xyz, worldPush.celestial.xyz, worldPush.lightDir.w, seed);
        float3 sunSource = sampledAmbientSunSource(
                p, rd, lightSample.direction, phaseG, sunSigma);
        float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, p,
                lightSample.direction, RAY_FAR).transmittance;
        float3 viewWeight = exp(-sigmaT * (tauNear / sigmaBar))
                          - exp(-sigmaT * (tauFar / sigmaBar));
        gathered += scatterAlbedo * viewWeight * sunSource * vis;
    }
    return gathered;
}

void integrateNoisyAmbient(inout SegmentIntegral seg, float3 ro, float3 rd,
                           float tEnd, uint seed) {
    float2 window = ambientWindow(tEnd);
    NoisyAmbientLayout layout = makeNoisyAmbientLayout(ro, rd, window);
    float baseTau = noisyAmbientLayoutTau(ro, rd, layout);
    if (baseTau <= 0.0) {
        return;
    }

    float3 scatterAlbedo = worldPush.fogScatter.xyz;
    float phaseG = worldPush.fogScatter.w;
    uint sunRays = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
            ? ((worldPush.flags >> 23u) & 7u) : 0u;
    bool sourceOn = ((worldPush.flags >> 16u) & 1u) == 0u;
    float3 sunSigma = multiScatterOn()
            ? volumeDiffuseAttenuation(worldPush.fogExtinction.xyz, scatterAlbedo, phaseG)
            : worldPush.fogExtinction.xyz;

    if (scatterVertexOn()) {
        uint vertexSeed = seed ^ 0xC2B2AE3Du;
        vertexSeed = pcg(vertexSeed);
        ScatterVertex vertex = sampleNoisyAmbientScatterVertex(
                worldPush.fogExtinction.xyz, scatterAlbedo, ro, rd,
                layout, baseTau, vertexSeed);
        seg.scatterPos = vertex.pos;
        seg.scatterWeight = vertex.weight;
    }

    AmbientAccum acc;
    acc.inScatter = float3(0.0, 0.0, 0.0);
    acc.transmittance = float3(1.0, 1.0, 1.0);
    float3 skySource = worldPush.mediumSkyRadiance.xyz;
    integrateNoisyAmbientRegion(acc, ro, rd, layout.edge.x, layout.edge.y, layout.steps.x,
            false, scatterAlbedo, skySource, phaseG, sunSigma, sunRays, sourceOn);
    integrateNoisyAmbientRegion(acc, ro, rd, layout.edge.y, layout.edge.z, layout.steps.y,
            true, scatterAlbedo, skySource, phaseG, sunSigma, sunRays, sourceOn);
    integrateNoisyAmbientRegion(acc, ro, rd, layout.edge.z, layout.edge.w, layout.steps.z,
            false, scatterAlbedo, skySource, phaseG, sunSigma, sunRays, sourceOn);
    seg.transmittance *= acc.transmittance;
    seg.inScatter += acc.inScatter;

    if (sourceOn && sunRays > 0u) {
        uint fogSeed = seed ^ 0x9E3779B9u;
        fogSeed = pcg(fogSeed);
        if (scatterVertexOn() && any(seg.scatterWeight > 0.0)) {
            SquareLightSample lightSample = sampleSquareLight(
                    worldPush.lightDir.xyz, worldPush.celestial.xyz,
                    worldPush.lightDir.w, fogSeed);
            float3 vertexSunSource = sampledAmbientSunSource(
                    seg.scatterPos, rd, lightSample.direction, phaseG, sunSigma);
            float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, seg.scatterPos,
                    lightSample.direction, RAY_FAR).transmittance;
            seg.inScatter += seg.scatterWeight * vertexSunSource * vis;
        } else if (!scatterVertexOn()) {
            seg.inScatter += noisyAmbientSunInScatter(ro, rd, layout, baseTau,
                    scatterAlbedo, phaseG, sunSigma, int(sunRays), fogSeed);
        }
    }
}

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

// Diffusion attenuation and its physical-domain clamp live in volume_source.slang so the froxel and
// marched estimators cannot drift. See that module for the derivation and rejected cloud-octave option.
/** Bit 26: let the source decay at the diffusion rate instead of the beam's. Off reproduces exactly. */
bool multiScatterOn() {
    return (worldPush.flags & (1u << 26u)) != 0u;
}

/**
 * Integral of the VIEW path's attenuation alone over [a, b]: what survives the trip from a scattering
 * point back to the eye, with nothing said about how the light got to that point in the first place.
 *
 * <p>The sun term weights its strata with this and measures the source's own path with a ray instead of
 * deriving it, so the two halves of the old combined integrand are now answered by two different things:
 * this one by arithmetic, the other by the geometry itself.
 */
float3 viewPathIntegral(float3 sigmaView, float a, float b) {
    if (b <= a) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 safe = max(sigmaView, float3(1.0e-6, 1.0e-6, 1.0e-6));
    return (exp(-safe * a) - exp(-safe * b)) / safe;
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
float3 depthAttenuatedIntegral(float3 sigmaSource, float3 sigmaView, float d0, float descentPerT,
                               float pathScale, float tEnd) {
    // Where the ray reaches depth 0. Only a climbing ray gets there; a level or descending one never
    // does, and its whole segment takes the submerged branch.
    float tSurface = descentPerT > 1.0e-6 ? d0 / descentPerT : 1.0e30;
    float tSub = clamp(tSurface, 0.0, tEnd);

    // Submerged stretch. Both exponents are linear in t, so the product stays closed form. `a` may be
    // positive here (climbing faster than the light descends) but only over [0, tSurface], where the
    // growth is exactly cancelled by `head` — at t = tSurface the two collapse to exp(-sigma_t*tSurface),
    // the plain view attenuation, which is also where the second piece picks up. Continuous, and bounded.
    // TWO coefficients, and which goes where is the whole point. `head` and the growth inside `a` are
    // the SOURCE's own path down to this depth, so they take sigmaSource — light that scatters on the way
    // down is still on its way down. The -1 term is the VIEW path from the scattering point to the eye,
    // which takes sigmaView, because a photon scattered out of that straight line does not arrive.
    float3 head = exp(-sigmaSource * (d0 * pathScale));
    float3 a = sigmaSource * (descentPerT * pathScale) - sigmaView;
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
        float3 safeSigma = max(sigmaView, float3(1.0e-6, 1.0e-6, 1.0e-6));
        total += (exp(-safeSigma * tSub) - exp(-safeSigma * tEnd)) / safeSigma;
    }
    return total;
}

// Water's ray cap. The fog's cap is Rt.Volumetrics.SUN_SHADOW_RAYS; both use the shared sampler above.
public static const int WATER_SUN_VIS_STRATA = 3;

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
/**
 * Water in-scatter from ONE sampled scattering vertex (M17, decision D29).
 *
 * Written beside the stratified estimator rather than woven into it, so the switch's off position is the
 * published code path unchanged rather than a claim about a shared one.
 *
 * <p>What this buys, and it is not resolution: the sky term can finally ask its question WHERE THE LIGHT
 * SCATTERS. The stratified path probes upward from the segment's start and gates the whole segment by
 * that one scalar, so a camera under water shares one probe across every primary ray -- one block over
 * the eye zeroed the scattering across the entire screen, and a cell-aligned grid turned a cave mouth
 * into a one-block step (D15). Here depth and openness are both properties of the sampled point, so a
 * block overhead darkens the water under that block and nothing else.
 *
 * <p>Ray budget is unchanged, not merely bounded: one shadow ray for the sun and one upward probe, the
 * same two the stratified path already casts per stratum and per segment respectively.
 */
float3 enclosedScatterVertexInScatter(ScatterVertex vertex, float3 sigmaT, uint mediumFlags,
                                      float3 albedo, float3 ro, float3 rd, float d0,
                                      bool sunSourceOn, bool skySourceOn, inout uint seed) {
    if (all(vertex.weight <= 0.0)) {
        return float3(0.0, 0.0, 0.0);
    }
    float phaseG = mediumPhaseGFlags(mediumFlags);
    // Depth of the event itself. The fallback surface still anchors it, but the descent is measured from
    // the sampled position rather than integrated along the whole segment.
    float vertexDepth = max(d0 - (vertex.pos.y - ro.y), 0.0);
    float3 sigmaSource = multiScatterOn()
            ? volumeDiffuseAttenuation(sigmaT, albedo, phaseG)
            : sigmaT;
    float3 source = float3(0.0, 0.0, 0.0);

    if (sunSourceOn) {
        // Same finite emitter, Snell refraction and phase as the stratified path -- only the position
        // the question is asked from differs. Consume all three random dimensions before the visibility
        // decision, for the same reason as there: the sequence must not change across sunrise.
        SquareLightSample lightSample = sampleSquareLight(
                worldPush.lightDir.xyz, worldPush.celestial.xyz, worldPush.lightDir.w, seed);
        float3 airLightDir = lightSample.direction;
        if (airLightDir.y > 0.0) {
            float sinAir = sqrt(max(0.0, 1.0 - airLightDir.y * airLightDir.y));
            float sinWater = sinAir / WATER_IOR;
            float cosWater = sqrt(max(1.0e-4, 1.0 - sinWater * sinWater));
            float sunPathScale = 1.0 / cosWater;
            float3 lightHoriz = float3(airLightDir.x, 0.0, airLightDir.z);
            float horizLen = max(length(lightHoriz), 1.0e-6);
            float3 refractedLight = (lightHoriz / horizLen) * sinWater + float3(0.0, cosWater, 0.0);
            bool useVisibility = (worldPush.flags & 32768u) == 0u;
            VisibilityResult shadow = visibilityMasked(CULL_SHADOW_NO_SELF, vertex.pos,
                                                       refractedLight, RAY_FAR);
            float3 vis = useVisibility ? shadow.transmittance : float3(1.0, 1.0, 1.0);
            float sunWaterPath = shadow.waterHitT > 0.0
                    ? shadow.waterHitT
                    : vertexDepth * sunPathScale;
            float3 sampledIrradiance = sampleProviderCelestialIrradiance(
                    worldPush, undyedCelestialIrradiance, airLightDir);
            source += sampledIrradiance
                    * enclosedPhase(dot(rd, refractedLight), phaseG, albedo)
                    * vis * exp(-sigmaSource * sunWaterPath);
        }
    }

    if (skySourceOn) {
        // THE D15 FIX. Both the column above the event and how open that column is are measured from the
        // event, so neither is shared with any other pixel or any other part of this segment.
        VisibilityResult up = visibilityMasked(CULL_SECONDARY_NO_SELF, vertex.pos,
                                               float3(0.0, 1.0, 0.0), RAY_FAR);
        float skyDepth = up.waterHitT > 0.0 ? up.waterHitT : vertexDepth;
        float3 skySigmaSource = multiScatterOn()
                ? volumeDiffuseAttenuation(sigmaT, albedo, phaseG)
                : sigmaT;
        // Asked above the surface rather than at this vertex; waterSurfaceSkyOpenness owns the reason.
        source += worldPush.mediumSkyRadiance.xyz * waterSurfaceSkyOpenness(vertex.pos, skyDepth)
                * exp(-skySigmaSource * skyDepth);
    }

    return vertex.weight * source;
}

float3 enclosedSingleScatter(float3 mediumExtinction, uint mediumFlags, float3 albedo,
                             float3 ro, float3 rd, float tEnd, ScatterVertex vertex,
                             inout uint seed) {
    float3 sigmaT = mediumSigmaTFields(mediumExtinction, mediumFlags, ro);
    // FALLBACK ONLY. Both scattering terms measure the light's path through the water with a ray now
    // (see below); this stands in for the cases where a ray found no surface to measure against, which is
    // rare and always means the geometry did not agree that this point is under water.
    //
    // It is kept rather than deleted because "the ray saw no water surface" and "there is no water above
    // this point" are not the same statement -- a missing or degenerate surface quad would otherwise
    // report zero attenuation and blow the term out, and erring toward the old estimate errs toward the
    // picture that shipped.
    float surfaceY = worldPush.waterAnchor.w;
    float d0 = surfaceY > -1.0e8 ? max(0.0, surfaceY - ro.y) : 0.0;

    float3 total = float3(0.0, 0.0, 0.0);

    // Which sources are live. A measurement switch (worldPush.flags bits 13-14, Rt.Water.SCATTER_SOURCE):
    // the two terms have independent occlusion models, so isolating one is the only way to attribute a
    // misbehaviour to it rather than to their sum.
    uint scatterSource = (worldPush.flags >> 13u) & 3u;
    bool sunSourceOn = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
            && (scatterSource == 0u || scatterSource == 1u);
    bool skySourceOn = scatterSource == 0u || scatterSource == 2u;

    // One sampled event replaces both closed forms below when the mode is on. Returning here rather than
    // interleaving is deliberate: everything past this line is the code that shipped, so "off" is that
    // picture by construction and not by argument.
    if (scatterVertexOn()) {
        return enclosedScatterVertexInScatter(vertex, sigmaT, mediumFlags, albedo, ro, rd, d0,
                                              sunSourceOn, skySourceOn, seed);
    }

    // The sun, reaching depth d after travelling d / cos(refracted zenith angle) through the water.
    //
    // NOT d / sin(elevation). That is the path through AIR, and light entering water bends toward the
    // vertical: Snell with n = 1.333 maps the whole sky onto a cone of half-angle 48.6 degrees inside
    // the water (the same critical angle that makes Snell's window), so the in-water path is at most
    // 1/cos(48.6) = 1.51 times the depth NO MATTER how low the sun sits. The air form is unbounded — at
    // sin(elevation) = 0.2 it claimed 5x the depth against a true 1.48x, and it diverges at sunset — so
    // the sun term died within a block or two of the surface and left a bright band hugging the surface
    // with darkness immediately under it. A bounded path is what turns that edge into a falloff.
    if (sunSourceOn) {
        float phaseG = mediumPhaseGFlags(mediumFlags);
        // The finite emitter direction, refraction and phase are sampled per stratum below.

        // THE DIRECTION EACH SAMPLED EMITTER POINT ACTUALLY COMES FROM, INSIDE THE WATER.
        //
        // The path length has used the refracted angle for a while; the direction had not, and the
        // comment that stood here said so and deferred it. It has a symptom now: at sunrise the shafts
        // ran almost horizontally and read as light arriving from somewhere far away underwater, because
        // the shadow ray was being fired at the true sun, which is nearly horizontal then.
        //
        // Snell with n = 1.333 folds the entire sky into a cone of half-angle 48.6 degrees, so the sun
        // seen from under the surface is NEVER lower than about 41.6 degrees of elevation however low it
        // sits in the air. That is the same critical angle that makes Snell's window, and the same one
        // bounding sunPathScale below — this is that fact applied to the direction as well as the length.
        //
        // Built by keeping the azimuth and swapping the zenith angle, which is what refraction at a flat
        // surface does: the bend happens in the plane of incidence, so only the tilt changes. The
        // horizontal part has length sinAir by construction, so normalising it and rescaling to sinWater
        // gives a unit vector without a normalize().
        //
        // The surface is taken as FLAT here, matching waterAnchor.w and the depth d0 measured against it.
        // Wave normals perturb what the surface looks like, not where this integral thinks it is.
        // rd.y, not rd.y/sunY. `descentPerT` is how fast the ray's DEPTH falls per unit t, which is the
        // ray's own vertical component and has nothing to do with where the sun is; the sun's slant is
        // `pathScale`, and depthAttenuatedIntegral already multiplies the two. Passing rd.y/sunY divided
        // by sin(elevation) twice, so a low sun squared its own slant. The sky term below always passed
        // rd.y; the two never agreed.
        // Shadow rays at FIXED OPTICAL DEPTHS along the segment. Each stratum carries the exact share of
        // the VIEW-path closed form, F(edge[i+1]) - F(edge[i]); visibility and the source's measured water
        // path are represented by one jittered sample inside it. That makes this a controlled stratified
        // approximation, not a strict f/pdf Monte Carlo estimator (decision D9; correction deferred to
        // M17 because it changes brightness/noise rather than only the interface).
        //
        // Why more than one ray: a single ray from the segment origin makes sun visibility ONE value for
        // the whole segment — and for the camera segment, one value for the whole screen. Any occlusion
        // boundary the camera crosses then flips the entire screen's scattering at once, which is
        // exactly what was observed: swimming above/below one precise height toggled the murk
        // everywhere, in every direction, as a step. Occlusion now varies along each ray, so it varies
        // across the screen, and the camera crossing a shadow boundary moves through it in stages.
        //
        // Why the strata sit at fixed optical depths (tau = 0, 1.25, 2.5 along the view path) rather
        // than at equal fractions of the segment: every interior boundary is independent of tEnd, so
        // neighbouring rays agree until the final tail stratum, which necessarily ends where the segment
        // ends. The jitter therefore decorrelates the remaining tail error instead of stamping geometry-
        // correlated blocks onto the image. Strata past the segment's end cast no ray, so short segments
        // still pay one ray; only long segments through murky water pay all three.
        //
        // Still CULL_SECONDARY_NO_SELF: the first ray's origin IS the camera for the camera segment,
        // which sits inside the first-person head mesh, and this remains the only shadow ray in the
        // renderer cast from the eye.
        // The source's coefficient. Equal to sigmaT with the switch off, so that path is unchanged.
        float3 sigmaSource = multiScatterOn()
                ? volumeDiffuseAttenuation(sigmaT, albedo, phaseG)
                : sigmaT;
        float sigmaBar = max(luminance(sigmaT), 1.0e-4);
        float3 sunTerm = float3(0.0, 0.0, 0.0);
        for (int s = 0; s < WATER_SUN_VIS_STRATA; ++s) {
            float2 stratum = homogeneousSunVisStratum(s, WATER_SUN_VIS_STRATA, sigmaBar, tEnd);
            float edgeNear = stratum.x;
            float edgeFar = stratum.y;
            if (edgeFar <= edgeNear) {
                break;
            }
            // The stratum's weight is now the VIEW path alone. How much sunlight ever reached the
            // sampled point is no longer computed here at all -- it is measured below, by the ray.
            float3 weight = viewPathIntegral(sigmaT, edgeNear, edgeFar);
            if (all(weight <= 1.0e-8)) {
                continue;
            }
            // One sample owns every directional quantity: emitter position, atmospheric colour,
            // refraction, phase and shadow. The old centre-direction `sunY` gate switched the complete
            // orange term on at once; a finite square instead contributes only the fraction above the
            // flat water horizon. With a finite emitter, consume all three random dimensions before the
            // visibility decision so later strata do not change sequence across sunrise.
            float sampleT = lerp(edgeNear, edgeFar, rndf(seed));
            SquareLightSample lightSample = sampleSquareLight(
                    worldPush.lightDir.xyz, worldPush.celestial.xyz, worldPush.lightDir.w, seed);
            float3 airLightDir = lightSample.direction;
            if (airLightDir.y <= 0.0) {
                continue;
            }

            float sinAir = sqrt(max(0.0, 1.0 - airLightDir.y * airLightDir.y));
            float sinWater = sinAir / WATER_IOR;
            // The floor is unreachable for WATER_IOR: the refracted cone bounds cosWater near 0.66. It
            // guards a future IOR change from silently creating a division by zero in sunPathScale.
            float cosWater = sqrt(max(1.0e-4, 1.0 - sinWater * sinWater));
            float sunPathScale = 1.0 / cosWater;
            float3 lightHoriz = float3(airLightDir.x, 0.0, airLightDir.z);
            float horizLen = max(length(lightHoriz), 1.0e-6);
            float3 refractedLight = (lightHoriz / horizLen) * sinWater
                    + float3(0.0, cosWater, 0.0);
            float3 sampledIrradiance = sampleProviderCelestialIrradiance(
                    worldPush, undyedCelestialIrradiance, airLightDir);
            // Bit 15: DISCARD the shadow ray's transmittance and take the sun as unoccluded. The ray is
            // still traced for every above-horizon emitter sample — shadow.waterHitT below measures the
            // sun's real water column, and the depth integral needs it even when occlusion is disabled.
            // A below-horizon sample has zero source and skips the ray in either switch state.
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
            // The view-path stratum weights remain closed-form; the one source/visibility sample standing
            // in for the whole stratum is the explicit M15 approximation documented above.
            bool useVisibility = (worldPush.flags & 32768u) == 0u;
            VolumeSunShadowSample shadow = traceVolumeSunShadow(
                    ro, rd, sampleT, refractedLight, useVisibility);

            // HOW MUCH WATER THE SUNLIGHT ACTUALLY CROSSED, measured by the ray that just crossed it.
            //
            // waterHitT is the distance from this sample to where its sun ray leaves the water, recorded
            // by the shadow any-hit that lets water tint a ray and pass it through rather than stopping
            // it. The renderer has been computing this all along and spending it only on caustics.
            //
            // It replaces three separate proxies at once, and each of them was wrong in a way the others
            // could not fix. waterAnchor.w is ONE scalar for the whole frame, describing the camera's own
            // water body, so a second pool at another height got the camera's depth. sunPathScale is an
            // analytic guess at the slant. And neither knows about a cave roof, an overhang, or the fact
            // that the surface above a point may not be the surface the camera is under. A ray knows all
            // of it, because it is the thing that travelled.
            //
            // Only when the ray found no surface at all does the old geometric estimate stand in --
            // that means nothing above this point along the sun's direction is water, so falling back
            // beats reporting zero attenuation and blowing the term out.
            float sunWaterPath = shadow.waterHitT > 0.0
                    ? shadow.waterHitT
                    : max(d0 - rd.y * shadow.t, 0.0) * sunPathScale;
            float3 phaseGain = sampledIrradiance
                    * enclosedPhase(dot(rd, refractedLight), phaseG, albedo);
            sunTerm += phaseGain * shadow.transmittance
                    * exp(-sigmaSource * sunWaterPath) * weight;
        }
        total += sunTerm;
    }

    // Sky light, arriving from overhead, so its path to depth d is d. The water's own source, not the
    // shared medium sky radiance — and gated by OPENNESS (the volumetric visibility grid's
    // zenith channel, sampled just above the measured surface below), which is what makes an underwater
    // cave dark while shadowed surface water keeps its glow: openness is a property of the column above
    // a point, not of the sun's direction, so a hill's shadow does not remove it but a cave roof does.
    // That split is exactly the one a constant source could not express. (M9's CPU sky-light grid
    // answered the same question more coarsely; removed in M15.0.)
    if (skySourceOn) {
        // The sky arrives from overhead and diffuses down through the same water, so it gets the same
        // treatment as the sun's source path. Without it the two sources would disagree about how deep
        // light reaches, which would show as the sky term dying at a different depth from the sun's.
        float3 skySigmaSource = multiScatterOn()
                ? volumeDiffuseAttenuation(sigmaT, albedo, mediumPhaseGFlags(mediumFlags))
                : sigmaT;
        // ONE RAY STRAIGHT UP, and it answers both questions the sky term has.
        //
        // How deep is this point: waterHitT is the distance to where the column of water above it ends,
        // measured rather than derived, so a second pool at another height and the camera's own body no
        // longer share one number.
        //
        // Is there sky up there at all: the same ray's transmittance. Water tints it and passes it
        // through; rock stops it. So a flooded cave under an overhang goes dark because the ray says so,
        // which is what settles the question the CPU's upward surface scan could not answer -- that scan
        // only finds where the water stops, not whether anything is above it.
        //
        // This replaced M9's CPU sky-light grid factor (removed in M15.0), whose grid carried a second
        // depth falloff of its own: vanilla
        // attenuates sky light one step per block of water, so it hit zero around fifteen blocks down and
        // stayed there. M9 accepted the doubled falloff deliberately, but the half it contributed was not
        // a falloff at all, it was a CLIFF, and it was measured as one -- with the surface at y=60 the
        // scattering vanished entirely below y=41, in water that continues down to y=33.
        //
        // The depth still VARIES along the segment analytically, which is why depthAttenuatedIntegral
        // stays: the ray anchors d0 at the segment's origin and the descent rate carries it from there.
        // One ray per segment, not per stratum, because the sky arrives from every direction and has no
        // single path to measure -- the vertical is its representative, not its whole story.
        VisibilityResult up = visibilityMasked(CULL_SECONDARY_NO_SELF, ro,
                                               float3(0.0, 1.0, 0.0), RAY_FAR);
        float skyDepth = up.waterHitT > 0.0 ? up.waterHitT : d0;

        // The OPENNESS comes from the interpolated grid, not from that ray's own transmittance.
        //
        // Using the ray for both was the previous attempt and it traded one artifact for another: a ray
        // is binary, so one block of rock overhead dropped the sky term from full to nothing and the
        // cliff this work removed came back as a step. Depth wants a ray because a ray measures a length;
        // openness wants the grid because the grid interpolates, and "how much sky is up there" is a
        // quantity that should never step.
        //
        // Asked just above the surface, whose height the ray above has now told us exactly.
        // waterSurfaceSkyOpenness owns the reason, including why depth comes from that ray and openness
        // does not.
        float skyOpen = waterSurfaceSkyOpenness(ro, skyDepth);
        total += worldPush.mediumSkyRadiance.xyz * skyOpen
                * depthAttenuatedIntegral(skySigmaSource, sigmaT, skyDepth, rd.y, 1.0, tEnd);
    }

    return albedo * sigmaT * total;
}

/**
 * The only external Interface for participating media over a path segment.
 *
 * Callers must accumulate in this order:
 *
 *     radiance += throughput * segment.inScatter;
 *     throughput *= segment.transmittance;
 *
 * The Implementation dispatches internally from mediumProfile: homogeneous enclosed media use an exact
 * closed form; the ambient field selects the exact analytic height estimator or the finite heterogeneous
 * march. Both adapters share Medium parameter queries and the sun-visibility sampler. Keeping the
 * estimator seam internal gives
 * callers one contract without degrading a homogeneous exact solution into numerical marching.
 */
public SegmentIntegral integrateSegment(float3 currentExtinction, uint currentFlags,
                                        float3 ro, float3 rd, float tEnd, uint seed) {
    SegmentIntegral seg;
    seg.transmittance = float3(1.0, 1.0, 1.0);
    seg.inScatter = float3(0.0, 0.0, 0.0);
    seg.scatterPos = float3(0.0, 0.0, 0.0);
    seg.scatterWeight = float3(0.0, 0.0, 0.0);

    uint profile = mediumProfileFlags(currentFlags);

    // Estimator dispatch behind the one segment interface. Homogeneous enclosed media retain their
    // exact closed form; the ambient field retains its analytic-height path at strength zero and selects
    // the finite heterogeneous march otherwise. The shared parameter queries and shadow sampler are the
    // seam -- forcing both through one numerical
    // method would make the homogeneous path slower and less accurate merely to look uniform.
    if (profile != MEDIUM_PROFILE_HETEROGENEOUS_AMBIENT) {
        float3 sigmaT = mediumSigmaTFields(currentExtinction, currentFlags, ro);
        if (any(sigmaT > 0.0)) {
            seg.transmittance = exp(-sigmaT * tEnd);

            float3 scatterAlbedo = mediumScatterAlbedoFields(currentExtinction, currentFlags);
            if (profile == MEDIUM_PROFILE_HOMOGENEOUS_ENCLOSED
                    && any(scatterAlbedo > 0.0)) {
                // Its own rehash of the caller's seed, distinct from the fog's below, so the water's
                // stratum jitter and the fog's cannot draw the same numbers on the same pixel.
                uint waterSeed = seed ^ 0x85EBCA6Bu;
                waterSeed = pcg(waterSeed);
                // Sampled HERE rather than inside the estimator so the segment has exactly one event:
                // the sun term below and the caller's emitter NEE describe the same scattering, and two
                // independently drawn points would be two different scatterings sharing one segment.
                ScatterVertex vertex = noScatterVertex();
                if (scatterVertexOn()) {
                    vertex = sampleHomogeneousScatterVertex(sigmaT, scatterAlbedo, ro, rd, tEnd,
                                                            waterSeed);
                    seg.scatterPos = vertex.pos;
                    seg.scatterWeight = vertex.weight;
                }
                seg.inScatter += enclosedSingleScatter(currentExtinction, currentFlags,
                                                       scatterAlbedo, ro, rd, tEnd, vertex,
                                                       waterSeed);
            }
        }
    }

    // A path inside water is inside water — not inside water *and* the world's fog.
    if (profile == MEDIUM_PROFILE_HETEROGENEOUS_AMBIENT) {
        // Strength zero never reaches this function: the entire published analytic-height estimator below
        // stays intact as the exact A/B path. Once enabled, all view extinction, visibility-weighted
        // in-scatter, light-path attenuation and the M17 event use one canonical numerical density field.
        if (ambientNoiseOn()) {
            integrateNoisyAmbient(seg, ro, rd, tEnd, seed);
            return seg;
        }
        float3 scatterAlbedo = mediumScatterAlbedoFields(currentExtinction, currentFlags);
        float phaseG = mediumPhaseGFlags(currentFlags);
        float2 window = ambientWindow(tEnd);
        float tau = ambientHeightIntegral(ro, rd, window.x, window.y);
        if (tau > 0.0) {
            float3 fogT = exp(-worldPush.fogExtinction.xyz * tau);
            seg.transmittance *= fogT;
            // Sampled before either source is evaluated, and independently of the sun's ray budget: the
            // caller's emitter NEE wants an event even where sun-shadow-rays is 0 and the sun is reading
            // the grid. One event per segment, shared by everything that describes it.
            if (scatterVertexOn()) {
                uint vertexSeed = seed ^ 0xC2B2AE3Du; // pcg takes an inout, so it needs a variable
                vertexSeed = pcg(vertexSeed);
                ScatterVertex ambientVertex = sampleAmbientScatterVertex(
                        worldPush.fogExtinction.xyz, scatterAlbedo, ro, rd, window, tau, vertexSeed);
                seg.scatterPos = ambientVertex.pos;
                seg.scatterWeight = ambientVertex.weight;
            }
            // The sunlight lighting the fog has itself crossed the fog to get here. Without that term
            // a low sun grew a halo that brightened with exactly the depth that ought to be
            // extinguishing it.
            //
            // Measured from the segment's ORIGIN, not from a point inside it. Sampling the midpoint made
            // this depend on segment length, and segment length is whatever the ray happened to hit: a
            // ray stopping at 30 blocks and its neighbour escaping to the cull distance sampled the sun's
            // transmittance an order of magnitude apart, so the halo came out blotchy across neighbouring
            // pixels and unstable as the camera moved. Every ray leaving one point now agrees.
            // The sun's own path THROUGH the fog to this segment, and the coefficient it decays at is
            // not the one the view path uses. See diffuseAttenuation: scattering removes light from the
            // beam without removing it from the air, so at density 10 the old form extinguished the sun
            // roughly three times too fast and the fog went black exactly where real fog turns into a
            // bright formless glow. The view path below still uses the full extinction.
            float3 sunSigma = multiScatterOn()
                    ? volumeDiffuseAttenuation(worldPush.fogExtinction.xyz, scatterAlbedo, phaseG)
                    : worldPush.fogExtinction.xyz;
            float3 sunTransmittance = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
                    ? ambientTransmittanceWith(sunSigma, ro, worldPush.lightDir.xyz, RAY_FAR)
                    : float3(1.0, 1.0, 1.0);
            // The celestial buffer is irradiance E, so the normalised phase density stays normalised:
            // the directional radiance source is E*p, not E*(4*pi*p).
            // The source, split into the two halves the visibility grid answers for SEPARATELY.
            //
            // The sun's lobe is gated by whether the sun is reachable. The other two are gated by whether
            // the SKY is, which is a different question and deliberately so: a hillside's shadow must not
            // drain the ambient light out of the fog standing in it, because what removes ambient light is
            // a roof, not a hill. The froxel splits its source the same way, and the two agreeing matters
            // — they are added together on screen, so a disagreement between them would show up as a seam
            // at whatever depth the camera's prefix segment happens to end.
            //
            // M15.2/M16: both estimators call evaluateAmbientRadianceSource and read the same GPU-reduced
            // mediumSkyRadiance for the sky. The centre-direction source below remains only the grid/outside-
            // grid fallback; D20's ray and in-grid paths rebuild it from the sampled emitter direction.
            // Froxel does the same lookup through its compute-local LUT binding and additionally integrates
            // the planetary atmosphere itself over the camera prefix.
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
            // Atmospheric multiple scattering is already inside the complete sky-view LUT reduction;
            // adding sampleMultiScatter here again would count the same energy twice.
            AmbientRadianceSource radianceSource = evaluateAmbientRadianceSource(
                    rd, worldPush.lightDir.xyz, phaseG,
                    worldPush.lightRadiance.xyz, sunTransmittance,
                    worldPush.mediumSkyRadiance.xyz);
            float3 sunSource = radianceSource.sun;
            float3 skySource = radianceSource.sky;
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
            uint sunRays = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
                    ? ((worldPush.flags >> 23u) & 7u) : 0u;
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
                ambientStep(acc, ro, rd, window.x, tEnter, scatterAlbedo,
                            gridSunSource, skySource, float2(1.0, 1.0));
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
                        float3 midP = ro + rd * ((tA + tB) * 0.5);
                        VolumeVisibilitySample visSample = sampleVolumeLightVisibility(midP);
                        volumeRestirDeposit(midP, worldPush.fogExtinction.xyz, MEDIUM_FLAG_AMBIENT);
                        float3 stepSunSource = gridSunSource;
                        if (sunRays == 0u
                                && worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE) {
                            float3 gridLightDir = sampleSquareLightDirection(
                                    worldPush.lightDir.xyz, worldPush.celestial.xyz,
                                    worldPush.lightDir.w, visSample.lightUv);
                            stepSunSource = sampledAmbientSunSource(
                                    ro, rd, gridLightDir, phaseG, sunSigma);
                        }
                        // Folded into the source rather than pushed through ambientStep's signature:
                        // the scalar it takes is an OPENNESS, and a directional estimate is not one. Sky
                        // openness then reads 1 because the radiance already carries the visibility.
                        float3 stepSkySource = skySource;
                        float stepSkyVis = visSample.sky;
                        if (visSample.skyDirValid) {
                            stepSkySource = visSample.skyRadiance
                                    * enclosedPhase(dot(rd, visSample.skyDir), phaseG, scatterAlbedo);
                            stepSkyVis = 1.0;
                        }
                        ambientStep(acc, ro, rd, tA, tB, scatterAlbedo,
                                    stepSunSource, stepSkySource,
                                    float2(visSample.sun, stepSkyVis));
                    }
                }
                ambientStep(acc, ro, rd, tExit, window.y, scatterAlbedo,
                            gridSunSource, skySource, float2(1.0, 1.0));
                // += rather than =: the enclosed branch above may already have deposited in-scatter.
                // Unreachable today (every enclosed medium has ambient false), but a future composite
                // profile that deposits both enclosed and ambient source would otherwise lose the first.
                seg.inScatter += acc.inScatter;
                if (sunRays > 0u) {
                    // Taken BY VALUE and rehashed rather than advanced in place. The callers include the
                    // camera prefix and the debug views, which have no path RNG stream to advance, and
                    // threading an inout through them would make every future caller carry one. The
                    // rehash is what keeps this sample from correlating with whatever the path draws next
                    // from the same seed.
                    uint fogSeed = seed ^ 0x9E3779B9u; // pcg takes an inout, so it needs a variable
                    fogSeed = pcg(fogSeed);
                    if (scatterVertexOn() && any(seg.scatterWeight > 0.0)) {
                        // One sampled event instead of the stratified sweep (M17, D29). The stratified
                        // form keeps closed-form weights per stratum and samples only the source inside
                        // each; this keeps ONE sample and pays for it with the exact f/pdf weight, so it
                        // is a strict Monte Carlo estimator where the other is a controlled approximation
                        // (D9). Noisier per frame by construction, and the reason the switch exists.
                        //
                        // The sun's transmittance is measured from the EVENT, not from the segment's
                        // origin as the closed form must. That was previously ruled out because a
                        // deterministic midpoint made the answer depend on segment length and came out as
                        // blotches; a sampled position depends on a random number instead, so the same
                        // error becomes noise the denoiser converges.
                        SquareLightSample lightSample = sampleSquareLight(
                                worldPush.lightDir.xyz, worldPush.celestial.xyz,
                                worldPush.lightDir.w, fogSeed);
                        float3 vertexSunSource = sampledAmbientSunSource(
                                seg.scatterPos, rd, lightSample.direction, phaseG, sunSigma);
                        float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, seg.scatterPos,
                                                      lightSample.direction, RAY_FAR).transmittance;
                        seg.inScatter += seg.scatterWeight * vertexSunSource * vis;
                    } else if (!scatterVertexOn()) {
                        seg.inScatter += sunInScatterStochastic(ro, rd, window, scatterAlbedo,
                                                                 phaseG, sunSigma,
                                                                 int(sunRays), fogSeed);
                    }
                }
            }
        }
    }
    return seg;
}
