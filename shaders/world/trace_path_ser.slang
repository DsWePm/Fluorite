// Joint radiance hit with EXT SER. Kept out of the ordinary fallback's import graph.
import world_common;
import world_core;
import path_hit;
import trace;
import trace_path;

public void tracePathRadianceReordered(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
        bool showCelestial, bool directView, float rayConeWidth, float rayConeSpread, uint pathPhaseHint) {
    uint flags = (showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u)
            | (directView ? PAYLOAD_DIRECT_VIEW : 0u);
    uint rayCone = packHalf2(float2(rayConeWidth, rayConeSpread));
    PathHitPayload tracePayload = makePathHitPayload(flags, rayCone);
    HitObject hObj = HitObject::TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), tracePayload);
    uint materialHint = hObj.GetShaderTableIndex() & (TERRAIN_BUCKETS - 1u);
    uint coherenceHint = (pathPhaseHint << 2u) | materialHint;
    ReorderThread(hObj, coherenceHint, 4u);
    // Only flags/cone span the reorder. The joint surface/geometry result is constructed after it.
    PathHitPayload hit = makePathHitPayload(flags, rayCone);
    HitObject::Invoke(topLevelAS, hObj, hit);
    payload = hit.surface;
    pathHitGeometry = hit.geometry;
}
