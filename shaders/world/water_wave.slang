// The wave field itself: one directional spectrum, evaluated as a height, a gradient, or both.
//
// NO BINDINGS AND NO worldPush, ON PURPOSE. This is the module the shading and the geometry share, and
// they cannot share anything that reaches for set 0 -- the raygen has world_core's descriptor set, the
// displacement compute pass has its own, and dragging one into the other would mean declaring bindings a
// pass never uses. So everything here is pure arithmetic over its arguments, and the two callers wrap it
// with whatever resources they happen to hold.
//
// That the two consumers evaluate ONE implementation is the whole point (D46). A CPU or compute-side
// reimplementation of the spectrum would agree at first and drift the moment anyone touched a constant,
// and geometry disagreeing with its own shading is close to undiagnosable from a screenshot.
//
// Three things make it read as water rather than animated glass:
//  * Sharp-crested profile: each component is h = a·exp(S·(sin φ − 1)) — peaked crests, wide flat
//    troughs (pure sines look like rolling jelly). Still C∞ smooth with an exact analytic gradient.
//  * Real deep-water dispersion ω = √(g·k): long swell strides past (λ=14 m → ~4.7 m/s) while short
//    chop flutters nearly in place — wrong relative speeds are the biggest tell of fake water.
//  * Wind + meander: headings spread around a fixed wind direction (tight for the swell, wide for the
//    chop), and the largest components' phase is modulated along their crest (chain-ruled into the
//    gradient) so wavefronts wander instead of ruling straight periodic lines across the sea.
// Callers pass a world-anchored position so the pattern is pinned in world space.

module water_wave;

// Local rather than world_core's, which is unreachable from here and would drag set 0 in with it.
static const float PI = 3.14159265359;

public static const int WATER_SIM_DIM = 256;

// The base scale of the whole field, height and slope together.
//
// TUNED AGAINST REFLECTIONS, which is worth knowing now that geometry consumes the same number. It makes
// the per-component steepness a*k about 0.01-0.02; real water under a light breeze runs 0.05-0.15, so as
// SLOPE this is calm water and as HEIGHT it is +/-2.5 cm with a maximum surface tilt of 3.7 degrees --
// nearly invisible as displacement. Callers scale it by an authored factor (1 = exactly this, which is
// the shipped look); see FluoriteConfig.Rt.Water.WAVE_AMPLITUDE.
public static const float WATER_WAVE_STRENGTH = 0.3;
public static const float WAVE_SPEED = 0.8;           // global time scale; dispersion keeps relative speeds physical
public static const int   WAVE_COUNT = 10;            // λ = 14 m … 0.38 m (each octave 1.5× shorter)
public static const float WAVE_G = 9.81;
// The wind is an ARGUMENT now, not a constant (D51): one global heading drives the sea and both cloud
// layers, each with its own offset. It arrives as a unit vector rather than an angle so the caller
// computes the sincos once instead of once per spectrum walk.
//
// Only the HEADING is shared with the clouds and only the heading can be. Their speed is authored in
// blocks per second; this spectrum's is not authored at all -- it falls out of w = sqrt(g*k) below, one
// speed per wavelength. That relationship is what makes long swell stride past while short chop flutters
// nearly in place, and a shared speed would destroy it.
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
/**
 * Everything that shapes the sea, in one place.
 *
 * <p>A struct rather than a parameter list because this is the third time the spectrum has gained an
 * argument and every one of them had to be threaded through six signatures and two consumers. The
 * consumers build it; the walk below only reads it.
 *
 * <p>{@code complexity} at 0 is the shipped field EXACTLY -- crossWind equals wind, the fan keeps its
 * original angles, and the extra meander bands are silent. That is iron rule 8, and here it is also the
 * only way to tell what the knob actually bought.
 */
public struct WaterWaveParams {
    public float2 wind;        // primary heading, unit
    public float2 crossWind;   // the second wave system's heading; equals wind at complexity 0
    public float  strength;    // D49 amplitude, with the weather folded in
    public float  baseLength;  // D50
    public float  steepness;   // multiplies the authored a*k; storms are steeper, not just taller
    public float  complexity;  // 0 = shipped look, 1 = crossing swell + wider fan + more meander
    public float  gustScale;   // patch size in blocks; <= 0 disables the gusts entirely
    public float  gustStrength;
    public float  gustSpeed;
};

