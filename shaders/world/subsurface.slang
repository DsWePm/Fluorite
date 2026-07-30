// Random-walk subsurface scattering: the volumetric BSSRDF that replaces the thin-shell approximation
// for materials thick enough to have an interior. Depends on trace, math, volume and world_core.
//
// A photon enters through the boundary, scatters inside the medium until it finds its way out, and
// leaves somewhere else in some other direction. That is the whole model — there is no closed form and
// no diffusion profile here, just the walk. It is what makes light bleed through the edge of a leaf and
// what gives quartz its depth instead of a painted-on glow.
//
// It runs entirely inside ONE Pass B shading vertex. Nothing about it crosses the pass boundary, so
// PackedPathSegment does not grow by a single byte for it — that is the payoff from splitting enclosed
// media (the stack) from ambient media (a pure function of position) back in M6.
import world_common;
import world_core;
import math;
import volume;
import trace;

/**
 * Traversal distance to the nearest surface, with no shading whatsoever.
 *
 * An inline ray query rather than TraceRay, and the difference is the whole reason the walk is
 * affordable. Every scattering event needs one of these, and a radiance trace would run the full
 * closest-hit shader — atlas fetches, material decode, normal mapping — to answer a question about
 * distance. The shadow path cannot answer it either: it accepts the FIRST hit rather than the nearest,
 * which is exactly the wrong thing when you need to know where the surface actually is.
 *
 * FORCE_OPAQUE skips any-hit as well, so alpha cutout is ignored inside the walk. A photon already
 * inside a leaf does not need the leaf's alpha mask re-tested to find the far side of it.
 *
 * Returns a negative distance when nothing was hit within tmax.
 */
float walkDistance(float3 origin, float3 dir, float tmax) {
    RayQuery<RAY_FLAG_FORCE_OPAQUE> q;
    q.TraceRayInline(topLevelAS, RAY_FLAG_FORCE_OPAQUE, CULL_SECONDARY,
                     makeRay(origin, RAY_TMIN, dir, tmax));
    while (q.Proceed()) {
        // FORCE_OPAQUE leaves nothing for this loop to arbitrate; it is here because the API is
        // specified around it and an implementation is free to return true once.
    }
    return q.CommittedStatus() == COMMITTED_TRIANGLE_HIT ? q.CommittedRayT() : -1.0;
}

/**
 * Single-scattering albedo that reproduces a target diffuse reflectance in a semi-infinite medium.
 *
 * The relationship is violently non-linear near white. A walk loses energy at every event, so feeding
 * the surface albedo in raw makes a saturated material come out far darker than it should — to read as
 * 0.9 diffuse you need a single-scatter albedo around 0.997, not 0.9.
 *
 * This is a monotone approximation with the right limits (0 to 0, 1 to 1) and the right steepness, and
 * it is PROVISIONAL. The published searchlight fits — Chiang et al. 2016, and the Disney BSSRDF before
 * it — are better, and swapping one in is a one-line change here. They are deliberately NOT transcribed
 * from memory: R20 in the plan exists because a constant nobody re-derived is worse than an
 * approximation everybody can see the shape of. Calibrate WALK_ALBEDO_STEEPNESS against the reference
 * view before trusting absolute colour.
 */
static const float WALK_ALBEDO_STEEPNESS = 2.5;

float3 walkAlbedo(float3 diffuse) {
    float3 a = clamp(diffuse, float3(0.0, 0.0, 0.0), float3(1.0, 1.0, 1.0));
    return float3(1.0, 1.0, 1.0) - pow(float3(1.0, 1.0, 1.0) - a,
                                       float3(WALK_ALBEDO_STEEPNESS, WALK_ALBEDO_STEEPNESS,
                                              WALK_ALBEDO_STEEPNESS));
}

/**
 * Mean free path a material gets when nothing authored one, in blocks.
 *
 * LabPBR has no channel for it: its subsurface byte is a mask saying "this is translucent", with no way
 * to say how far light travels inside. So every material coming from a pack without a Fluorite JSON
 * needs a number, and zero is not it — zero means an infinitely dense medium, the walk leaves at the
 * point it entered, and the result is indistinguishable from the feature being switched off.
 *
 * A quarter of a block reads as leaf-and-petal thickness rather than as a solid stone block, which is
 * what the LabPBR mask is overwhelmingly used for today.
 */
public static const float SSS_DEFAULT_RADIUS = 0.25;

