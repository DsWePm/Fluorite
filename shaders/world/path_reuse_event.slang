// Source-event evaluation after the actual opaque vertex has been decoded. No tracing or RNG here.
struct ReuseEventEvaluation {
    float3 weight;
    float pdf;
    bool valid;
};

ReuseEventEvaluation reuseEvaluateEvent(ReuseOpaqueVertex vertex, PackedPathReservoir record,
        float3 rayOrigin, float3 incomingDirection, float previousPdf, bool previousCelestial) {
    ReuseEventEvaluation event;
    event.weight = float3(0.0, 0.0, 0.0);
    event.pdf = 0.0;
    event.valid = false;
    if (pathReservoirEmpty(record)) return event;
    uint technique = (record.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
    uint kind = (record.bits >> REUSE_RECORD_EVENT_KIND_SHIFT) & 15u;
    uint lobe = (record.bits >> REUSE_RECORD_LOBE_SHIFT) & 7u;
    float3 direction = reuseLoadDirection(record);
    if (technique == REUSE_TECHNIQUE_D || technique == REUSE_TECHNIQUE_H) {
        if ((technique == REUSE_TECHNIQUE_D && kind != 0u)
                || (technique == REUSE_TECHNIQUE_H && kind > 2u)
                || record.neePdf < 0.0 || !isfinite(record.neePdf)
                || (kind == 2u && (!(record.sourcePdf > 0.0) || !isfinite(record.sourcePdf)))) return event;
        ReuseContinuationSample evaluated = reuseEvaluateContinuation(vertex.bc, vertex.pf, vertex.ps,
                vertex.walkSssShare, lobe, direction);
        event.weight = evaluated.weight;
        if (technique == REUSE_TECHNIQUE_H) {
            // Emitter-hit MIS keeps the original area-measure epsilon, rather than moving it to angle measure.
            event.weight *= kind == 2u ? powerHeuristic(evaluated.pdf * record.sourcePdf, record.neePdf)
                    : reuseLocalHitMis(evaluated.pdf, record.neePdf);
        }
        event.pdf = evaluated.pdf;
        event.valid = reuseAnalyticContinuation(evaluated) && reuseNonnegativeFinite(event.weight);
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
    } else if (technique == REUSE_TECHNIQUE_L && kind == REUSE_LOCAL_EMISSION) {
        bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
        float emission = payloadEmission();
        float share = reuseLocalEmitterShare(risOn, previousCelestial, previousPdf, vertex.n,
                vertex.bc.hitPos, rayOrigin, incomingDirection, vertex.albedo, emission);
        event.weight = vertex.albedo * emission * share * rainBaseExitTransmission(vertex.bc);
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
    float3 transportPrefix;
    float3 scatterWeight;
    float sourcePdf;
    bool supported;
};

ReuseEventReference emptyReuseEventReference() {
    ReuseEventReference reference;
    reference.transportPrefix = float3(1.0, 1.0, 1.0);
    reference.scatterWeight = float3(0.0, 0.0, 0.0);
    reference.sourcePdf = 0.0;
    reference.supported = false;
    return reference;
}

void reuseSelectReference(ReusePathTree tree, inout ReuseEventReference reference,
                          float3 scatterWeight, float sourcePdf, float3 transportPrefix, bool supported) {
    if (tree.lastSelected) {
        reference.transportPrefix = transportPrefix;
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
    record.reconNrm = packUnorm16x2(octEncode(recon.normal));
    record.jacDenom = denominator;
    record.bits = reuseLocalRecordBits(depth, REUSE_TECHNIQUE_L, mediumFlags);
    record.objectId = primary.objectId;
    record = reuseRecordEndpoint(record, recon, edgeDirection);
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

// H retains the chosen analytic outgoing lobe, but never bakes its old hit MIS into incident RGB.
PackedPathReservoir reuseHitEventRecord(PackedPathReservoir record, float competingPdf) {
    if (pathReservoirEmpty(record)) return record;
    record.bits = (record.bits & ~(7u << REUSE_RECORD_TECHNIQUE_SHIFT))
            | (REUSE_TECHNIQUE_H << REUSE_RECORD_TECHNIQUE_SHIFT);
    uint previousLobe = (record.bits >> REUSE_RECORD_PREVIOUS_LOBE_SHIFT) & 7u;
    return reuseRecordSampling(record, previousLobe, competingPdf > 0.0 ? 1u : 0u,
            record.sourcePdf, competingPdf);
}

// H kind 2 uses sourcePdf's tagged auxiliary lane for the fixed outgoing endpoint geometry.
// The incoming connection density is reevaluated; its source value is already in jacDenom.
PackedPathReservoir reuseHitAreaRecord(PackedPathReservoir record, float areaPdf, float geometry) {
    if (!(geometry > 0.0) || !isfinite(geometry)) return emptyPathReservoir();
    record = reuseHitEventRecord(record, areaPdf);
    uint previousLobe = (record.bits >> REUSE_RECORD_PREVIOUS_LOBE_SHIFT) & 7u;
    record = reuseRecordSampling(record, previousLobe, 2u, record.sourcePdf, areaPdf);
    if (pathReservoirEmpty(record)) return record;
    record.sourcePdf = geometry;
    return record;
}
