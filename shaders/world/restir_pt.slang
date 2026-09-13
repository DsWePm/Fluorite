// The S2 path reservoir's ESTIMATOR side: the packer the bounce loop's write takes, and the
// temporal-merge constants. Companion to restir.slang, which holds M24's DIRECT-lighting store --
// keeping the two apart is deliberate: their lifetimes, their consumers and their failure modes are
// different, and M24's store predates this one by three milestones.
//
// THE RECORD ITSELF (PackedPathReservoir) lives in world_common.slang, beside PackedPathSegment and
// for the same reason: its true std430 stride must be reflected into the generated Java record the
// allocation is sized from, and this module's import chain reaches world_core, whose set-0 bindings
// collide with the layout probe's. D220 is the proof this matters -- a uint declared before the
// record's float3 padded the real stride to 64 against a 48-byte allocation, and the first full-
// resolution session faulted the GPU. The struct banner carries the field semantics; the REPLAY
// CONTRACT on pathSeed is pinned in segment.slang and RtPathReplayContractTest.

import world_common;
import math;
import segment; // octEncode / packUnorm16x2 / packRgb9e5: the same packing idioms the queue record uses

// ---- Temporal-merge constants (S2). Named twins of restir.slang's receiver tests, kept separate
// because the two stores reject different things: a DI reservoir must describe the same SURFACE, a
// path reservoir must describe the same SUFFIX -- and a suffix's home vertex is allowed to sit deeper
// in the path than the receiver a DI reservoir anchors.

/** How far the stored reconnection vertex may sit from this frame's, before the suffix is not ours. */
public static const float PATH_REUSE_MAX_DRIFT = 0.05; // blocks, same scale M24 measured with
/** And how much its normal may have turned (cos). */
public static const float PATH_REUSE_MIN_NORMAL_AGREEMENT = 0.9; // cos, about 26 degrees

/**
 * How much history one path reservoir may accumulate, as an effective sample count.
 *
 * <p>The same "the past must not outvote the present forever" bound REUSE_M_CAP buys the DI store: m
 * only sets how loudly the history argues in the survivor choice, and capping it bounds how long a
 * changed scene keeps being blended against. Unused by the temporal write since D219 -- records are
 * always fresh single samples -- and kept for S3, where spatial domains contribute many independent
 * candidates and accumulation has a real estimator role.
 */
public static const float PATH_RESERVOIR_M_CAP = 16.0;

/**
 * The effective sample count a history needs before its value is ALLOWED to reach the pixel.
 *
 * <p>D221's acceptance finding, and the reason the S2 switch is visually inert until S3: at 1 spp
 * under DLSS-RR, a ONE-sample history merged at weight 1/2 is strictly worse than no merge. The raw
 * frame's variance halves, but the history's contribution is TEMPORALLY CORRELATED (a <=2-frame-old
 * sample, re-broadcast), and RR averages independent noise across its ~16-frame window while
 * preserving correlated structure -- so the denoised variance goes from Var/N (off) to roughly
 * (1/4)(1/2 + 1/N) (on): ~2.3x WORSE at N=16. The merge only wins when the history carries several
 * INDEPENDENT candidates -- m >= 2, which means S3's spatial domains; a single re-broadcast sample is
 * never worth more than what RR already does with fresh ones. The merge is inert while moving for
 * the same reason M24's temporal reuse measured 0-30% acceptance there: the reprojection/drift
 * machinery rejects, and RR loses its history too, so there is also no moving-frame regime to win.
 */
public static const float PATH_RESERVOIR_APPLY_MIN_M = 2.0;

/**
 * The one form the bounce loop's write takes. Everything is packed here, next to the estimator that
 * produces the values, so a packing change cannot drift from the record layout it feeds.
 *
 * <p>`li` is the suffix's OWN estimate of incident radiance at the reconnection vertex -- the
 * throughput that carried it is deliberately NOT folded in, because whoever merges this record
 * re-weights it with their own prefix. `targetSum` is this record's home-vertex contribution
 * luminance. `w` is the merge weight, W = targetSum / (m * t_chosen) in the general form -- the
 * identity that keeps W * target(y) an unbiased estimate wherever the record is re-evaluated. Since
 * D219 the temporal write is always a FRESH single-sample record (m = 1, W = 1: one unbiased draw,
 * kept at most two frames so no static sample outlives the denoiser's accumulation window); the
 * general form returns with S3.
 */
public PackedPathReservoir packPathReservoir(uint pathSeed, float3 reconPos, float3 reconNrm,
                                             float2 wiOct, float3 li, float m, float targetSum,
                                             float w, uint reconIndex) {
    PackedPathReservoir r;
    r.reconPos = reconPos;
    r.pathSeed = pathSeed;
    r.reconNrm = packUnorm16x2(octEncode(reconNrm));
    r.li = packRgb9e5(li);
    r.wi = packUnorm16x2(wiOct);
    r.m = m;
    r.targetSum = targetSum;
    r.reconIndex = reconIndex;
    r.W = asuint(w);
    return r;
}
