// Bindings, per-frame push state, the ray payload, and the constants every pass shares.
// Import first: everything below depends on pc, worldPush and payload.

import world_common;

[[vk::push_constant]] public WorldPushConstants pc;

[[vk::binding(0, 0)]] public RaytracingAccelerationStructure topLevelAS;
// HDR trace target: linear radiance, may exceed 1. Tonemap happens later at the display.comp seam.
[[vk::binding(1, 0)]] [format("rgba16f")] public RWTexture2D<float4> outImage;
// Guide buffers — first-hit (primary-visibility) attributes consumed by the denoiser/DLSS-RR. Written
// every frame regardless of accumulation. gNormal.w carries LINEAR roughness (= GGX alpha, which is what
// DLSS-RR documents for this input); gDepth carries HW reversed-Z depth.
[[vk::binding(3, 0)]] [format("rgba16f")] public RWTexture2D<float4> gNormal;     // xyz world normal, w linear roughness
[[vk::binding(4, 0)]] [format("rgba16f")] public RWTexture2D<float4> gAlbedo;     // rgb diffuse albedo
[[vk::binding(5, 0)]] [format("r32f")]    public RWTexture2D<float>  gDepth;      // HW reversed-Z depth
[[vk::binding(6, 0)]] [format("rg16f")]   public RWTexture2D<float2> gMotion;     // screen-space motion (render px)
[[vk::binding(7, 0)]] [format("rgba16f")] public RWTexture2D<float4> gSpecAlbedo; // exact-mirror reflected diffuse × reflectance; material reflectance otherwise
[[vk::binding(8, 0)]] [format("rg16f")]   public RWTexture2D<float2> gSpecMotion; // reflection MVs for RR

// Per-frame push data loaded once from the BDA buffer at the top of main() (same pattern as the GLSL
// `worldPush` global). Layout constants generated from this module's SPIR-V — see world_common.WorldPush.
public static WorldPush worldPush = {};

// The BDA carries the active celestial emitter's UNDYED irradiance. Raygen keeps that value here before
// dyeCelestialLight mutates its local WorldPush copy for legacy centre-direction consumers. Finite-emitter
// estimators need the original so each sampled direction can receive its own atmospheric transmittance.
// Module state is per invocation; this adds no WorldPush lane and no cross-thread storage.
public static float3 undyedCelestialIrradiance = {};

// The radiance payload remains module-level so tracePath and its guide helpers can share it across
// HitObject trace/invoke calls, mirroring the GLSL rayPayloadEXT global.
public static Payload payload = {};

public struct VisibilityResult {
    public float3 transmittance;
    public float waterHitT;
};



public uint payloadMaterial() { return payload.flags & PAYLOAD_MATERIAL_MASK; }
// Set by world.rchit on any dielectric hit (see PAYLOAD_DIELECTRIC_ENTERING) — whether the ray was
// travelling into the volume (vs. out of it) at this hit's face.
public bool payloadDielectricEntering() { return (payload.flags & PAYLOAD_DIELECTRIC_ENTERING) != 0u; }
public bool payloadEmitterInList() { return (payload.flags & PAYLOAD_EMITTER_IN_LIST) != 0u; }
/** True when a PROXY covers this emitter rather than a second sampling of the surface just hit. */
public bool payloadEmitterProxied() { return (payload.flags & PAYLOAD_EMITTER_PROXIED) != 0u; }
public bool payloadRainFilmReceiver() {
    return payloadMaterial() == MATERIAL_OPAQUE
            && (payload.flags & (PAYLOAD_RAIN_TOP_FACE | PAYLOAD_RAIN_PLANT)) != 0u;
}
public bool payloadRainTopFace() {
    return payloadMaterial() == MATERIAL_OPAQUE && (payload.flags & PAYLOAD_RAIN_TOP_FACE) != 0u;
}
public bool payloadRainPlant() {
    return payloadMaterial() == MATERIAL_OPAQUE && (payload.flags & PAYLOAD_RAIN_PLANT) != 0u;
}
// LINEAR roughness, i.e. GGX alpha directly — NOT perceptual roughness. This is the one convention used
// end to end: LabPBR defines roughness = (1 - perceptualSmoothness)^2 and RtLabPbr.decode stores exactly
// that, RtMaterials.Profile carries the same units, and DLSS-RR wants linear roughness in its guide. So
// nothing squares it: ggxD/ggxG1/sampleGGXVNDF/rrSpecularAlbedo all take this value as alpha as-is, and
// any threshold compared against it is an alpha threshold.
public float payloadRoughness() { return unpackHalf2(payload.roughMetal).x; }
public float payloadMetalness() { return unpackHalf2(payload.roughMetal).y; }
public float payloadEmission() { return unpackHalf2(payload.emissionSss).x; }
public float payloadSss() { return unpackHalf2(payload.emissionSss).y; }
public float payloadIor() { return unpackHalf2(payload.iorTransmission).x; }
public float payloadTransmission() { return unpackHalf2(payload.iorTransmission).y; }

