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

// By default a canonical endpoint is checked by one nearest-hit ray; intervening geometry rejects it.
// The explicit reverse-visibility approximation uses typed any-hit to ignore other keys. No second ray.
public void tracePathEndpointRadiance(uint cullMask, float3 ro, float3 rd, float tmax,
        bool showCelestial, float rayConeWidth, float rayConeSpread,
        uint objectId, uint geometryIndex, uint primitiveIndex, float2 barycentrics, bool visibility = true) {
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (visibility ? 0u : PATH_ENDPOINT_NO_VISIBILITY);
    PathHitPayload hit = makePathHitPayload(flags, packHalf2(float2(rayConeWidth, rayConeSpread)));
    if (objectId != 0xFFFFFFFFu && all(isfinite(barycentrics))) {
        hit.geometry = makePathEndpointRequest(objectId, geometryIndex, primitiveIndex, barycentrics);
        TraceRay(topLevelAS, visibility ? RAY_FLAG_NONE : RAY_FLAG_FORCE_NON_OPAQUE, cullMask,
                SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
                makeRay(ro, 0.0, rd, tmax), hit);
    }
    payload = hit.surface;
    pathHitGeometry = hit.geometry;
}
