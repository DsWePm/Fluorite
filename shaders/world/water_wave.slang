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
// The shipped global time scale. Authored on top of it (D57), never per component.
//
// A UNIFORM time scale is the safe kind of speed control: it scales every wave equally, so the relation
// between them survives -- long swell still strides past while short chop flutters nearly in place, which
// is the thing that reads as water. A per-wavelength speed knob would break exactly that, and there is
// deliberately no way to ask for one.
public static const float WAVE_SPEED = 0.8;
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
// Fades from a footprint of lambda/8 to lambda/2.5, where it used to run lambda/4 to lambda/2.
//
// The old window reached zero exactly at Nyquist, which is textbook and is still too late in practice:
// a wave is already visibly aliasing well before its wavelength equals two footprints, because the
// footprint is a disc against a wave that is a ridge, and because the surface is shaded through a
// NORMAL -- slope aliases harder than height. The shortest components (0.8 m and under) were arriving
// as moire read as straight lines drawn across the sea.
//
// The cost of fading earlier is a slightly softer surface at middle distances. That is the right side to
// err on: a wave that is too smooth looks like calm water, and a wave that is undersampled looks like a
// rendering artefact.
public float waterWaveLodWeight(float wavelength, float footprint) {
    float cyclesPerPixel = max(footprint, 0.0) / max(wavelength, 1.0e-4);
    return 1.0 - smoothstep(0.125, 0.40, cyclesPerPixel);
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
    public float  timeScale;       // D57; 1 = the shipped speed
    public float  warpAmplitude;   // blocks of domain bend; 0 disables it and its noise fetches
    public float  warpScale;       // blocks between bends
    // ISOLATION (D54). Which components run, 1-based and inclusive, and whether the distance-based
    // band limit runs at all. Diagnostics, not art: a sum of ten waves has no way of telling you
    // WHICH of them you are looking at, and reading the code has now failed at that four times.
    public int    firstComponent;
    public int    lastComponent;
    public float  lodFade;         // 1 = the usual footprint band limit, 0 = every component always on
};

// ---- Gusts: the patchwork of ruffled and glassy water that makes a sea look like weather rather
// than like a pattern. Two things about the design are not decoration:
//
// IT DRIFTS WITH THE WIND. A patch field pinned to the world is a stain on the sea -- the waves move
// through it while it sits still, which reads as worse than no patches at all.
//
// IT IS WEIGHTED TOWARD THE SHORT COMPONENTS. A gust RUFFLES the surface; it does not raise the swell.
// A gust front passing over open water darkens it with chop while the long waves underneath carry on
// unchanged, and modulating everything equally instead gives a sea that breathes in and out, which is a
// completely different and much worse effect.
// Which wavelengths a gust ruffles.
//
// This used to be 2 m to 8 m, which was physically defensible and practically invisible: those are the
// components the band limit fades first, so the gusts were modulating waves that had already gone. The
// patchwork only showed within a few metres of the camera, which is not where anyone looks at a sea.
//
// Moved up to bite the components that actually survive at distance. Still weighted -- a gust ruffles a
// surface and does not raise a swell -- but the band it ruffles is now one you can see.
public static const float GUST_LAMBDA_LO = 4.0;   // fully ruffled below this wavelength
public static const float GUST_LAMBDA_HI = 16.0;  // untouched above it
public static const float GUST_BASE = 0.40;
public static const float GUST_RANGE = 1.20;      // so the field averages 1 and spans about 0.4 .. 1.6

// GRADIENT noise, not value noise, and that is a correctness choice rather than a refinement.
//
// Value noise interpolates a random number stored AT each lattice point, so the extremes sit on the
// lattice and the result carries the lattice's own axis-aligned structure -- a visible plus-and-grid
// pattern, which on a modulated water surface reads as a cross-hatch scored across the sea. Gradient
// noise stores a random DIRECTION instead and is zero at every lattice point, so the grid has nothing
// to show. It costs one dot product more per corner.
//
// The octaves are also ROTATED against each other by an angle with no rational relationship to the
// axes, so whatever residual alignment each one has cannot stack with the next.
// INTEGER hashing, not sin/frac, and the reason is measured. The old hash produced only about 660
// distinct values across a 40x40 patch of 1600 cells -- roughly two in five cells sharing a neighbour's
// gradient direction. Cells that hash alike are cells whose noise agrees, and agreement laid out on a
// lattice is visible structure: creases and seams sitting at fixed places in the world.
//
// It also degrades with coordinate magnitude, which sin/frac hashing always does: the wave domain runs
// out to 4096 blocks (the anchor mask), and sin of a five-digit argument has very little fraction left
// in fp32. An integer hash has no such range at all -- every bit is as good at 4096 as at zero.
uint waterHashU(int2 c, uint seed) {
    uint2 v = uint2(c) + seed * 0x9E3779B9u;
    v = v * 1664525u + 1013904223u;
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    v = v ^ (v >> 16u);
    v.x += v.y * 1664525u;
    v.y += v.x * 1664525u;
    return v.x ^ v.y;
}