/** Deterministic per-component jitter in [-1, 1]. Cheap, and stable across frames and machines. */
float waterWaveJitter(int i, float salt) {
    float x = sin(float(i) * 12.9898 + salt * 78.233) * 43758.5453;
    return frac(x) * 2.0 - 1.0;
}

public void waterWaveSpectrum<let WITH_DT : int, let WITH_HEIGHT : int>(float2 p, float t, float footprint,
                                          WaterWaveParams params,
                                          out float height, out float2 grad, out float2 gradDt) {
    float strength = params.strength;
    float baseLength = params.baseLength;
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED;
    height = 0.0;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    // The whole spectrum hangs off this. Guarded because a zero would divide by zero in k and take the
    // surface with it, and the setting is reachable from a config file as well as from the menu.
    float lambda = max(baseLength, 0.5);
    float complexity = saturate(params.complexity);
    for (int i = 0; i < WAVE_COUNT; i++) {
        float lodWeight = waterWaveLodWeight(lambda, footprint);
        if (lodWeight <= 0.0) break;
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // TWO WAVE SYSTEMS, not one fan. Every third component travels on the second heading, so the
        // two interleave across the whole spectrum rather than splitting it into a long half and a short
        // half. What that buys is INTERFERENCE -- crossing seas make a shifting diamond lattice that
        // reads as far more complex than any single fan, and real water almost always has one, because a
        // swell outlives the wind that made it and arrives from wherever that was.
        //
        // At complexity 0 crossWind equals wind, so this costs a select and changes nothing.
        float2 base = ((i % 3) == 1) ? params.crossWind : params.wind;
        // Heading: deterministic irregular spread about the wind, widening down the spectrum. The jitter
        // is what stops the ten angles reading as a comb; it is off at complexity 0.
        float spread = (0.15 + 0.11 * float(i)) * sin(float(i) * 2.4 + 1.3)
                     + complexity * 0.30 * waterWaveJitter(i, 1.0);
        float cs = cos(spread), sn = sin(spread);
        float2 d = float2(base.x * cs - base.y * sn, base.x * sn + base.y * cs);
        float ph = k * dot(d, p) - w * t + 1.7 * float(i) * float(i);
        float2 dph = k * d;
        float phDt = -w;                      // ∂φ/∂t
        float2 dphDt = float2(0.0, 0.0);      // ∂(∇φ)/∂t
        // Meander: the phase wobbles along the crest, so wavefronts wander instead of ruling straight
        // lines. It used to stop at the four longest components, which left the mid band as visible
        // straight edges; complexity extends it to seven, fading in so 0 is unchanged.
        float meander = i < 4 ? WAVE_MEANDER : (i < 7 ? WAVE_MEANDER * complexity : 0.0);
        if (meander > 0.0) {
            float2 pd = float2(-d.y, d.x);
            float wph = 0.4 * k * dot(pd, p) + 0.5 * w * t;
            ph += meander * sin(wph);
            dph += (meander * cos(wph) * 0.4 * k) * pd;
            if (WITH_DT != 0) {
                float wphDt = 0.5 * w;
                phDt += meander * cos(wph) * wphDt;
                dphDt = (-meander * sin(wph) * wphDt * 0.4 * k) * pd;
            }
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // a = Q/k; h = a·e^{S(sin φ−1)}; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float amplitude = lodWeight * (Q[i] * params.steepness / k) * S;
        grad += amplitude * cosPh * e * dph;
        if (WITH_HEIGHT != 0) {
            // The mean comes off weighted by lodWeight too — otherwise a component fading out of band
            // would leave its offset behind and the surface would drift as the band limit moved.
            height += lodWeight * (Q[i] * params.steepness / k) * (e - WAVE_PROFILE_MEAN[i]);
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
    // ONE SCALE FOR BOTH. Height and gradient are the same function, so they take the same factor --
    // scaling them apart would mean shading a surface the geometry is not.
    float scale = WATER_WAVE_STRENGTH * strength;
    height *= scale;
    grad *= scale;
    gradDt *= scale;
}

public float2 waterWaveGrad(float2 p, float t, float footprint, WaterWaveParams params) {
    float height;
    float2 grad, gradDt;
    waterWaveSpectrum<0, 0>(p, t, footprint, params, height, grad, gradDt);
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
public float waterWaveHeight(float2 p, float t, float footprint, WaterWaveParams params) {
    float height;
    float2 grad, gradDt;
    waterWaveSpectrum<0, 1>(p, t, footprint, params, height, grad, gradDt);
    return height;
}

public float2 waterWaveGrad(float2 p, float t, WaterWaveParams params) {
    return waterWaveGrad(p, t, 0.0, params);
}

// Evaluate the current gradient and linearly rewind it to the previous phase in one spectrum walk.
// Frame deltas are bounded on the CPU, so this avoids a second set of trigonometric/exponential work
// while remaining accurate over the sub-frame interval used by reflection reprojection.
public void waterWaveGradTemporal(float2 p, float currentT, float previousT, float footprint,
                           WaterWaveParams params,
                           out float2 currentGrad, out float2 previousGrad) {
    float height;
    float2 gradDt;
    waterWaveSpectrum<1, 0>(p, currentT, footprint, params, height, currentGrad, gradDt);
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
// WHERE IT GETS ADDED lives in water.slang, at applyWaterWaves and applyWaterWavesTemporal. Both, not
// one: the primary pass needs a previous normal too, and for a whole milestone it built that pair itself
// and omitted the simulation, so ripples were missing from precisely the surface anyone looks at. The
// comment that used to sit here claimed a single entry point, which is what stopped anyone checking.

// ---- The simulation domain's mapping, shared by every consumer so they cannot disagree about it.
//
// PARAMETERISED BY THE RESOURCES rather than reading them, because the two callers hold different ones:
// the shading has the set-0 binding and worldPush, the displacement pass has its own descriptor and its
// own push constants. Same arithmetic either way -- and if the gradient and the height disagreed about
// the mapping or the edge fade, the geometry would keep rippling past where the shading stopped.
//
// `domain` is xy origin (in the caller's space), z blocks per cell, w strength. Returns false when the
// simulation contributes nothing here: switched off (z <= 0), or outside the grid.
public bool waterSimSample(float4 domain, float4 plane, float3 world, out float2 uv, out float weight) {
    float2 worldXZ = world.xz;
    uv = float2(0.0, 0.0);
    weight = 0.0;
    float cellSize = domain.z;
    if (cellSize <= 0.0) {
        return false; // simulation off: the spectrum stands alone, bit for bit as before
    }
    // ON THIS PLANE, or not at all. The field is two-dimensional, so without this every water surface
    // sharing a column with the simulated one gets its ripples -- a pool under a waterfall answering a
    // splash in the lake above it. Faded rather than clipped so a surface that is merely near the plane
    // (a flowing block, a lily-covered edge) does not pop.
    if (plane.y > 0.0) {
        float offPlane = abs(world.y - plane.x);
        weight = 1.0 - smoothstep(plane.y * 0.5, plane.y, offPlane);
        if (weight <= 0.0) {
            return false;
        }
    } else {
        weight = 1.0;
    }
    float planeWeight = weight;
    float2 cell = (worldXZ - domain.xy) / cellSize;
    uv = cell / float(WATER_SIM_DIM);
    if (any(uv < 0.0) || any(uv > 1.0)) {
        return false;
    }
    // Fade over the outer eighth. The domain is a rectangle that follows the player, and a rectangle's
    // edge is the one thing in this system with no physical meaning at all -- so nothing may be visible
    // there. Both axes, multiplied, so the corners fade too.
    float2 edge = min(uv, 1.0 - uv);
    weight = planeWeight * smoothstep(0.0, 0.125, edge.x) * smoothstep(0.0, 0.125, edge.y) * domain.w;
    return true;
}

/** The simulated displacement at a world position, in metres. Zero outside the domain. */
public float waterSimHeightOf(Sampler2D<float> tex, float4 domain, float4 plane, float3 world) {
    float2 uv;
    float weight;
    if (!waterSimSample(domain, plane, world, uv, weight)) {
        return 0.0;
    }
    return tex.SampleLevel(uv, 0.0) * weight;
}

/** The simulated slope at a world position. Central differences on the FILTERED height. */
public float2 waterSimGradOf(Sampler2D<float> tex, float4 domain, float4 plane, float3 world) {
    float2 uv;
    float weight;
    if (!waterSimSample(domain, plane, world, uv, weight)) {
        return float2(0.0, 0.0);
    }
    // The texture is filtered, so this is the slope of the interpolated surface rather than of the
    // lattice -- which is what the shading wants, and it is also why the sponge's non-physical edge band
    // never shows: the fade above has already reached zero before the sampler gets there.
    float texel = 1.0 / float(WATER_SIM_DIM);
    float hx0 = tex.SampleLevel(uv - float2(texel, 0.0), 0.0);
    float hx1 = tex.SampleLevel(uv + float2(texel, 0.0), 0.0);
    float hz0 = tex.SampleLevel(uv - float2(0.0, texel), 0.0);
    float hz1 = tex.SampleLevel(uv + float2(0.0, texel), 0.0);
    return float2(hx1 - hx0, hz1 - hz0) / (2.0 * domain.z) * weight;
}

/**
 * The water surface's height above the still-water plane, in metres -- the geometry counterpart of
 * applyWaterWaves, and the only thing the vertex displacement pass evaluates.
 *
 * <p>`cellSize` is the mesh's spacing, and it band-limits the spectrum to what that mesh can carry (see
 * waterWaveLodWeight). Total = spectrum + simulation, the same sum applyWaterWaves takes slopes of, so
 * the displaced surface is the surface the shading describes.
 *
 * <p>NOTE WHAT IS NOT HERE: no second waveform, no separate displacement amplitude, no split of the
 * field between geometry and normal. The mesh carries a band-limited version of the one field and the
 * shading normal stays the analytic normal of the whole of it. That is ordinary normal mapping -- the
 * geometry is a low-frequency proxy and the normal carries the rest -- and it does NOT double-count,
 * because the shading normal REPLACES the mesh's facet normal rather than composing with it. It also
 * means near and far water are shaded by the identical expression, which was the requirement: crossing
 * the deformation boundary changes whether the shape is in the geometry, never which shape it is.
 */
/**
 * The simulated height, LOW-PASSED to what a mesh of the given spacing can actually hold.
 *
 * <p>WITHOUT THIS A RIPPLE BECOMES A SPIKE. The simulation resolves features down to a couple of its own
 * cells -- tens of centimetres -- while the water mesh has a vertex per block. Sampling that field at
 * those vertices is straightforward aliasing: whichever vertex happens to land near the peak takes the
 * whole of it and its neighbours take nothing, so a smooth bump arrives as one sharp triangle. It is the
 * same failure the spectrum avoids by weighting components against the footprint; the simulation reaches
 * the mesh through a texture instead of through a sum, so it needs a filter rather than a weight.
 *
 * <p>A box over the mesh cell, using the sampler's own bilinear filtering between taps, so what survives
 * is the part of the disturbance whose wavelength the mesh can represent. A broad swell from a big splash
 * still moves the geometry; the fine ripples do not, and stay in the normal where they always were.
 * Subdividing the mesh (D48) shrinks the box on its own and lets them through with no change here.
 */
float waterSimHeightBandLimited(Sampler2D<float> tex, float4 domain, float4 plane, float3 world,
                                float cellSize) {
    float2 uv;
    float weight;
    if (!waterSimSample(domain, plane, world, uv, weight)) {
        return 0.0;
    }
    // Taps spread over the cell, in uv. Below about two simulation cells there is nothing to filter and
    // the centre tap is the honest answer.
    float span = 0.5 * max(cellSize, 0.0) / (domain.z * float(WATER_SIM_DIM));
    if (span <= 0.5 / float(WATER_SIM_DIM)) {
        return tex.SampleLevel(uv, 0.0) * weight;
    }
    float sum = 0.0;
    [unroll]
    for (int j = 0; j < 4; ++j) {
        [unroll]
        for (int i = 0; i < 4; ++i) {
            // Tap centres at -3/4, -1/4, +1/4, +3/4 of the span: a 4x4 box whose samples each stand for
            // an equal quarter of the cell.
            float2 o = float2(float(i) * 0.5 - 0.75, float(j) * 0.5 - 0.75) * span;
            sum += tex.SampleLevel(uv + o, 0.0);
        }
    }
    return sum * (1.0 / 16.0) * weight;
}

public float waterSurfaceHeight(Sampler2D<float> simTex, float4 simDomain, float4 simPlane,
                                float3 world, float t, float cellSize, WaterWaveParams params) {
    return waterWaveHeight(world.xz, t, cellSize, params)
            + waterSimHeightBandLimited(simTex, simDomain, simPlane, world, cellSize);
}
