// M29 first-person hand, fragment stage: the hand receives the renderer's OWN lighting instead of the
// vanilla lightmap. Three direct terms -- celestial (sun/moon), local emitters (static grid + M18's
// dynamic records, which is where a held torch lives), and the M28 sky field -- weighted by the same
// Disney BSDF the world shades with, decoded from the SAME canonical material the entity path uses.
//
// REUSED, NOT RE-SPELLED, wherever reuse was reachable: the push block IS world_core's
// WorldPushConstants (so `worldPush` and every address resolves exactly as in the RT shaders), the sky
// term is volume_visibility's sector machinery consumed through the one function this line added
// (volumeSkySurfaceIrradiance), cloud shadowing is cloud_density's parameterised transmittance, emitter
// selection and sphere sampling are light_sampling's, and the BSDF is bsdf's own context math.
//
// KNOWN SECOND SPELLING (recorded, M29): the canonical-page decode below mirrors rchit's
// evaluateMaterial line for line. rchit is another parallel track's file this milestone, so extracting
// the shared body waits for the merge; RtHandLitContractTest pins the shared constants against rchit's
// text so the two cannot drift silently. Flagged for consolidation in S4.
//
// Deliberate v1 gaps (each recorded in the M29 devlog): no GI (the hand receives direct light only --
// the sky field's openness is its indirect stand-in), no rain film on the hand, no celestial term for
// non-atmosphere providers (the environment disk's irradiance needs its own tables; rain's streaks make
// the same choice), and no self-shadow between arm and held item (the hand is not in the TLAS).

import hand_lit_common;
import world_common;
import math;
import world_core;
import atmosphere_lut;
import volume_visibility;
import cloud_density;
import light_sampling;
import lighting;
import bsdf;

[[vk::binding(HAND_BINDING_TLAS, 0)]] RaytracingAccelerationStructure handTlas;
[[vk::binding(HAND_BINDING_CLOUD_SHADOW, 0)]] Sampler2D handCloudShadow;

// The bindless material arrays keep the RT pipeline's exact binding numbers (set 1), so the descriptor
// population and the sampling index space are shared with the entity path.
[[vk::binding(0, 1)]] Sampler2D entityAlbedoTex[];
[[vk::binding(1, 1)]] Sampler2D materialSurface0Tex[];
[[vk::binding(2, 1)]] Sampler2D materialNormalAoTex[];
[[vk::binding(3, 1)]] Sampler2D materialSurface1Tex[];

struct FragmentIn {
    float2 uv : TEXCOORD0;
    float3 worldNormal : TEXCOORD1;
    float3 worldPos : TEXCOORD2;
    float3 worldTangent : TEXCOORD3;
    float tangentHandedness : TEXCOORD4;
    float4 tintEmission : TEXCOORD5;
    nointerpolation uint4 data : TEXCOORD6;   // x texSlot, y materialId, z primFlags, w aux0
    nointerpolation float4 extra : TEXCOORD7; // x overlayStrength, y alphaBucket
};

// Cutout parity with the entity path: the collector's ANY_HIT bucket is exactly "alpha-test me", and
// the threshold is vanilla's cutout reference the renderer already shades terrain with.
static const float HAND_CUTOUT_ALPHA = 0.1;
// Instance mask for every hand shadow ray: secondary-no-self (trace.slang's CULL_SECONDARY_NO_SELF --
// terrain, entities and blocks occlude; the first-person body, which vanilla never draws here either,
// does not). Kept local because trace.slang's payloads and SBT machinery have no reason to import here.
static const uint HAND_SHADOW_MASK = 0x01u;
// Emitter shadow rays cap at the same reach the streak lighting uses; dynamic spheres shadow to their
// own surface + slack so a held torch lights the palm holding it.
static const float HAND_SHADOW_TMAX = 4096.0;
static const float HAND_DYN_SHADOW_SLACK = 1.25;
static const uint  HAND_DYN_LIGHT_SCAN = 32u;
static const uint  HAND_DYN_SHADOWED = 3u;
// The enchantment foil's constants, from the rchit entity branch (RtHandLitContractTest pins the pair).
static const float3 GLINT_TINT = float3(0.62, 0.36, 0.96);
static const float3 GLINT_PHASE_AXIS = float3(1.7, 1.3, 1.1);
static const float GLINT_TINT_WEIGHT = 0.35;
static const float GLINT_EMISSION = 2.0;
static const float GLINT_RATE = 0.02;

