// Volumetric clouds as a PURE RAY FUNCTION (M11, decision D33).
//
// WHERE THIS RUNS. On the segment that escapes to sky, before the raygen breaks out of its bounce loop.
// That placement is what puts clouds in reflections and in secondary bounces: a mirror's ray escapes too.
//
// WHY NOT INSIDE integrateSegment. The unified medium interface dispatches on a profile, and the ambient
// profile's height fog is integrated in CLOSED FORM -- clouds are the first client that genuinely needs a
// numerical march. Putting that march in the per-segment path would charge every bounce segment for it,
// including the ones near the ground that can never reach the shell, and M17 measured what per-segment
// work costs in this raygen. Same split as D2: the interface unifies, the estimators do not.
//
// THE HARD RULE (R18): THE CAMERA POSITION MUST NOT APPEAR IN THIS FILE. Everything is derived from the
// ray's own origin and direction. The reference implementation broke this twice -- flattening the high
// layer by horizontal distance to the camera, and ordering the layers by camera altitude -- and either
// one makes the clouds in a reflection sit at a different height from the clouds above it, so a lake and
// the sky above it contradict each other. There is no camera here to be tempted by.
//
// WORLD-ANCHORED, and that took a fix rather than a comment. Every coordinate the ray tracer handles is
// relative to a terrain rebase origin that follows the camera, so evaluating the density field on those
// coordinates directly anchored the clouds to the PLAYER: the deck rode along at its authored altitude
// above them, unreachable, and the whole pattern jumped whenever the origin reset. worldOf() adds the
// origin back; nothing else in this file may sample the field without it.
//
// Structure is HPVolumeCloud's (weather map x 3D noise x height profile, adaptive stepping); no constant
// is. See R20: its Intensity carries units of m^-2 and every number here is chosen for this world's scale.

module cloud;

import atmosphere;      // raySphere, PLANET_R
import atmosphere_lut;  // mediumSkyRadiance lives in WorldPush, read via world_core
import math;
import world_common;
import world_core;

[[vk::binding(17, 0)]] Sampler3D cloudNoise;

// How far along a ray clouds are still worth sampling. Beyond this the shell is below the horizon for any
// upward ray and the remaining crossing is grazing, where the march would spend its whole budget on a
// sliver of nearly transparent volume.
public static const float CLOUD_MAX_DIST = 40000.0;

// The one feature size that stays fixed: the weather map's period, in blocks. It is not authored because
// it is not a look, it is the scale at which the sky is allowed to be different from itself — roughly a
// render distance across, so a player sees a few cells and not a texture.
public static const float CLOUD_WEATHER_SCALE = 9000.0;
// Cloud TYPE varies far more slowly still. A storm covers a region; it does not alternate with fair
// weather every kilometre.
public static const float CLOUD_TYPE_SCALE = 26000.0;

// Everything else is authored per frame, from worldPush:
//   cloudParams = (coverage bias, density scale, type bias, extinction per block at full density)
//   cloudShape  = (deck bottom altitude, deck thickness, base feature size, detail feature size)
// Authored rather than constant because the vanilla weather system has to be able to move them — see
// RtComposite.cloudParams, where rain drives coverage and thunder drives type.
/**
 * A rebased position in ABSOLUTE world blocks.
 *
 * <p>Everything the ray tracer handles is expressed relative to the terrain rebase origin, and that
 * origin FOLLOWS THE CAMERA. Sampling the density field at rebased coordinates therefore anchors the
 * clouds to the player rather than to the world: the deck climbs with them, so an altitude of 192 means
 * "192 blocks above wherever you are" and can never be reached, and the whole cloud pattern jumps
 * sideways every time the origin is reset. This one addition is what the file's world-anchored claim
 * actually rests on.
 */
float3 worldOf(float3 p) { return p + worldPush.cloudRebase.xyz; }

float cloudBottom() { return worldPush.cloudShape.x; }
float cloudThickness() { return max(worldPush.cloudShape.y, 1.0); }
float cloudTop() { return cloudBottom() + cloudThickness(); }

public struct CloudResult {
    public float3 inScatter;
    public float transmittance;
};

public CloudResult noClouds() {
    CloudResult r;
    r.inScatter = float3(0.0, 0.0, 0.0);
    r.transmittance = 1.0;
    return r;
}

