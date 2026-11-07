// Record-only initial path-tree resampling. Included only by the reuse raygen variant.
// Each candidate is ONE terminal lighting event after an already chosen D reconnection.
// Its incident value excludes the prefix, scattering at x_k, and ALL external RR factors.
static const uint REUSE_D_ENDPOINT_SOLAR = 0u;
static const uint REUSE_D_ENDPOINT_SKY = 1u;
static const uint REUSE_D_ENDPOINT_EMITTER = 2u;
static const uint REUSE_D_ENDPOINT_AMBIENT = 3u;
static const uint REUSE_D_ENDPOINT_THIN = 4u;
struct ReusePathTree {
    float weightSum;
    float selectedTarget;
    float3 selectedIncident;
    float3 selectedValue;
    float3 sourceSum;
    bool lastSelected;
    // Diagnostic provenance only. All these endpoints share D's stored incident and source density.
    uint selectedEndpoint;
    bool valid;
};

ReusePathTree emptyReusePathTree() {
    ReusePathTree tree;
    tree.weightSum = 0.0;
    tree.selectedTarget = 0.0;
    tree.selectedIncident = float3(0.0, 0.0, 0.0);
    tree.selectedValue = float3(0.0, 0.0, 0.0);
    tree.sourceSum = float3(0.0, 0.0, 0.0);
    tree.lastSelected = false;
    tree.selectedEndpoint = REUSE_D_ENDPOINT_SOLAR;
    tree.valid = true;
    return tree;
}

bool reuseNonnegativeFinite(float3 value) {
    return all(isfinite(value)) && all(value >= 0.0);
}

// w_e = |F_e| / Q_e. Q_e is the product of the actual RR survivals before that event,
// including survivals after x_k. It may differ between candidates in the same path tree.
// An invalid numeric candidate invalidates this record, never silently drops energy from its tree.
bool reuseTreeUpdate(inout ReusePathTree tree, float3 sourceWeight, float3 incident,
                     float survival, uint endpoint, inout uint stream) {
    tree.lastSelected = false;
    float3 value = sourceWeight * incident;
    float target = luminance(value);
    if (endpoint > REUSE_D_ENDPOINT_THIN
            || !reuseNonnegativeFinite(sourceWeight) || !reuseNonnegativeFinite(incident)
            || !(survival > 0.0) || survival > 1.0 || !isfinite(survival)
            || !isfinite(target)) {
        tree.valid = false;
        return false;
    }
    if (!(target > 0.0)) {
        return false;
    }
    float weight = target / survival;
    float total = tree.weightSum + weight;
    if (!(weight > 0.0) || !isfinite(weight) || !isfinite(total)) {
        tree.valid = false;
        return false;
    }
    if (rndf(stream) * total < weight) {
        tree.lastSelected = true;
        tree.selectedTarget = target;
        tree.selectedIncident = incident;
        tree.selectedValue = value;
        tree.selectedEndpoint = endpoint;
    }
    tree.weightSum = total;
    tree.sourceSum += value / survival;
    if (!reuseNonnegativeFinite(tree.sourceSum)) tree.valid = false;
    return true;
}

// The discrete event may reconnect at another vertex. Carry its own record with the same WRS draw.
bool reuseTreeUpdateRecord(inout ReusePathTree tree, inout PackedPathReservoir selected,
                           PackedPathReservoir candidate, float3 sourceWeight, float3 incident,
                           float survival, uint endpoint, inout uint stream) {
    tree.lastSelected = false;
    if (pathReservoirEmpty(candidate)) return false;
    bool accepted = reuseTreeUpdate(tree, sourceWeight, incident, survival, endpoint, stream);
    if (tree.lastSelected) selected = candidate;
    return accepted;
}

