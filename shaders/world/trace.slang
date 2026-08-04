// Ray dispatch: SBT/cull constants, payload construction, ordinary/reordered radiance, guide probes,
// and shadow visibility. Depends on core.

// receiver billboards only. The first-person camera owner uses 0x04 (CULL_SELF) alone.

import world_common;
import world_core;

// TLAS instance-mask bits, ANDed against the per-ray cull mask (RtEntities sets them).
//
//   0x01 secondary — shadows, GI bounces, reflections. Terrain and ordinary entities carry it.
//   0x02 primary   — the camera ray.
//   0x04 self      — the first-person camera OWNER'S OWN BODY, and nothing else.
//
// The self bit exists because "visible to secondary rays" and "visible to secondary rays cast FROM THE
// EYE" are different questions, and the body is the only instance for which they differ. It sits at the
// camera, so any ray starting there hits it immediately; every other instance is somewhere the eye is
// not. Keeping it on its own bit lets a ray opt out of it without opting out of the world.
public static const uint CULL_SECONDARY_NO_SELF = 0x01u;
public static const uint CULL_SELF = 0x04u;
public static const uint CULL_SECONDARY = CULL_SECONDARY_NO_SELF | CULL_SELF;
public static const uint CULL_PRIMARY = 0x02u;

// worldPush.flags bit 12: hide the first-person body from every secondary ray this frame. Set while the
// eye is inside a water block — see secondaryCullMask.
public static const uint FLAG_HIDE_SELF = 1u << 12u;

/**
 * Cull mask for secondary rays: reflections, GI bounces, and NEE shadow rays.
 *
 * Normally the first-person body is included, so the player sees themselves in a pond as anyone else
 * would. While the eye is inside a water block it is dropped, because the water surface is then within
 * centimetres of the camera and its reflection shows the body sliced off at the waterline — a view
 * vanilla never presents, since first person does not draw the body at all. One uniform branch per path
 * rather than a per-ray distance test: whether the eye is in the water is a property of the frame.
 */
public uint secondaryCullMask() {
    return (worldPush.flags & FLAG_HIDE_SELF) != 0u ? CULL_SECONDARY_NO_SELF : CULL_SECONDARY;
}
public static const uint TERRAIN_BUCKETS = 4u;
public static const uint SBT_RADIANCE = 0u;
public static const uint SBT_SHADOW = TERRAIN_BUCKETS;
public static const uint SBT_STRIDE_BUCKET = 1u;
public static const uint MISS_RADIANCE = 0u;
public static const uint MISS_GUIDE = 1u;

public RayDesc makeRay(float3 origin, float tmin, float3 dir, float tmax) {
    RayDesc r;
    r.Origin = origin;
    r.TMin = tmin;
    r.Direction = dir;
    r.TMax = tmax;
    return r;
}

public float3 offsetSurfaceOrigin(float3 position, float3 surfaceNormal,
                           float3 outgoingDirection, float bias) {
    float3 sideNormal = dot(surfaceNormal, outgoingDirection) >= 0.0
            ? surfaceNormal : -surfaceNormal;
    return position + sideNormal * bias;
}

// A radiance payload carrying only the two words the invoked hit/miss shader READS: `flags`
// (show-celestial gate, consumed by world.rmiss) and the packed ray cone (consumed by world.rchit for
// texture LOD). Every other member is an OUTPUT that world.rchit/world.rmiss write, so it carries no
// information into a trace and is initialized to a neutral value here.
public Payload makeRadiancePayload(uint flags, uint rayCone) {
    Payload p;
    p.albedo = half3(0.0h, 0.0h, 0.0h);
    p.normal = half3(0.0h, 0.0h, 0.0h);
    p.hitT = -1.0; // miss sentinel: world.rmiss writes only albedo, so a miss leaves this negative
    p.motionPrev = half3(0.0h, 0.0h, 0.0h);
    p.f0 = half3(0.0h, 0.0h, 0.0h);
    p.flags = flags;
    p.roughMetal = 0u;
    p.emissionSss = 0u;
    p.iorTransmission = 0u;
    p.rayCone = rayCone;
    return p;
}