bool handOccluded(float3 p, float3 direction, float tMax) {
    RayDesc ray;
    ray.Origin = p;
    ray.Direction = direction;
    ray.TMin = 0.03;
    ray.TMax = tMax;
    // Secondary-no-self, the rain streaks' mask: terrain, entities and blocks occlude; the first-person
    // body (which vanilla never draws here either) does not.
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(handTlas,
            RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH,
            HAND_SHADOW_MASK, ray);
    while (query.Proceed()) {
    }
    return query.CommittedStatus() != COMMITTED_NOTHING;
}

/** The shared specular+diffuse evaluation, spelled exactly as restir_pt's continuation weight. */
float3 handBrdf(BsdfContext bc, float3 wi) {
    float ndl = max(0.0, dot(bc.n, wi));
    float3 h = normalize(wi + bc.v);
    float ndh = max(0.0, dot(bc.n, h));
    float vdh = max(0.0, dot(bc.v, h));
    float D = ggxDAniso(dot(h, bc.t), dot(h, bc.b), ndh, bc.alphaX, bc.alphaY);
    float G = bc.g1v * ggxG1Aniso(dot(wi, bc.t), dot(wi, bc.b), ndl, bc.alphaX, bc.alphaY);
    float3 F = fresnelSchlick(vdh, bc.F0);
    return bc.diffAlb * INV_PI + (D * G) * F / (4.0 * bc.ndv * max(ndl, 1.0e-4));
}

/** Celestial NEE: the world's square-light sample, atmospheric transmittance, D176 cloud shadow, and a
 * jittered shadow ray -- the terrain's sun discipline at a receiver the sun can actually reach. */
