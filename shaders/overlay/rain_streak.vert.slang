// Dense procedural rain: six vertices per instance, no CPU vertex stream.
// Positions are generated on a one-block world lattice around the camera, advected along the same
// global direction as D105's exposure rays, and rejected against that map before rasterization.

import rain_streak_common;
import world_common;

[[vk::push_constant]] RainStreakPush push;

[[vk::binding(1, 0)]] RWTexture2D<float> exposureDepth;

struct VertexOut {
    float4 position : SV_Position;
    float2 local : TEXCOORD0;
    float3 radiance : TEXCOORD1;
    float exposure : TEXCOORD2;
};

float exposedToRain(WorldPush wp, float3 p) {
    float resolution = wp.rainExposureOrigin.w;
    float t = (p.y - wp.rainExposureOrigin.y) / wp.rainDirection.y;
    float3 entry = p - wp.rainDirection.xyz * t;
    float2 texel = entry.xz - wp.rainExposureOrigin.xz;
    if (resolution <= 0.0 || t < 0.0 || t > wp.rainDirection.w
            || any(texel < 0.0) || any(texel >= resolution)) {
        return 0.0;
    }
    float depth = exposureDepth.Load(int2(floor(texel)));
    if (depth < 0.0 || t > depth + 0.10) {
        return 0.0;
    }
    float edge = min(min(texel.x, texel.y), min(resolution - texel.x, resolution - texel.y));
    return smoothstep(0.0, 8.0, edge);
}

[shader("vertex")]
VertexOut main(uint vertexId : SV_VertexID, uint instanceId : SV_InstanceID) {
    VertexOut o;
    WorldPush wp = ConstPtr<WorldPush>(push.worldPushAddr)[0];
    RainStreakPlacement placement = rainStreakPlacement(
            wp, instanceId, push.instanceCount, push.speed);
    float3 centre = placement.centre;

    float exposure = instanceId < push.instanceCount ? exposedToRain(wp, centre) : 0.0;
    float along = (vertexId == 1u || vertexId == 2u || vertexId == 4u) ? 1.0 : 0.0;
    float sideSign = (vertexId == 0u || vertexId == 1u || vertexId == 3u) ? -1.0 : 1.0;
    float3 toCamera = wp.camOffset - centre;
    float rawDistanceToCamera = length(toCamera);
    float3 sideAxis = cross(wp.rainDirection.xyz, toCamera);
    // Looking almost exactly along the fall direction otherwise normalises a zero vector and sprays
    // NaNs through the clip position. A fixed horizontal fallback is rotationally imperfect but only
    // affects the unobservable roll of a line seen end-on.
    float3 side = dot(sideAxis, sideAxis) > 1.0e-8
            ? normalize(sideAxis) : float3(1.0, 0.0, 0.0);
    float distanceToCamera = max(rawDistanceToCamera, 1.0);
    float halfWidth = max(0.008, distanceToCamera / max(push.displaySize.y, 1.0) * 0.75);
    // Test the whole segment, not only its centre: an endpoint or interior crossing the near plane can
    // project into a screen-wide diagonal slash while the centre itself remains more than a block away.
    float halfLength = push.length * 0.5;
    float3 segmentStart = centre - wp.rainDirection.xyz * halfLength;
    float3 segmentEnd = centre + wp.rainDirection.xyz * halfLength;
    float minEndpointDistance = min(length(segmentStart - wp.camOffset),
                                    length(segmentEnd - wp.camOffset));
    float closestAlong = clamp(dot(toCamera, wp.rainDirection.xyz), -halfLength, halfLength);
    float segmentDistance = length((centre + wp.rainDirection.xyz * closestAlong) - wp.camOffset);
    float nearCamera = smoothstep(0.35, 1.25, min(segmentDistance, minEndpointDistance));
    float3 point = centre + (along - 0.5) * wp.rainDirection.xyz * push.length * nearCamera
            + side * sideSign * halfWidth;
    float4 clip = mul(wp.curViewProj, float4(point - wp.camOffset, 1.0));
    if (exposure <= 0.0 || clip.w <= 0.0) {
        clip = float4(2.0, 2.0, 0.0, 1.0);
    }
    o.position = clip;
    o.local = float2(sideSign, along);
    o.exposure = exposure * nearCamera;
    // One value per instance, written by rain_streak_light.comp. Keeping lighting out of this six-vertex
    // stage avoids tracing the same two shadow rays six times for one procedural line.
    o.radiance = ConstPtr<float4>(push.radianceAddr)[instanceId].xyz;
    return o;
}
