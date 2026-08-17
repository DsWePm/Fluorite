// Persistent reservoir storage: where a reservoir lives between frames, and the only code that knows
// its layout in memory.
//
// EVERYTHING ABOUT THE STORAGE FORMAT IS BEHIND packReservoir/unpackReservoir AND reservoirSlot. That
// is deliberate and it is the point of this file. The stored format is the expensive decision here --
// at one reservoir per bounce vertex per pixel, double buffered, it is the single largest allocation
// the renderer makes -- and the user's call was to measure the uncompressed version before paying for
// a compressed one. When that measurement says to compress (store a light index and (s,t) on the
// emitter rectangle instead of the resolved sample, ~12 B), the change is these three functions and
// the size constant, not every call site.
//
// Not in lighting.slang, which owns the Reservoir type and the RIS that fills one, because that module
// is about ESTIMATION and this is about a buffer. lighting.slang has no idea a reservoir can outlive
// the vertex that produced it, and keeping it that way means the estimator stays testable against a
// single frame.

import world_common;
import world_core; // pc and worldPush: where the store, its shape and the light buffer are published
import bsdf;       // BsdfContext, the vertex a reused sample is re-evaluated against
import lighting;   // the Reservoir this packs, and the target function that re-evaluation uses
import math;       // rndf, for the weighted choice between history and this frame

/**
 * 64 bytes, not the 56 the fields add up to.
 *
 * <p>Four float4s rather than fourteen floats: std430 would give a scalar-member struct a stride of 56,
 * which is not a multiple of 16, so every other element would straddle a cache line on a buffer that is
 * read once per bounce vertex per pixel. The 14% is bought back by never paying for a split access.
 *
 * <p>Three of the four w lanes carry a scalar that belongs with the vector beside it -- the emitter's
 * area with its sample point, W with the radiance it weights, the receiver's normal with its position.
 * `misc` holds what is left: M, the emitter normal folded octahedrally into one lane -- which is what
 * made room for the receiver position -- and the emitter's index in the light buffer, which is how a
 * stored sample can be asked whether its light still exists. One lane spare. Nothing is padding.
 */
public struct PackedReservoir {
    public float4 lightPosArea; // xyz = light sample point (rebased world space), w = emitter area
    public float4 leW;          // xyz = emitter radiance,                         w = W
    public float4 recvPosNrm;   // xyz = RECEIVING vertex position,                w = its packed normal
    public float4 misc;         // x = M, y = packed emitter normal, z = light index (uint), w = spare
};

/** Matches RtSky.RESERVOIR_BYTES on the Java side; the allocation and this must move together. */
public static const uint RESERVOIR_BYTES = 64u;

// THE RECEIVING VERTEX IS STORED AS A POSITION, NOT A DEPTH, and that is a bounce-vertex requirement.
//
// Depth along the camera ray identifies a primary hit perfectly well, and the first version of this
// stored exactly that. It is meaningless at a bounce vertex: there is no camera ray there, and this slot
// is addressed by (pixel, bounce) with no reprojection of any kind, so last frame's third bounce for
// this pixel can be anywhere in the world. Only a world position can answer whether the two are the same
// surface, and answering that is the entire basis for reusing anything.
//
// §8.9 separately requires validating the LIGHT -- position, radius and radiance, to reject hash
// collisions and entity-id reuse -- and the unpacked light fields carry all three.
//
// wSum and phat are deliberately NOT stored. phat has to be re-evaluated at the CURRENT vertex when a
// stored reservoir is reused (reusing the value from the vertex that produced it is exactly the mistake
// that makes temporal reuse wrong), and wSum only exists while a reservoir is being filled. W is the
// finished weight and is what shading consumes, so W is what persists. Dropping the two freed the lanes
// the receiver position needed.

/** Octahedral 16:16. Cheap, and far finer than a rejection test needs. */
public float packReservoirNormal(float3 n) {
    float2 p = n.xy * (1.0 / (abs(n.x) + abs(n.y) + abs(n.z) + 1.0e-8));
    if (n.z < 0.0) {
        p = (1.0 - abs(p.yx)) * float2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    }
    uint2 q = uint2(clamp(p * 0.5 + 0.5, 0.0, 1.0) * 65535.0 + 0.5);
    return asfloat((q.y << 16) | q.x);
}

public float3 unpackReservoirNormal(float packed) {
    uint bits = asuint(packed);
    float2 p = float2(float(bits & 0xFFFFu), float(bits >> 16)) * (1.0 / 65535.0) * 2.0 - 1.0;
    float3 n = float3(p.x, p.y, 1.0 - abs(p.x) - abs(p.y));
    float t = max(-n.z, 0.0);
    n.x += n.x >= 0.0 ? -t : t;
    n.y += n.y >= 0.0 ? -t : t;
    return normalize(n);
}

