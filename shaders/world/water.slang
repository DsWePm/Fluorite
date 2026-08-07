// Water surface: the wave spectrum (with its analytic time derivative), the normal perturbation it
// drives, and the caustic term that differentiates the same field. Depends on core.


// Animated surface waves — a directional wave spectrum, gradient-only (the surface stays geometrically
// flat; only the normal tilts). Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// The domain is world-anchored (see applyWaterWaves) so the pattern is pinned in world space, and the
// caustics (waterCaustic) differentiate this same field, so they stay in sync with the visible ripples.

import world_common;
import world_core;

// The simulated height field (M12), in metres of displacement. RAYGEN and closest-hit both reach it
// through applyWaterWaves.
public static const int WATER_SIM_DIM = 256;
[[vk::binding(18, 0)]] Sampler2D<float> waterSimHeightTex;

public static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
public static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
public static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
public static const float WAVE_G = 9.81;
public static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
public static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components

// Per-component mean of the crest profile, ⟨e^{S(sin φ − 1)}⟩ = e^{−S}·I₀(S) for S = 1.2 + 0.12·i.
//
// ONLY THE HEIGHT NEEDS THESE, and it needs them badly. The profile is strictly positive, so a raw sum of
// components sits ENTIRELY ABOVE the still-water plane by ~40% of the summed amplitude — as a normal that
// is invisible (a derivative kills any constant), which is why nothing needed this until geometry started
// consuming the field. Left in, the water surface would float a fixed distance above the block face and
// the shore would not meet it. Subtracting the exact mean per component makes the field zero-mean, so the
// displaced surface oscillates about the plane the flat quads occupy — which is also what lets the
// deformation region fade into the flat far field without a step.
//
// Analytic; matched against 200k-sample quadrature to machine precision.
public static const float WAVE_PROFILE_MEAN[WAVE_COUNT] = {
    0.41978, 0.39680, 0.37662, 0.35879, 0.34298, 0.32887, 0.31623, 0.30484, 0.29454, 0.28517
};

// The band limit, in both of its jobs. Originally a pixel-footprint LOD fade for the normal; the mesh
// side (M12.5) passes its CELL SIZE and gets the Nyquist band-limit out of the same expression, because
// "detail finer than the sampling can carry" is one question asked by two consumers. This is D46's
// wavelength split, and it needed no new machinery.
public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

// One walk of the wave spectrum, shared by every consumer so the spectrum constants exist once. WITH_DT
// and WITH_HEIGHT are generics so the derivative and height terms are compiled out entirely for callers
// that do not want them — the trigonometry is shared, but the chain-rule work is not free, and the
// gradient-only callers are the hot ones (every shaded water hit).
//
// HEIGHT AND GRADIENT COME OUT OF THE SAME WALK ON PURPOSE. h = a·e^{S(sin φ − 1)} and the gradient
// returned here is its exact analytic ∇h, so geometry built from one and shading built from the other
// describe the same surface. Two separate evaluations would drift apart as soon as anyone touched a
// constant, and the drift would be slow enough to look like something else.
public void waterWaveSpectrum<let WITH_DT : int, let WITH_HEIGHT : int>(float2 p, float t, float footprint,
                                          out float height, out float2 grad, out float2 gradDt) {
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    height = 0.0;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    float lambda = 14.0;
    for (int i = 0; i < WAVE_COUNT; i++) {
        float lodWeight = waterWaveLodWeight(lambda, footprint);
        if (lodWeight <= 0.0) break;
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // Heading: deterministic irregular spread about the wind, widening down the spectrum.
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(WAVE_WIND.x * cs - WAVE_WIND.y * sn, WAVE_WIND.x * sn + WAVE_WIND.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        float phDt = -w;                      // ∂φ/∂t
        float2 dphDt = float2(0.0, 0.0);      // ∂(∇φ)/∂t
        if (i < 4) { // meander only the components whose fronts are long enough to read as lines
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += WAVE_MEANDER * sin(wph);
            dph += (WAVE_MEANDER * cos(wph) * 0.4 * k) * pd;
            if (WITH_DT != 0) {
                float wphDt = 0.5 * w;
                phDt += WAVE_MEANDER * cos(wph) * wphDt;
                dphDt = (-WAVE_MEANDER * sin(wph) * wphDt * 0.4 * k) * pd;
            }
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // a = Q/k; h = a·e^{S(sin φ−1)}; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float amplitude = lodWeight * (Q[i] / k) * S;
        grad += amplitude * cosPh * e * dph;
        if (WITH_HEIGHT != 0) {
            // The mean comes off weighted by lodWeight too — otherwise a component fading out of band
            // would leave its offset behind and the surface would drift as the band limit moved.
            height += lodWeight * (Q[i] / k) * (e - WAVE_PROFILE_MEAN[i]);
        }
        if (WITH_DT != 0) {
            // ∂/∂t of the term above, chain-ruled through φ and ∇φ.
            gradDt += amplitude * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= 1.5;
    }
    // ONE SCALE FOR BOTH. Height and gradient are the same function, so they take the same factor —
    // scaling them apart would mean shading a surface the geometry is not.
    height *= WATER_WAVE_STRENGTH;
    grad *= WATER_WAVE_STRENGTH;
    gradDt *= WATER_WAVE_STRENGTH;
}

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float height;
    float2 grad, gradDt;
    waterWaveSpectrum<0, 0>(p, t, footprint, height, grad, gradDt);
    return grad;
}

/**
 * The wave field's HEIGHT about the still-water plane, band-limited to what a sampler of the given
 * footprint can carry. Metres.
 *
 * <p>Pass the mesh cell size as {@code footprint} and this returns exactly the part of the field that
 * mesh can represent — components shorter than about twice the cell are faded out rather than aliased
 * into a crawling mess. That is D46's split, and the measurement says it costs almost nothing: the long
 * components hold 96% of the rms height (λ ≥ 2.77 m of a 14 m … 0.38 m spectrum), so the chop the mesh
 * drops was never shape. It was always slope, and slope is the normal's job.
 */
public float waterWaveHeight(float2 p, float t, float footprint) {
    float height;
    float2 grad, gradDt;
    waterWaveSpectrum<0, 1>(p, t, footprint, height, grad, gradDt);
    return height;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0);
}

// Evaluate the current gradient and linearly rewind it to the previous phase in one spectrum walk.
// Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential work
// while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float height;
    float2 gradDt;
    waterWaveSpectrum<1, 0>(p, currentT, footprint, height, currentGrad, gradDt);
    previousGrad = currentGrad - (currentT - previousT) * WAVE_SPEED * gradDt;
}

