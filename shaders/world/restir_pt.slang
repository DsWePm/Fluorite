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
import bsdf;    // the continuation-mixture evaluation a stored direction is re-weighted by
// Safe now only because the layout probe no longer imports this module (it reads the record through
// world_common): world_core carries INV_PI and its set-0 bindings would otherwise collide there.
import world_core;

// ---- Temporal-merge constants (S2). Named twins of restir.slang's receiver tests, kept separate
// because the two stores reject different things: a DI reservoir must describe the same SURFACE, a
// path reservoir must describe the same SUFFIX -- and a suffix's home vertex is allowed to sit deeper
// in the path than the receiver a DI reservoir anchors.

/** And how much its normal may have turned (cos). */
public static const float PATH_REUSE_MIN_NORMAL_AGREEMENT = 0.9; // cos, about 26 degrees
/** The cosine floor both shift directions insist on (tangent directions have no honest Jacobian). */
public static const float PATH_REUSE_MIN_SURFACE_COS = 0.1;

// Enhanced §4.2's single-vertex roughness gate, applied to the reconnection vertex's PREDECESSOR
// x_{k-1}: a suffix reconnected behind a near-mirror bounce is a sample the receiving pixel could
// never have drawn, and f(wi)'s directional sensitivity amplifies the retarget error exactly where
// the lobe is sharpest. The paper's rho_min = 0.2 is Falcor's perceptual-roughness semantics; this
// repository stores GGX alpha directly (iron law 2), so the value arrives as 0.2^2 = 0.04 -- a
// one-time unit conversion of the paper's constant, not a square of any stored value. Starting
// point only: calibrate against the deep-recon acceptance share (stats lane 5).
public static const float PATH_RECONNECT_MIN_ALPHA = 0.04;

// ---- Spatial-shift predicate (S3a): a neighbouring pixel was never the same point, so "did this
// point move" is meaningless there and the question becomes "is this the same surface". The two
// bounds below are name-twins of restir.slang's spatial test for the same reason that test exists.
//
// D223: THE BOUNDS ARE FOOTPRINT-SCALED, not world blocks. The first acceptance run measured the
// consequence of world-block bounds: spatial acceptance collapsed to 0.8-8% (a 0.05-block plane
// tolerance is a fraction of a pixel's footprint past a few metres, and sub-pixel under DLSS-RR's
// downscale), so the application gate crossed on ~nothing and the acceptance run saw no effect. A
// footprint is the surface area one pixel covers at the vertex -- thresholds in footprints are
// distance- and resolution-independent by construction, which is the whole of the Enhanced paper's
// reconnection-criterion argument.

/** How far off this vertex's tangent plane a neighbour's reconnection vertex may sit, in footprints. */
public static const float PATH_REUSE_MAX_PLANE_FOOTPRINTS = 2.0;
/** How far sideways, in pixel footprints at this vertex -- the ray-cone width over the incidence
 * cosine, which is the surface area one pixel covers there. */
public static const float PATH_REUSE_LATERAL_FOOTPRINTS = 24.0; // uncalibrated, like its twin
/** And how far the temporal shift's own point may have drifted, in footprints (D223: was 0.05
 * blocks, over-strict at distance by the same argument). */
public static const float PATH_REUSE_MAX_DRIFT_FOOTPRINTS = 3.0;

/**
 * The compatibility score of a neighbour whose soft bounds failed -- the graded acceptance that
 * replaces a binary kill for borderline candidates (compatibility-guided selection, Enhanced-era).
 * Position similarity folds in through the soft bounds themselves (a candidate this far away only
 * reaches the soft gate when its plane/lateral excess is marginal); the normal term is the survey's
 * max(cos,0)^8, which lets a 60-degree neighbour through occasionally while a 120-degree one never.
 * The caller accepts with this probability: thinning the candidate set is unbiased for the values --
 * every accepted candidate still carries its exact reconnection weight -- and it concentrates the
 * accepted set on the neighbours most likely to share the suffix radiance.
 */
public float pathCompatibilityScore(float3 storedNrm, float3 n, float3 drift, float lateralBound) {
    float normalAgreement = max(dot(storedNrm, n), 0.0);
    float score = normalAgreement * normalAgreement * normalAgreement * normalAgreement
                * normalAgreement * normalAgreement * normalAgreement * normalAgreement;
    // And the position half of the survey's score: a Gaussian in the excess over the lateral bound,
    // so a candidate just outside gets exp(-~1) while a far one reaches zero without a hard kill.
    float excess2 = dot(drift, drift) / max(lateralBound * lateralBound, 1.0e-8);
    return score * exp(-excess2);
}

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

/**
 * Validate a stored record against THIS reconnection vertex and, if it passes, evaluate the stored
 * direction's candidate value here. ONE spelling for both shifts -- temporal and spatial differ only
 * in the surface predicate, and two spellings of a validation that must agree is exactly how a reuse
 * rate quietly stops meaning anything (the M24 note that wrote itself after the pairwise-MIS merge).
 *
 * <p>`spatialPredicate` selects the question: false = "is this the same point last frame was at"
 * (absolute drift -- the temporal/d0 arrangement); true = "is this the same surface a neighbour is
 * on" (plane offset + lateral bound in pixel footprints -- M24's spatial arrangement). Everything
 * else is shared: replay-depth match, normal agreement, the cosine floors, the antiparallel guard,
 * the weight/target sanity, and the empty test.
 *
 * <p>`value` is the per-sample candidate value at this vertex: W (the record's accumulated merge
 * weight) times the reconnection weight -- f(wi)*cos/pdf over THIS vertex's continuation mixture,
 * times the clamped cosine-ratio Jacobian, times the stored suffix radiance. `count` is the record's
 * effective sample count (capped); `meanTarget` its per-sample home target -- reported but, since
 * A-02b, NOT the selection weight: callers select by the RECEIVER-domain luminance(value), which
 * needs no unbiasedness argument (a GRIS selection target may be any positive function) as long as
 * the draw, the sums and t_chosen all stay in one domain. Both shifts' callers multiply value by
 * count when accumulating the pixel's merged estimate.
 */
