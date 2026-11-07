// R3 primary domain: retain the exact input queue ray, then decode real geometry/material once.
// These fields replace the old hit-position/normal/UV caches; the record remains 128 B.
PackedPathReservoir reuseStorePrimaryRay(PackedPathReservoir record, float3 origin,
                                         uint packedDirection, uint packedCone) {
    if (pathReservoirEmpty(record)) return record;
    float2 cone = unpackHalf2(packedCone);
    if (!all(isfinite(origin)) || !all(isfinite(cone)) || any(cone < 0.0) || !(cone.y > 0.0))
        return emptyPathReservoir();
    record.primaryOrigin = origin;
    record.primaryPackedDirection = packedDirection;
    record.primaryRayCone = packedCone;
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