public struct WalkResult {
    public bool escaped;   // found its way out: origin/dir/weight describe where and with what
    // Ended by Russian roulette rather than by the event cap. The two must not be conflated: roulette
    // is an unbiased way to stop, so its paths contribute nothing and the caller terminates. Hitting the
    // cap is a budget, so those paths fall back to the cheap lobe instead of losing their energy.
    public bool absorbed;
    public float3 origin;
    public float3 dir;
    public float3 weight;
};

/**
 * Walk a photon through the material and report where it left.
 *
 * `radius` is the mean free path per channel, in blocks, which is the language an author thinks in:
 * "light travels about this far inside this stuff". sigma_t is its reciprocal.
 *
 * Chromatic media are the part that is easy to get wrong. sigma_t differs per channel, so one sampled
 * distance cannot be importance-sampled for all three at once. The distance is drawn from a channel
 * picked at random and weighted by the balance heuristic across the three — standard spectral MIS, and
 * the reason a material with a red-transmitting interior keeps its hue instead of drifting toward grey.
 * Dropping it and using a mean sigma_t is the single most common way this effect ends up colourless.
 *
 * Terminating at the event cap does NOT truncate the energy: it reports escaped = false and the caller
 * substitutes the cheap lobe. Truncating instead would darken exactly the thickest parts of a material,
 * which reads as dirt rather than as a budget.
 */
public WalkResult randomWalkSubsurface(float3 entryPos, float3 n, float3 diffAlb, float3 radius,
                                       float phaseG, int maxEvents, inout uint seed) {
    WalkResult r;
    r.escaped = false;
    r.absorbed = false;
    r.origin = entryPos;
    r.dir = n;
    r.weight = float3(0.0, 0.0, 0.0);

    float3 sigmaT = float3(1.0, 1.0, 1.0) / max(radius, float3(1.0e-3, 1.0e-3, 1.0e-3));
    float3 albedo = walkAlbedo(diffAlb);
    float3 weight = float3(1.0, 1.0, 1.0);

    // Enter through the boundary as a cosine lobe about the inward normal. A full Fresnel refraction is
    // more correct, but every material this runs on in Minecraft — leaves, grass, ice, quartz — has a
    // rough boundary, and a rough boundary is what a cosine lobe describes.
    float3 dir = cosineDir(-n, seed);
    float3 pos = entryPos - n * SURF_BIAS;

    for (int k = 0; k < maxEvents; k++) {
        int c = min(int(rndf(seed) * 3.0), 2);
        float st = max(sigmaT[c], 1.0e-6);
        float t = -log(max(1.0e-7, 1.0 - rndf(seed))) / st;

        float hit = walkDistance(pos, dir, t);
        if (hit >= 0.0) {
            // Reached a boundary before scattering. Weight by the probability of surviving that far,
            // averaged over the three channels the distance could have been drawn from.
            float3 tr = exp(-sigmaT * hit);
            float pdf = (tr.x + tr.y + tr.z) * (1.0 / 3.0);
            if (pdf <= 0.0) {
                return r;
            }
            weight *= tr / pdf;
            r.escaped = true;
            // Step past the face rather than reconstructing its normal to offset along. The direction
            // already points outward through it — that is what made it the exit — so a step along the
            // ray clears the surface without needing to know which way it faces.
            r.origin = pos + dir * (hit + SURF_BIAS);
            r.dir = dir;
            r.weight = weight;
            return r;
        }

        // Scattered. Same balance heuristic, now over the density of sampling this distance rather than
        // of surviving it.
        float3 tr = exp(-sigmaT * t);
        float3 sigmaS = albedo * sigmaT;
        float3 pdfChannel = sigmaT * tr;
        float pdf = (pdfChannel.x + pdfChannel.y + pdfChannel.z) * (1.0 / 3.0);
        if (pdf <= 0.0) {
            return r;
        }
        weight *= tr * sigmaS / pdf;
        pos += dir * t;
        dir = samplePhaseHG(dir, phaseG, seed);

        // Roulette on the walk's own throughput. Without it a bright medium runs every event every
        // time, and the events are the cost.
        float q = clamp(max(weight.r, max(weight.g, weight.b)), 0.05, 1.0);
        if (rndf(seed) > q) {
            r.absorbed = true;
            return r;
        }
        weight /= q;
    }
    return r; // hit the event cap
}