public bool evalPathSuffixCandidate(BsdfContext bc, float3 diffAlb, float3 F0,
                                    float pf, float ps, float walkSssShare, float3 v,
                                    float3 reconPos, float3 reconNrm, float3 reconTpre,
                                    float footprint, uint reconDepth, bool spatialPredicate,
                                    inout uint seed, PackedPathReservoir stored,
                                    out float3 value, out float count, out float meanTarget) {
    value = float3(0.0, 0.0, 0.0);
    count = 0.0;
    meanTarget = 0.0;

    float storedW = asfloat(stored.W);
    meanTarget = stored.m > 0.0 ? stored.targetSum / stored.m : 0.0;
    float3 storedNrm = octDecode(unpackUnorm16x2(stored.reconNrm));
    float3 storedWi = octDecode(unpackUnorm16x2(stored.wi));
    float cosStored = dot(storedWi, storedNrm);
    float cosHere = dot(storedWi, reconNrm);
    float3 drift = stored.reconPos - reconPos;

    bool usable = !pathReservoirEmpty(stored)
            && storedW > 0.0 && meanTarget > 0.0
            && stored.reconIndex == reconDepth
            && dot(storedNrm, reconNrm) >= PATH_REUSE_MIN_NORMAL_AGREEMENT
            && cosStored > PATH_REUSE_MIN_SURFACE_COS && cosHere > PATH_REUSE_MIN_SURFACE_COS
            // A stored continuation pointing straight back along the view degenerates the half-vector
            // below into normalize(0) -- rejected rather than survived.
            && dot(storedWi, v) < 0.9999;
    if (usable) {
        if (spatialPredicate) {
            // Same surface, not same point: off-plane first (it is what rejects a different surface
            // behind or in front of this one), then the lateral bound -- both in footprints, so the
            // bounds are distance- and resolution-independent (D223).
            float planeBound = PATH_REUSE_MAX_PLANE_FOOTPRINTS * max(footprint, 1.0e-4);
            float lateralBound = PATH_REUSE_LATERAL_FOOTPRINTS * max(footprint, 1.0e-4);
            bool hardPass = abs(dot(drift, reconNrm)) <= planeBound
                    && dot(drift, drift) <= lateralBound * lateralBound
                    && dot(storedNrm, reconNrm) >= PATH_REUSE_MIN_NORMAL_AGREEMENT;
            if (!hardPass) {
                // D223 graded acceptance: a borderline neighbour is thinned by compatibility instead
                // of killed outright -- the first acceptance run measured the binary form collapsing
                // spatial acceptance to under ten percent and starving the application gate.
                usable = rndf(seed) < pathCompatibilityScore(storedNrm, reconNrm,
                                                              drift, lateralBound);
            }
        } else {
            usable = dot(drift, drift) <= PATH_REUSE_MAX_DRIFT_FOOTPRINTS
                                           * PATH_REUSE_MAX_DRIFT_FOOTPRINTS
                                           * max(footprint * footprint, 1.0e-8);
        }
    }
    if (!usable) {
        return false;
    }

    // The continuation weight this vertex would have given the stored direction, and the density
    // that weights it: f(wi)*cos/pdf, spelled exactly as the lobe mixture samples. Deliberately NOT
    // evalSampleContrib -- that helper is an AREA-light target (emitter tint inside, SSS back-lobe
    // branch, film floor tied to emitter MIS), none of which describes a suffix sample.
    float ndlStored = cosHere;
    float3 hStored = normalize(storedWi + v);
    float ndh = max(0.0, dot(reconNrm, hStored));
    float vdh = max(0.0, dot(v, hStored));
    float D = ggxDAniso(dot(hStored, bc.t), dot(hStored, bc.b), ndh, bc.alphaX, bc.alphaY);
    float G = bc.g1v * ggxG1Aniso(dot(storedWi, bc.t), dot(storedWi, bc.b),
                                  ndlStored, bc.alphaX, bc.alphaY);
    float3 F = fresnelSchlick(vdh, F0);
    float3 brdf = diffAlb * INV_PI + (D * G) * F / (4.0 * bc.ndv * ndlStored);
    brdf = brdf * rainBaseTransmission(bc, storedWi) + rainFilmBrdf(bc, storedWi, false);
    float pdf = bsdfContinuationPdf(bc, storedWi, pf, ps, walkSssShare);
    if (!(pdf > 0.0)) {
        return false;
    }
    // Changed-measure correction: the producing vertex sampled this direction against ITS
    // reconnection normal; this vertex weighs it against its own. The material is taken to be this
    // vertex's (M24's retarget approximation), which leaves the cosine ratio as the whole Jacobian --
    // and cosStored == cosHere on any static scene, which is what the fixed-pose convergence check
    // exercises. Clamped: the normals already agree within 26 degrees, so a ratio outside this band
    // means the direction ran tangent to one of them, and the honest answer is a damped weight
    // rather than a firefly.
    float jacobian = clamp(cosHere / cosStored, 1.0 / 3.0, 3.0);
    value = max(storedW * reconTpre * (brdf * ndlStored / pdf) * jacobian
                        * unpackRgb9e5(stored.li),
                float3(0.0, 0.0, 0.0));
    count = min(stored.m, PATH_RESERVOIR_M_CAP);
    return true;
}