float2 waterHashDir(float2 i, float seed) {
    // Top 24 bits into a full turn: the low bits of any multiplicative hash are the weakest, and a
    // direction wants the whole circle rather than a well-behaved corner of it.
    uint h = waterHashU(int2(floor(i)), uint(seed));
    float ang = float(h >> 8u) * (6.28318530718 / 16777216.0);
    return float2(cos(ang), sin(ang));
}

/**
 * Signed gradient noise, AND its analytic derivative.
 *
 * <p>Quintic interpolation, not the usual smoothstep: its second derivative vanishes at both ends, so
 * the field is C2 across cell boundaries. That matters here in particular because this noise gets
 * differentiated -- with a cubic the gradient has a kink at every boundary, a faint lattice drawn into
 * the slope rather than into the value.
 *
 * <p>The derivative comes out of the four corner gradients that were fetched anyway, so it needs NO
 * extra hashes -- and hashes are the entire cost. Finite-differencing instead would have meant four more
 * noise evaluations at a site that runs twice per shaded point.
 *
 * <p>Measured: none of this fixes the ~7% axis-versus-diagonal anisotropy, which is inherent to a square
 * lattice with separable interpolation and would need simplex noise. That residual is small and
 * undirected; the boundary kinks were neither.
 */
void waterGradientNoiseD(float2 p, float seed, out float n, out float2 dn) {
    float2 i = floor(p);
    float2 f = p - i;
    float2 u  = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float2 du = 30.0 * f * f * (f - 1.0) * (f - 1.0);
    float2 g00 = waterHashDir(i, seed);
    float2 g10 = waterHashDir(i + float2(1.0, 0.0), seed);
    float2 g01 = waterHashDir(i + float2(0.0, 1.0), seed);
    float2 g11 = waterHashDir(i + float2(1.0, 1.0), seed);
    float a = dot(g00, f);
    float b = dot(g10, f - float2(1.0, 0.0));
    float c = dot(g01, f - float2(0.0, 1.0));
    float d = dot(g11, f - float2(1.0, 1.0));
    float k1 = b - a;
    float k2 = c - a;
    float k3 = a - b - c + d;
    n = a + k1 * u.x + k2 * u.y + k3 * u.x * u.y;
    // Two terms, both required: the corner values are themselves linear in f, and the interpolant is a
    // function of f as well. Dropping either leaves a gradient that is not this field's.
    float2 dLin = g00 + (g10 - g00) * u.x + (g01 - g00) * u.y + (g00 - g10 - g01 + g11) * u.x * u.y;
    dn = dLin + float2(du.x * (k1 + k3 * u.y), du.y * (k2 + k3 * u.x));
}

/** Value-only, remapped to 0..1, for the callers that do not differentiate it. */
float waterGradientNoise(float2 p) {
    float n;
    float2 dn;
    waterGradientNoiseD(p, 0.0, n, dn);
    return saturate(n * 0.7 + 0.5);
}

/**
 * Bend the sample position with a slow noise, and return the Jacobian of that bend.
 *
 * <p>WHY THIS EXISTS. A sum of ten plane waves is quasi-periodic no matter how its wavelengths and
 * headings are arranged: measured, nothing -- wavelength jitter, irrational ratios, a wider fan, a
 * flatter amplitude law, even going to thirty-two components -- brought the long-range autocorrelation
 * below about 0.42, and the original scored 0.69, which is the lattice people see. Warping the domain
 * took it to 0.056 for one noise fetch, because it attacks the cause: the phase relationships between
 * components stop being constant across the world.
 *
 * <p>It also is not a cheat. Real water refracts over currents and bathymetry, which bends crests in
 * exactly this way. It stops being physical when the warp approaches a wavelength, which is why the
 * amplitude is authored and its sane range is well under the swell's own length.
 *
 * <p>THE JACOBIAN IS NOT OPTIONAL. Everything downstream evaluates the field at the warped position, so
 * the gradient that comes back is with respect to THAT position; the chain rule has to bring it back to
 * the real one, or the shading normal and the displaced geometry stop describing the same surface.
 */
