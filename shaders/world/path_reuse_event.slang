// Source-event evaluation after the actual opaque vertex has been decoded. No tracing or RNG here.
struct ReuseEventEvaluation {
    float3 weight;
    float pdf;
    bool valid;
};

ReuseEventEvaluation reuseEvaluateEvent(ReuseOpaqueVertex vertex, PackedPathReservoir record) {
    ReuseEventEvaluation event;
    event.weight = float3(0.0, 0.0, 0.0);
    event.pdf = 0.0;
    event.valid = false;
    uint technique = (record.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
    uint kind = (record.bits >> REUSE_RECORD_EVENT_KIND_SHIFT) & 15u;
    uint lobe = (record.bits >> REUSE_RECORD_LOBE_SHIFT) & 7u;
    float3 direction = reuseLoadDirection(record);
    if (technique == REUSE_TECHNIQUE_D || technique == REUSE_TECHNIQUE_H) {
        ReuseContinuationSample evaluated = reuseEvaluateContinuation(vertex.bc, vertex.pf, vertex.ps,
                vertex.walkSssShare, lobe, direction);
        event.weight = evaluated.weight;
        event.pdf = evaluated.pdf;
        event.valid = reuseAnalyticContinuation(evaluated);
        return event;
    }
    if (lobe != REUSE_LOBE_NONE) return event;
    bool celestialOn = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
            || worldPush.skyProvider == SKY_PROVIDER_ENVIRONMENT;
    float halfAngle = celestialOn ? worldPush.lightDir.w : 0.0;
    float lightPdf = halfAngle > 0.0 ? squareLightPdf(halfAngle) : 0.0;
    bool misActive = (worldPush.flags & 32u) != 0u && halfAngle > 0.0;
    if (technique == REUSE_TECHNIQUE_L && kind == REUSE_LOCAL_AMBIENT) {
        event.weight = reuseLocalAmbientWeight(vertex.bc);
        event.pdf = 1.0;
    } else if (technique == REUSE_TECHNIQUE_S && celestialOn) {
        float ndl = max(0.0, dot(vertex.bc.n, direction));
        event.weight = reuseLocalSunBrdf(vertex.bc, vertex.ext, vertex.pf, vertex.ps,
                vertex.walkSssShare, misActive, vertex.exactSpecular, lightPdf, direction, ndl) * ndl;
        event.pdf = lightPdf > 0.0 ? lightPdf : 1.0;
    } else if (technique == REUSE_TECHNIQUE_T && celestialOn) {
        event.weight = reuseLocalThinWeight(vertex.bc, direction, sssStrength(),
                sssPhase(dot(direction, -vertex.bc.v)));
        event.pdf = lightPdf > 0.0 ? lightPdf : 1.0;
    }
    event.valid = event.pdf > 0.0 && isfinite(event.pdf) && reuseNonnegativeFinite(event.weight);
    return event;
}

// Reference values are captured when the event is selected, independently of the replay evaluator.
struct ReuseEventReference {
    float3 scatterWeight;
    float sourcePdf;
    bool supported;
};

ReuseEventReference emptyReuseEventReference() {
    ReuseEventReference reference;
    reference.scatterWeight = float3(0.0, 0.0, 0.0);
    reference.sourcePdf = 0.0;
    reference.supported = false;
    return reference;
}

void reuseSelectReference(ReusePathTree tree, inout ReuseEventReference reference,
                          float3 scatterWeight, float sourcePdf, bool supported) {
    if (tree.lastSelected) {
        reference.scatterWeight = scatterWeight;
        reference.sourcePdf = sourcePdf;
        reference.supported = supported;
    }
}

// Build the terminal-density template before a continuation is sampled, including at the bounce cap.
PackedPathReservoir reuseMakeLocalRecord(ReuseHitGeometry primary, ReuseHitGeometry previous,
        ReuseHitGeometry recon, ReuseContinuationSample previousSample,
        float3 edgeDirection, float edgeDistance, float3 cameraOrigin,
        uint pathSeed, uint depth, uint mediumFlags) {
    PackedPathReservoir record = emptyPathReservoir();
    float3 toCamera = cameraOrigin - primary.position;
    float distanceSq = dot(toCamera, toCamera);
    float cosine = distanceSq > 0.0 ? dot(primary.normal, toCamera * rsqrt(distanceSq)) : 0.0;
    float denominator;
    if (!reuseEventFootprintEligible(previousSample, 1.0, true, edgeDirection, edgeDistance,
            previous.normal, recon.normal, distanceSq, cosine,
            REUSE_MIN_ALPHA, REUSE_FOOTPRINT_SCALE, denominator)) return record;
    record.reconPos = recon.position;
    record.pathSeed = pathSeed;
    record.primaryPos = primary.position;
    record.primaryNrm = packUnorm16x2(octEncode(primary.normal));
    record.reconNrm = packUnorm16x2(octEncode(recon.normal));
    record.jacDenom = denominator;
    record.bits = reuseLocalRecordBits(depth, REUSE_TECHNIQUE_L, mediumFlags);
    record.objectId = primary.objectId;
    record.primaryUv = packHalf2(primary.uv);
    record = reuseStoreDirection(record, float3(0.0, 0.0, 0.0));
    return reuseRecordSampling(record, previousSample.lobe, REUSE_LOCAL_AMBIENT, 1.0, 0.0);
}

PackedPathReservoir reuseLocalEventRecord(PackedPathReservoir record, uint technique, uint kind,
                                         float3 direction, float pdf) {
    if (pathReservoirEmpty(record)) return record;
    record.bits = (record.bits & ~(7u << REUSE_RECORD_TECHNIQUE_SHIFT))
            | (technique << REUSE_RECORD_TECHNIQUE_SHIFT);
    record.jacDenom *= pdf;
    record = reuseStoreDirection(record, direction);
    uint previousLobe = (record.bits >> REUSE_RECORD_PREVIOUS_LOBE_SHIFT) & 7u;
    return reuseRecordSampling(record, previousLobe, kind, pdf, 0.0);
}
