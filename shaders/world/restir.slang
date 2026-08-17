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
 * stored sample can be asked whether its light still exists, and the last lane carries the target value
 * of the domain that produced it, which is what S2's MIS weights are built from. Nothing is padding.
 */
public struct PackedReservoir {
    public float4 lightPosArea; // xyz = light sample point (rebased world space), w = emitter area
    public float4 leW;          // xyz = emitter radiance,                         w = W
    public float4 recvPosNrm;   // xyz = RECEIVING vertex position,                w = its packed normal
    public float4 misc;         // x = M, y = packed emitter normal, z = light index (uint), w = source target
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
    p.misc = float4(r.M, packReservoirNormal(r.lnrm), asfloat(r.lightIndex), r.phat);
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

/**
 * The target function of the domain that PRODUCED this sample, evaluated at its own vertex.
 *
 * <p>Deliberately not reachable through unpackReservoir, which still reports phat as zero, and named for
 * the domain rather than for the quantity. These are two numbers a careless reader would merge: the
 * estimator's denominator is the target at the vertex REUSING the sample and has to be recomputed there
 * -- that is the mistake D181 was written about -- while this is the other domain's target at its own
 * vertex, and it exists only to weigh how much that domain deserves the credit for the sample.
 *
 * <p>Storing it beats reconstructing it on both counts. Reconstruction would evaluate the stored geometry
 * against THIS vertex's material; the value here was computed against the real one. And it saves an
 * evalSampleContrib at every reusing vertex. It costs the last spare lane, which is what the lane was for.
 */
public float storedSourceTarget(PackedReservoir p) { return p.misc.w; }
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
 * The SPATIAL predicate, which has to ask a different question: a neighbouring pixel was never the same
 * point, so "did this point move" is meaningless there and only "is this the same surface" survives.
 *
 * <p>Three parts. Off-plane distance rejects a neighbour that is on a different surface behind or in front
 * of this one -- the case that would paste a wall's lighting onto the floor in front of it. Normal
 * agreement is shared with the temporal test. And a LATERAL bound, without which a large flat floor lets
 * any pixel reuse any other pixel on it, which is no longer reuse of a neighbourhood but of the whole
 * plane, at whatever variance the MIS weights happen to leave.
 *
 * <p>The lateral bound is measured in PIXEL FOOTPRINTS rather than blocks. A fixed distance is wrong at
 * both ends -- it covers a fraction of a pixel across the room and a dozen pixels underfoot -- while the
 * ray cone's width at the vertex, divided by the incidence cosine, is exactly the surface area one pixel
 * covers there. Neighbours are chosen within a screen-space radius, so bounding the world separation by a
 * multiple of that radius' worth of footprint is the same statement in the space the search happens in.
 *
 * <p>Deliberately NOT applied to the temporal test, which keeps the absolute drift bound it was measured
 * with. Changing both at once would leave the next measurement unable to say which one moved it.
 */
public static const float REUSE_MAX_PLANE_OFFSET = 0.05;   // blocks off this vertex's tangent plane
public static const float REUSE_LATERAL_FOOTPRINTS = 24.0; // pixel footprints, uncalibrated (§8.11)

/**
 * How much history one reservoir may accumulate, as a multiple of the candidates this frame supplied.
 *
 * <p>Without a cap M grows without bound and the reservoir stops responding to change -- a light that
 * switches off keeps being resampled because its weight was earned over hundreds of frames. Twenty is
 * the usual starting point in the literature; it is a starting point here too, and §8.11 owns pricing
 * it against the noise it buys.
 *
 * <p>Under the MIS combination this reads more honestly than it did under the plain one. M no longer sits
 * in the estimator's denominator at all -- it appears only inside `m_i(y)`, where it says how loudly each
 * domain argues for its own candidate. Capping it is therefore exactly "how far the past may outvote the
 * present", rather than a correction factor with a cap bolted on.
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
    return reservoirSampleUsable(stored, receiverNormal);
}

/**
 * As above, but for a neighbouring pixel, where "the same point" was never the question.
 *
 * <p>`footprint` is what one pixel covers on this surface -- the ray cone's width at the vertex over the
 * incidence cosine -- and it is what turns the lateral bound into a statement about the neighbourhood
 * being searched rather than about a distance in blocks. See REUSE_LATERAL_FOOTPRINTS.
 */
public bool reservoirReusableSpatial(PackedReservoir stored, float3 receiverPos, float3 receiverNormal,
                                     float footprint) {
    if (storedReservoirEmpty(stored)) {
        return false;
    }
    float3 delta = storedReceiverPos(stored) - receiverPos;
    // Off-plane first: it is the one that rejects a different surface rather than a distant part of this
    // one, and it is the cheaper of the two.
    if (abs(dot(delta, receiverNormal)) > REUSE_MAX_PLANE_OFFSET) {
        return false;
    }
    float lateral = REUSE_LATERAL_FOOTPRINTS * max(footprint, 1.0e-4);
    if (dot(delta, delta) > lateral * lateral) {
        return false;
    }
    return reservoirSampleUsable(stored, receiverNormal);
}

/** What both predicates share: the normal, the sample's own sanity, and whether its light still exists. */
public bool reservoirSampleUsable(PackedReservoir stored, float3 receiverNormal) {
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

// ---- Combining domains. One canonical reservoir (this vertex's own RIS) and k reused ones (last frame's
// slot, plus however many neighbours), resampled into one survivor.
//
// WHERE THE BIAS WAS. Plain ReSTIR weights each contributing reservoir by M_i/M. Those sum to one, so they
// ARE a MIS weighting -- what they get wrong is the set they sum over. A MIS weight has to sum to one
// across the domains that could have produced the chosen sample, and a light below another vertex's
// horizon has zero target there, so that domain could not have produced it. Handing it M_i/M of the credit
// anyway leaves the total under one, and under one is darker. Silhouettes, grazing angles, anywhere a
// normal turning inside the 26-degree window carries a light across the horizon.
//
// WHAT REPLACES IT is pairwise MIS against the canonical. For each reused domain i,
//
//     p_i(y) = M_i phat_i(y) / (M_i phat_i(y) + M_c phat_c(y)),        q_i(y) = 1 - p_i(y)
//     m_i(y) = p_i(y) / k                     m_c(y) = (1/k) sum_i q_i(y)
//
// which sums to one by construction -- (1/k) sum_i (p_i + q_i) = 1 -- and is zero wherever a domain's
// target is, which is exactly the property M_i/M lacked. The full balance heuristic over k+1 domains would
// want phat_j(y_i) for every pair, k^2 target evaluations; this wants two per domain and k = 1 reproduces
// the two-domain balance heuristic term for term.
//
// The estimator then reads W = wSum / phat(y) with NO division by M. The MIS weight has already done that
// normalisation and dividing again would apply it twice.
//
// It collapses to plain ReSTIR's expression whenever the domains share a target function, which is both
// the check on the algebra (RtRestirReuseContractTest evaluates it numerically) and the reason the
// TEMPORAL correction alone was expected to be nearly invisible: the temporal predicate admits only
// vertices within 0.05 blocks and 26 degrees, so the two targets usually are the same function. Spatial
// reuse is where the domains genuinely differ and where this earns its cost.
//
// No shadow ray enters any of it. The target is the UNSHADOWED contribution and visibility is applied once
// afterwards by shadeReservoir; RIS is unbiased for any target positive on the integrand's support, and
// the unshadowed contribution is positive wherever the shadowed one is.

public struct RestirCombine {
    public Reservoir chosen;  // the survivor so far; starts as the canonical, which is streamed last
    public float wSumRaw;     // sum of candidate weights, still carrying the 1/k the finish divides out
    public float qSum;        // sum of q_i(y_canonical): how much of the canonical's credit survives
    public float mTotal;      // canonical M plus every accepted domain's capped M
    public uint domains;      // k
};

public RestirCombine restirCombineBegin(Reservoir canonical) {
    RestirCombine s;
    s.chosen = canonical;
    s.wSumRaw = 0.0;
    s.qSum = 0.0;
    s.mTotal = canonical.M;
    s.domains = 0u;
    return s;
}

/** Fold one reused domain in. Two target evaluations; the third value rides in the stored lane. */
public void restirCombineDomain(inout RestirCombine s, BsdfContext bc, Reservoir canonical,
                                PackedReservoir stored, inout uint seed) {
    Reservoir other = unpackReservoir(stored);
    float mOther = min(other.M, REUSE_M_CAP);
    float mCanon = canonical.M;

    // The other domain, as far as it survives: its geometry is stored, its material is taken to be this
    // vertex's. See retargetBsdfContext for what that approximation is worth.
    BsdfContext otherCtx = retargetBsdfContext(bc, storedReceiverPos(stored), storedReceiverNormal(stored));

    float phatCanonAtOther; // the stored sample here -- the estimator's denominator if it wins
    evalSampleContrib(bc, other.pos, other.lnrm, other.le, other.area, phatCanonAtOther);
    float phatOtherAtCanon; // this vertex's sample there -- could that domain have produced it?
    evalSampleContrib(otherCtx, canonical.pos, canonical.lnrm, canonical.le, canonical.area,
                      phatOtherAtCanon);
    float phatOtherAtOther = storedSourceTarget(stored);
    float phatCanonAtCanon = canonical.phat;

    float denomOther = mOther * phatOtherAtOther + mCanon * phatCanonAtOther;
    float denomCanon = mOther * phatOtherAtCanon + mCanon * phatCanonAtCanon;
    // A zero denominator means no domain could have produced that candidate, which also means its own
    // target is zero, so the weight is zero either way.
    float p = denomOther > 0.0 ? mOther * phatOtherAtOther / denomOther : 0.0;
    float q = denomCanon > 0.0 ? mCanon * phatCanonAtCanon / denomCanon : 0.0;

    s.qSum += q;
    s.mTotal += mOther;
    s.domains += 1u;

    float w = p * phatCanonAtOther * other.W;
    s.wSumRaw += w;
    if (w > 0.0 && rndf(seed) * s.wSumRaw < w) {
        s.chosen.pos = other.pos;
        s.chosen.lnrm = other.lnrm;
        s.chosen.le = other.le;
        s.chosen.area = other.area;
        s.chosen.phat = phatCanonAtOther;
        s.chosen.lightIndex = other.lightIndex;
    }
}

/**
 * Stream the canonical last, then normalise.
 *
 * <p>Last because its MIS weight is not known until every domain has been offered: `m_c` is the average of
 * what each pairwise comparison left it, and a comparison that never happened leaves nothing.
 *
 * <p>`chosen` already holds the canonical, so selecting it here is a no-op assignment -- but the draw still
 * has to happen, because the canonical competes for the survivor slot on the same terms as everyone else.
 */
public Reservoir restirCombineFinish(RestirCombine s, Reservoir canonical, inout uint seed) {
    if (s.domains == 0u) {
        return canonical; // nothing was reusable; this is the single-frame RIS untouched
    }
    float wCanon = s.qSum * canonical.phat * canonical.W;
    float wSumRaw = s.wSumRaw + wCanon;
    if (wCanon > 0.0 && rndf(seed) * wSumRaw < wCanon) {
        s.chosen = canonical;
    }
    Reservoir r = s.chosen;
    r.M = s.mTotal;
    r.wSum = wSumRaw / float(s.domains);
    r.W = r.phat > 0.0 ? r.wSum / r.phat : 0.0;
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

/**
 * Four uints per depth: temporal attempts, temporal accepts, spatial attempts, spatial accepts. Matches
 * RtRestirStats on the Java side.
 *
 * <p>Two rates rather than one because the two predicates ask different questions -- "is this the same
 * point" against "is this the same surface" -- and a combined rate would report whichever one happened to
 * dominate as though it described both.
 */
public static const uint RESTIR_STAT_LANES = 4u;

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
public void restirStatsAccumulate(uint4 reuseCounts, uint pixelIndex) {
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
        uint base = d * RESTIR_STAT_LANES;
        InterlockedAdd(stats[base], attempts);
        uint accepts = (reuseCounts.y >> shift) & 0xFu;
        if (accepts != 0u) {
            InterlockedAdd(stats[base + 1u], accepts);
        }
        uint spatialAttempts = (reuseCounts.z >> shift) & 0xFu;
        if (spatialAttempts != 0u) {
            InterlockedAdd(stats[base + 2u], spatialAttempts);
        }
        uint spatialAccepts = (reuseCounts.w >> shift) & 0xFu;
        if (spatialAccepts != 0u) {
            InterlockedAdd(stats[base + 3u], spatialAccepts);
        }
    }
}

/**
 * Which pixel's reservoir saw this point last frame — the reprojection, and the reason d0 is not useless
 * the moment the camera moves.
 *
 * <p>MEASURED, not assumed. With the slot addressed by raw pixel index, acceptance at the primary hit was
 * 95-99% standing still and fell to 0-30% the instant the view moved. That is the wrong way round: noise
 * is least objectionable when nothing is moving. The renderer already knows where a point used to be on
 * screen — it builds motion vectors from exactly this projection — so the history is not missing, it was
 * being looked for in the wrong place.
 *
 * <p>REPROJECTION ERROR CANNOT PRODUCE WRONG REUSE, only missed reuse. Landing on the wrong pixel yields a
 * stored receiver position that fails the drift test, so a moving entity (whose object-space displacement
 * this does not model) or a refracted vertex simply declines to reuse. That asymmetry is what makes it
 * safe to reproject approximately.
 *
 * <p>Only at the primary hit. A bounce vertex is not on a camera ray, and the pixel it happens to project
 * to is the pixel whose PRIMARY hit is there — whose bounce vertex is somewhere else entirely. There is no
 * reprojection to do, which is a separate fact from the measurement that says those depths are not worth
 * storing at all.
 */
public bool restirPreviousPixel(float3 hitPos, uint2 renderSize, out uint prevPixelIndex) {
    prevPixelIndex = 0u;
    bool valid;
    float2 ndc = projectPrevNdc(hitPos, valid);
    if (!valid) {
        return false;
    }
    // Inverse of cameraRay's uv -> ndc. Rounding rather than flooring because the jitter that displaces
    // this frame's ray from the pixel centre is bounded by half a pixel, so the nearest pixel is the one
    // that cast it; flooring would hand half the frames to the neighbour on a perfectly static camera.
    float2 prevPixel = (ndc * 0.5 + 0.5) * float2(renderSize) - 0.5;
    int2 p = int2(round(prevPixel));
    if (any(p < int2(0, 0)) || any(p >= int2(renderSize))) {
        return false; // off screen last frame: there is no history, which is not the same as rejecting one
    }
    prevPixelIndex = uint(p.y) * renderSize.x + uint(p.x);
    return true;
}

/**
 * How far a spatial neighbour may be, in pixels, and where in that disk to look.
 *
 * <p>A screen-space radius rather than a world one, because the search is a screen-space search: the
 * reservoirs are addressed by pixel and there is no spatial index over the world. What keeps a 12-pixel
 * step from reaching across a room is the world-space predicate, not this.
 *
 * <p>Uniform over the DISK, not over the radius -- sqrt on the radial draw. Without it the samples pile up
 * at the centre, where the neighbour is most likely to be the pixel already read and least likely to add
 * anything the canonical reservoir does not already have.
 *
 * <p>Twelve is a starting point and is not calibrated (§8.11). It trades off against the lateral bound
 * from the other end: too small and neighbours are redundant, too large and they are rejected before they
 * are read, and only the acceptance rate can say which side it is on.
 */
public static const float RESTIR_SPATIAL_RADIUS_PIXELS = 12.0;

public int2 spatialNeighbourOffset(inout uint seed) {
    float angle = rndf(seed) * 6.28318530718;
    float radius = sqrt(rndf(seed)) * RESTIR_SPATIAL_RADIUS_PIXELS;
    return int2(round(float2(cos(angle), sin(angle)) * radius));
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
 * <p>THE READ IS REPROJECTED AND THE WRITE IS NOT. History is looked for wherever this point was on last
 * frame's screen; the result is always stored under this invocation's own pixel. Only reads move, so no
 * two invocations can ever contend for a slot, and the whole reprojection stays a hint that the validation
 * is free to reject.
 *
 * <p>A slot that this frame does not reach is simply not written; next frame reads a slot two frames old
 * instead, and the receiver test decides whether that is still the same surface. There is nothing to
 * clear: an old reservoir for a surface that has not moved is old, not wrong.
 *
 * <p>`reuseCounts` is four nibble words per depth: temporal attempts, temporal accepts, spatial attempts,
 * spatial accepts. Split because the two predicates are different questions with different answers, and a
 * single combined rate would hide whichever one is doing the work.
 */
public Reservoir restirReuse(BsdfContext bc, Reservoir current, uint bounce, uint pathIndex,
                             uint2 pix, uint2 renderSize, float footprint,
                             inout uint seed, inout uint4 reuseCounts) {
    uint depth = worldPush.restirReuseDepth;
    if (pc.reservoirAddr == 0 || depth == 0u || bounce >= depth) {
        return current;
    }
    uint paths = worldPush.restirPathCount;
    if (pathIndex >= paths) {
        return current; // spp changed under a store sized for the old one; next resize fixes it
    }
    uint pixelIndex = pix.y * renderSize.x + pix.x;
    uint pixelCount = renderSize.x * renderSize.y;
    // Where to LOOK. The primary hit gets last frame's screen position; a bounce vertex has no such thing
    // and reads its own pixel, which is the arrangement the depth measurement was taken under.
    uint readPixel = pixelIndex;
    bool haveHistory = bounce != 0u || restirPreviousPixel(bc.hitPos, renderSize, readPixel);

    DevicePtr<PackedReservoir> store = DevicePtr<PackedReservoir>(pc.reservoirAddr);
    uint parity = worldPush.frameIndex & 1u;
    uint slotCur = reservoirSlot(pixelIndex, pixelCount, pathIndex, paths, bounce, depth, parity);
    RestirCombine combine = restirCombineBegin(current);

    restirCountBump(reuseCounts.x, bounce);
    if (haveHistory) {
        uint slotPrev = reservoirSlot(readPixel, pixelCount, pathIndex, paths, bounce, depth, parity ^ 1u);
        PackedReservoir stored = store[slotPrev];
        if (reservoirReusable(stored, bc.hitPos, bc.n)) {
            restirCombineDomain(combine, bc, current, stored, seed);
            restirCountBump(reuseCounts.y, bounce);
        }
    }

    // Neighbours, from the SAME half the temporal read came from. There is no seam in this dispatch where
    // every reservoir is written and nothing is shaded yet -- shading is inline in the bounce loop -- so
    // reading this frame's neighbours would be reading whatever they happened to have got to. Last frame's
    // half is complete by definition, and it makes spatial and temporal one loop over reused domains
    // rather than two mechanisms. The cost is that a neighbour is also a frame old.
    //
    // Centred on the REPROJECTED pixel where there is one, so the neighbourhood searched is the
    // neighbourhood of this surface point rather than of this screen position.
    uint neighbours = worldPush.restirSpatialNeighbours;
    for (uint i = 0u; i < neighbours; i++) {
        int2 centre = int2(int(readPixel % renderSize.x), int(readPixel / renderSize.x));
        int2 q = centre + spatialNeighbourOffset(seed);
        if (any(q < int2(0, 0)) || any(q >= int2(renderSize))) {
            continue; // off screen: no neighbour there to reject
        }
        uint neighbourIndex = uint(q.y) * renderSize.x + uint(q.x);
        if (neighbourIndex == readPixel) {
            continue; // that is the temporal sample; counting it twice would double its say
        }
        restirCountBump(reuseCounts.z, bounce);
        PackedReservoir stored = store[
                reservoirSlot(neighbourIndex, pixelCount, pathIndex, paths, bounce, depth, parity ^ 1u)];
        if (reservoirReusableSpatial(stored, bc.hitPos, bc.n, footprint)) {
            restirCombineDomain(combine, bc, current, stored, seed);
            restirCountBump(reuseCounts.w, bounce);
        }
    }

    Reservoir r = restirCombineFinish(combine, current, seed);
    store[slotCur] = packReservoir(r, bc.hitPos, bc.n);
    return r;
}