public static const float PI = 3.14159265359;
public static const float INV_PI = 0.31830988618;

// The analytic sky (gradient + sun/moon discs + stars) is computed in world.rmiss. On a miss, raygen
// reads payload.albedo and accumulates that sky radiance. The directional NEE light is pushed separately
// as worldPush.lightDir / worldPush.lightRadiance / worldPush.sunDir.
public static const float SURF_BIAS = 0.005;  // offset secondary-ray origins along the normal (anti-acne)
public static const float RAY_TMIN = 0.001;   // tiny tmin: the normal offset already clears the surface
// How far a ray may travel before it is treated as having escaped. Named because it is not only a tmax:
// a ray that escapes still travelled this far through whatever medium it was in, and the segment
// attenuation has to use the same distance the trace did or the two silently drift apart.
public static const float RAY_FAR = 10000.0;

/** The camera ray for one dispatch index, plus the jittered NDC both passes derive it from. */
public struct CameraRay {
    public float3 origin;
    public float3 dir;
    public float2 jndc;   // primaryRayConeSpread needs this; the volumetric prefix does not
};

/**
 * Reconstructs the camera ray. Shared because both raygens need it and must agree exactly: the indirect
 * pass measures the prefix segment the primary pass consumed as the distance from this origin to the
 * queued record's, so two copies of these eight lines drifting apart would show up as fog appearing or
 * vanishing in front of every water surface.
 */