// ---- The interactive simulation's contribution (M12), sampled as a SLOPE and added to the spectrum's.
//
// ADDED RATHER THAN SUBSTITUTED, which is a deliberate choice with a known physical gap. Two independent
// wave fields do not interact -- a simulated ripple does not refract around a swell crest, and a swell
// does not carry a ripple along with it -- so this is not one wave field, it is two summed. What it buys
// is that the ocean inside the domain is still the ocean: the spectrum is the floor everywhere, and the
// simulation is a disturbance on top of it, so crossing the domain boundary fades one term out rather
// than swapping the water for a different water. The alternative, replacing the field inside the domain,
// is more self-consistent and looks worse -- the simulation cannot produce a swell out of nothing, so
// the domain would read as a conspicuously calm rectangle following the player around.
//
// ONE ENTRY POINT. This is added inside applyWaterWaves, which every consumer already goes through:
// primary rays, the RR guide buffers, and the caustics (causticLanding differentiates the same field).
// So ripples reach reflections and caustics without any of those knowing the simulation exists.
// Where a world position lands in the simulation domain, and how much of the simulation applies there.
// Split out because the gradient (shading) and the height (M12.5 displacement) must agree exactly about
// both the mapping and the edge fade -- if they disagreed, the geometry would keep rippling past where
// the shading stopped, which is the same class of "both sides right alone, wrong where they meet" bug
// this milestone has already produced three of.
//
// Returns false when the simulation contributes nothing here: switched off, or outside the domain.
bool waterSimSample(float2 worldXZ, out float2 uv, out float weight) {
    uv = float2(0.0, 0.0);
    weight = 0.0;
    float cellSize = worldPush.waterSimDomain.z;
    if (cellSize <= 0.0) {
        return false; // simulation off: the spectrum stands alone, bit for bit as before
    }
    float2 cell = (worldXZ - worldPush.waterSimDomain.xy) / cellSize;
    uv = cell / float(WATER_SIM_DIM);
    if (any(uv < 0.0) || any(uv > 1.0)) {
        return false;
    }
    // Fade over the outer eighth. The domain is a rectangle that follows the player, and a rectangle's
    // edge is the one thing in this system with no physical meaning at all -- so nothing may be visible
    // there. Both axes, multiplied, so the corners fade too.
    float2 edge = min(uv, 1.0 - uv);
    weight = smoothstep(0.0, 0.125, edge.x) * smoothstep(0.0, 0.125, edge.y)
            * worldPush.waterSimDomain.w;
    return true;
}

/** The simulated displacement at a world position, in metres. Zero outside the domain. */
public float waterSimHeight(float2 worldXZ) {
    float2 uv;
    float weight;
    if (!waterSimSample(worldXZ, uv, weight)) {
        return 0.0;
    }
    return waterSimHeightTex.SampleLevel(uv, 0.0) * weight;
}

