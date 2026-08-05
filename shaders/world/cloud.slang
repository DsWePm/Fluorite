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
// Structure is HPVolumeCloud's (weather map x 3D noise x height profile, adaptive stepping); no constant
// is. See R20: its Intensity carries units of m^-2 and every number here is chosen for this world's scale.

module cloud;

import atmosphere;      // raySphere, PLANET_R
import atmosphere_lut;  // mediumSkyRadiance lives in WorldPush, read via world_core
import math;
import world_common;
import world_core;

[[vk::binding(17, 0)]] Sampler3D cloudNoise;

// The shell, in blocks above the world's zero. Minecraft's own clouds sit at 192; this is a band around
// that, thick enough for a march to have something to integrate through.
public static const float CLOUD_BOTTOM = 180.0;
public static const float CLOUD_TOP = 320.0;
// How far along a ray clouds are still worth sampling. Beyond this the shell is below the horizon for any
// upward ray and the remaining crossing is grazing, where the march would spend its whole budget on a
// sliver of nearly transparent volume.
public static const float CLOUD_MAX_DIST = 40000.0;

// Feature sizes, in blocks. The base is the width of a cloud, the detail the size of the bites taken out
// of its edge; both are periods of the baked volume mapped onto the world.
public static const float CLOUD_BASE_SCALE = 2400.0;
public static const float CLOUD_DETAIL_SCALE = 340.0;
public static const float CLOUD_WEATHER_SCALE = 9000.0;

public static const float CLOUD_EXTINCTION = 0.045; // per block, at full density

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
    float3 centreRelative = ro + float3(0.0, PLANET_R, 0.0);
    float2 inner = raySphere(centreRelative, rd, PLANET_R + CLOUD_BOTTOM);
    float2 outer = raySphere(centreRelative, rd, PLANET_R + CLOUD_TOP);
    if (outer.y <= 0.0) {
        return float2(0.0, -1.0); // the shell is entirely behind this ray
    }
    float enter;
    float exit;
    if (inner.x > 0.0) {
        // Below the deck looking up: the crossing runs from the inner sphere to the outer one.
        enter = inner.x;
        exit = outer.y;
    } else if (inner.y > 0.0) {
        // Inside the deck: from here to whichever boundary is met first.
        enter = 0.0;
        exit = min(inner.y, outer.y);
    } else {
        // Above the deck, or looking down at it from within the outer sphere.
        enter = max(outer.x, 0.0);
        exit = outer.y;
    }
    return float2(max(enter, 0.0), min(exit, CLOUD_MAX_DIST));
}

/** 0 at both boundaries of the shell, 1 through the middle, with the flat base a cloud has. */
float cloudHeightProfile(float altitude) {
    float t = saturate((altitude - CLOUD_BOTTOM) / (CLOUD_TOP - CLOUD_BOTTOM));
    // Asymmetric on purpose: convective cloud has a flat bottom and a rounded top, so the lower edge
    // sharpens quickly and the upper one tapers.
    return saturate(smoothstep(0.0, 0.12, t) * (1.0 - smoothstep(0.45, 1.0, t)));
}

/**
 * Coverage, from a world-anchored slice of the same volume.
 *
 * <p>Anchored to world coordinates rather than to anything view-dependent, so a cloud stays over the
 * same ground as the ray that finds it moves — which is the property the whole file exists to protect.
 */
float cloudCoverage(float3 p) {
    float2 w = p.xz / CLOUD_WEATHER_SCALE;
    float m = cloudNoise.SampleLevel(float3(w.x, 0.37, w.y), 0.0).r;
    // Widened around the middle so the sky is broken rather than either overcast or empty.
    return saturate((m - 0.42) * 2.6);
}

/**
 * Density at a point. `cheap` skips the erosion fetch, which is what makes the empty-space skip pay for
 * itself: most steps along most rays are outside any cloud, and they only need to know that.
 */
float cloudDensity(float3 p, bool cheap) {
    float profile = cloudHeightProfile(p.y);
    if (profile <= 0.0) {
        return 0.0;
    }
    float coverage = cloudCoverage(p);
    if (coverage <= 0.0) {
        return 0.0;
    }
    float shape = cloudNoise.SampleLevel(p / CLOUD_BASE_SCALE, 0.0).r;
    // Coverage raises the whole field rather than masking it, so a cloud grows from its centre outward
    // instead of appearing with hard edges where the mask crosses a threshold.
    float density = saturate(shape + coverage - 1.0) * profile;
    if (density <= 0.0 || cheap) {
        return density;
    }
    float erosion = cloudNoise.SampleLevel(p / CLOUD_DETAIL_SCALE, 0.0).g;
    // Erosion only bites where the cloud is already thin, so the body keeps its shape while the edges
    // break up. Subtracting everywhere would hollow the cloud out.
    return saturate(density - erosion * 0.35 * (1.0 - density));
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
            float sigmaT = density * CLOUD_EXTINCTION;
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
