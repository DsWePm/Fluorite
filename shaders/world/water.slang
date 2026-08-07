// Water surface, as the RAY TRACER sees it: the wave field wrapped in the descriptors and push
// constants of set 0, the normal perturbation it drives, and the caustic term that differentiates the
// same field. Depends on core.
//
// THE FIELD ITSELF LIVES IN water_wave, which has no bindings, so the displacement compute pass can
// share it (M12.5). What is left here is the wrapping: which texture the simulation is in, which lane of
// worldPush its domain arrived in. Keep it that way -- anything moved back up here stops being shareable
// and the geometry starts drifting from its own shading.

import world_common;
import world_core;
// Re-exported: the split is an implementation detail of sharing the field with the compute pass, and
// callers that were reaching for the spectrum before it moved should not have to know it happened.
__exported import water_wave;

// The simulated height field (M12), in metres of displacement. RAYGEN and closest-hit both reach it
// through applyWaterWaves.
[[vk::binding(18, 0)]] Sampler2D<float> waterSimHeightTex;

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
float2 waterSimGrad(float2 worldXZ) {
    return waterSimGradOf(waterSimHeightTex, worldPush.waterSimDomain, worldXZ);
}

// The authored scale of the whole wave field (D49). Named here so the lane it rides in is written down
// once: waterAmbient.xyz were retired in M16 and have been held at zero since only to avoid ABI churn,
// which is precisely what a dead lane is for -- x now carries this and WorldPush does not grow.
// 1 is the shipped look exactly.
public float waterWaveAmplitude() {
    return worldPush.waterAmbient.x;
}

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
/**
 * Debug view 24: the simulated field AS THE SHADING SAMPLES IT, painted on the water in world space.
 *
 * <p>The companion to view 23, and the two answer different halves of one question. View 23 paints the
 * texture across the screen, so it proves only that the field HAS content — it maps screen uv straight to
 * texels and never touches the world-to-domain mapping the shading depends on. A field that simulates
 * perfectly and reaches nothing looks identical to a working one there. This view goes the other way: it
 * takes the water hit's own position, puts it through the same waterSimSample the normal path uses, and
 * shows what came back.
 *
 * <p>{@code ro} is the queued record's origin — where the primary pass stopped, so for a pixel looking at
 * water it is that surface point. Taken from the queue rather than re-traced, because a diagnostic that
 * traces its own ray is testing its own ray.
 *
 * <ul>
 *   <li><b>RED</b> — waterSimSample declined: off, or this point is outside the domain. The shading gets
 *       exactly zero here, whatever view 23 shows.</li>
 *   <li><b>BLUE</b> — inside the domain, tracking the edge fade. This is the domain's footprint made
 *       visible: it should be a patch around you, and it should stay put in the world as you move.</li>
 *   <li><b>GREEN</b> — the slope waterSimGrad actually returns, scaled so the procedural spectrum's own
 *       median slope would read about a third. Green is the whole point: it means the simulation IS
 *       reaching the normal, and anything still invisible is a matter of amplitude rather than plumbing.
 *       </li>
 * </ul>
 */
public float3 waterSimReachDebug(float3 ro) {
    float2 worldXZ = ro.xz + worldPush.waterAnchor.xy;
    float2 uv;
    float weight;
    if (!waterSimSample(worldPush.waterSimDomain, worldXZ, uv, weight)) {
        return float3(1.0, 0.0, 0.0);
    }
    float2 g = waterSimGradOf(waterSimHeightTex, worldPush.waterSimDomain, worldXZ);
    return float3(0.0, saturate(length(g) * 20.0), 0.15 + weight * 0.35);
}

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

// Perturb a (near-)horizontal water surface normal by the wave field. Flowing side faces (|n.y| small)
// keep their flat geometric normal. `worldXZ` must be a world-stable coordinate (rebased hit + the pushed
// anchor) so the ripples stay pinned in the world. `nGeo` is already oriented toward the viewer, so the
// down-viewed underside (n.y < 0) takes the mirrored slope (-up).
//
// STILL THE ANALYTIC NORMAL OF THE WHOLE FIELD, even where the geometry has been displaced (M12.5). It
// replaces the facet normal rather than composing with it, so the mesh carrying part of the shape does
// not steepen the shading, and near and far water are shaded by the identical expression.
public float3 applyWaterWaves(float3 nGeo, float2 worldXZ, float t, float footprint) {
    if (abs(nGeo.y) < 0.5) return nGeo;
    float2 grad = waterWaveGrad(worldXZ, t, footprint, waterWaveAmplitude()) + waterSimGrad(worldXZ);
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
    float2 g = waterWaveGrad(xz, t, waterWaveAmplitude());
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