/** Bit 30 (Rt.Clouds.ENABLED). */
public bool cloudsOn() {
    return (worldPush.flags & (1u << 30u)) != 0u;
}

/**
 * Where a ray enters and leaves the cloud shell, as distances along it.
 *
 * <p>A SPHERICAL shell, not a slab, and the difference is the horizon: on a slab the deck would extend
 * flat to infinity and meet the sky at a hard line, while on a shell it curves away and sets. The sphere
 * is centred a planet radius below the ray's own origin altitude, so the world is tangent to it -- and
 * that construction reads the RAY's origin, never the camera's.
 */
float2 cloudShellSpan(float3 ro, float3 rd) {
    // Vertically anchored to the WORLD: the centre sits a planet radius below world y = 0, so the radii
    // below are absolute altitudes and the deck stays where it was authored however far the player
    // climbs. Horizontally it stays under the rebase origin, which is within a rebase distance of the
    // camera -- that is what gives each ray its own horizon, and the curvature error from those a
    // hundred-odd blocks is a thousandth of a block.
    float3 centreRelative = ro + float3(0.0, PLANET_R + worldPush.cloudRebase.y, 0.0);
    float rBottom = PLANET_R + cloudBottom();
    float rTop = PLANET_R + cloudTop();
    float2 inner = raySphere(centreRelative, rd, rBottom);
    float2 outer = raySphere(centreRelative, rd, rTop);
    if (outer.y <= 0.0) {
        return float2(0.0, -1.0); // the shell is entirely behind this ray
    }
    // BRANCH ON THE ORIGIN'S RADIUS, not on the signs of the roots. This was the bug that made the sky
    // empty at every setting: the old test asked whether the inner sphere's NEAR root was ahead, meaning
    // to detect "below the deck, looking up" -- but a ray starting below cloud base is INSIDE the inner
    // sphere, so its near root is always behind it and that test never fired. Every such ray fell into
    // the "already inside the deck" case and got the span from the camera up to the underside: hundreds
    // of blocks of clear air, sampled dutifully, all of it below the deck, density zero everywhere.
    float radius = length(centreRelative);
    float enter;
    float exit;
    if (radius < rBottom) {
        // Below the deck. The crossing starts where the ray passes UP through the underside, which is
        // the FAR root of the inner sphere. A downward ray has no such crossing within reach: its far
        // root is on the other side of the planet, and the distance clamp below turns that into an
        // empty span on its own.
        if (inner.y <= 0.0) {
            return float2(0.0, -1.0);
        }
        enter = inner.y;
        exit = outer.y;
    } else if (radius <= rTop) {
        // Inside the deck: from here to whichever boundary is met first. Looking down, that is the
        // underside, and the inner sphere is genuinely ahead so its near root is positive. Looking up,
        // the inner sphere is behind and the ray leaves through the top.
        enter = 0.0;
        exit = inner.x > 0.0 ? inner.x : outer.y;
    } else {
        // Above the deck. Enter through the top, then leave either through the underside or, for a
        // grazing ray that never reaches it, back out through the top.
        if (outer.x <= 0.0) {
            return float2(0.0, -1.0);
        }
        enter = outer.x;
        exit = inner.x > 0.0 ? inner.x : outer.y;
    }
    return float2(max(enter, 0.0), min(exit, CLOUD_MAX_DIST));
}

/**
 * Vertical profile, selected by cloud TYPE — which is the difference between weather and wallpaper.
 *
 * <p>One deck, three shapes blended along a single axis, because from the ground the families that read
 * differently are a sheet, a heap and a tower:
 *
 * <ul>
 *   <li><b>0, stratus</b>: a shallow sheet lying on the deck's floor, flat above as well as below. It is
 *       a layer, not an object, so it occupies only the lowest tenth of the deck.</li>
 *   <li><b>0.5, cumulus</b>: flat-bottomed, rounded on top, filling the lower half. Fair weather.</li>
 *   <li><b>1, cumulonimbus</b>: the full depth, still flat-bottomed, and WIDENING again near the top.
 *       That late flare is the anvil — the tower reaches stable air and spreads sideways instead of
 *       continuing to climb. Without it a storm cloud reads as a column rather than as a storm.</li>
 * </ul>
 *
 * <p>All three share the flat base for one physical reason: convective cloud condenses at the altitude
 * where rising air reaches saturation, and that altitude is the same across a region, so the underside of
 * a cloud field is a plane. The tops differ because a top is wherever that particular parcel ran out of
 * lift.
 *
 * <p>Blended in two halves rather than by a single lerp between the extremes, so the middle of the range
 * is cumulus rather than a half-height average of a sheet and a tower.
 */