// Produced D subset: deeper solar/ambient/thin shading, and emitter/sky hits after an analytic vertex.
// The immediate emitter/sky hit is H; other endpoints and S/L/H/T/R remain in the own traced sum.
static const uint REUSE_TECHNIQUE_D = 1u;
static const uint REUSE_TECHNIQUE_S = 2u;
static const uint REUSE_TECHNIQUE_L = 3u;
static const uint REUSE_TECHNIQUE_H = 4u;
static const uint REUSE_TECHNIQUE_T = 5u;
static const uint REUSE_LOCAL_AMBIENT = 0u;
static const uint REUSE_LOCAL_EMISSION = 1u;
static const uint REUSE_RECORD_DEPTH_SHIFT = 1u;
static const uint REUSE_RECORD_TECHNIQUE_SHIFT = 5u;
static const uint REUSE_RECORD_LOBE_SHIFT = 8u;
static const uint REUSE_RECORD_CURRENT_MEDIUM_SHIFT = 12u;
static const uint REUSE_RECORD_OUTER_MEDIUM_SHIFT = 14u;
static const uint REUSE_RECORD_PRIMARY_SHIFT = 16u;
static const uint REUSE_RECORD_PREVIOUS_LOBE_SHIFT = 19u;
static const uint REUSE_RECORD_EVENT_KIND_SHIFT = 22u;
static const uint REUSE_PRIMARY_OPAQUE = 1u;
PackedPathReservoir reuseRecordEndpoint(PackedPathReservoir record, ReuseHitGeometry endpoint,
                                        float3 incomingDirection) {
    if (pathReservoirEmpty(record) || endpoint.objectId == PATH_OBJECT_UNAVAILABLE
            || !all(isfinite(endpoint.barycentrics)) || !all(isfinite(incomingDirection))
            || !(dot(incomingDirection, incomingDirection) > 0.0)) return emptyPathReservoir();
    record.reconObjectId = endpoint.objectId;
    record.reconGeometryIndex = endpoint.geometryIndex;
    record.reconPrimitiveIndex = endpoint.primitiveIndex;
    record.reconBarycentrics = endpoint.barycentrics;
    // The record's existing valid bit covers the endpoint too. Keep the original ray in the last
    // three scalar lanes: reconstructing it from rounded world positions loses grazing precision.
    record.incomingDirX = asuint(incomingDirection.x);
    record.incomingDirY = asuint(incomingDirection.y);
    record.incomingDirZ = asuint(incomingDirection.z);
    return record;
}

float3 reuseLoadIncomingDirection(PackedPathReservoir record) {
    return float3(asfloat(record.incomingDirX), asfloat(record.incomingDirY), asfloat(record.incomingDirZ));
}

uint reuseDRecordBits(uint depth, uint lobe, uint mediumFlags) {
    // Reject unknown traits instead of truncating future medium flags into today's two-bit tags.
    if (depth < 2u || depth > 15u
            || (lobe != REUSE_LOBE_DIFFUSE && lobe != REUSE_LOBE_SPECULAR && lobe != REUSE_LOBE_FILM)
            || (mediumFlags & ~0x00030003u) != 0u) {
        return 0u;
    }
    return PATH_RECORD_VALID | (depth << REUSE_RECORD_DEPTH_SHIFT)
            | (REUSE_TECHNIQUE_D << REUSE_RECORD_TECHNIQUE_SHIFT)
            | (lobe << REUSE_RECORD_LOBE_SHIFT)
            | (activeCurrentMediumFlags(mediumFlags) << REUSE_RECORD_CURRENT_MEDIUM_SHIFT)
            | (activeOuterMediumFlags(mediumFlags) << REUSE_RECORD_OUTER_MEDIUM_SHIFT)
            | (REUSE_PRIMARY_OPAQUE << REUSE_RECORD_PRIMARY_SHIFT);
}

