// One history domain, full pairwise MIS. Failed/empty candidates do not erase a valid domain.
// Scalar WRS chooses next frame's representative; RGB uses the sum of domain RIS estimates.
float reusePairwiseWeight(float numerator, float competitor) {
    float scale = max(numerator, competitor);
    if (!(scale > 0.0) || !isfinite(scale)) return 0.0;
    float a = numerator / scale;
    float b = competitor / scale;
    return a / (a + b);
}

// Pure estimator kernel, also exercised by the standalone GPU oracle.
struct ReuseTemporalEstimate {
    float3 value;
    float ownWeight;
    float historyWeight;
    float total;
    bool valid;
};
ReuseTemporalEstimate reuseTemporalEstimate(float3 ownRadiance, float3 sourceSum,
        float3 ownValue, float3 historyValue, float ownTarget, float reverseTarget,
        float historyTarget, float forwardTarget, float ownW, float historyW,
        float reverseJacobian, float forwardJacobian, float historyConfidence) {
    ReuseTemporalEstimate e;
    float ownMis = reusePairwiseWeight(ownTarget, historyConfidence * reverseTarget * reverseJacobian);
    float historyMis = reusePairwiseWeight(historyConfidence * historyTarget, forwardTarget * forwardJacobian);
    e.ownWeight = ownMis * ownTarget * ownW;
    e.historyWeight = historyMis * forwardTarget * historyW * forwardJacobian;
    e.total = e.ownWeight + e.historyWeight;
    e.value = ownRadiance - sourceSum + (ownMis * ownW) * ownValue
            + (historyMis * historyW * forwardJacobian) * historyValue;
    e.valid = isfinite(e.ownWeight) && e.ownWeight >= 0.0
            && isfinite(e.historyWeight) && e.historyWeight >= 0.0
            && isfinite(e.total) && all(isfinite(e.value));
    return e;
}

#ifndef REUSE_TEMPORAL_MATH_ONLY
// Sample the temporal path independently of the fresh-source diagnostics.
void reuseTemporalCount(uint2 pixel, uint lane, uint count = 1u) {
#ifdef FLUORITE_PROBES
    if (worldPush.pathReservoirStatsAddr != 0u && ((pixel.x + 3u * pixel.y) & 15u) == 0u)
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[lane], count);
#endif
}
void reuseTemporalConfidence(uint2 pixel, float confidence) {
#ifdef FLUORITE_PROBES
    if (worldPush.pathReservoirStatsAddr != 0u && ((pixel.x + 3u * pixel.y) & 15u) == 0u)
        InterlockedMax(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[103], asuint(confidence));
#endif
}

// Score the G-buffer that Pass A has already written. The selected position is kept until
// reprojection so a winning tap is never sampled or unprojected twice.
static const uint REUSE_SPATIAL_TAPS = 32u;
static const uint REUSE_SPATIAL_MAX = 8u;
static const uint REUSE_SPATIAL_NO_PIXEL = 0xffffffffu;

bool reuseReadSpatialGuide(uint2 pixel, uint2 dimensions, out float3 position, out float3 normal) {
    position = float3(0);
    normal = float3(0);
    float3 n = gNormal[int2(pixel)].xyz;
    float depth = gDepth[int2(pixel)];
    float n2 = dot(n, n);
    if (!(depth > 0.0 && depth <= 1.0) || !(n2 > 0.0) || !isfinite(n2)) return false;
    float2 uv = (float2(pixel) + 0.5) / float2(dimensions);
    float2 jndc = (uv + worldPush.jitter / float2(dimensions)) * 2.0 - 1.0;
    float4 clip = float4(jndc, depth, 1.0);
    float4 h = mul(worldPush.invViewProj, clip);
    if (!(h.w > 0.0) || !all(isfinite(h))) return false;
    position = h.xyz / h.w;
    normal = n * rsqrt(n2);
    return all(isfinite(position));
}