float cloudHeightProfile(float altitude, float type) {
    float t = saturate((altitude - cloudBottom()) / cloudThickness());
    float stratus = smoothstep(0.0, 0.03, t) * (1.0 - smoothstep(0.07, 0.18, t));
    float cumulus = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.30, 0.62, t));
    float anvil = smoothstep(0.60, 0.78, t) * (1.0 - smoothstep(0.92, 1.0, t));
    float cumulonimbus = max(smoothstep(0.0, 0.04, t) * (1.0 - smoothstep(0.68, 0.95, t)), anvil * 0.9);
    float profile = type < 0.5
            ? lerp(stratus, cumulus, saturate(type * 2.0))
            : lerp(cumulus, cumulonimbus, saturate(type * 2.0 - 1.0));
    return saturate(profile);
}

/**
 * Coverage, from a world-anchored slice of the same volume.
 *
 * <p>Anchored to world coordinates rather than to anything view-dependent, so a cloud stays over the
 * same ground as the ray that finds it moves — which is the property the whole file exists to protect.
 */
float cloudCoverage(float3 p) {
    float2 w = worldOf(p).xz / CLOUD_WEATHER_SCALE;
    float m = cloudNoise.SampleLevel(float3(w.x, 0.37, w.y), 0.0).r;
    // Widened around the middle so the sky is broken rather than either overcast or empty. The authored
    // bias then shifts the whole field, which is how rain overcasts a sky: one number, not a second field.
    return saturate((m - 0.42) * 2.6 + worldPush.cloudParams.x);
}

/**
 * Which family of cloud stands over this column, in [0,1] — the argument to cloudHeightProfile.
 *
 * <p>Its own slice of the volume, at its own much longer period, so type and coverage are independent:
 * an overcast sky can be a flat sheet or a wall of towers, and those are different weather.
 *
 * <p>The authored bias is ADDITIVE and saturating rather than a replacement, so a thunderstorm pushes the
 * whole sky toward towers while the field still decides where the individual cells stand. Replacing the
 * field would make every cloud in the sky identical the moment the weather changed, which reads as a
 * switch being thrown rather than as a storm arriving.
 */
float cloudType(float3 p) {
    float2 w = worldOf(p).xz / CLOUD_TYPE_SCALE;
    float m = cloudNoise.SampleLevel(float3(w.x, 0.61, w.y), 0.0).b;
    // Scaled below 1 so fair weather stays in the sheet-to-heap half of the range and the tower is
    // something the weather has to ask for.
    return saturate(m * 0.75 + worldPush.cloudParams.z);
}

/**
 * Density at a point. `cheap` skips the erosion fetch, which is what makes the empty-space skip pay for
 * itself: most steps along most rays are outside any cloud, and they only need to know that.
 *
 * <p>ORDERED BY COST, and that ordering is load-bearing rather than tidy. Every factor below multiplies,
 * so any one of them reaching zero ends the function — which makes it worth asking the free question
 * first and the expensive one last. The deck bounds are arithmetic on p.y and reject the majority of
 * steps along the majority of rays; coverage is one fetch and rejects most of what survives; type is a
 * second fetch and cannot reject anything by itself, so it is deliberately NOT hoisted above the height
 * test even though the height profile now takes it as an argument. Hoisting it would charge every empty
 * step in the sky for a texture read that only matters inside a cloud.
 */
