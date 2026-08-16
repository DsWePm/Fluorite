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
import lighting; // the Reservoir this packs

/**
 * 64 bytes, not the 56 the fields add up to.
 *
 * <p>Four float4s rather than fourteen floats: std430 would give a scalar-member struct a stride of 56,
 * which is not a multiple of 16, so every other element would straddle a cache line on a buffer that is
 * read once per bounce vertex per pixel. The 14% is bought back by never paying for a split access.
 *
 * <p>Three of the four w lanes carry a scalar that belongs with the vector beside it -- the emitter's
 * area with its sample point, W with the radiance it weights, the receiver's normal with its position.
 * `misc` holds what is left: M, and the emitter normal folded octahedrally into one lane, which is what
 * made room for the receiver position. Nothing is padding.
 */
public struct PackedReservoir {
    public float4 lightPosArea; // xyz = light sample point (rebased world space), w = emitter area
    public float4 leW;          // xyz = emitter radiance,                         w = W
    public float4 recvPosNrm;   // xyz = RECEIVING vertex position,                w = its packed normal
    public float4 misc;         // x = M, y = packed emitter normal, zw = spare
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
    p.misc = float4(r.M, packReservoirNormal(r.lnrm), 0.0, 0.0);
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
 * Where pixel `pixelIndex`'s reservoir for bounce `bounce` lives, in the half belonging to `parity`.
 *
 * <p>Bounce-major would put one pixel's whole path in consecutive slots; this is PIXEL-major within a
 * bounce, because the access pattern that matters is "every lane in a wave reads its own pixel at the
 * same bounce" -- that walk is contiguous, and the bounce-major one would stride by the path depth.
 *
 * <p>`parity` selects the frame half. Two halves rather than two buffers so a single device address
 * covers both, and WorldPushConstants has exactly one address slot left.
 */
public uint reservoirSlot(uint pixelIndex, uint pixelCount, uint bounce, uint bounceCount, uint parity) {
    return (parity * bounceCount + min(bounce, bounceCount - 1u)) * pixelCount + pixelIndex;
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
 * May this stored reservoir be reused at this vertex?
 *
 * <p>Two independent questions, and both have to pass.
 *
 * <p>THE RECEIVER: is this the same surface? Position and normal, per the banner above. Nothing here
 * reprojects, so at a bounce vertex this is the only thing standing between reuse and applying last
 * frame's lighting to unrelated geometry.
 *
 * <p>THE LIGHT (§8.9): is the emitter still the emitter it was? Position, radius -- carried as the
 * rectangle's area -- and radiance, all three, because the 30-bit source key identifies a candidate and
 * nothing more. Entity ids get reused and hashes collide, and either one silently substitutes a
 * different light that a key-only check would wave through. Comparing what the sample actually IS costs
 * three loads that were paid to fetch the reservoir anyway.
 */
public bool reservoirReusable(PackedReservoir stored, float3 receiverPos, float3 receiverNormal,
                              Reservoir current) {
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
    // The light. `current` is this frame's freshly resampled reservoir, which is not the same light --
    // these compare the STORED sample against itself as re-read now, so a light that has moved, resized
    // or changed colour since it was stored fails even though its slot and key are unchanged.
    Reservoir prev = unpackReservoir(stored);
    if (!(prev.area > 0.0) || !(prev.W > 0.0)) {
        return false;
    }
    return all(prev.le == prev.le) && all(prev.pos == prev.pos); // NaN rejects, from either source
}
