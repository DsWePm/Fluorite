// One direct-light evaluation per procedural rain streak. The result is consumed after RR so the thin
// line remains sharp, while light selection and visibility still use the renderer's unified RT inputs.

import atmosphere;
import light_sampling;
import precipitation;
import rain_streak_common;
import world_common;

[[vk::push_constant]] RainStreakPush push;

[[vk::binding(0, 0)]] Sampler2D transmittanceLut;
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

static const uint RAIN_STREAK_SHADOW_MASK = 0x01u;
static const float RAIN_INV_FOUR_PI = 0.079577471546;
// The old direct-celestial term was E*0.004. With a normalised isotropic phase E/(4*pi), multiplying by
// 0.004*4*pi preserves that daytime energy while allowing sky and local emitters to share the same units.
static const float RAIN_STREAK_SCATTER_RESPONSE = 0.05026548246;
/**
 * Snow's response is its ALBEDO, and unlike the rain constant above this one is derived rather than fit.
 *
 * <p>A raindrop is transparent: it refracts most of what reaches it onward and scatters a little toward
 * the viewer, which is what the small fitted number above encodes. A snowflake is the opposite -- an
 * aggregate of ice crystals that is opaque and very nearly white, so the honest model is a small diffuse
 * particle rather than a thin scatterer.
 *
 * <p>For a Lambertian particle bathed in isotropic radiance L, the irradiance is pi*L and the outgoing
 * radiance is albedo*(pi*L)/pi = albedo*L. And `source` below is already exactly that mean incident
 * radiance: mediumSkyRadiance is a 1/(4*pi) sphere integral, while the sun arrives as E/(4*pi), which is
 * also what a diffuse SPHERE averages over its surface (cross-section pi*r^2 over area 4*pi*r^2). So the
 * source assembly needs nothing changed and the response is simply the albedo. Fresh snow's broadband
 * albedo is 0.8 to 0.9.
 *
 * <p>WHY THIS WAS A BUG AND NOT A DIM SETTING. The streaks composite with straight-alpha over, so a
 * fragment contributes alpha*(L_particle - L_background): when the particle is darker than what is
 * behind it, drawing it SUBTRACTS light. At the thin-scatterer response a flake was about a fifth of the
 * sky's radiance while covering the pixel at 0.55 alpha, so snow against an overcast sky rendered as
 * black specks. Rain hid the same arithmetic because its streaks are hairline and its alpha is lower.
 */
static const float SNOW_STREAK_SCATTER_RESPONSE = 0.9;

bool rainStreakOccluded(float3 p, float3 direction, float tMax) {
    RayDesc ray;
    ray.Origin = p;
    ray.Direction = direction;
    ray.TMin = 0.03;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> query;
    query.TraceRayInline(tlas,
            RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH,
            RAIN_STREAK_SHADOW_MASK, ray);
    while (query.Proceed()) {
    }
    return query.CommittedStatus() != COMMITTED_NOTHING;
}

LightBufferAddresses rainStreakLightAddresses() {
    LightBufferAddresses addresses;
    addresses.light = push.lightBufAddr;
    addresses.globalAlias = push.lightAliasAddr;
    addresses.localAlias = push.lightLocalAliasAddr;
    addresses.gridCell = push.lightGridCellAddr;
    addresses.gridSpan = push.lightGridSpanAddr;
    return addresses;
}

float3 rainStreakCelestialSource(WorldPush wp, float3 p) {
    if (wp.skyProvider != SKY_PROVIDER_ATMOSPHERE
            || all(wp.lightRadiance.xyz <= float3(0.0, 0.0, 0.0))
            || !celestialDirectionVisible(wp.lightDir.xyz)
            || rainStreakOccluded(p, wp.lightDir.xyz, 4096.0)) {
        return float3(0.0, 0.0, 0.0);
    }
    float r = PLANET_R + ATMOS_VIEW_ALTITUDE;
    float3 atmosphereT = transmittanceLut.SampleLevel(
            transmittanceLutUv(r, clamp(wp.lightDir.y, -1.0, 1.0)), 0.0).rgb;
    return wp.lightRadiance.xyz * atmosphereT * RAIN_INV_FOUR_PI;
}

float3 rainStreakLocalSource(WorldPush wp, float3 p, int2 worldCell) {
    uint seed = rainCellSeed(worldCell, 0x51ed270bu);
    uint proposalSeed = rainCellSeed(worldCell, 0xc2b2ae35u);
    EmitterSample emitter;
    if (!sampleVolumeEmitter(
            wp, rainStreakLightAddresses(), p, seed, proposalSeed, emitter)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 toLight = emitter.pos - p;
    float dist2 = dot(toLight, toLight);
    if (dist2 <= 0.0036) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toLight / dist;
    float cosLight = max(0.0, dot(emitter.normal, -wi));
    if (cosLight <= 0.0 || rainStreakOccluded(p, wi, dist * 0.999)) {
        return float3(0.0, 0.0, 0.0);
    }
    return emitter.radiance
            * (RAIN_INV_FOUR_PI * cosLight * emitter.area / dist2)
            / emitter.sourcePdf;
}

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 dispatchId : SV_DispatchThreadID) {
    uint instanceId = dispatchId.x;
    if (instanceId >= push.instanceCount) {
        return;
    }
    DevicePtr<float4> radiance = DevicePtr<float4>(push.radianceAddr);
    radiance[instanceId] = float4(0.0, 0.0, 0.0, 0.0);

    WorldPush wp = ConstPtr<WorldPush>(push.worldPushAddr)[0];
    RainStreakPlacement placement = rainStreakPlacement(
            wp, push.precipitationAddr, instanceId, push.instanceCount, push.speed, push.snowSpeed);
    bool snow = placement.precipitation == PRECIP_SNOW;

    // PUBLISHED BEFORE THE FRUSTUM TEST, not after. This buffer is where the geometry stage learns where
    // its flake is, so it has to be written for every instance the draw will touch -- including the ones
    // this pass then declines to light. Returning early with a stale position would leave last frame's
    // flake standing in this frame's sky.
    DevicePtr<float4> placementOut = DevicePtr<float4>(push.placementAddr);
    placementOut[instanceId] = float4(placement.centre, snow ? 1.0 : 0.0);

    // Do not cast rays for lattice cells outside the current view. The margin retains long streaks whose
    // centre is just beyond the edge while avoiding most of the otherwise invisible shadow work.
    float4 clip = mul(wp.curViewProj, float4(placement.centre - wp.camOffset, 1.0));
    if (clip.w <= 0.0 || any(abs(clip.xy) > clip.w * 1.15)) {
        return;
    }

    // mediumSkyRadiance is already the isotropic phase integral. Directional irradiance and finite area
    // emitters explicitly receive the same 1/(4*pi) phase, preserving one energy convention throughout.
    float3 source = wp.mediumSkyRadiance.xyz;
    source += rainStreakCelestialSource(wp, placement.centre);
    source += rainStreakLocalSource(wp, placement.centre, placement.worldCell);
    float response = snow ? SNOW_STREAK_SCATTER_RESPONSE : RAIN_STREAK_SCATTER_RESPONSE;
    radiance[instanceId] = float4(max(source * response, float3(0.0, 0.0, 0.0)), 1.0);
}
