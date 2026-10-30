// Record-only initial path-tree resampling. Included only by the reuse raygen variant.
// Each candidate is ONE terminal lighting event after an already chosen D reconnection.
// Its incident value excludes the prefix, scattering at x_k, and ALL external RR factors.
struct ReusePathTree {
    float weightSum;
    float selectedTarget;
    float3 selectedIncident;
    bool valid;
};

ReusePathTree emptyReusePathTree() {
    ReusePathTree tree;
    tree.weightSum = 0.0;
    tree.selectedTarget = 0.0;
    tree.selectedIncident = float3(0.0, 0.0, 0.0);
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
                     float survival, inout uint stream) {
    float target = luminance(sourceWeight * incident);
    if (!reuseNonnegativeFinite(sourceWeight) || !reuseNonnegativeFinite(incident)
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
        tree.selectedTarget = target;
        tree.selectedIncident = incident;
    }
    tree.weightSum = total;
    return true;
}

// R2's first produced subset: D events ending at a deeper opaque vertex's solar NEE.
// Other D endpoints and S/L/H/T/R at x_k remain in the pixel's own traced sum.
static const uint REUSE_TECHNIQUE_D = 1u;
static const uint REUSE_RECORD_DEPTH_SHIFT = 1u;
static const uint REUSE_RECORD_TECHNIQUE_SHIFT = 5u;
static const uint REUSE_RECORD_LOBE_SHIFT = 8u;
static const uint REUSE_RECORD_CURRENT_MEDIUM_SHIFT = 12u;
static const uint REUSE_RECORD_OUTER_MEDIUM_SHIFT = 14u;
static const uint REUSE_RECORD_PRIMARY_SHIFT = 16u;
static const uint REUSE_PRIMARY_OPAQUE = 1u;

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
// radiance stores R, and the former spare lanes store G/B. Primary object/UV hooks are independent.
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
            || !all(isfinite(unpackHalf2(record.primaryUv)))) {
        return emptyPathReservoir();
    }
    return record;
}