uint reuseSpatialPixels(uint2 pixel, uint2 dimensions, uint neighbours, uint temporalPixel,
                        inout uint seed,
                        out uint result[REUSE_SPATIAL_MAX]) {
    uint count = 0u;
    for (uint i = 0u; i < REUSE_SPATIAL_MAX; ++i) result[i] = REUSE_SPATIAL_NO_PIXEL;
    float3 centre, normal;
    if (!reuseReadSpatialGuide(pixel, dimensions, centre, normal)) return count;
    float scale = length(centre) * sqrt(0.05 / 3.141592653589793);
    if (!(scale > 0.0) || !isfinite(scale)) return count;
    uint visited[REUSE_SPATIAL_TAPS];
    float keys[REUSE_SPATIAL_MAX];
    float3 positions[REUSE_SPATIAL_MAX];
    uint chosen[REUSE_SPATIAL_MAX];
    for (uint i = 0u; i < REUSE_SPATIAL_MAX; ++i) {
        keys[i] = asfloat(0x7f800000u);
        positions[i] = float3(0);
        chosen[i] = REUSE_SPATIAL_NO_PIXEL;
    }
    uint visitedCount = 0u;
    uint positiveCount = 0u;
    float total = 0.0;
    for (uint i = 0u; i < REUSE_SPATIAL_TAPS; ++i) {
        float angle = rndf(seed) * 6.28318530718;
        float radius = sqrt(rndf(seed)) * 30.0;
        int2 q = int2(pixel) + int2(round(float2(cos(angle), sin(angle)) * radius));
        if (any(q < 0) || any(q >= int2(dimensions)) || all(q == int2(pixel))) continue;
        uint tap = uint(q.y) * dimensions.x + uint(q.x);
        bool duplicate = false;
        for (uint j = 0u; j < visitedCount; ++j) duplicate |= visited[j] == tap;
        if (duplicate) continue;
        visited[visitedCount++] = tap;

        float3 position, tapNormal;
        if (!reuseReadSpatialGuide(uint2(q), dimensions, position, tapNormal)) continue;
        float cosine = clamp(dot(normal, tapNormal), 0.0, 1.0);
        float fourth = cosine * cosine;
        fourth *= fourth;
        float score = exp(-length(centre - position) / scale) * (fourth * fourth);
        if (!(score > 0.0) || !isfinite(score)) continue;
        positiveCount++;
        float u = clamp(rndf(seed), 1.0 / 16777216.0, 1.0 - 1.0 / 16777216.0);
        if (neighbours == 1u) {
            total += score;
            if (u * total < score) { positions[0] = position; chosen[0] = tap; }
        } else {
            // A-ES keys in log space avoid dividing by a potentially tiny score.
            float key = log(-log(u)) - log(score);
            for (uint j = 0u; j < neighbours; ++j) {
                if (key >= keys[j]) continue;
                float displacedKey = keys[j];
                float3 displacedPosition = positions[j];
                uint displacedTap = chosen[j];
                keys[j] = key;
                chosen[j] = tap;
                tap = displacedTap;
                positions[j] = position;
                key = displacedKey;
                position = displacedPosition;
            }
        }
    }
    uint selected = min(neighbours, positiveCount);
    for (uint i = 0u; i < selected; ++i) {
        uint previousPixel;
        if (!restirPreviousPixel(positions[i] + worldPush.camOffset, dimensions, previousPixel)) continue;
        bool duplicate = previousPixel == temporalPixel;
        for (uint j = 0u; j < count; ++j) duplicate |= result[j] == previousPixel;
        if (!duplicate) result[count++] = previousPixel;
    }
    reuseTemporalCount(pixel, 111u, positiveCount);
    reuseTemporalCount(pixel, 112u, selected);
    reuseTemporalCount(pixel, 113u, count);
    return count;
}