public PackedReservoir packReservoir(Reservoir r, float3 receiverPos, float3 receiverNormal) {
    PackedReservoir p;
    p.lightPosArea = float4(r.pos, r.area);
    p.leW = float4(r.le, r.W);
    p.recvPosNrm = float4(receiverPos, packReservoirNormal(receiverNormal));
    p.misc = float4(r.M, packReservoirNormal(r.lnrm), asfloat(r.lightIndex), 0.0);
    return p;
}

public Reservoir unpackReservoir(PackedReservoir p) {
    Reservoir r;
    r.pos = p.lightPosArea.xyz;
    r.area = p.lightPosArea.w;
    r.lnrm = unpackReservoirNormal(p.misc.y);
    r.M = p.misc.x;
    r.le = p.leW.xyz;
    r.W = p.leW.w;
    r.lightIndex = asuint(p.misc.z);
    // Not stored, and not reconstructible from what is -- see the banner. A reused reservoir's phat must
    // be evaluated against the vertex reusing it, so leaving these at zero is the honest value: any
    // caller that reads them without recomputing is asking the wrong question and gets an answer that
    // makes that obvious rather than one that looks plausible.
    r.wSum = 0.0;
    r.phat = 0.0;
    return r;
}

/** An unwritten slot. M == 0 is the emptiness test; every real reservoir has counted at least one candidate. */
public bool storedReservoirEmpty(PackedReservoir p) { return !(p.misc.x > 0.0); }
public float3 storedReceiverPos(PackedReservoir p) { return p.recvPosNrm.xyz; }
public float3 storedReceiverNormal(PackedReservoir p) { return unpackReservoirNormal(p.recvPosNrm.w); }

/**
 * Where one reservoir lives: pixel `pixelIndex`, path `pathIndex`, bounce `bounce`, frame half `parity`.
 *
 * <p>Bounce-major would put one pixel's whole path in consecutive slots; this is PIXEL-major within a
 * bounce, because the access pattern that matters is "every lane in a wave reads its own pixel at the
 * same bounce" -- that walk is contiguous, and the bounce-major one would stride by the path depth.
 *
 * <p>`parity` selects the frame half. Two halves rather than two buffers so a single device address
 * covers both, and WorldPushConstants has exactly one address slot left.
 *
 * <p>THE PATH PLANE IS NOT OPTIONAL. main() calls tracePath spp x MAX_PATH_SEGMENTS times per pixel and
 * every one of them starts its own bounce numbering, so without this dimension they would all address
 * the same slot: the last one written wins, and every sample after the first reads a history that a
 * DIFFERENT sample wrote. For the spp samples that means one shared history and correlated noise, which
 * quietly eats part of what spp is bought for; for the second path segment -- the refraction split,
 * which is a different surface entirely -- it means validating against a receiver that was never its
 * own. `pathIndex` is `sample + leaf * spp`, exactly the sampleIndex tracePath already decorrelates by.
 *
 * <p>It costs a factor of `pathCount` in memory, and that is the expensive half of the choice: at
 * 1080p, depth 4 and the default spp of 1 it is 2.1 GB rather than 1.06 GB, because MAX_PATH_SEGMENTS
 * is 2 and the second plane is allocated for every pixel even though only refracting pixels queue one.
 * Sized like the continuation queue, which reserves its second record the same way for the same reason.
 */
public uint reservoirSlot(uint pixelIndex, uint pixelCount, uint pathIndex, uint pathCount,
                          uint bounce, uint bounceCount, uint parity) {
    uint plane = (parity * pathCount + pathIndex) * bounceCount + min(bounce, bounceCount - 1u);
    return plane * pixelCount + pixelIndex;
}

// ---- Reuse. Whether a stored reservoir may be combined with the one just built, and how.

/**
 * How far the receiving vertex may have moved, and how much its normal may have turned, before last
 * frame's reservoir stops describing this frame's surface.
 *
 * <p>Both are generous on purpose. This is a REJECTION test, not a similarity metric: a sample that
 * survives it still has its target function re-evaluated at the current vertex, so a marginal accept
 * costs accuracy only through the MIS weighting that S2 adds, while a marginal reject costs the whole
 * point of the feature. Erring toward accepting is the cheaper error here.
 */
public static const float REUSE_MAX_POSITION_DRIFT = 0.05; // blocks
public static const float REUSE_MIN_NORMAL_AGREEMENT = 0.9; // cos, about 26 degrees