float2 waterSimGrad(float2 worldXZ) {
    float2 uv;
    float weight;
    if (!waterSimSample(worldXZ, uv, weight)) {
        return float2(0.0, 0.0);
    }
    float cellSize = worldPush.waterSimDomain.z;
    // Central differences on the filtered height, one texel apart. The texture is filtered, so this is
    // the slope of the interpolated surface rather than of the lattice -- which is what the shading
    // wants, and it is also why the sponge's non-physical edge band never shows: the fade below has
    // already reached zero before the sampler gets there.
    float texel = 1.0 / float(WATER_SIM_DIM);
    float hx0 = waterSimHeightTex.SampleLevel(uv - float2(texel, 0.0), 0.0);
    float hx1 = waterSimHeightTex.SampleLevel(uv + float2(texel, 0.0), 0.0);
    float hz0 = waterSimHeightTex.SampleLevel(uv - float2(0.0, texel), 0.0);
    float hz1 = waterSimHeightTex.SampleLevel(uv + float2(0.0, texel), 0.0);
    float2 grad = float2(hx1 - hx0, hz1 - hz0) / (2.0 * cellSize);
    return grad * weight;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
/**
 * Debug view 23: the simulated height field, painted across the screen.
 *
 * <p>Answers the two questions that look identical on a flat pond — is there anything in the field, and
 * if not, is that because nothing disturbs it or because it cannot propagate. The view drives a test
 * impulse at the domain centre from the CPU while it is active, so a field that stays black under it has
 * a broken solver or an obstacle mask that says everything is a wall; a field that ripples under it but
 * not in normal play has a broken impulse path instead.
 *
 * <ul>
 *   <li><b>RED</b> — the domain is not live at all. No water found near the camera, or the setting is
 *       off, or the push lane never got written.</li>
 *   <li><b>GREEN/BLUE</b> — height, split by sign: green rises, blue falls. Black is a flat field.</li>
 * </ul>
 */
public float3 waterSimDebug(float2 screenUv) {
    if (worldPush.waterSimDomain.z <= 0.0) {
        return float3(1.0, 0.0, 0.0);
    }
    float h = waterSimHeightTex.SampleLevel(screenUv, 0.0);
    // Amplified hard: a ripple is millimetres of displacement against a domain tens of blocks across, so
    // the honest range is invisible and a diagnostic that shows nothing is not a diagnostic.
    float a = saturate(abs(h) * 400.0);
    return h >= 0.0 ? float3(0.0, a, 0.0) : float3(0.0, 0.0, a);
}

public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint) + waterSimGrad(worldXZ);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
}

/**
 * The water surface's height above the still-water plane at a world position, in metres — the geometry
 * counterpart of {@link applyWaterWaves}, and the only thing the vertex displacement pass evaluates.
 *
 * <p>{@code cellSize} is the mesh's spacing, and it band-limits the spectrum to what that mesh can carry
 * (see waterWaveLodWeight). Total = spectrum + simulation, the same sum {@link applyWaterWaves} takes
 * slopes of, so the displaced surface is the surface the shading describes.
 *
 * <p>NOTE WHAT IS NOT HERE: no second waveform, no separate displacement amplitude, no split of the
 * field between geometry and normal. The mesh carries a band-limited version of the one field and the
 * shading normal stays the analytic normal of the whole of it. That is ordinary normal mapping — the
 * geometry is a low-frequency proxy and the normal carries the rest — and it does NOT double-count,
 * because the shading normal REPLACES the mesh's facet normal rather than composing with it. It also
 * means near and far water are shaded by the identical expression, which was the requirement: crossing
 * the deformation boundary changes whether the shape is in the geometry, never which shape it is.
 */
public float waterSurfaceHeight(float2 worldXZ, float t, float cellSize) {
    return waterWaveHeight(worldXZ, t, cellSize) + waterSimHeight(worldXZ);
}

// ---- Water caustics. Sunlight refracting through the waved surface converges/diverges before it
// reaches an underwater receiver; the analytic wave field makes the true focusing factor computable
// instead of faked with a scrolling texture. Where the shadow ray of an underwater NEE vertex crossed
// water (returned by visibility() as VisibilityResult.waterHitT), evaluate the horizontal landing
// position of the refracted sun ray as a function of surface position and finite-difference it. The
// caustic intensity is the inverse Jacobian determinant of that surface→floor mapping (area compression = brightening, real
// fold caustics where det → 0). Because this uses the SAME wave field as the visible surface normals,
// the caustic pattern stays in sync with the ripples, and the per-sample sun-quad jitter (sampleSquare)
// shifts the pattern per sample → caustics physically blur with depth under DLSS-RR accumulation.
public static const float CAUSTIC_EPS = 0.25; // finite-difference step (m); also low-passes sub-step wave detail
public static const float CAUSTIC_MAX = 6.0;  // focusing clamp: keeps fold-line singularities from becoming fireflies

