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

public static const float WATER_WAVE_STRENGTH = 0.3;  // slope scale (higher = choppier glints / more broken reflections)
public static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
public static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
public static const float WAVE_G = 9.81;
public static const float2 WAVE_WIND = normalize(float2(1.0, 0.35)); // dominant travel direction
public static const float WAVE_MEANDER = 1.1;         // crest phase-wobble amplitude (rad) on the long components
public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.25, 0.5, cyclesPerPixel);
}

// One walk of the wave spectrum, shared by every consumer so the spectrum constants exist once. WITH_DT
// is a generic so the derivative terms are compiled out entirely for callers that only want the
// gradient — the trigonometry is shared, but the chain-rule work is not free.
public void waterWaveSpectrum<let WITH_DT : int>(float2 p, float t, float footprint,
                                          out float2 grad, out float2 gradDt) {
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
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
        // a = Q/k; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float amplitude = lodWeight * (Q[i] / k) * S;
        grad += amplitude * cosPh * e * dph;
        if (WITH_DT != 0) {
            // ∂/∂t of the term above, chain-ruled through φ and ∇φ.
            gradDt += amplitude * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= 1.5;
    }
    grad *= WATER_WAVE_STRENGTH;
    gradDt *= WATER_WAVE_STRENGTH;
}

public float2 waterWaveGrad(float2 p, float t, float footprint) {
    float2 grad, gradDt;
    waterWaveSpectrum<0>(p, t, footprint, grad, gradDt);
    return grad;
}

public float2 waterWaveGrad(float2 p, float t) {
    return waterWaveGrad(p, t, 0.0);
}

// Evaluate the current gradient and linearly rewind it to the previous phase in one spectrum walk.
// Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential work
// while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           out float2 currentGrad, out float2 previousGrad) {
    float2 gradDt;
    waterWaveSpectrum<1>(p, currentT, footprint, currentGrad, gradDt);
    previousGrad = currentGrad - (currentT - previousT) * WAVE_SPEED * gradDt;
}

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint);
    float3 up = normalize(float3(-grad.x, 1.0, -grad.y));
    return nGeo.y >= 0.0 ? up : -up;
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
