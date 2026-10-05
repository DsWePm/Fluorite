// Included only by the path-reuse raygen variant. The radiance hit/payload ABI stays unchanged.
// Resolve metadata for the hit TraceRay already chose; this is not a visibility/nearest-hit query.

static const uint REUSE_GEOMETRY_OK = 0u;
static const uint REUSE_GEOMETRY_MISSING = 1u;
static const uint REUSE_GEOMETRY_AMBIGUOUS = 2u;
static const uint REUSE_GEOMETRY_INVALID = 3u;

struct ReuseHitGeometry {
    float3 position;
    float3 normal;
    float2 uv;
    uint objectId;
    uint geometryIndex;
    uint primitiveIndex;
};

ReuseHitGeometry emptyReuseHitGeometry() {
    ReuseHitGeometry hit;
    hit.position = float3(0.0, 0.0, 0.0);
    hit.normal = float3(0.0, 0.0, 0.0);
    hit.uv = float2(0.0, 0.0);
    hit.objectId = PATH_OBJECT_UNAVAILABLE;
    hit.geometryIndex = 0u;
    hit.primitiveIndex = 0u;
    return hit;
}

// Eight float rounding units at the ray's coordinate/distance scale. A wider tolerance only rejects
// more unresolved ties; it never chooses a different surface just because that surface is nearer.
float reuseGeometryTolerance(float3 origin, float hitT) {
    float scale = max(abs(origin.x), max(abs(origin.y), abs(origin.z)));
    return 9.5367431640625e-7 * (1.0 + scale + abs(hitT));
}

