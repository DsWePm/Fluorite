// k=2 connection evaluator. The same nearest radiance hit supplies visibility, material and geometry.
// It does not consume history, resample, perform roulette, or modify the source path's own radiance.
#include "path_reuse_connection_geometry.slang"

static const uint REUSE_CONNECTION_OK = 0u;
static const uint REUSE_CONNECTION_SCOPE = 1u;
static const uint REUSE_CONNECTION_HIT = 2u;
static const uint REUSE_CONNECTION_FOOTPRINT = 3u;
static const uint REUSE_CONNECTION_NUMERIC = 4u;

struct ReuseConnectionEvaluation {
    float3 value;
    float jacobian;
    float denominator;
    uint status;
    float3 direction;
    float eventPdf;
};

struct ReuseConnectionDomain {
    ReuseOpaqueVertex vertex;
    ReuseHitGeometry geometry;
    ReuseReplayState prefix;
    float coneSpread;
};

bool reuseReadK2Domain(__ref PathSegment seg, uint entryMediumFlags, uint pathSeed,
                       out ReuseConnectionDomain domain) {
    float3 position;
    uint geometryStatus;
    int reached = reuseReplayPrefix(seg, entryMediumFlags, pathSeed, 1, position,
            false, true, domain.geometry, geometryStatus, domain.prefix);
    if (reached != 1 || !domain.prefix.opaquePrefix || geometryStatus != REUSE_GEOMETRY_OK) return false;
    uint sssMode = (worldPush.flags >> 7u) & 3u;
    int sssMaxEvents = int((worldPush.flags >> 9u) & 7u);
    reuseCanonicalizeReplayPayload();
    domain.vertex = reuseOpaqueVertex(position, payload.normal, domain.prefix.incomingDirection,
            domain.prefix.rayConeWidth, 0, sssMode, sssMaxEvents);
    domain.coneSpread = seg.rayConeSpread;
    return true;
}

