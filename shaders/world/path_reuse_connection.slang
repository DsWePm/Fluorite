// k=2 connection evaluator. The same nearest radiance hit supplies visibility, material and geometry.
// It does not consume history, resample, perform roulette, or modify the source path's own radiance.
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
};

ReuseConnectionEvaluation reuseEvaluateK2Connection(__ref PathSegment seg, uint entryMediumFlags,
                                                   PackedPathReservoir record) {
    ReuseConnectionEvaluation result;
    result.value = float3(0.0, 0.0, 0.0);
    result.jacobian = 0.0;
    result.denominator = 0.0;
    result.status = REUSE_CONNECTION_SCOPE;
    uint depth = (record.bits >> REUSE_RECORD_DEPTH_SHIFT) & 15u;
    if (pathReservoirEmpty(record) || depth != 2u || seg.bounce != 0
            || ((record.bits >> REUSE_RECORD_PRIMARY_SHIFT) & 7u) != REUSE_PRIMARY_OPAQUE
            || ((record.bits >> REUSE_RECORD_CURRENT_MEDIUM_SHIFT) & 3u)
                != activeCurrentMediumFlags(entryMediumFlags)
            || ((record.bits >> REUSE_RECORD_OUTER_MEDIUM_SHIFT) & 3u)
                != activeOuterMediumFlags(entryMediumFlags)) return result;

    float3 primaryPosition;
    ReuseHitGeometry primary;
    uint geometryStatus;
    ReuseReplayState prefix;
    int reached = reuseReplayPrefix(seg, entryMediumFlags, record.pathSeed, 1, primaryPosition,
                                    true, true, primary, geometryStatus, prefix);
    if (reached != 1 || !prefix.opaquePrefix || geometryStatus != REUSE_GEOMETRY_OK) return result;
    uint sssMode = (worldPush.flags >> 7u) & 3u;
    int sssMaxEvents = int((worldPush.flags >> 9u) & 7u);
    reuseCanonicalizeReplayPayload();
    ReuseOpaqueVertex receiver = reuseOpaqueVertex(primaryPosition, payload.normal,
            prefix.incomingDirection, prefix.rayConeWidth, 0, sssMode, sssMaxEvents);
    // Connect from the tracer's actual biased ray origin, not from an idealised unoffset surface.
    float3 edge = record.reconPos - receiver.p;
    float distanceSq = dot(edge, edge);
    if (!(distanceSq > 0.0) || !isfinite(distanceSq)) return result;
    float distance = sqrt(distanceSq);
    if (!(distance < RAY_FAR)) return result;
    float3 direction = edge / distance;
    uint previousLobe = (record.bits >> REUSE_RECORD_PREVIOUS_LOBE_SHIFT) & 7u;
    ReuseContinuationSample connection = reuseEvaluateContinuation(receiver.bc, receiver.pf, receiver.ps,
            receiver.walkSssShare, previousLobe, direction);
    if (!reuseAnalyticContinuation(connection)) return result;
    float coneSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
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
    tracePathRadiance(secondaryCullMask(), receiver.p, 0.0, direction, min(RAY_FAR, distance + tolerance),
            showCelestial, false, prefix.rayConeWidth, coneSpread);
    // An intervening interface is a failed shift, not an extra transparent layer to march through.
    if (payload.hitT < 0.0 || payloadMaterial() != MATERIAL_OPAQUE
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
    if (!event.valid) return result;
    uint technique = (record.bits >> REUSE_RECORD_TECHNIQUE_SHIFT) & 7u;
    uint lobe = (record.bits >> REUSE_RECORD_LOBE_SHIFT) & 7u;
    bool viewIndependent = (technique != REUSE_TECHNIQUE_D && technique != REUSE_TECHNIQUE_H)
            || lobe == REUSE_LOBE_DIFFUSE;
    float3 toCamera = seg.ro - primary.position;
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
    float3 transport = prefix.transportPrefix;
    reuseAccumulateTransport(transport, seg.medium.current.extinction,
            activeCurrentMediumFlags(entryMediumFlags), receiver.p, direction, hitDistance,
            record.pathSeed, 1u, true);
    float3 scatter = (prefix.scatterPrefix * connection.weight) * event.weight;
    result.value = (transport * scatter) * reuseLoadIncident(record);
    if (!reuseNonnegativeFinite(result.value) || !(result.jacobian > 0.0)
            || !isfinite(result.jacobian)) return result;
    result.status = REUSE_CONNECTION_OK;
    return result;
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