uint reuseQueryHitGeometry(uint cullMask, float3 ro, float3 rd, float hitT,
                          out ReuseHitGeometry hit) {
    hit = emptyReuseHitGeometry();
    if (!isfinite(hitT) || hitT < 0.0 || !all(isfinite(ro)) || !all(isfinite(rd))) {
        return REUSE_GEOMETRY_INVALID;
    }
    float tolerance = reuseGeometryTolerance(ro, hitT);
    float3 expectedPos = ro + rd * hitT;
    if (!all(isfinite(expectedPos))) {
        return REUSE_GEOMETRY_INVALID;
    }
    // FORCE_NON_OPAQUE exposes opaque and cutout triangles alike. Do not commit candidates: traversal
    // order is unspecified, and committing one would hide competing coplanar hits. Cutout any-hit has
    // already chosen hitT in TraceRay; candidates at other distances do not supply its metadata.
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE> query;
    query.TraceRayInline(topLevelAS, RAY_FLAG_FORCE_NON_OPAQUE, cullMask,
                         makeRay(ro, 0.0, rd, min(RAY_FAR, hitT + tolerance)));
    bool seen = false;
    bool valid = false;
    bool ambiguous = false;
    bool invalid = false;
    uint3 firstKey = uint3(0u, 0u, 0u);
    while (query.Proceed()) {
        if (query.CandidateType() != CANDIDATE_NON_OPAQUE_TRIANGLE) {
            continue;
        }
        if (abs(query.CandidateTriangleRayT() - hitT) > tolerance) {
            continue;
        }
        uint objectId = query.CandidateInstanceID();
        uint geometryIndex = query.CandidateGeometryIndex();
        uint primitiveIndex = query.CandidatePrimitiveIndex();
        uint3 key = uint3(objectId, geometryIndex, primitiveIndex);
        if (seen && any(key != firstKey)) {
            ambiguous = true;
        }
        if (!seen) {
            firstKey = key;
            seen = true;
        }
        // A shared edge between different primitives is also unresolved: hitT alone cannot tell us
        // which primitive's UV/material the radiance shader used. G16 excludes particle records.
        bool entity = (objectId & ENTITY_BIT) != 0u;
        if ((objectId & PARTICLE_BIT) != 0u || geometryIndex >= (entity ? 2u : TERRAIN_BUCKETS)) {
            invalid = true;
            continue;
        }
        float3 positions[3] = query.CandidateGetIntersectionTriangleVertexPositions();
        float3x4 transform = query.CandidateObjectToWorld3x4();
        // KHR position fetch returns object-space vertices with the geometry transform applied,
        // but without the instance transform. Cross transformed edges for nonuniform/mirrored scale.
        float3 edge1 = mul((float3x3)transform, positions[1] - positions[0]);
        float3 edge2 = mul((float3x3)transform, positions[2] - positions[0]);
        float3 normal = cross(edge1, edge2);
        float normalSq = dot(normal, normal);
        float2 bary = query.CandidateTriangleBarycentrics();
        float3 weights = float3(1.0 - bary.x - bary.y, bary.x, bary.y);
        float3 position = mul(transform, float4(positions[0], 1.0))
                + bary.x * edge1 + bary.y * edge2;
        float3 miss = position - expectedPos;
        if (!isfinite(normalSq) || normalSq <= 0.0 || !all(isfinite(position))
                || dot(miss, miss) > tolerance * tolerance) {
            invalid = true;
            continue;
        }
        normal *= rsqrt(normalSq);
        normal = dot(normal, rd) > 0.0 ? -normal : normal;
        float2 uv0;
        float2 uv1;
        float2 uv2;
        // Match world.rchit's two indexing contracts exactly: terrain has contiguous triangle-corner
        // UVs; entities have indexed vertex UVs. triBase is per geometry in both tables.
        if (entity) {
            EntityGeom geom = ConstPtr<EntityGeom>(pc.entityTableAddr)[objectId & ~ENTITY_BIT];
            uint pid = primitiveIndex + geom.triBase[geometryIndex];
            ConstPtr<uint> indices = ConstPtr<uint>(geom.idxAddr);
            ConstPtr<float2> uvs = ConstPtr<float2>(geom.uvAddr);
            uv0 = uvs[indices[3u * pid + 0u]];
            uv1 = uvs[indices[3u * pid + 1u]];
            uv2 = uvs[indices[3u * pid + 2u]];
        } else {
            Section section = ConstPtr<Section>(pc.tableAddr)[objectId];
            uint pid = primitiveIndex + section.triBase[geometryIndex];
            ConstPtr<float2> uvs = ConstPtr<float2>(section.uvAddr);
            uv0 = uvs[3u * pid + 0u];
            uv1 = uvs[3u * pid + 1u];
            uv2 = uvs[3u * pid + 2u];
        }
        float2 uv = weights.x * uv0 + weights.y * uv1 + weights.z * uv2;
        if (!all(isfinite(uv))) {
            invalid = true;
            continue;
        }
        hit.position = position;
        hit.normal = normal;
        hit.uv = uv;
        // Current TLAS custom index, not a stable cross-frame object identity (LoD work is pending).
        hit.objectId = objectId;
        hit.geometryIndex = geometryIndex;
        hit.primitiveIndex = primitiveIndex;
        valid = true;
    }
    uint status = ambiguous ? REUSE_GEOMETRY_AMBIGUOUS
            : (invalid ? REUSE_GEOMETRY_INVALID : (valid ? REUSE_GEOMETRY_OK : REUSE_GEOMETRY_MISSING));
    if (status != REUSE_GEOMETRY_OK) {
        hit = emptyReuseHitGeometry();
    }
    return status;
}

// Compare actual metadata, not just two query success flags. UV is compared before packing; object,
// geometry and primitive IDs must agree exactly, including the source triangle at shared edges.
bool reuseSameHitGeometry(ReuseHitGeometry source, ReuseHitGeometry replayed) {
    float3 delta = source.position - replayed.position;
    float2 uvDelta = abs(source.uv - replayed.uv);
    float2 uvScale = 1.0 + max(abs(source.uv), abs(replayed.uv));
    return source.objectId == replayed.objectId
            && source.geometryIndex == replayed.geometryIndex
            && source.primitiveIndex == replayed.primitiveIndex
            && dot(delta, delta) <= 1.0e-8 * (1.0 + dot(source.position, source.position))
            && dot(source.normal, replayed.normal) >= 1.0 - 1.0e-6
            && all(uvDelta <= 9.5367431640625e-7 * uvScale);
}

void reuseCountSourceGeometry(uint status) {
    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[2], 1u);
    // 3 resolved, 4 missing, 5 ambiguous, 6 invalid: the status is a complete partition of attempts.
    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[3u + status], 1u);
}
