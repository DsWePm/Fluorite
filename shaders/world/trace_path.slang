// Pass B's joint radiance-hit dispatch. Pass A, guide probes and shadows keep trace.slang's interface.
import world_common;
import world_core;
import path_hit;
import trace;

public static PathHitGeometry pathHitGeometry = {};

public PathHitPayload makePathHitPayload(uint flags, uint rayCone) {
    PathHitPayload hit;
    hit.surface = makeRadiancePayload(flags, rayCone);
    hit.geometry = emptyPathHitGeometry();
    return hit;
}

public void tracePathRadiance(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
        bool showCelestial, bool directView, float rayConeWidth, float rayConeSpread) {
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (directView ? PAYLOAD_DIRECT_VIEW : 0u);
    PathHitPayload hit = makePathHitPayload(flags, packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), hit);
    payload = hit.surface;
    pathHitGeometry = hit.geometry;
}

// A canonical endpoint is still checked by one nearest-hit ray. An intervening triangle makes this
// request fail; it is never skipped to force the requested surface visible. Shadow ABI is untouched.
public void tracePathEndpointRadiance(uint cullMask, float3 ro, float3 rd, float tmax,
        bool showCelestial, float rayConeWidth, float rayConeSpread,
        uint objectId, uint geometryIndex, uint primitiveIndex, float2 barycentrics) {
    uint flags = showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u;
    PathHitPayload hit = makePathHitPayload(flags, packHalf2(float2(rayConeWidth, rayConeSpread)));
    if (objectId != 0xFFFFFFFFu && all(isfinite(barycentrics))) {
        hit.geometry = makePathEndpointRequest(objectId, geometryIndex, primitiveIndex, barycentrics);
        TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
                makeRay(ro, 0.0, rd, tmax), hit);
    }
    payload = hit.surface;
    pathHitGeometry = hit.geometry;
}