public CameraRay cameraRay(uint2 dispatchIndex, uint2 dimensions) {
    float2 size = float2(dimensions);
    float2 uv = (float2(dispatchIndex) + 0.5) / size;
    float2 jndc = (uv + worldPush.jitter / size) * 2.0 - 1.0;
    float4 nearH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(jndc.x, jndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    CameraRay ray;
    ray.origin = nearP + worldPush.camOffset;
    ray.dir = normalize(farP - nearP);
    ray.jndc = jndc;
    return ray;
}

/**
 * Where a world point sat on LAST frame's screen, in NDC. Invalid means behind the previous camera --
 * which a point in view now easily can be, most readily at a grazing angle or as the camera turns.
 *
 * <p>`camDelta` is what moves the point into the previous camera's frame; `camOffset` is the current
 * rebase. Both passes need this: pass A to build motion vectors, pass B to find where a shading vertex's
 * reservoir was stored. It lives HERE rather than in guides.slang for exactly that reason — pass B
 * deliberately does not import guides, and two copies of a reprojection are two things to drift apart.
 *
 * <p>The matrix is jitter-free (see RtComposite.mvCurProjView), so this answers where the point would
 * have projected at the pixel grid, not which jittered sample happened to land on it.
 */
public float2 projectPrevNdc(float3 worldPos, out bool valid) {
    float4 clip = mul(worldPush.prevViewProj,
            float4(worldPos - worldPush.camOffset + worldPush.camDelta, 1.0));
    valid = clip.w > 0.0;
    return valid ? clip.xy / clip.w : float2(0.0, 0.0);
}

// Water is a smooth dielectric. IOR 1.333; absorption is per-block Beer-Lambert extinction.
public static const float WATER_IOR = 1.333;
public static const float3 SKY_SPEC_ALBEDO = float3(0.5, 0.5, 0.5); // RR guide default for sky pixels.
public static const float3 SKY_DIFF_ALBEDO = float3(0.5, 0.5, 0.5);

// Transmitted-ray origin offset when crossing a face from the TRANSLUCENT terrain layer (glass, ice).
// Must be SMALLER than the mesher-side TRANSLUCENT_INSET (RtTerrainMesher, 2e-4 blocks): those quads are
// recessed into their block by that inset, so a face touching a slab/stair leaves a tiny gap before the
// neighbour's surface. A large bias (SURF_BIAS) would restart the ray past that neighbour and see
// through it; this lands the ray inside the gap so the neighbour is still hit, while still clearing the
// surface it left to avoid self-intersection. Water is meshed by RtFluidMesher with no inset, so it uses
// the ordinary bias.
public static const float INSET_TRANSMIT_BIAS = 1.0e-4;

// Ray-cone texture LOD: raygen tracks a one-pixel footprint along the path, and closest-hit converts it
// to texture mip levels from the local UV/world-space scale. Diffuse/glossy bounces widen the cone so
// indirect texture fetches don't keep sampling mip 0 through broad lobes.
public static const float RAY_CONE_MIN_SPREAD = 1.0e-5;
public static const float RAY_CONE_MIN_WIDTH = 1.0e-5;
public static const float RAY_CONE_DIFFUSE_SPREAD = 0.25;
public static const float RAY_CONE_GLOSSY_SPREAD_SCALE = 0.35;

/**
 * How much surface one pixel covers at this vertex: the cone's width there, spread over the incidence.
 *
 * <p>A cone of a given width lands on a patch that grows without bound as the surface turns edge-on, which
 * is why the cosine divides rather than multiplies. The floor keeps a grazing hit from producing a patch
 * the size of the world -- the same 0.12 the rain ripples have always used, hoisted here when ReSTIR's
 * spatial reuse needed the identical quantity to bound how far a neighbour may be.
 */
public float surfaceFootprint(float coneWidth, float3 rd, float3 n) {
    return coneWidth / max(abs(dot(rd, n)), 0.12);
}

// Stored roughness IS alpha (see payloadRoughness), so these compare against it directly.
public static const float MIRROR_ALPHA_MAX = 4.0e-4; // LabPBR perceptual smoothness >= 0.98
// Above this the specular lobe is too broad for a single mirrored reprojection to describe, so the
// reflection MV is left at zero and RR falls back to the ordinary MV.
public static const float SPEC_MOTION_ALPHA_MAX = 0.25; // perceptual smoothness <= 0.5

/**
 * Alpha floor for a specular peak that nothing weights.
 *
 * A GGX peak goes as 1/(PI alpha^2), so a light sampled by area against a near-mirror receiver puts the
 * whole lobe into one sample and the estimator has no way to temper it. Where the light is also sampled
 * by the BSDF, MIS does the tempering and no floor is wanted.
 *
 * BLOCK EMITTERS NO LONGER USE IT. They were the main reason it existed, and M24 S4 gave them the real
 * fix the old note here promised: their estimator is now weighed against BSDF sampling, which bounds the
 * peak by weighting rather than by blurring. What is left is the SUN WITH MIS SWITCHED OFF — a light of
 * zero angular size has no finite density for anything to compete with, so there is nothing to weigh and
 * this is the only thing standing between a mirror-smooth surface and an unbounded highlight.
 *
 * It is not physically motivated: it makes a very smooth surface reflect a light as though it were
 * slightly rougher. 0.02 is where the 1e-7 epsilon that used to sit in ggxD effectively capped the peak,
 * so surfaces look as they did when that epsilon was doing this job for the whole renderer.
 *
 * Delta lobes are exempt and must stay exempt: alpha 0 means no microfacet specular term at all, and
 * flooring it would invent a highlight on a mirror that has never had one.
 */
public static const float UNWEIGHTED_SPEC_ALPHA_FLOOR = 0.02;

public bool isDeltaAlpha(float roughness) {
    return clamp(roughness, 0.0, 1.0) <= MIRROR_ALPHA_MAX;
}

/**
 * Above this alpha a dielectric interface stops being a delta and is resolved as a microfacet lobe.
 *
 * Deliberately NOT MIRROR_ALPHA_MAX, and the difference is load-bearing. That threshold is 4e-4, and
 * every transparent block the game ships is authored above it — glass and ice at 0.0025, water at
 * 0.0064. Reusing it here would take every pane of glass in Minecraft off Pass A's split path and
 * switch off resolveTransmissionGuide with it, which is the entire reason that chain exists.
 *
 * 0.06 leaves about a factor of ten over water, the roughest thing vanilla authors, so only a pack that
 * genuinely means frosted glass crosses it. Note that means a LabPBR pack authoring a high roughness on
 * glass in its _s texture now gets what it asked for, where before the value was read and discarded.
 */
public static const float TRANSMISSION_DELTA_ALPHA_MAX = 0.06;

public bool isDeltaTransmission(float roughness) {
    return clamp(roughness, 0.0, 1.0) <= TRANSMISSION_DELTA_ALPHA_MAX;
}

// Continuations one sample may carry: the camera segment, plus the reflection branch a primary
// dielectric splits off. Raising this needs a real queue, which is what the continuation buffer is for.
public static const uint MAX_PATH_SEGMENTS = 2u;
