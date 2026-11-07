// Joint radiance-hit interface; imported only by the path-reuse stages.
// The original material/shadow Payload stays in world_common with its published ABI.
import world_common;
// Input-only radiance request bit. Closest hit replaces the material flags; shadow uses its old ABI.
public static const uint PATH_ENDPOINT_NO_VISIBILITY = 2u;

// Authoritative geometry of the SAME intersection used to shade a radiance hit. No second traversal.
// Keep geometric normals and UV at float32; the ordinary shading-normal channel remains unchanged.
public struct PathHitGeometry {
    public float3 normal;
    public uint objectId;
    public float2 uv;
    public uint geometryIndex;
    public uint primitiveIndex;
    public float2 barycentrics;
};

// A separate radiance interface. Shadow rays continue to use the original Payload, with no growth.
public struct PathHitPayload {
    public Payload surface;
    public PathHitGeometry geometry;
};

public PathHitGeometry emptyPathHitGeometry() {
    PathHitGeometry hit;
    hit.normal = float3(0.0, 0.0, 0.0);
    hit.objectId = 0xFFFFFFFFu;
    hit.uv = float2(0.0, 0.0);
    hit.geometryIndex = 0u;
    hit.primitiveIndex = 0u;
    hit.barycentrics = float2(0.0, 0.0);
    return hit;
}

// Ordinary rays carry an unavailable object id. Endpoint rays carry the expected key and canonical
// barycentrics in the same exchange; the closest hit replaces them with the actual result.
public PathHitGeometry makePathEndpointRequest(uint objectId, uint geometryIndex, uint primitiveIndex,
                                             float2 barycentrics) {
    PathHitGeometry request = emptyPathHitGeometry();
    request.objectId = objectId;
    request.geometryIndex = geometryIndex;
    request.primitiveIndex = primitiveIndex;
    request.barycentrics = barycentrics;
    return request;
}

public bool pathEndpointRequestMatches(PathHitGeometry request, uint objectId,
                                      uint geometryIndex, uint primitiveIndex) {
    return request.objectId != 0xFFFFFFFFu && all(isfinite(request.barycentrics))
            && request.objectId == objectId && request.geometryIndex == geometryIndex
            && request.primitiveIndex == primitiveIndex;
}

public float2 pathHitShadingBarycentrics(PathHitGeometry request, float2 tracedBarycentrics,
                                       uint objectId, uint geometryIndex, uint primitiveIndex) {
    return pathEndpointRequestMatches(request, objectId, geometryIndex, primitiveIndex)
            ? request.barycentrics : tracedBarycentrics;
}

public PathHitGeometry pathTriangleHitGeometry(float3 edge1, float3 edge2, float2 uv, float3 rayDirection,
        uint objectId, uint geometryIndex, uint primitiveIndex) {
    PathHitGeometry hit = emptyPathHitGeometry();
    // Preserve exact cancellation for degenerate edges; a contracted cross(a,a) can manufacture a
    // tiny nonzero vector whose normalization would invent a seemingly valid geometric normal.
    // As in reuseReplayPosition, explicit SPIR-V decorations are required by the pinned SDK.
    float3 ayzx = edge1.yzx, azxy = edge1.zxy;
    float3 byzx = edge2.yzx, bzxy = edge2.zxy;
    float3 normal = spirv_asm {
        %positive : $$float3 = OpFMul $ayzx $bzxy;
        OpDecorate %positive NoContraction;
        %negative : $$float3 = OpFMul $azxy $byzx;
        OpDecorate %negative NoContraction;
        %normal : $$float3 = OpFSub %positive %negative;
        OpDecorate %normal NoContraction;
        result : $$float3 = OpCopyObject %normal
    };
    float normalSq = dot(normal, normal);
    if (!(normalSq > 0.0) || !isfinite(normalSq) || !all(isfinite(uv))) return hit;
    normal *= rsqrt(normalSq);
    hit.normal = dot(normal, rayDirection) > 0.0 ? -normal : normal;
    hit.objectId = objectId;
    hit.uv = uv;
    hit.geometryIndex = geometryIndex;
    hit.primitiveIndex = primitiveIndex;
    return hit;
}
