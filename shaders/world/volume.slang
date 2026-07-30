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
import trace;
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

/**
 * Share of the light the fog removes from the sun's direct lobe that is returned as isotropic glow.
 *
 * A single-scattering model has nowhere for extinguished light to go, so a dense layer simply loses the
 * sun. Real fog scatters it many times and it comes back spread across the sky. This is not that
 * calculation — the froxel pass is — but it keeps the energy roughly where it belongs and removes a
 * visible discontinuity.
 */
static const float MULTI_SCATTER_RETURN = 0.35;

/** What a segment did to the path: what survives it, and what it added. */
public struct SegmentIntegral {
    public float3 transmittance;
    public float3 inScatter;
};

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
 * Analytic and unshadowed. For a source radiance that is constant along the segment, the
 * single-scattering integral collapses exactly to albedo * Ls * (1 - T) — and height fog keeps that
 * identity, because all the height variation lives inside the optical depth. So this costs no marching,
 * no extra rays and no new path vertices.
 *
 * What it gives up is occlusion: the fog is lit as though nothing shadowed it. That is the price of not
 * spending a ray here, and it is why god rays need the froxel pass rather than this function.
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

public float enclosedPhase(float cosT, float g) {
    float rayleigh = (3.0 / (16.0 * PI)) * (1.0 + cosT * cosT);
    return lerp(hg(cosT, g), rayleigh, ENCLOSED_RAYLEIGH_FRACTION);
}

/**
 * integral of exp(-sigma_t * (d0 - rd_y_over_sun * t) * pathScale) * exp(-sigma_t * t) dt over [0, T].
 *
 * Split out because the sun and the sky differ only in how long their path to a given depth is: the sun
 * arrives at an angle and travels depth / sin(elevation), the sky arrives overhead and travels depth.
 * Same closed form, one parameter apart.
 */
float3 depthAttenuatedIntegral(float3 sigmaT, float d0, float descentPerT, float pathScale, float tEnd) {
    float3 head = exp(-sigmaT * (d0 * pathScale));
    // Clamped at zero, and this is load-bearing rather than defensive. `a` combines the light getting
    // BRIGHTER as the ray rises toward the surface with the view path attenuating as it goes; when a ray
    // climbs faster than the light descends the first wins and exp(a T) grows without bound. The
    // underlying error is that depth is allowed to go negative — above the surface there is no water
    // left to attenuate the light, so exp(-sigma d) quietly becomes exp(+sigma |d|) and the source is
    // amplified instead. It showed up as water sparkling at grazing angles, where segments are longest,
    // and on any upward look from below the surface.
    float3 a = min(sigmaT * (descentPerT * pathScale - 1.0), float3(0.0, 0.0, 0.0));
    float3 span;
    // a -> 0 is a ray climbing at exactly the rate the light descends, where the exponential is flat and
    // the closed form is 0/0. The limit is T.
    [unroll]
    for (int c = 0; c < 3; ++c) {
        float ac = a[c];
        span[c] = abs(ac) < 1.0e-5 ? tEnd : (exp(ac * tEnd) - 1.0) / ac;
    }
    return head * span;
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
float3 enclosedSingleScatter(Medium medium, float3 albedo, float3 ro, float3 rd, float tEnd) {
    float3 sigmaT = medium.extinction;
    float surfaceY = worldPush.waterAnchor.w;
    // Submerged: depth is measured from the pushed surface. Otherwise this segment began where the ray
    // crossed into the water, so it starts at the surface by construction.
    float d0 = surfaceY > -1.0e8 ? max(0.0, surfaceY - ro.y) : 0.0;

    float3 total = float3(0.0, 0.0, 0.0);

    // The sun, reaching depth d after travelling d / sin(elevation) through the water.
    float sunY = worldPush.lightDir.y;
    if (sunY > 1.0e-3) {
        float cosT = dot(rd, worldPush.lightDir.xyz);
        float3 source = worldPush.lightRadiance.xyz
                * (4.0 * PI * enclosedPhase(cosT, worldPush.waterScatter.w));
        total += source * depthAttenuatedIntegral(sigmaT, d0, rd.y / sunY, 1.0 / sunY, tEnd);
    }

    // Sky light, arriving from overhead, so its path to depth d is d.
    total += worldPush.fogAmbient.xyz * depthAttenuatedIntegral(sigmaT, d0, rd.y, 1.0, tEnd);

    return albedo * sigmaT * total;
}

public SegmentIntegral integrateSegment(MediumStack stack, float3 ro, float3 rd, float tEnd) {
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
            seg.inScatter += enclosedSingleScatter(stack.current, scatterAlbedo, ro, rd, tEnd);
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
            // Light taken out of the forward lobe does not vanish — in real fog it comes back as the
            // diffuse glow that multiple scattering spreads over the whole sky. Returning a fraction of
            // it isotropically is a cheap stand-in, and it is what stops a dense layer from switching
            // between a sharp halo and no halo at all as the sun's path through it changes.
            float3 extinguished = worldPush.lightRadiance.xyz
                    * (float3(1.0, 1.0, 1.0) - sunTransmittance) * MULTI_SCATTER_RETURN;
            // 4*PI undoes hg()'s sphere normalisation: the phase function is a density over directions,
            // and what is wanted here is its gain relative to isotropic.
            float3 source = worldPush.lightRadiance.xyz * sunTransmittance
                    * (4.0 * PI * evalPhase(dot(rd, worldPush.lightDir.xyz), worldPush.fogScatter.w))
                    + worldPush.fogAmbient.xyz + extinguished;
            seg.inScatter = worldPush.fogScatter.xyz * source
                    * (float3(1.0, 1.0, 1.0) - fogT);
        }
    }
    return seg;
}