// Domains are decoded once by the caller, so forward/reverse evaluation can share real primary hits.
ReuseConnectionEvaluation reuseEvaluateK2AtDomains(__ref PathSegment seg, uint entryMediumFlags,
        ReuseConnectionDomain source, ReuseConnectionDomain destination, PackedPathReservoir record,
        bool visibility = true) {
    ReuseConnectionEvaluation result = {};
    result.value = float3(0.0, 0.0, 0.0);
    result.jacobian = 0.0;
    result.denominator = 0.0;
    result.status = REUSE_CONNECTION_SCOPE;
    uint depth = (record.bits >> REUSE_RECORD_DEPTH_SHIFT) & 15u;
    if (pathReservoirEmpty(record) || depth != 2u || seg.bounce != 0
            || !all(isfinite(record.reconBarycentrics))
            || ((record.bits >> REUSE_RECORD_PRIMARY_SHIFT) & 7u) != REUSE_PRIMARY_OPAQUE
            || ((record.bits >> REUSE_RECORD_CURRENT_MEDIUM_SHIFT) & 3u)
                != activeCurrentMediumFlags(entryMediumFlags)
            || ((record.bits >> REUSE_RECORD_OUTER_MEDIUM_SHIFT) & 3u)
                != activeOuterMediumFlags(entryMediumFlags)) return result;
    ReuseOpaqueVertex receiver = destination.vertex;
    ReuseHitGeometry primary = destination.geometry;
    ReuseReplayState prefix = destination.prefix;
    uint sssMode = (worldPush.flags >> 7u) & 3u;
    int sssMaxEvents = int((worldPush.flags >> 9u) & 7u);
    // Connect from the tracer's actual biased ray origin, not from an idealised unoffset surface.
    float3 direction;
    float distance;
    if (!reuseConnectionDirection(source.vertex.p, reuseLoadIncomingDirection(record), receiver.p,
            record.reconPos, direction, distance) || !(distance < RAY_FAR)) return result;
    uint previousLobe = (record.bits >> REUSE_RECORD_PREVIOUS_LOBE_SHIFT) & 7u;
    ReuseContinuationSample connection = reuseEvaluateContinuation(receiver.bc, receiver.pf, receiver.ps,
            receiver.walkSssShare, previousLobe, direction);
    if (!reuseAnalyticContinuation(connection)) return result;
    float coneSpread = max(destination.coneSpread, RAY_CONE_MIN_SPREAD);
    if (previousLobe == REUSE_LOBE_DIFFUSE) {
        coneSpread = max(coneSpread, RAY_CONE_DIFFUSE_SPREAD);
    } else if (previousLobe == REUSE_LOBE_FILM) {
        coneSpread = max(coneSpread, receiver.rain.filmAlpha * RAY_CONE_GLOSSY_SPREAD_SCALE);
    } else {
        coneSpread = max(coneSpread,
                max(receiver.bc.alphaX, receiver.bc.alphaY) * RAY_CONE_GLOSSY_SPREAD_SCALE);
    }
    bool showCelestial = previousLobe != REUSE_LOBE_DIFFUSE;
    float tolerance = reuseGeometryTolerance(receiver.p, distance);
    result.status = REUSE_CONNECTION_HIT;
    tracePathEndpointRadiance(secondaryCullMask(), receiver.p, direction, min(RAY_FAR, distance + tolerance),
            showCelestial, prefix.rayConeWidth, coneSpread, record.reconObjectId,
            record.reconGeometryIndex, record.reconPrimitiveIndex, record.reconBarycentrics, visibility);
    // An intervening interface is a failed shift, not an extra transparent layer to march through.
    if (payload.hitT < 0.0 || payloadMaterial() != MATERIAL_OPAQUE
            || pathHitGeometry.objectId != record.reconObjectId
            || pathHitGeometry.geometryIndex != record.reconGeometryIndex
            || pathHitGeometry.primitiveIndex != record.reconPrimitiveIndex
            || abs(payload.hitT - distance) > tolerance) return result;
    float3 hitPosition = reuseReplayPosition(receiver.p, direction, payload.hitT);
    ReuseHitGeometry recon;
    if (reuseReadHitGeometry(hitPosition, pathHitGeometry, recon) != REUSE_GEOMETRY_OK) return result;
    float3 miss = recon.position - record.reconPos;
    float3 sourceNormal = octDecode(unpackUnorm16x2(record.reconNrm));
    // Match the existing geometry diagnostic's angular rounding bound, including oct16 storage.
    if (dot(miss, miss) > tolerance * tolerance
            || !(dot(sourceNormal, -direction) > 0.0)
            || dot(sourceNormal, recon.normal) < 1.0 - 1.0e-6) return result;
    float hitDistance = payload.hitT;
    float coneWidth = max(prefix.rayConeWidth + coneSpread * max(hitDistance, 0.0), RAY_CONE_MIN_WIDTH);
    reuseCanonicalizeReplayPayload();
    ReuseOpaqueVertex fresh = reuseOpaqueVertex(hitPosition, payload.normal, direction,
            coneWidth, 1, sssMode, sssMaxEvents);
    ReuseEventEvaluation event = reuseEvaluateEvent(fresh, record, receiver.p, direction,
            connection.pdf, showCelestial);
    result.status = REUSE_CONNECTION_NUMERIC;
    if (!event.valid) {
        // A changed view can remove an analytic event's support. This is a failed mapping,
        // distinct from NaN/Inf arithmetic on a supported mapping.
        if (isfinite(event.pdf) && all(isfinite(event.weight))) result.status = REUSE_CONNECTION_SCOPE;
        return result;
    }
    uint technique = (record.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
    uint lobe = (record.bits >> REUSE_RECORD_LOBE_SHIFT) & 7u;
    bool viewIndependent = (technique != REUSE_TECHNIQUE_D && technique != REUSE_TECHNIQUE_H)
            || lobe == REUSE_LOBE_DIFFUSE;
    float3 toCamera = prefix.rayOrigin - primary.position;
    float primaryDistanceSq = dot(toCamera, toCamera);
    float primaryCosine = primaryDistanceSq > 0.0
            ? dot(primary.normal, toCamera * rsqrt(primaryDistanceSq)) : 0.0;
    result.status = REUSE_CONNECTION_FOOTPRINT;
    if (!reuseEventFootprintEligible(connection, event.pdf, viewIndependent, direction, hitDistance,
            primary.normal, recon.normal, primaryDistanceSq, primaryCosine,
            REUSE_MIN_ALPHA, REUSE_FOOTPRINT_SCALE, result.denominator)) return result;
    result.status = REUSE_CONNECTION_NUMERIC;
    if (!(record.jacDenom > 0.0) || !isfinite(record.jacDenom)) return result;
    result.jacobian = result.denominator / record.jacDenom;
    // The edge seed belongs to the candidate, including reverse evaluation of the own sample.
    // A shared material domain must never borrow another candidate's stochastic primary T.
    float3 transport = float3(1.0, 1.0, 1.0);
    reuseAccumulateTransport(transport, seg.medium.current.extinction,
            activeCurrentMediumFlags(entryMediumFlags), prefix.rayOrigin, prefix.incomingDirection,
            prefix.hitDistance, record.pathSeed, 0u, false);
    reuseAccumulateTransport(transport, seg.medium.current.extinction,
            activeCurrentMediumFlags(entryMediumFlags), receiver.p, direction, hitDistance,
            record.pathSeed, 1u, true);
    float3 scatter = (prefix.scatterPrefix * connection.weight) * event.weight;
    result.value = (transport * scatter) * reuseLoadIncident(record);
    result.direction = direction;
    result.eventPdf = event.pdf;
    if (!reuseNonnegativeFinite(result.value) || !(result.jacobian > 0.0)
            || !isfinite(result.jacobian)) return result;
    result.status = REUSE_CONNECTION_OK;
    return result;
}

ReuseConnectionEvaluation reuseEvaluateK2Connection(__ref PathSegment seg, uint entryMediumFlags,
                                                   PackedPathReservoir record) {
    ReuseConnectionDomain domain;
    PathSegment recordedSegment = reuseRestorePrimaryRay(seg, record);
    if (reusePrimaryRayMatchesSegment(recordedSegment, seg)
            && reuseReadK2Domain(recordedSegment, entryMediumFlags, record.pathSeed, domain))
        return reuseEvaluateK2AtDomains(seg, entryMediumFlags, domain, domain, record);
    ReuseConnectionEvaluation failed;
    failed.value = float3(0.0, 0.0, 0.0);
    failed.jacobian = 0.0;
    failed.denominator = 0.0;
    failed.status = REUSE_CONNECTION_SCOPE;
    return failed;
}

// Keep the original source-seed replay and this genuinely reconstructed edge as separate checks.
void reuseDiagnoseK2Connection(__ref PathSegment seg, uint entryMediumFlags,
                               PackedPathReservoir record, float3 referenceValue) {
    if (((record.bits >> REUSE_RECORD_DEPTH_SHIFT) & 15u) != 2u) return;
    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[76], 1u);
    ReuseConnectionEvaluation evaluated = reuseEvaluateK2Connection(seg, entryMediumFlags, record);
    if (evaluated.status != REUSE_CONNECTION_OK) {
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[77u + evaluated.status], 1u);
        return;
    }
    float valueError = reuseRelativeRgbError(referenceValue, evaluated.value);
    float jacError = reuseRelativeScalarError(1.0, evaluated.jacobian);
    InterlockedMax(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[82], asuint(valueError));
    InterlockedMax(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[83], asuint(jacError));
    if (valueError <= 0.001 && jacError <= 0.001) {
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[77], 1u);
    } else {
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[81], 1u);
    }
}
