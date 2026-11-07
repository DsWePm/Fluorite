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
void reuseTemporalCount(uint2 pixel, uint lane) {
#ifdef FLUORITE_PROBES
    if (worldPush.pathReservoirStatsAddr != 0u && ((pixel.x + 3u * pixel.y) & 15u) == 0u)
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[lane], 1u);
#endif
}
void reuseTemporalConfidence(uint2 pixel, float confidence) {
#ifdef FLUORITE_PROBES
    if (worldPush.pathReservoirStatsAddr != 0u && ((pixel.x + 3u * pixel.y) & 15u) == 0u)
        InterlockedMax(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[103], asuint(confidence));
#endif
}

float3 reuseTemporal(__ref PathSegment seg, uint entryMediumFlags, uint2 pixel, uint2 dimensions,
        ReuseConnectionDomain current, bool currentValid, ReusePathTree tree,
        float3 ownRadiance, inout PackedPathReservoir own, inout uint stream) {
    if (!currentValid || !reusePrimaryDomainValid(own)) return ownRadiance;
    if (tree.weightSum > 0.0 && pathReservoirEmpty(own)) return ownRadiance;
    reuseTemporalCount(pixel, 84u);
    PackedPathReservoir canonical = own;
    PathHistoryHeader root = reuseHistoryRoot();
    if (!reuseHistoryReady(root)) return ownRadiance;
    reuseTemporalCount(pixel, 85u);
    uint previousPixel;
    if (!restirPreviousPixel(current.geometry.position, dimensions, previousPixel)) return ownRadiance;
    reuseTemporalCount(pixel, 104u);
    DevicePtr<PackedPathReservoir> store = DevicePtr<PackedPathReservoir>(root.storeAddr);
    PackedPathReservoir history = store[pathReservoirSlot(previousPixel, dimensions.x * dimensions.y,
            (worldPush.frameIndex ^ 1u) & 1u)];
    if (!reusePrimaryDomainValid(history)) return ownRadiance;
    reuseTemporalCount(pixel, 105u);
    if (!reuseUnchangedSurface(root, history.objectId)) return ownRadiance;
    reuseTemporalCount(pixel, 106u);
    float historyConfidence = min(unpackHalf2(history.confPdf).x, float(root.confidenceCap));
    if (!(historyConfidence > 0.0) || !isfinite(historyConfidence)) return ownRadiance;
    reuseTemporalCount(pixel, 107u);

    // True old primary, decoded once. The frame context proves the initial medium matches.
    PathSegment historyRay = reuseRestorePrimaryRay(seg, history);
    ReuseConnectionDomain old;
    if (!reuseReadK2Domain(historyRay, entryMediumFlags, history.pathSeed, old)) return ownRadiance;
    reuseTemporalCount(pixel, 108u);
    if (!reuseStablePrimaryHit(old.geometry.objectId, old.geometry.geometryIndex)) return ownRadiance;
    reuseTemporalCount(pixel, 109u);
    if (old.geometry.objectId != history.objectId) return ownRadiance;

    reuseTemporalCount(pixel, 86u);
    if (pathReservoirEmpty(history)) reuseTemporalCount(pixel, 87u);
    ReuseConnectionEvaluation forward = {};
    forward.status = REUSE_CONNECTION_SCOPE;
    if (!pathReservoirEmpty(history) && reuseUnchangedSurface(root, history.reconObjectId))
        forward = reuseEvaluateK2AtDomains(seg, entryMediumFlags, old, current, history);
    ReuseConnectionEvaluation reverse = {};
    reverse.status = REUSE_CONNECTION_SCOPE;
    if (!pathReservoirEmpty(own) && reuseUnchangedSurface(root, own.reconObjectId))
        reverse = reuseEvaluateK2AtDomains(seg, entryMediumFlags, current, old, own,
                (worldPush.pathReplayEnabled & 2u) != 0u);

    if (!pathReservoirEmpty(history)) {
        reuseTemporalCount(pixel, 88u);
        reuseTemporalCount(pixel, 89u + forward.status);
    }
    if (!pathReservoirEmpty(own)) {
        reuseTemporalCount(pixel, 94u);
        reuseTemporalCount(pixel, 95u + reverse.status);
    }
    float ownTarget = pathReservoirEmpty(own) ? 0.0 : tree.selectedTarget;
    float reverseJacobian = reverse.status == REUSE_CONNECTION_OK ? reverse.jacobian : 0.0;
    float forwardJacobian = forward.status == REUSE_CONNECTION_OK ? forward.jacobian : 0.0;
    float reverseTarget = reverse.status == REUSE_CONNECTION_OK ? luminance(reverse.value) : 0.0;
    float forwardTarget = forward.status == REUSE_CONNECTION_OK ? luminance(forward.value) : 0.0;
    ReuseTemporalEstimate estimate = reuseTemporalEstimate(ownRadiance, tree.sourceSum,
            tree.selectedValue, forward.value, ownTarget, reverseTarget, history.targetOwn,
            forwardTarget, own.W, history.W, reverseJacobian, forwardJacobian, historyConfidence);
    if (!estimate.valid) { reuseTemporalCount(pixel, 102u); return ownRadiance; }
    float3 value = estimate.value;
    float total = estimate.total;
    float historyWeight = estimate.historyWeight;
    float confidence = 1.0 + historyConfidence;
    if (total > 0.0 && rndf(stream) * total < historyWeight) {
        reuseTemporalCount(pixel, 100u);
        PackedPathReservoir selected = history;
        selected.jacDenom = forward.denominator;
        selected.targetOwn = forwardTarget;
        selected.W = total / forwardTarget;
        selected.incomingDirX = asuint(forward.direction.x);
        selected.incomingDirY = asuint(forward.direction.y);
        selected.incomingDirZ = asuint(forward.direction.z);
        uint technique = (selected.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
        uint kind = (selected.bits >> REUSE_RECORD_EVENT_KIND_SHIFT) & 15u;
        if (!(technique == REUSE_TECHNIQUE_H && kind == 2u)) selected.sourcePdf = forward.eventPdf;
        selected = reuseStorePrimaryDomain(selected, own.primaryOrigin, own.primaryPackedDirection,
                own.primaryRayCone, own.objectId, selected.pathSeed, entryMediumFlags);
        own = selected;
    } else if (total > 0.0 && ownTarget > 0.0) {
        own.W = total / ownTarget;
    } else {
        // A valid zero domain still advances confidence, without inventing a candidate.
        uint objectId = own.objectId;
        float3 origin = own.primaryOrigin;
        uint direction = own.primaryPackedDirection, cone = own.primaryRayCone, seed = own.pathSeed;
        own = reuseStorePrimaryDomain(emptyPathReservoir(), origin, direction, cone, objectId, seed, entryMediumFlags);
    }
    if (!(own.W >= 0.0) || !isfinite(own.W)) { reuseTemporalCount(pixel, 102u); own = canonical; return ownRadiance; }
    own.confPdf = packHalf2(float2(confidence, 0.0));
    reuseTemporalCount(pixel, 101u);
    reuseTemporalConfidence(pixel, confidence);
    return value;
}

#endif // REUSE_TEMPORAL_MATH_ONLY
