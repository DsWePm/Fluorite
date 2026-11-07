// Solid terrain and opaque entity geometry both use index zero. An any-hit primary can change
// layers within the same object between frames, so its raw ray alone cannot prove correspondence.
bool reuseStablePrimaryHit(uint objectId, uint geometryIndex) {
    return objectId != PATH_OBJECT_UNAVAILABLE && (objectId & PARTICLE_BIT) == 0u && geometryIndex == 0u;
}

// R3 primary domain: retain the exact input queue ray, then decode real geometry/material once.
// These fields replace the old hit-position/normal/UV caches; the record remains 128 B.
static const uint REUSE_PRIMARY_DOMAIN_VALID = 1u << 26u;

bool reusePrimaryDomainValid(PackedPathReservoir record) {
    return (record.bits & REUSE_PRIMARY_DOMAIN_VALID) != 0u
            && record.objectId != PATH_OBJECT_UNAVAILABLE;
}

PackedPathReservoir reuseStorePrimaryRay(PackedPathReservoir record, float3 origin,
                                         uint packedDirection, uint packedCone) {
    float2 cone = unpackHalf2(packedCone);
    if (!all(isfinite(origin)) || !all(isfinite(cone)) || any(cone < 0.0) || !(cone.y > 0.0))
        return emptyPathReservoir();
    record.primaryOrigin = origin;
    record.primaryPackedDirection = packedDirection;
    record.primaryRayCone = packedCone;
    return record;
}

// A zero WRS tree is still a proposal domain. Keep its primary inputs and fresh confidence even
// without an event-valid bit; reverse own-sample MIS must not drop this domain's probability mass.
// The caller excludes numerically invalid trees and unsupported primary hits before publication.
PackedPathReservoir reuseStorePrimaryDomain(PackedPathReservoir record, float3 origin,
        uint packedDirection, uint packedCone, uint objectId, uint pathSeed, uint mediumFlags) {
    if (objectId == PATH_OBJECT_UNAVAILABLE || (mediumFlags & ~0x00030003u) != 0u)
        return emptyPathReservoir();
    record.objectId = objectId;
    record = reuseStorePrimaryRay(record, origin, packedDirection, packedCone);
    if (record.objectId != objectId) return emptyPathReservoir(); // Invalid raw ray.
    record.pathSeed = pathSeed;
    uint domainMask = (3u << REUSE_RECORD_CURRENT_MEDIUM_SHIFT)
            | (3u << REUSE_RECORD_OUTER_MEDIUM_SHIFT) | (7u << REUSE_RECORD_PRIMARY_SHIFT);
    record.bits = (record.bits & ~domainMask) | REUSE_PRIMARY_DOMAIN_VALID
            | (activeCurrentMediumFlags(mediumFlags) << REUSE_RECORD_CURRENT_MEDIUM_SHIFT)
            | (activeOuterMediumFlags(mediumFlags) << REUSE_RECORD_OUTER_MEDIUM_SHIFT)
            | (REUSE_PRIMARY_OPAQUE << REUSE_RECORD_PRIMARY_SHIFT);
    record.confPdf = packHalf2(float2(1.0, 0.0));
    return record;
}

// The caller supplies the domain frame's medium/camera state. This restores ray inputs only;
// it does not borrow a current receiver's material or reconstruct a direction from a rounded point.
PathSegment reuseRestorePrimaryRay(PathSegment domainTemplate, PackedPathReservoir record) {
    PathSegment restored = domainTemplate;
    restored.ro = record.primaryOrigin;
    restored.rd = reuseDecodePrimaryDirection(record.primaryPackedDirection);
    float2 cone = unpackHalf2(record.primaryRayCone);
    restored.rayConeWidth = cone.x;
    restored.rayConeSpread = cone.y;
    return restored;
}

// Diagnostic only: fail before tracing if the record cannot recover the actual original queue ray.
bool reusePrimaryRayMatchesSegment(PathSegment restored, PathSegment actual) {
    return all(asuint(restored.ro) == asuint(actual.ro))
            && all(asuint(restored.rd) == asuint(actual.rd))
            && asuint(restored.rayConeWidth) == asuint(actual.rayConeWidth)
            && asuint(restored.rayConeSpread) == asuint(actual.rayConeSpread);
}