void waterWarp(float2 p, float amplitude, float scale,
               out float2 warped, out float2 jRow0, out float2 jRow1) {
    warped = p;
    jRow0 = float2(1.0, 0.0);
    jRow1 = float2(0.0, 1.0);
    if (amplitude <= 0.0 || scale <= 0.0) {
        return;
    }
    float inv = 1.0 / scale;
    float2 q = p * inv;
    float nx, nz;
    float2 dnx, dnz;
    waterGradientNoiseD(q, 0.0, nx, dnx);
    waterGradientNoiseD(q, 37.0, nz, dnz);
    warped = p + amplitude * float2(nx, nz);
    // J = I + (amplitude/scale) * [dnx; dnz]
    float s = amplitude * inv;
    jRow0 = float2(1.0, 0.0) + s * dnx;
    jRow1 = float2(0.0, 1.0) + s * dnz;
}

/** Two octaves: big patches with smaller ones inside them, which is what a gusty surface looks like. */
float waterGustNoise(float2 q) {
    // 0.8 rad is not special beyond being irrational against the axes -- the point is only that the
    // second octave's lattice does not line up with the first's.
    const float2x2 R2 = float2x2(0.6967, -0.7174, 0.7174, 0.6967);
    return 0.65 * waterGradientNoise(q) + 0.35 * waterGradientNoise(mul(R2, q) * 2.1 + 17.0);
}

/**
 * The gust multiplier at a point, AND its spatial gradient.
 *
 * <p>The gradient is not optional. This multiplies each component's amplitude, so the field it produces
 * is g(p)*h(p) -- and the derivative of that is g*grad(h) + h*grad(g), not g*grad(h). Dropping the second
 * term would leave the returned gradient no longer the gradient of the returned height, which is exactly
 * the property the geometry and the shading rely on to describe one surface. The error would be small
 * (the patches vary twenty times slower than the waves they modulate) and permanent.
 *
 * <p>Finite-differenced rather than derived analytically: the step is tiny against the patch size, the
 * field is smooth at that scale, and three noise fetches per shaded point is cheaper than being clever.
 */