float cloudDensity(float3 p, bool cheap) {
    float3 pw = worldOf(p);
    if (pw.y < cloudBottom() || pw.y > cloudTop()) {
        return 0.0; // outside the deck: no type can put cloud here
    }
    float coverage = cloudCoverage(p);
    if (coverage <= 0.0) {
        return 0.0;
    }
    float type = cloudType(p);
    float profile = cloudHeightProfile(pw.y, type);
    if (profile <= 0.0) {
        return 0.0;
    }
    float shape = cloudNoise.SampleLevel(pw / max(worldPush.cloudShape.z, 1.0), 0.0).r;
    // Coverage raises the whole field rather than masking it, so a cloud grows from its centre outward
    // instead of appearing with hard edges where the mask crosses a threshold.
    float density = saturate(shape + coverage - 1.0) * profile;
    // Type raises density as well as height, because a cumulonimbus is not a tall cumulus — it is a tall
    // cumulus that light struggles to get through, and that opacity is most of what makes a storm read as
    // one from underneath. Applied AFTER the erosion below rather than here: the erosion term is written
    // against a density normalised to [0,1] and would invert if a gain pushed its argument past one.
    float gain = worldPush.cloudParams.y * (1.0 + type * 1.6);
    if (density <= 0.0 || cheap) {
        return density * gain;
    }
    float erosion = cloudNoise.SampleLevel(pw / max(worldPush.cloudShape.w, 1.0), 0.0).g;
    // Erosion only bites where the cloud is already thin, so the body keeps its shape while the edges
    // break up. Subtracting everywhere would hollow the cloud out.
    return saturate(density - erosion * 0.35 * (1.0 - density)) * gain;
}

/**
 * March the shell and return what the clouds added and what of the sky survives them.
 *
 * <p>SLICE ONE OF THREE (D35). The source here is ambient only — the shared medium sky radiance, the
 * same field the fog and the water read since M16 — so the clouds are lit but flat. The sun's own term,
 * its self-shadowing march and the phi_fwd multiple-scattering that makes a thick cloud's interior stay
 * bright are the second slice; the two layers, the camera-proximity fade and the reflection strategy are
 * the third. Being able to price the march before any of that is the reason it is split this way: R19
 * names the cost of this milestone as its principal risk.
 *
 * <p>Step length grows with distance FROM THE RAY'S ORIGIN, so a cloud a long way off is integrated
 * coarsely and one overhead finely. Measured from the origin rather than the camera for the usual
 * reason, and it is also what lets a secondary ray degrade gracefully: a reflected ray starts at the
 * water, so its budget is spent where its own clouds are.
 */
public CloudResult traceClouds(float3 ro, float3 rd, float tMax, uint seed) {
    CloudResult result = noClouds();
    if (!cloudsOn()) {
        return result;
    }
    float2 span = cloudShellSpan(ro, rd);
    float tEnter = span.x;
    float tExit = min(span.y, tMax);
    if (tExit <= tEnter) {
        return result;
    }

    float3 ambient = worldPush.mediumSkyRadiance.xyz;
    // Jittered entry, for the same reason every other estimator here jitters: a fixed start puts the
    // step lattice at the same depth on neighbouring pixels and draws it as banding, and this renderer
    // would rather have noise a denoiser can integrate than structure it cannot.
    uint marchSeed = seed ^ 0x7F4A7C15u;
    marchSeed = pcg(marchSeed);
    float t = tEnter + rndf(marchSeed) * min(64.0, (tExit - tEnter) * 0.1);

    float transmittance = 1.0;
    float3 gathered = float3(0.0, 0.0, 0.0);
    int emptySteps = 0;
    for (int i = 0; i < 96 && t < tExit; ++i) {
        float step = max(24.0, t * 0.125);
        float3 p = ro + rd * t;
        float density = cloudDensity(p, true);
        if (density <= 0.0) {
            // Nothing here. Step long, and keep stepping long while the emptiness holds.
            emptySteps++;
            t += step * (emptySteps > 2 ? 3.0 : 1.5);
            continue;
        }
        emptySteps = 0;
        density = cloudDensity(p, false);
        if (density > 0.0) {
            float sigmaT = density * worldPush.cloudParams.w;
            float stepT = exp(-sigmaT * step);
            // The same closed form the fog's sub-steps use: for a source taken as constant over the step,
            // the in-scatter integrates exactly to source * (1 - T), and doing it this way keeps a long
            // step from overshooting the way a midpoint sample would.
            gathered += transmittance * ambient * (1.0 - stepT);
            transmittance *= stepT;
            if (transmittance < 0.01) {
                transmittance = 0.0;
                break;
            }
        }
        t += step;
    }

    result.inScatter = gathered;
    result.transmittance = transmittance;
    return result;
}

