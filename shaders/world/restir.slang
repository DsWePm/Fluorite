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
 * <p>Members are grouped so the three vectors keep their natural alignment and the four scalars ride in
 * the w lanes -- no padding is wasted, and `area`/`M`/`W` land next to the vector each belongs with.
 */
public struct PackedReservoir {
    public float4 posArea;   // xyz = sample point (rebased world space), w = emitter area
    public float4 lnrmM;     // xyz = emitter outward normal,            w = M
    public float4 leW;       // xyz = emitter radiance,                  w = W
    public float4 sumPhat;   // x = wSum, y = phat, zw = reserved (see below)
};

/** Matches RtSky.RESERVOIR_BYTES on the Java side; the allocation and this must move together. */
public static const uint RESERVOIR_BYTES = 64u;

/**
 * A stored reservoir is only reusable if the vertex that produced it is the same vertex.
 *
 * <p>`sumPhat.zw` carry what makes that answerable: z is the receiving vertex's depth along the camera
 * ray and w packs its normal. Without them a temporal read has nothing to reject on and reuse becomes a
 * blur -- last frame's lighting applied to this frame's geometry. §8.9 additionally requires validating
 * the LIGHT (position, radius, radiance), which the unpacked fields already carry; this pair is about
 * the RECEIVER, which nothing else records.
 */
public float packReservoirNormal(float3 n) {
    // Octahedral, in 16:16. Cheap, and the precision is far beyond what a rejection test needs.
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

public PackedReservoir packReservoir(Reservoir r, float receiverDepth, float3 receiverNormal) {
    PackedReservoir p;
    p.posArea = float4(r.pos, r.area);
    p.lnrmM = float4(r.lnrm, r.M);
    p.leW = float4(r.le, r.W);
    p.sumPhat = float4(r.wSum, r.phat, receiverDepth, packReservoirNormal(receiverNormal));
    return p;
}

public Reservoir unpackReservoir(PackedReservoir p) {
    Reservoir r;
    r.pos = p.posArea.xyz;
    r.area = p.posArea.w;
    r.lnrm = p.lnrmM.xyz;
    r.M = p.lnrmM.w;
    r.le = p.leW.xyz;
    r.W = p.leW.w;
    r.wSum = p.sumPhat.x;
    r.phat = p.sumPhat.y;
    return r;
}

public float storedReceiverDepth(PackedReservoir p) { return p.sumPhat.z; }
public float3 storedReceiverNormal(PackedReservoir p) { return unpackReservoirNormal(p.sumPhat.w); }

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