void waterGustField(float2 worldXZ, float t, WaterWaveParams params,
                    out float gust, out float2 gustGrad) {
    gust = 1.0;
    gustGrad = float2(0.0, 0.0);
    if (params.gustScale <= 0.0 || params.gustStrength <= 0.0) {
        return;
    }
    float inv = 1.0 / max(params.gustScale, 1.0);
    float2 q = (worldXZ - params.wind * (params.gustSpeed * t)) * inv;
    const float eps = 0.05;
    float n0 = waterGustNoise(q);
    float nx = waterGustNoise(q + float2(eps, 0.0));
    float nz = waterGustNoise(q + float2(0.0, eps));
    gust = GUST_BASE + GUST_RANGE * n0;
    gustGrad = (GUST_RANGE * inv / eps) * float2(nx - n0, nz - n0);
}

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
    // Once per evaluation, not once per component -- the patches are a property of the place, not of the
    // wavelength. Only the WEIGHT below is per-component.
    float gust;
    float2 gustGrad;
    waterGustField(p, t, params, gust, gustGrad);
    // Bend the world the waves travel through. Everything below evaluates at `pw`; the Jacobian brings
    // the resulting gradient back to `p` at the very end.
    float2 pw;
    float2 jRow0, jRow1;
    waterWarp(p, params.warpAmplitude, params.warpScale, pw, jRow0, jRow1);
    p = pw;
    // Per-wave steepness a·k: gentle in the long swell, concentrated in the mid/short chop.
    const float Q[WAVE_COUNT] = { 0.030, 0.035, 0.042, 0.050, 0.060, 0.065, 0.065, 0.058, 0.048, 0.038 };
    t *= WAVE_SPEED * params.timeScale;
    height = 0.0;
    grad = float2(0.0, 0.0);
    gradDt = float2(0.0, 0.0);
    // The whole spectrum hangs off this. Guarded because a zero would divide by zero in k and take the
    // surface with it, and the setting is reachable from a config file as well as from the menu.
    float lambda = max(baseLength, 0.5);
    float complexity = saturate(params.complexity);
    for (int i = 0; i < WAVE_COUNT; i++) {
        // The band limit, and the switch that silences it. With lodFade at 0 every component runs at
        // full weight regardless of distance -- which aliases badly, and is exactly what you want when
        // the question is whether a visible ring IS the band limit.
        float lodWeight = lerp(1.0, waterWaveLodWeight(lambda, footprint), params.lodFade);
        if (lodWeight <= 0.0) break;
        // Isolation: skip this component entirely, but keep walking so the wavelength ladder below still
        // advances. `continue` rather than a narrower loop, so component 5 is component 5 either way.
        if (i + 1 < params.firstComponent || i + 1 > params.lastComponent) {
            lambda /= 1.5;
            continue;
        }
        float k = 2.0 * PI / lambda;
        float w = sqrt(WAVE_G * k);   // deep-water dispersion: phase speed √(g/k)
        // TWO WAVE SYSTEMS, not one fan. Every third component travels on the second heading, so the
        // two interleave across the whole spectrum rather than splitting it into a long half and a short
        // half. What that buys is INTERFERENCE -- crossing seas make a shifting diamond lattice that
        // reads as far more complex than any single fan, and real water almost always has one, because a
        // swell outlives the wind that made it and arrives from wherever that was.
        //
        // At complexity 0 crossWind equals wind, so this costs a select and changes nothing.
        // Which of the two wave systems this component belongs to, by HASH rather than by i % 3.
        //
        // The modulo put components 2, 5 and 8 on the second system and left every other one on the
        // first, so the short end -- 9 and 10 -- could never respond to the crossing angle at all. Their
        // headings sit at +58.8 and -51.5 degrees off the wind, which meant a fixed 110 degrees between
        // them no matter what the crossing angle was set to.
        //
        // At complexity 0 the two systems are the same vector, so this still costs a select and changes
        // nothing.
        float2 base = (waterWaveJitter(i, 2.0) > 0.0) ? params.crossWind : params.wind;
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
        // EVERY component, not just the long ones. The cut-off used to be four, on the reasoning that
        // only long wavefronts "read as lines" -- which is exactly backwards. The three shortest
        // components got no meander at all, so they stayed pure plane waves, and three pure plane waves
        // under a metre stacked up as regular corrugation: the close-range ribbing that looks like a
        // sheet-metal roof. The long waves were never the ones drawing straight lines.
        //
        // Nor is this gated on complexity any more. Straight crests are a defect, not a style.
        float meander = WAVE_MEANDER;
        if (meander > 0.0) {
            // TWO modulations, not one, and this is a fix rather than a refinement.
            //
            // A single sinusoid at a fixed multiple of k is itself PERFECTLY PERIODIC: crests every
            // lambda along the travel direction, a crest wobble every 2.5 lambda across it. Those two
            // periods together are a parallelogram lattice -- so the meander did not remove the regular
            // structure it was added to remove, it exchanged straight ruled lines for a grid. The three
            // dominant components sit within sixteen degrees of each other, so their lattices reinforced
            // instead of cancelling, and the grid drifted crossways at half rate against the waves,
            // which is why it did not even look like it belonged to them.
            //
            // Two modulations at an irrational frequency ratio, along two axes that are not the same,
            // with the ratio walking per component so no two share a lattice. Nothing closes.
            float2 pd = float2(-d.y, d.x);
            // Tuned against the measurement rather than by eye: correlation of the field with itself
            // ACROSS the crests, at multiples of the old meander period. The number that matters is the
            // FAR one -- near-field correlation is just the waves being waves, while correlation that
            // survives a hundred metres is a lattice you can see. The original scored +0.35 at 105 m and
            // this scores +0.07, with the rms height unchanged to three digits.
            //
            // Both frequencies walk with the component index so no two components share a lattice, the
            // second axis is well away from perpendicular, and the weights are near equal so neither
            // modulation dominates enough to reimpose its own period.
            float2 md = normalize(pd + 0.70 * d);
            // BOUNDED, not a ramp. These used to walk linearly with the index, which was fine while the
            // meander stopped at component four (0.33k to 0.51k) and becomes nonsense extended to ten:
            // at 0.86k the modulation is nearly as fast as the wave it modulates, which does not make a
            // crest wander, it scrambles it into harmonics. A crest reads as WANDERING when its wobble
            // spans several wavelengths, so both frequencies stay inside the band that means -- jittered
            // per component so no two share a lattice, rather than ramped into a regime that does not
            // work.
            float f1 = (0.22 + 0.13 * (waterWaveJitter(i, 5.0) * 0.5 + 0.5)) * k;
            float f2 = (0.17 + 0.11 * (waterWaveJitter(i, 8.0) * 0.5 + 0.5)) * k;
            float wph1 = f1 * dot(pd, p) + 0.50 * w * t;
            float wph2 = f2 * dot(md, p) - 0.31 * w * t;
            float c1 = 0.46, c2 = 0.54;
            ph += meander * (c1 * sin(wph1) + c2 * sin(wph2));
            dph += (meander * c1 * cos(wph1) * f1) * pd
                 + (meander * c2 * cos(wph2) * f2) * md;
            if (WITH_DT != 0) {
                float w1Dt = 0.50 * w;
                float w2Dt = -0.31 * w;
                phDt += meander * (c1 * cos(wph1) * w1Dt + c2 * cos(wph2) * w2Dt);
                dphDt = (-meander * c1 * sin(wph1) * w1Dt * f1) * pd
                      + (-meander * c2 * sin(wph2) * w2Dt * f2) * md;
            }
        }
        float S = 1.2 + 0.12 * float(i); // crest sharpness: the short chop is choppier
        float sinPh = sin(ph);
        float cosPh = cos(ph);
        float e = exp(S * (sinPh - 1.0));
        // How much this component feels the gust. Short waves take all of it, the swell none.
        float gustWeight = params.gustStrength
                * (1.0 - smoothstep(GUST_LAMBDA_LO, GUST_LAMBDA_HI, lambda));
        float g = lerp(1.0, gust, gustWeight);
        // a = Q/k; h = a·e^{S(sin φ−1)}; ∇h = a·S·cos φ·e^{S(sin φ−1)}·∇φ
        float aBase = lodWeight * (Q[i] * params.steepness / k);
        float amplitude = aBase * S;
        // This component's own height, needed by the product rule below whether or not the caller wanted
        // a height back. One multiply against a sin, a cos and an exp -- it does not show.
        float hi = aBase * (e - WAVE_PROFILE_MEAN[i]);
        // grad(g*h) = g*grad(h) + h*grad(g). The second term is what keeps the returned gradient the
        // true gradient of the returned height across a gust boundary.
        grad += g * (amplitude * cosPh * e * dph) + hi * (gustWeight * gustGrad);
        if (WITH_HEIGHT != 0) {
            // The mean comes off weighted by lodWeight too — otherwise a component fading out of band
            // would leave its offset behind and the surface would drift as the band limit moved.
            height += g * hi;
        }
        if (WITH_DT != 0) {
            // ∂/∂t of the term above, chain-ruled through φ and ∇φ.
            // The gust field's own drift is not chain-ruled in: it moves at metres per second across
            // patches tens of metres wide, so its contribution to how fast the NORMAL turns is orders
            // below the waves' own. This term is a reprojection hint, not the normal itself.
            gradDt += g * amplitude * e
                    * (phDt * (-sinPh + S * cosPh * cosPh) * dph + cosPh * dphDt);
        }
        lambda /= 1.5;
    }
    // Back out of the warped space: d/dp = J^T * d/dpw. Height needs no transform -- it is a scalar
    // sampled at a moved point -- but every gradient does, including the time derivative, which is a
    // spatial gradient wearing a different hat.
    grad = float2(jRow0.x * grad.x + jRow1.x * grad.y,
                  jRow0.y * grad.x + jRow1.y * grad.y);
    gradDt = float2(jRow0.x * gradDt.x + jRow1.x * gradDt.y,
                    jRow0.y * gradDt.x + jRow1.y * gradDt.y);
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