/**
 * How much history one reservoir may accumulate, as a multiple of the candidates this frame supplied.
 *
 * <p>Without a cap M grows without bound and the reservoir stops responding to change -- a light that
 * switches off keeps being resampled because its weight was earned over hundreds of frames. Twenty is
 * the usual starting point in the literature; it is a starting point here too, and §8.11 owns pricing
 * it against the noise it buys.
 */
public static const float REUSE_M_CAP = 20.0;

/**
 * How far a re-read Light record may differ from the stored sample and still be the same light.
 *
 * <p>Relative, and loose. This is not measuring a light, it is asking whether index N still names the
 * emitter it named last frame: the discriminating test is the containment one below, and these two only
 * have to be tight enough that a genuinely different light fails. Loose also buys immunity to the last
 * ulp -- `lightArea` is arithmetic, and the compiler is free to contract it differently at two call
 * sites, which an exact comparison would read as "the torch moved".
 */
public static const float LIGHT_MATCH_TOLERANCE = 1.0 / 256.0;
/** How far outside the rectangle the stored point may land. s,t are exactly [-1,1]; this is rounding. */
public static const float LIGHT_EXTENT_SLACK = 1.02;
/** And how far off its plane, in blocks. */
public static const float LIGHT_PLANE_SLACK = 1.0e-3;

/**
 * Is the emitter this sample came from still there, and still this emitter? (§8.9's light-side check.)
 *
 * <p>WITHOUT THIS, A MINED TORCH GOES ON LIGHTING THE ROOM. A stored reservoir carries a position, a
 * radiance and an area, which is everything shading needs; nothing in it stops being valid when the
 * light stops existing. Its target function re-evaluates to the same value, the shadow ray flies through
 * the air where the torch was and finds nothing to occlude it, and the sample is written back every
 * frame -- so it does not decay either. A bright, close ghost simply keeps winning.
 *
 * <p>The index is the "source key" §8.9 describes, and it is used the way that note requires: to FIND a
 * candidate, never to accept one. Light indices are rebuilt every frame from the terrain hierarchy, so
 * index N after a chunk changes is some other light -- which is exactly the entity-id-reuse case, and
 * the three comparisons below are what catch it. Radiance and area say "same kind of light"; the
 * containment test says "and the stored point is still ON it", which is the only one of the three that
 * a light that MOVED cannot pass.
 *
 * <p>Failure is safe in the direction that matters: placing a torch anywhere shifts every index after it
 * and mass-rejects reuse for a single frame, after which the new indices are what got stored. A terrain
 * rebase does the same to the receiver test one level up, for the same one frame.
 */
public bool storedLightStillPresent(PackedReservoir p) {
    uint index = asuint(p.misc.z);
    if (pc.lightBufAddr == 0 || index >= worldPush.lightCount) {
        return false;
    }
    Light lg = ConstPtr<Light>(pc.lightBufAddr)[index];
    if (lightIsSphere(lg)) {
        return false; // M18 sphere records decode their axes differently; nothing below would mean anything
    }
    float3 le = lightRadiance(lg);
    float3 leRef = max(abs(le), abs(p.leW.xyz));
    if (any(abs(le - p.leW.xyz) > LIGHT_MATCH_TOLERANCE * max(leRef, float3(1.0e-4, 1.0e-4, 1.0e-4)))) {
        return false;
    }
    float area = lightArea(lg);
    if (abs(area - p.lightPosArea.w) > LIGHT_MATCH_TOLERANCE * max(area, 1.0e-4)) {
        return false;
    }
    // Containment. risInitial built the stored point as centre + s*U + t*V with s,t in [-1,1], so
    // subtracting the centre back off has to land inside the same rectangle. For the perpendicular axes
    // a block emitter actually has, dot(d,U) is s*|U|^2 -- so comparing against |U|^2 IS comparing s
    // against 1, with no division.
    float3 u = lightHalfU(lg);
    float3 v = lightHalfV(lg);
    float uu = dot(u, u);
    float vv = dot(v, v);
    if (!(uu > 0.0) || !(vv > 0.0)) {
        return false;
    }
    float3 d = p.lightPosArea.xyz - (lg.pos + worldPush.lightRebase.xyz);
    if (abs(dot(d, u)) > uu * LIGHT_EXTENT_SLACK || abs(dot(d, v)) > vv * LIGHT_EXTENT_SLACK) {
        return false;
    }
    float3 cross_uv = cross(u, v);
    return abs(dot(d, cross_uv)) <= LIGHT_PLANE_SLACK * length(cross_uv);
}