uint reuseLocalRecordBits(uint depth, uint technique, uint mediumFlags) {
    if (depth < 2u || depth > 15u || (mediumFlags & ~0x00030003u) != 0u
            || (technique != REUSE_TECHNIQUE_S && technique != REUSE_TECHNIQUE_L
                && technique != REUSE_TECHNIQUE_T)) return 0u;
    return PATH_RECORD_VALID | (depth << REUSE_RECORD_DEPTH_SHIFT)
            | (technique << REUSE_RECORD_TECHNIQUE_SHIFT)
            | (activeCurrentMediumFlags(mediumFlags) << REUSE_RECORD_CURRENT_MEDIUM_SHIFT)
            | (activeOuterMediumFlags(mediumFlags) << REUSE_RECORD_OUTER_MEDIUM_SHIFT)
            | (REUSE_PRIMARY_OPAQUE << REUSE_RECORD_PRIMARY_SHIFT);
}

PackedPathReservoir reuseRecordSampling(PackedPathReservoir record, uint previousLobe, uint kind,
                                       float currentPdf, float competingPdf) {
    if (pathReservoirEmpty(record) || previousLobe < REUSE_LOBE_DIFFUSE || previousLobe > REUSE_LOBE_FILM
            || kind > 15u || !(currentPdf > 0.0) || !isfinite(currentPdf)
            || competingPdf < 0.0 || !isfinite(competingPdf)) return emptyPathReservoir();
    record.bits &= ~((7u << REUSE_RECORD_PREVIOUS_LOBE_SHIFT) | (15u << REUSE_RECORD_EVENT_KIND_SHIFT));
    record.bits |= (previousLobe << REUSE_RECORD_PREVIOUS_LOBE_SHIFT) | (kind << REUSE_RECORD_EVENT_KIND_SHIFT);
    record.sourcePdf = currentPdf;
    record.neePdf = competingPdf;
    return record;
}

// Full float32 direction: decoding must not normalize or otherwise perturb the sampled vector.
PackedPathReservoir reuseStoreDirection(PackedPathReservoir record, float3 direction) {
    record.dirK = asuint(direction.x);
    record.dirY = asuint(direction.y);
    record.dirZ = asuint(direction.z);
    return record;
}

float3 reuseLoadDirection(PackedPathReservoir record) {
    return float3(asfloat(record.dirK), asfloat(record.dirY), asfloat(record.dirZ));
}

// Full float32 incident RGB in the record. The ABI keeps its original field names:
// radiance stores R, and the former spare lanes store G/B. Primary ray metadata is independent.
PackedPathReservoir reuseStoreIncident(PackedPathReservoir record, float3 incident) {
    record.radiance = asuint(incident.r);
    record.reserved0 = asuint(incident.g);
    record.reserved1 = asuint(incident.b);
    return record;
}

float3 reuseLoadIncident(PackedPathReservoir record) {
    return float3(asfloat(record.radiance), asfloat(record.reserved0), asfloat(record.reserved1));
}

PackedPathReservoir reuseFinishTree(PackedPathReservoir record, ReusePathTree tree) {
    if (pathReservoirEmpty(record) || !tree.valid || !(tree.selectedTarget > 0.0)
            || !(tree.weightSum > 0.0) || !isfinite(tree.weightSum)
            || !reuseNonnegativeFinite(tree.selectedIncident)) {
        return emptyPathReservoir();
    }
    // P(e) = (target_e/Q_e)/sum. W_tree/Q_e = sum/target_e: no RR in the stored incident.
    record.W = tree.weightSum / tree.selectedTarget;
    record.targetOwn = tree.selectedTarget;
    record = reuseStoreIncident(record, tree.selectedIncident);
    // Fresh source confidence 1; D has no competing local NEE technique at x_k.
    record.confPdf = packHalf2(float2(1.0, 0.0));
    if (!(record.W > 0.0) || !isfinite(record.W) || !any(reuseLoadIncident(record) > 0.0)
            || !all(isfinite(record.primaryOrigin))
            || !all(isfinite(unpackHalf2(record.primaryRayCone)))) {
        return emptyPathReservoir();
    }
    return record;
}