// One translated domain and one evaluation per direction, shared by temporal and spatial reuse.
bool reuseReadPair(__ref PathSegment seg, uint entryMediumFlags, uint2 pixel,
                   uint2 dimensions, PathHistoryHeader root, uint previousPixel,
                   ReuseConnectionDomain current, PackedPathReservoir own, bool temporal,
                   out PackedPathReservoir history, out ReuseConnectionEvaluation forward,
                   out float historyConfidence, out float reverseTarget, out float reverseJacobian) {
    forward = {};
    historyConfidence = 0.0;
    reverseTarget = 0.0;
    reverseJacobian = 0.0;
    DevicePtr<PackedPathReservoir> store = DevicePtr<PackedPathReservoir>(root.storeAddr);
    history = store[pathReservoirSlot(previousPixel, dimensions.x * dimensions.y,
            (worldPush.frameIndex ^ 1u) & 1u)];
    if (!reusePrimaryDomainValid(history)) return false;
    if (temporal) reuseTemporalCount(pixel, 105u);
    if (!reuseUnchangedSurface(root, history.objectId)) return false;
    if (temporal) reuseTemporalCount(pixel, 106u);
    historyConfidence = min(unpackHalf2(history.confPdf).x, float(root.confidenceCap));
    if (!(historyConfidence > 0.0) || !isfinite(historyConfidence)) return false;
    if (temporal) reuseTemporalCount(pixel, 107u);

    // True old primary, decoded once. The frame context proves the initial medium matches.
    PathSegment historyRay = reuseRestorePrimaryRay(seg, history);
    ReuseConnectionDomain old;
    if (!reuseReadK2Domain(historyRay, entryMediumFlags, history.pathSeed, old)) return false;
    if (temporal) reuseTemporalCount(pixel, 108u);
    if (!reuseStablePrimaryHit(old.geometry.objectId, old.geometry.geometryIndex)) return false;
    if (temporal) reuseTemporalCount(pixel, 109u);
    if (old.geometry.objectId != history.objectId) return false;

    if (temporal) reuseTemporalCount(pixel, 86u);
    if (temporal && pathReservoirEmpty(history)) reuseTemporalCount(pixel, 87u);
    forward.status = REUSE_CONNECTION_SCOPE;
    if (!pathReservoirEmpty(history) && reuseUnchangedSurface(root, history.reconObjectId)) {
        if (!temporal) reuseTemporalCount(pixel, 119u);
        forward = reuseEvaluateK2AtDomains(seg, entryMediumFlags, old, current, history);
    }
    ReuseConnectionEvaluation reverse = {};
    reverse.status = REUSE_CONNECTION_SCOPE;
    if (!pathReservoirEmpty(own) && reuseUnchangedSurface(root, own.reconObjectId)) {
        if (!temporal) reuseTemporalCount(pixel, 121u);
        reverse = reuseEvaluateK2AtDomains(seg, entryMediumFlags, current, old, own,
                (worldPush.pathReplayEnabled & 2u) != 0u);
    }

    if (temporal && !pathReservoirEmpty(history)) {
        reuseTemporalCount(pixel, 88u);
        reuseTemporalCount(pixel, 89u + forward.status);
    }
    if (temporal && !pathReservoirEmpty(own)) {
        reuseTemporalCount(pixel, 94u);
        reuseTemporalCount(pixel, 95u + reverse.status);
    }
    reverseJacobian = reverse.status == REUSE_CONNECTION_OK ? reverse.jacobian : 0.0;
    reverseTarget = reverse.status == REUSE_CONNECTION_OK ? luminance(reverse.value) : 0.0;
    if (!temporal) {
        reuseTemporalCount(pixel, 114u); // Valid spatial proposal domain, even when its tree is empty.
        if (forward.status == REUSE_CONNECTION_OK) reuseTemporalCount(pixel, 115u);
        if (reverse.status == REUSE_CONNECTION_OK) reuseTemporalCount(pixel, 116u);
        if (forward.status == REUSE_CONNECTION_NUMERIC || reverse.status == REUSE_CONNECTION_NUMERIC)
            reuseTemporalCount(pixel, 120u);
    }
    return true;
}