/**
 * May this stored reservoir be reused at this vertex?
 *
 * <p>Two independent questions, and both have to pass.
 *
 * <p>THE RECEIVER: is this the same surface? Position and normal, per the banner above. Nothing here
 * reprojects, so at a bounce vertex this is the only thing standing between reuse and applying last
 * frame's lighting to unrelated geometry.
 *
 * <p>THE LIGHT: is the emitter still there? See storedLightStillPresent.
 */
public bool reservoirReusable(PackedReservoir stored, float3 receiverPos, float3 receiverNormal) {
    if (storedReservoirEmpty(stored)) {
        return false;
    }
    float3 drift = storedReceiverPos(stored) - receiverPos;
    if (dot(drift, drift) > REUSE_MAX_POSITION_DRIFT * REUSE_MAX_POSITION_DRIFT) {
        return false;
    }
    if (dot(storedReceiverNormal(stored), receiverNormal) < REUSE_MIN_NORMAL_AGREEMENT) {
        return false;
    }
    if (!(stored.lightPosArea.w > 0.0) || !(stored.leW.w > 0.0)) {
        return false; // no area or no weight: nothing to inherit
    }
    // NaN from either side rejects, and has to be tested before the light check reads these lanes.
    if (any(stored.leW.xyz != stored.leW.xyz) || any(stored.lightPosArea.xyz != stored.lightPosArea.xyz)) {
        return false;
    }
    return storedLightStillPresent(stored);
}

/**
 * Combine one stored reservoir into the one this vertex just built, and return the result.
 *
 * <p>p-hat IS RE-EVALUATED HERE, at this vertex, against the stored sample. Carrying the value forward
 * from the vertex that produced it is the classic way to make temporal reuse wrong, and it is why
 * unpackReservoir refuses to hand one back (restir.slang's banner).
 *
 * <p>The current reservoir's own unnormalised weight is `wSum` exactly as risInitial left it -- which is
 * `phat * W * M` by construction, so history and present are weighted by the same expression and there
 * is no second formula to drift.
 *
 * <p><b>This combination is biased, and S2 is what fixes it.</b> The stored reservoir's M candidates were
 * drawn at LAST frame's vertex, and counting them as candidates at this one is the plain ReSTIR
 * combination without MIS weights. Where the two vertices see the light differently -- silhouettes,
 * grazing angles, anything the 26-degree normal test lets through -- the M grows while the weight does
 * not, and the result is slightly too dark. The receiver test is what keeps that small; the MIS weights
 * S2 adds are what remove it. A "skip M when the stored sample contributes nothing here" patch would
 * hide the darkening and replace it with an unprincipled brightening, so it is deliberately absent.
 */
public Reservoir combineStoredReservoir(BsdfContext bc, Reservoir current, PackedReservoir stored,
                                        inout uint seed) {
    Reservoir prev = unpackReservoir(stored);
    float phatPrev;
    evalSampleContrib(bc, prev.pos, prev.lnrm, prev.le, prev.area, phatPrev);
    float mPrev = min(prev.M, REUSE_M_CAP);
    float wPrev = phatPrev * prev.W * mPrev;

    Reservoir r = current;
    float wSum = r.wSum + wPrev;
    float m = r.M + mPrev;
    if (wSum > 0.0 && rndf(seed) * wSum < wPrev) {
        r.pos = prev.pos;
        r.lnrm = prev.lnrm;
        r.le = prev.le;
        r.area = prev.area;
        r.phat = phatPrev;
        r.lightIndex = prev.lightIndex;
    }
    r.M = m;
    r.wSum = wSum;
    r.W = r.phat > 0.0 ? wSum / (m * r.phat) : 0.0;
    return r;
}

// ---- The measurement. Reuse costs 265 MB per depth per frame half; this is what says whether the deep
// ones ever earn it, and it is the reason RESTIR_REUSE_DEPTH's own documentation promises a number.

/** Reuse depths a pixel can account for: eight nibbles in one uint, matching RESTIR_REUSE_DEPTH's cap. */
public static const uint RESTIR_MAX_STAT_DEPTH = 8u;

/**
 * Add one to `depth`'s nibble, saturating at 15.
 *
 * <p>COUNTS, NOT FLAGS. Every path of a pixel -- each spp sample, and the refraction split -- asks
 * independently at every depth and they routinely disagree, so a bitmask OR-ed across them would report
 * the pixel as accepting whenever ANY one path did, and the rate would come out flattering. Saturation
 * needs eight paths at one depth in one pixel (spp 4) and is a clamp rather than a carry, because a carry
 * would silently add to the NEXT depth's count.
 */