float3 handSunTerm(WorldPush wp, BsdfContext bc, float3 p, float3 n, float3 pos, inout uint seed) {
    if (wp.skyProvider != SKY_PROVIDER_ATMOSPHERE || wp.lightDir.w <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 dir = sampleSquare(wp.lightDir.xyz, wp.lightDir.w, seed);
    if (dot(n, dir) <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    if (handOccluded(p, dir, HAND_SHADOW_TMAX)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 e = wp.lightRadiance.xyz * sampleTransmittanceToSpace(dir);
    e *= cloudSunTransmittance(handCloudShadow, wp, pos);
    return handBrdf(bc, dir) * max(dot(n, dir), 0.0) * e;
}

/** One local-emitter candidate from the world's own grid/alias selection, shadowed. */
float3 handEmitterTerm(WorldPush wp, BsdfContext bc, float3 p, float3 n, inout uint seed) {
    LightBufferAddresses addresses;
    addresses.light = pc.lightBufAddr;
    addresses.globalAlias = pc.lightAliasAddr;
    addresses.localAlias = pc.lightLocalAliasAddr;
    addresses.gridCell = pc.lightGridCellAddr;
    addresses.gridSpan = pc.lightGridSpanAddr;
    if (pc.lightBufAddr == 0u) {
        return float3(0.0, 0.0, 0.0);
    }
    uint proposalSeed = pc.frameIndex * 2654435761u ^ asuint(p.x * 113.0) ^ asuint(p.z * 271.0);
    proposalSeed = pcg(proposalSeed);
    EmitterSample emitter;
    if (!sampleVolumeEmitter(wp, addresses, p, seed, proposalSeed, emitter)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 toLight = emitter.pos - p;
    float dist2 = dot(toLight, toLight);
    if (dist2 <= 1.0e-4) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toLight / dist;
    float cosLight = max(0.0, dot(emitter.normal, -wi));
    if (cosLight <= 0.0 || dot(n, wi) <= 0.0 || handOccluded(p, wi, dist * 0.999)) {
        return float3(0.0, 0.0, 0.0);
    }
    // Area-light NEE with the receiver's real BRDF: the rain streaks' factor with the shared specular
    // replacing their isotropic 1/(4 pi) response.
    return handBrdf(bc, wi) * max(dot(n, wi), 0.0) * emitter.radiance
            * (emitter.area * cosLight / dist2) / emitter.sourcePdf;
}

/** M18's dynamic records -- the held torch, burning entities, glowing particles -- evaluated directly:
 * the static grid never sees them (M24 S3 proposed them beside the alias tables, and the hand pass has
 * no reservoir of its own). Held lights are nearly always the nearest, so the scan ranks by unoccluded
 * estimate and shadow-traces only the strongest HAND_DYN_SHADOWED; the rest assume visibility, which is
 * the recorded bias of this term (hand-lit-by-own-torch must not fall to ray-budget arithmetic). */
float3 handDynamicTerm(WorldPush wp, BsdfContext bc, float3 p, float3 n, inout uint seed) {
    if (wp.dynamicLightAddr == 0u || wp.dynamicLightCount == 0u) {
        return float3(0.0, 0.0, 0.0);
    }
    ConstPtr<Light> lights = ConstPtr<Light>(wp.dynamicLightAddr);
    uint count = min(wp.dynamicLightCount, HAND_DYN_LIGHT_SCAN);
    float3 shadowedDir[HAND_DYN_SHADOWED];
    float shadowedDist[HAND_DYN_SHADOWED];
    float3 shadowed[HAND_DYN_SHADOWED];
    float shadowedEst[HAND_DYN_SHADOWED];
    [unroll]
    for (uint k = 0u; k < HAND_DYN_SHADOWED; k++) {
        shadowedEst[k] = -1.0;
        shadowed[k] = float3(0.0, 0.0, 0.0);
        shadowedDir[k] = float3(0.0, 0.0, 0.0);
        shadowedDist[k] = 0.0;
    }
    float3 total = float3(0.0, 0.0, 0.0);
    for (uint i = 0u; i < count; i++) {
        Light light = lights[i];
        if (!lightIsSphere(light)) {
            continue;
        }
        float radius = lightSphereRadius(light);
        float3 toCentre = light.pos - p;
        float dist2 = dot(toCentre, toCentre);
        if (dist2 <= radius * radius * 0.25) {
            continue; // receiver inside the emitter: no facing half, rejected like the sampler's contract
        }
        float3 sp;
        float3 lnrm;
        float area;
        if (!sampleSphereEmitter(light, p, seed, sp, lnrm, area)) {
            continue;
        }
        float3 toSample = sp - p;
        float sampleDist2 = max(dot(toSample, toSample), 1.0e-6);
        float3 wi = toSample * rsqrt(sampleDist2);
        float ndl = max(0.0, dot(n, wi));
        float cosLight = max(0.0, dot(lnrm, -wi));
        if (ndl <= 0.0 || cosLight <= 0.0) {
            continue;
        }
        float3 radiance = emitterRadiance(light);
        float est = luminance(radiance) * ndl / sampleDist2;
        float3 contribution = handBrdf(bc, wi) * ndl * radiance
                * (cosLight * 6.28318530718 * radius * radius / sampleDist2);
        // Solid-angle form of the hemisphere sample: pdf 1/(2 pi r^2) folds into 2 pi r^2 / d^2.
        // Insertion into the ranked top set: rotate displaced occupants down; whatever falls out of
        // the last slot is the weakest estimate and joins the unshadowed tail instead.
        [unroll]
        for (uint k = 0u; k < HAND_DYN_SHADOWED; k++) {
            if (est > shadowedEst[k]) {
                float3 tmpC = shadowed[k];
                float3 tmpD = shadowedDir[k];
                float tmpT = shadowedDist[k];
                float tmpE = shadowedEst[k];
                shadowed[k] = contribution;
                shadowedDir[k] = wi;
                shadowedDist[k] = sqrt(sampleDist2);
                shadowedEst[k] = est;
                contribution = tmpC;
                wi = tmpD;
                float oldDist = tmpT;
                est = tmpE;
                sampleDist2 = oldDist * oldDist;
            }
        }
    }
    float3 shadowOrigin = p + n * SURF_BIAS;
    [unroll]
    for (uint k = 0u; k < HAND_DYN_SHADOWED; k++) {
        if (shadowedEst[k] < 0.0) {
            continue;
        }
        // Sphere-scale occlusion: trace to the emitter's centre pushed past it by slack, so a held
        // torch lights the palm holding it instead of shadowing itself at its own rim.
        if (!handOccluded(shadowOrigin, shadowedDir[k],
                shadowedDist[k] * HAND_DYN_SHADOW_SLACK)) {
            total += shadowed[k];
        }
    }
    return total;
}

[shader("fragment")]
float4 main(FragmentIn input) : SV_Target {
    // The world modules resolve `worldPush` from the shared push's BDA, exactly as the RT shaders do.
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];

    int texSlot = int(input.data.x + 0.5);
    uint materialId = input.data.y;
    uint primFlags = input.data.z;
    uint aux0 = input.data.w;
    float overlayStrength = input.extra.x;
    float alphaBucket = input.extra.y;

    float4 entityTexel = entityAlbedoTex[NonUniformResourceIndex(texSlot)].Sample(input.uv);
    // Vanilla's hurt/flash colour overlay, blended in the texture's own sRGB space -- the rchit entity
    // branch's arithmetic (see its comment there for why sRGB and why strength is 1 - alpha).
    float3 overlayRgb = float3(float((aux0 >> 16) & 0xFFu),
                               float((aux0 >> 8) & 0xFFu),
                               float(aux0 & 0xFFu)) * (1.0 / 255.0);
    float3 entitySrgb = lerp(entityTexel.rgb, overlayRgb, overlayStrength);
    // Enchantment foil: the rchit branch's breathing violet sheen, phased on the frame index the shared
    // push carries (the hit shader may not read the world clock; neither needs to).
    float glintSheen = 0.0;
    if ((primFlags & ENTITY_PRIM_GLINT) != 0u) {
        glintSheen = 0.5 + 0.5 * sin(dot(input.worldPos, GLINT_PHASE_AXIS)
                + float(pc.frameIndex) * GLINT_RATE);
        entitySrgb = lerp(entitySrgb, GLINT_TINT, GLINT_TINT_WEIGHT * glintSheen);
    }
    MaterialHeader header = ConstPtr<MaterialHeader>(pc.materialTableAddr)[materialId];
    uint material = header.model;
    float3 baseAlbedo = srgbToLinear(entitySrgb) * input.tintEmission.rgb;
    float3 albedo = material == MATERIAL_DIELECTRIC
            ? lerp(float3(1.0, 1.0, 1.0), baseAlbedo, entityTexel.a) : baseAlbedo;
    float alpha = entityTexel.a;
    if (alphaBucket != 0.0 && alpha < HAND_CUTOUT_ALPHA) {
        discard;
    }

    // ---- canonical material pages: the rchit evaluateMaterial decode (recorded second spelling).
    float roughness = header.params.x;
    float metalness = header.params.y;
    float3 f0 = lerp(float3(0.04, 0.04, 0.04), albedo, metalness);
    float emission = input.tintEmission.a;
    float ao = 1.0;
    float2 localUv = clamp((input.uv - header.albedoUv.xy) * header.albedoUv.zw,
            float2(0.0, 0.0), float2(1.0, 1.0));
    float2 pageUv = header.materialUv.xy + localUv * header.materialUv.zw;
    float maxMaterialLod = float((header.features >> MATERIAL_MAX_LOD_SHIFT) & MATERIAL_MAX_LOD_MASK);
    uint page = header.texturePage;
    if ((header.features & (MATERIAL_FEATURE_SPEC | MATERIAL_FEATURE_HEURISTIC_EMISSION)) != 0u) {
        float4 surface0 = materialSurface0Tex[NonUniformResourceIndex(page)].Sample(pageUv);
        if ((header.features & MATERIAL_FEATURE_SPEC) != 0u) {
            float4 surface1 = materialSurface1Tex[NonUniformResourceIndex(page)].Sample(pageUv);
            if ((header.features & MATERIAL_FEATURE_ROUGHNESS_AUTHORED) == 0u) {
                roughness = surface0.r;
            }
            if ((header.features & MATERIAL_FEATURE_METALNESS_AUTHORED) == 0u) {
                metalness = surface0.g;
            }
            emission = surface0.b;
            f0 = surface1.rgb;
        } else {
            emission = surface0.b * emission;
        }
    }
    float strength = float((header.features >> MATERIAL_EMISSION_STRENGTH_SHIFT)
            & MATERIAL_EMISSION_STRENGTH_MASK)
            * (MATERIAL_MAX_EMISSION_STRENGTH / float(MATERIAL_EMISSION_STRENGTH_MASK));
    emission *= strength;
    float3 n = input.worldNormal;
    if ((header.features & MATERIAL_FEATURE_NORMAL) != 0u) {
        float4 normalAo = materialNormalAoTex[NonUniformResourceIndex(page)].Sample(pageUv);
        // perturbNormal's tail against the CPU-computed tangent (the raster fragment has no neighbour
        // vertices to re-derive the UV gradient from; the capture baked the triangle tangent).
        float3 t = normalize(input.worldTangent);
        float3 b = cross(n, t) * input.tangentHandedness;
        float2 nxy = normalAo.xy * 2.0 - 1.0;
        float nz = sqrt(max(0.0, 1.0 - dot(nxy, nxy)));
        float3 nm = normalize(nxy.x * t + nxy.y * b + nz * n);
        float noV = dot(nm, normalize(worldPush.camOffset - input.worldPos));
        if (noV <= 0.0) {
            nm = n; // the black-spot fix: grazing perturbations may not invert through the surface
        }
        n = nm;
        ao = lerp(1.0, normalAo.z, 0.5); // NORMAL_AO_STRENGTH, same mild folding the entity path applies
    }

    // ---- the lighting itself.
    float3 v = normalize(worldPush.camOffset - input.worldPos);
    float3 rd = -v;
    BsdfContext bc = makeBsdfContext(input.worldPos, n, v, rd, albedo, f0, roughness, true, 0.0);
    uint seed = pc.frameIndex * 747796405u + asuint(input.worldPos.x * 913.0)
            ^ asuint(input.worldPos.z * 193.0);
    seed = pcg(seed);
    float3 light = handSunTerm(worldPush, bc, input.worldPos + n * SURF_BIAS, n,
            input.worldPos, seed);
    light += handEmitterTerm(worldPush, bc, input.worldPos + n * SURF_BIAS, n, seed);
    light += handDynamicTerm(worldPush, bc, input.worldPos + n * SURF_BIAS, n, seed);
    // The sky field is an irradiance factor: albedo times it, the scalar mode's exact convention.
    light += albedo * volumeSkySurfaceIrradiance(input.worldPos, n);
    // Emission is radiance, not a light term: the foil's shimmer rides on the decoded emission like the
    // entity path's does (max, never + -- a foil on an emissive block must not stack into a firefly).
    emission = max(emission, GLINT_EMISSION * glintSheen);
    light += emission;
    light *= ao;

    return float4(max(light, float3(0.0, 0.0, 0.0)), alpha);
}