PackedPathReservoir reuseRetargetSelected(PackedPathReservoir history, ReuseConnectionEvaluation forward,
        float target, PackedPathReservoir own, uint entryMediumFlags) {
    history.jacDenom = forward.denominator;
    history.targetOwn = target;
    history.incomingDirX = asuint(forward.direction.x);
    history.incomingDirY = asuint(forward.direction.y);
    history.incomingDirZ = asuint(forward.direction.z);
    uint technique = (history.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
    uint kind = (history.bits >> REUSE_RECORD_EVENT_KIND_SHIFT) & 15u;
    if (!(technique == REUSE_TECHNIQUE_H && kind == 2u)) history.sourcePdf = forward.eventPdf;
    return reuseStorePrimaryDomain(history, own.primaryOrigin, own.primaryPackedDirection,
            own.primaryRayCone, own.objectId, history.pathSeed, entryMediumFlags);
}

float3 reuseTemporal(__ref PathSegment seg, uint entryMediumFlags, uint2 pixel, uint2 dimensions,
        ReuseConnectionDomain current, bool currentValid, ReusePathTree tree,
        float3 ownRadiance, inout PackedPathReservoir own, inout uint stream) {
    if (!currentValid || !reusePrimaryDomainValid(own)) return ownRadiance;
    if (tree.weightSum > 0.0 && pathReservoirEmpty(own)) return ownRadiance;
    reuseTemporalCount(pixel, 84u);
    PathHistoryHeader root = reuseHistoryRoot();
    if (!reuseHistoryReady(root)) return ownRadiance;
    reuseTemporalCount(pixel, 85u);
    uint temporalPixel = REUSE_SPATIAL_NO_PIXEL;
    if (pathReuseTemporalEnabled()
            && !restirPreviousPixel(current.geometry.position, dimensions, temporalPixel))
        temporalPixel = REUSE_SPATIAL_NO_PIXEL;
    if (temporalPixel != REUSE_SPATIAL_NO_PIXEL) reuseTemporalCount(pixel, 104u);

    uint neighbours = min(pathReuseSpatialNeighbours(), REUSE_SPATIAL_MAX);
    uint spatialPixels[REUSE_SPATIAL_MAX];
    uint spatialCount = 0u;
    if (neighbours > 0u) {
        reuseTemporalCount(pixel, 110u);
        // Separate the proposal stream from both continuation and WRS.
        uint spatialSeed = seg.seed ^ 0x385f92c7u;
        pcg(spatialSeed);
        spatialCount = reuseSpatialPixels(pixel, dimensions, neighbours, temporalPixel, spatialSeed, spatialPixels);
    }

    uint accepted = 0u;
    uint spatialAccepted = 0u;
    float confidence = 1.0;
    float total = 0.0;
    float3 rgb = float3(0);
    float canonicalWeight = 0.0;
    float historyWeight = 0.0;
    float ownTarget = pathReservoirEmpty(own) ? 0.0 : tree.selectedTarget;
    PackedPathReservoir survivor = own;
    ReuseConnectionEvaluation survivalShift = {};
    float survivalTarget = ownTarget;
    bool chooseHistory = false;
    bool spatialSurvivor = false;
    // Slot zero is temporal; every other slot comes from geometry-only selection.
    [loop]
    for (uint i = 0u; i <= spatialCount; ++i) {
        uint previousPixel = i == 0u ? temporalPixel : spatialPixels[i - 1u];
        if (previousPixel == REUSE_SPATIAL_NO_PIXEL) continue;
        PackedPathReservoir history;
        ReuseConnectionEvaluation forward;
        float historyConfidence, reverseTarget, reverseJacobian;
        if (!reuseReadPair(seg, entryMediumFlags, pixel, dimensions, root, previousPixel, current,
                own, i == 0u, history, forward, historyConfidence, reverseTarget, reverseJacobian)) continue;
        float forwardJacobian = forward.status == REUSE_CONNECTION_OK ? forward.jacobian : 0.0;
        float forwardTarget = forward.status == REUSE_CONNECTION_OK ? luminance(forward.value) : 0.0;
        ReuseTemporalEstimate estimate = reuseTemporalEstimate(
                spatialCount == 0u ? ownRadiance : float3(0),
                spatialCount == 0u ? tree.sourceSum : float3(0), tree.selectedValue,
                forward.value, ownTarget, reverseTarget, history.targetOwn, forwardTarget,
                own.W, history.W, reverseJacobian, forwardJacobian, historyConfidence);
        if (!estimate.valid) {
            reuseTemporalCount(pixel, 102u);
            return ownRadiance;
        }
        accepted++;
        if (i > 0u) spatialAccepted++;
        confidence += historyConfidence;
        // Use the R3 estimate directly when there are no spatial proposals.
        if (spatialCount == 0u) {
            rgb = estimate.value;
            total = estimate.total;
        } else {
            rgb += estimate.value;
            canonicalWeight += estimate.ownWeight;
            historyWeight += estimate.historyWeight;
        }
        float partial = spatialCount == 0u ? total : historyWeight;
        if (partial > 0.0 && rndf(stream) * partial < estimate.historyWeight) {
            survivor = history;
            survivalShift = forward;
            survivalTarget = forwardTarget;
            chooseHistory = true;
            spatialSurvivor = i > 0u;
        }
    }
    if (accepted == 0u) return ownRadiance;

    if (spatialCount > 0u) {
        total = canonicalWeight + historyWeight;
        if (canonicalWeight > 0.0 && rndf(stream) * total < canonicalWeight) chooseHistory = false;
        total /= float(accepted);
        rgb = ownRadiance - tree.sourceSum + rgb / float(accepted);
    }
    if (!(total >= 0.0) || !isfinite(total) || !all(isfinite(rgb))) {
        reuseTemporalCount(pixel, 102u);
        return ownRadiance;
    }
    PackedPathReservoir next = own;
    if (chooseHistory) {
        next = reuseRetargetSelected(survivor, survivalShift, survivalTarget, own, entryMediumFlags);
        next.W = total / survivalTarget;
    } else if (total > 0.0 && ownTarget > 0.0) {
        next.W = total / ownTarget;
    } else {
        next = reuseStorePrimaryDomain(emptyPathReservoir(), own.primaryOrigin, own.primaryPackedDirection,
                own.primaryRayCone, own.objectId, own.pathSeed, entryMediumFlags);
    }
    if (!(next.W >= 0.0) || !isfinite(next.W)) {
        reuseTemporalCount(pixel, 102u);
        return ownRadiance;
    }
    next.confPdf = packHalf2(float2(confidence, 0.0));
    own = next;
    if (chooseHistory) reuseTemporalCount(pixel, spatialSurvivor ? 117u : 100u);
    if (spatialAccepted > 0u) reuseTemporalCount(pixel, 118u);
    if (accepted > spatialAccepted) reuseTemporalCount(pixel, 101u);
    reuseTemporalConfidence(pixel, confidence);
    return rgb;
}

#endif // REUSE_TEMPORAL_MATH_ONLY