// Ordinary radiance trace for latency-sensitive, coherent work such as Pass A. It invokes hit/miss
// shaders directly and therefore requires no invocation-reorder capability.
public void traceRadiance(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                    bool showCelestial, float rayConeWidth, float rayConeSpread) {
    payload = makeRadiancePayload(showCelestial ? PAYLOAD_SHOW_CELESTIAL : 0u,
            packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask,
            SBT_RADIANCE, SBT_STRIDE_BUCKET, MISS_RADIANCE,
            makeRay(ro, tmin, rd, tmax), payload);
}

// Auxiliary RR probes deliberately use ordinary TraceRay. They are one-off, incoherent rays whose
// result is consumed immediately, so paying a reorder barrier and carrying guide state across it only
// adds latency. The dedicated miss record also avoids the atmosphere march: guides need hit-vs-miss,
// not sky radiance.
public void traceGuide(uint cullMask, float3 ro, float tmin, float3 rd, float tmax,
                float rayConeWidth, float rayConeSpread) {
    payload = makeRadiancePayload(0u, packHalf2(float2(rayConeWidth, rayConeSpread)));
    TraceRay(topLevelAS, RAY_FLAG_NONE, cullMask, SBT_RADIANCE, SBT_STRIDE_BUCKET,
            MISS_GUIDE, makeRay(ro, tmin, rd, tmax), payload);
}

public Payload makeShadowPayload() {
    Payload shadowPayload;
    shadowPayload.albedo = half3(1.0h, 1.0h, 1.0h);
    shadowPayload.hitT = -1.0; // water-crossing sentinel, filled by the water any-hit
    shadowPayload.normal = half3(0.0h, 0.0h, 0.0h);
    shadowPayload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    shadowPayload.f0 = half3(0.0h, 0.0h, 0.0h);
    // world_guide.rmiss clears this sentinel. An accepted opaque hit skips closest-hit and therefore
    // leaves it set, while ignored translucent/water hits continue until either a later opaque hit or miss.
    shadowPayload.flags = 1u;
    shadowPayload.roughMetal = 0u;
    shadowPayload.emissionSss = 0u;
    shadowPayload.iorTransmission = 0u;
    shadowPayload.rayCone = 0u;
    return shadowPayload;
}

public VisibilityResult visibilityMasked(uint cullMask, float3 origin, float3 dir, float tmax) {
    // Vulkan requires an identical payload structure for every stage reachable by this trace. The shadow
    // path uses only albedo as accumulated transmittance and hitT as the nearest-water crossing.
    Payload shadowPayload = makeShadowPayload();
    // Shadow SBT records run any-hit only for cutout/translucent/water. Cutout alpha-tests; translucent
    // and water tint shadowPayload.albedo and pass through. Solid blocks terminate traversal. There is no
    // closest shader worth executing. The lightweight guide miss clears the flags sentinel so this path
    // needs only ordinary TraceRay and no invocation-reorder capability.
    TraceRay(topLevelAS,
            RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER,
            cullMask, SBT_SHADOW, SBT_STRIDE_BUCKET, MISS_GUIDE,
            makeRay(origin, RAY_TMIN, dir, tmax), shadowPayload);
    VisibilityResult result;
    result.transmittance = shadowPayload.flags == 0u
            ? shadowPayload.albedo : float3(0.0, 0.0, 0.0);
    result.waterHitT = shadowPayload.hitT;
    return result;
}

/** Shadow ray from a shading point: the ordinary secondary mask, so the body shadows like any other. */
public VisibilityResult visibility(float3 origin, float3 dir, float tmax) {
    return visibilityMasked(secondaryCullMask(), origin, dir, tmax);
}

// `valid` is false when the point projects behind the previous camera, where the perspective divide
// would otherwise manufacture an arbitrarily large NDC. Mirror images reach that case easily: a virtual
// image sits as far behind the reflector as the source is in front, so a reflection seen at a grazing