/**
 * Debug view 22: name the first stage of the cloud chain that fails, as a flat colour.
 *
 * <p>Exists because "there are no clouds" was diagnosed twice by reading call graphs, and the second
 * diagnosis was wrong. Every stage below can independently produce an empty sky, they all look identical
 * on screen, and none of them logs anything. One launch and one screenshot should say which it is.
 *
 * <ul>
 *   <li><b>RED</b> — the enable bit never reached the shader. Config, flags packing, or push upload.</li>
 *   <li><b>MAGENTA</b> — the noise volume reads zero everywhere. Bake never ran, descriptor never
 *       written, or the binding index disagrees between pipeline layout and shader.</li>
 *   <li><b>BLUE</b> — the ray never enters the shell. Geometry: altitude, thickness, or the span.</li>
 *   <li><b>YELLOW</b> — inside the shell, but density is zero along the whole crossing. The density
 *       field's own balance: coverage, shape, profile or the type axis.</li>
 *   <li><b>GREY</b> — clouds exist here; brightness is 1 - transmittance, so white is opaque cloud.
 *       If this view is grey and the world is not, the failure is in the LIGHTING, not the shape.</li>
 * </ul>
 */
public float3 cloudDebugProbe(float3 ro, float3 rd) {
    if (!cloudsOn()) {
        return float3(1.0, 0.0, 0.0);
    }
    // Four widely separated taps rather than one: a single tap could legitimately land on a zero, and
    // reporting "the texture is dead" because of that would send the search to exactly the wrong place.
    float noise = cloudNoise.SampleLevel(float3(0.11, 0.37, 0.23), 0.0).r
            + cloudNoise.SampleLevel(float3(0.61, 0.13, 0.79), 0.0).r
            + cloudNoise.SampleLevel(float3(0.29, 0.83, 0.41), 0.0).g
            + cloudNoise.SampleLevel(float3(0.73, 0.51, 0.07), 0.0).b;
    if (noise <= 0.0) {
        return float3(1.0, 0.0, 1.0);
    }

    float2 span = cloudShellSpan(ro, rd);
    if (span.y <= span.x) {
        return float3(0.0, 0.0, 1.0);
    }

    // A fixed uniform walk, deliberately unlike the real march: this has to be able to disagree with it.
    // If the field has density and the real march still shows nothing, the fault is in the march.
    //
    // The three factors are tracked SEPARATELY as well as multiplied. They all multiply, so any one of
    // them being zero empties the sky and the product alone cannot say which -- and a CPU replica of the
    // density field said all three should be healthy, which was wrong about something. This reports the
    // maxima instead of asserting them.
    float maxDensity = 0.0;
    float maxCoverage = 0.0;
    float maxProfile = 0.0;
    float maxShape = 0.0;
    float inDeck = 0.0;
    float tau = 0.0;
    float step = (span.y - span.x) / 64.0;
    for (int i = 0; i < 64; ++i) {
        float3 p = ro + rd * (span.x + step * (float(i) + 0.5));
        float3 pw = worldOf(p);
        if (pw.y >= cloudBottom() && pw.y <= cloudTop()) {
            inDeck += 1.0;
            float type = cloudType(p);
            maxCoverage = max(maxCoverage, cloudCoverage(p));
            maxProfile = max(maxProfile, cloudHeightProfile(pw.y, type));
            maxShape = max(maxShape, cloudNoise.SampleLevel(pw / max(worldPush.cloudShape.z, 1.0), 0.0).r);
        }
        float d = cloudDensity(p, true);
        maxDensity = max(maxDensity, d);
        tau += d * worldPush.cloudParams.w * step;
    }
    if (maxDensity <= 0.0) {
        // Not a flat yellow any more: R is the best coverage seen, G the best height profile, B the best
        // shape sample. Black means the walk never landed inside the deck at all, which would make the
        // shell span and the density field disagree about where the deck is.
        return inDeck <= 0.0 ? float3(0.0, 0.0, 0.0)
                             : float3(maxCoverage, maxProfile, maxShape);
    }
    return float3(1.0 - exp(-tau));
}