// Water's refractive index really does vary with wavelength — about 1.331 at 700 nm against 1.340 at
// 450 nm — so the three primaries refract at slightly different angles and their focusing patterns do
// not coincide. The visible result is coloured fringing along the bright filaments, which is where a
// fold line sits: det -> 0 there, so a fraction of a percent of extra bending moves the fold visibly
// even though it barely moves anything else. Half-spread from the green centre, scaled by the
// dispersion knob (worldPush.waterAmbient.w; 1 = these physical values, 0 = monochrome).
// MEASURED, and not what was expected: even at twenty times this, the three channels' focusing stays
// within a percent of each other — a debug view amplifying the deviation twentyfold showed neutral grey
// (debugView 11). Colour needs roughly a hundred times physical, which is why the config default is a
// large exaggeration rather than 1. The suspect is CAUSTIC_MAX below: the bright filaments all clamp to
// the same ceiling, so a fringe can only appear where the fold has shifted far enough that one channel
// clamps and another does not. Raising the ceiling would change the caustic's whole look, so it has not
// been touched on suspicion alone.
public static const float CAUSTIC_IOR_SPREAD = 0.0045;

// Surface normal at one wave-domain position. Split out from the landing calculation because the wave
// field is the only expensive part of a caustic and it does not know about colour: all three
// wavelengths share these, so dispersion costs three refracts and no extra field evaluations.
float3 causticNormal(float2 xz, float t) {
    float2 g = waterWaveGrad(xz, t);
    return normalize(float3(-g.x, 1.0, -g.y));
}

// Horizontal landing offset of a refracted sun ray entering the surface at world-stable xz with normal
// nw, intersected with the receiver plane h metres below. inc = sunlight travel direction (downward,
// unit). invEta = 1/n for this wavelength; air->water never total-internal-reflects.
public float2 causticLanding(float2 xz, float3 nw, float3 inc, float h, float invEta) {
    float3 rdw = refract(inc, nw, invEta);
    return xz + rdw.xz * (h / max(-rdw.y, 0.05));
}

public float3 waterCaustic(float3 exitPos, float3 lightDir, float waterDist) {
    // Grazing light: the receiver-plane intersection degenerates and caustics vanish in reality anyway
    // (long slant paths absorb + the pattern smears out) — fade the effect to neutral 1.0.
    float fade = smoothstep(0.06, 0.18, lightDir.y);
    if (fade <= 0.0) return float3(1.0, 1.0, 1.0);
    float h = max(waterDist * lightDir.y, 0.05); // vertical depth of the receiver below the exit point
    float t = worldPush.waterParams.w;
    float3 inc = -lightDir;
    float2 base = exitPos.xz + worldPush.waterAnchor.xy;  // world-stable wave domain (same as applyWaterWaves)
    // The three field evaluations, shared by every wavelength below.
    float3 n0 = causticNormal(base, t);
    float3 nx = causticNormal(base + float2(CAUSTIC_EPS, 0.0), t);
    float3 nz = causticNormal(base + float2(0.0, CAUSTIC_EPS), t);
    float spread = CAUSTIC_IOR_SPREAD * worldPush.waterAmbient.w;
    float3 focus;
    [unroll]
    for (int c = 0; c < 3; ++c) {
        // Red bends least, blue most, green at the base index — so red takes the LOWER index and blue
        // the higher. c = 0,1,2 -> -1,0,+1 of the spread. (Written the other way round first, which
        // would have inverted the fringe order without removing it.)
        //
        // Floored at 1.05 because the knob goes far past physical: a large enough spread drives the red
        // index below 1, at which point refract() sees the denser medium on the wrong side, can return
        // the zero vector, and that channel silently degenerates to no caustic at all — an exaggeration
        // control that quietly removes the thing it exaggerates is worse than one that saturates.
        float invEta = 1.0 / max(WATER_IOR + spread * float(c - 1), 1.05);
        float2 p0 = causticLanding(base, n0, inc, h, invEta);
        float2 px = causticLanding(base + float2(CAUSTIC_EPS, 0.0), nx, inc, h, invEta);
        float2 pz = causticLanding(base + float2(0.0, CAUSTIC_EPS), nz, inc, h, invEta);
        float det = abs((px.x - p0.x) * (pz.y - p0.y) - (px.y - p0.y) * (pz.x - p0.x));
        // Flat water ⇒ all landings displace identically ⇒ det = eps² ⇒ focus = 1 (energy-neutral); the
        // clamp bounds the fold-line spikes whose energy the surrounding darkening already paid for.
        focus[c] = min((CAUSTIC_EPS * CAUSTIC_EPS) / max(det, 1.0e-5), CAUSTIC_MAX);
    }
    return lerp(float3(1.0, 1.0, 1.0), focus, fade);
}