public void restirCountBump(inout uint packed, uint depth) {
    if (depth >= RESTIR_MAX_STAT_DEPTH) {
        return;
    }
    uint shift = depth * 4u;
    if (((packed >> shift) & 0xFu) < 0xFu) {
        packed += 1u << shift;
    }
}

/** Two uints per depth: attempts then accepts. Matches RtRestirStats on the Java side. */
public static const uint RESTIR_STAT_LANES = 2u;

/**
 * Fold one pixel's counts into the frame's per-depth totals.
 *
 * <p>SAMPLED, not exhaustive: one pixel in sixteen contributes, and which sixteenth rotates with the
 * frame so the whole image is covered over sixteen frames. A ratio is unbiased under sampling -- both
 * halves are counted over the same pixels -- and the alternative is two million atomic adds landing on
 * sixteen addresses. Sampling is what makes the diagnostic cheap enough to leave on while measuring the
 * thing it is measuring, which matters: iron law 7 wants the A/B run in one session.
 *
 * <p>No subgroup reduction, deliberately. Ray-tracing stages are not guaranteed to be in
 * subgroupSupportedStages, and a diagnostic that fails to compile on some driver is worse than one that
 * issues sixteen more atomics per sampled pixel.
 */
public void restirStatsAccumulate(uint2 reuseCounts, uint pixelIndex) {
    if (worldPush.restirStatsAddr == 0 || ((pixelIndex ^ worldPush.frameIndex) & 15u) != 0u) {
        return;
    }
    DevicePtr<uint> stats = DevicePtr<uint>(worldPush.restirStatsAddr);
    uint depth = min(worldPush.restirReuseDepth, RESTIR_MAX_STAT_DEPTH);
    for (uint d = 0u; d < depth; d++) {
        uint shift = d * 4u;
        uint attempts = (reuseCounts.x >> shift) & 0xFu;
        if (attempts == 0u) {
            continue; // this pixel's paths never reached this depth; not a rejection
        }
        uint accepts = (reuseCounts.y >> shift) & 0xFu;
        uint base = d * RESTIR_STAT_LANES;
        InterlockedAdd(stats[base], attempts);
        if (accepts != 0u) {
            InterlockedAdd(stats[base + 1u], accepts);
        }
    }
}

/**
 * The whole of temporal reuse at one shading vertex: read last frame's slot, validate it, combine it,
 * and leave the result where next frame will find it.
 *
 * <p>Returns `current` untouched when the store is absent or this bounce is past the configured depth,
 * which is the single-frame RIS that predates this buffer, byte for byte (iron law 8).
 *
 * <p>Reads the half belonging to the OTHER parity and writes this one, so a sample can never be combined
 * with something written moments ago in this same frame -- including by the other spp samples of this
 * pixel, which is what would turn independent estimates into one shared history.
 *
 * <p>A slot that this frame does not reach is simply not written; next frame reads a slot two frames old
 * instead, and the receiver test decides whether that is still the same surface. There is nothing to
 * clear: an old reservoir for a surface that has not moved is old, not wrong.
 *
 * <p>`reuseCounts.x` counts the depths where a stored reservoir was looked at, `reuseCounts.y` those where
 * it was accepted. That ratio, per depth, is the measurement the whole store is on trial for.
 */
public Reservoir restirReuse(BsdfContext bc, Reservoir current, uint bounce, uint pathIndex,
                             uint pixelIndex, uint pixelCount, inout uint seed, inout uint2 reuseCounts) {
    uint depth = worldPush.restirReuseDepth;
    if (pc.reservoirAddr == 0 || depth == 0u || bounce >= depth) {
        return current;
    }
    uint paths = worldPush.restirPathCount;
    if (pathIndex >= paths) {
        return current; // spp changed under a store sized for the old one; next resize fixes it
    }
    DevicePtr<PackedReservoir> store = DevicePtr<PackedReservoir>(pc.reservoirAddr);
    uint parity = worldPush.frameIndex & 1u;
    uint slotPrev = reservoirSlot(pixelIndex, pixelCount, pathIndex, paths, bounce, depth, parity ^ 1u);
    uint slotCur = reservoirSlot(pixelIndex, pixelCount, pathIndex, paths, bounce, depth, parity);

    PackedReservoir stored = store[slotPrev];
    restirCountBump(reuseCounts.x, bounce);
    Reservoir r = current;
    if (reservoirReusable(stored, bc.hitPos, bc.n)) {
        r = combineStoredReservoir(bc, current, stored, seed);
        restirCountBump(reuseCounts.y, bounce);
    }
    store[slotCur] = packReservoir(r, bc.hitPos, bc.n);
    return r;
}
