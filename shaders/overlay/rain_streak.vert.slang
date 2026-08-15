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
    float flake : TEXCOORD3;
};

float exposedToRain(WorldPush wp, float3 p) {
    float resolution = wp.rainExposureOrigin.w;
    float t = (p.y - wp.rainExposureOrigin.y) / wp.rainDirection.y;
    float2 texel = rainStreakEntryTexel(wp, p);
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
    // READ, NOT RECOMPUTED. The lighting pass owns where each flake is: snow's path needs a buffer read
    // for its column's class and a curl evaluation on top of that, and two stages deriving it separately
    // would be two chances to disagree about which pixel a lit flake occupies.
    float4 published = ConstPtr<float4>(push.placementAddr)[instanceId];
    float3 centre = published.xyz;
    bool snow = published.w > 0.5;

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
    float3 point;
    if (snow) {
        // A FLAKE IS A POINT, NOT A STREAK, and that is arithmetic rather than taste. Rain's quad is
        // elongated because a drop at the authored 24 blocks a second crosses 0.4 blocks in a frame, so
        // the streak IS its motion blur. Snow falls at a couple of blocks a second and covers about
        // three centimetres in the same frame -- there is no blur to draw, and stretching it along the
        // fall direction would render a smear that the physics does not contain.
        //
        // Camera-facing, therefore, and built from the same two vertex parameters so the six-vertex
        // layout is untouched.
        float3 view = rawDistanceToCamera > 1.0e-4
                ? toCamera / rawDistanceToCamera : float3(0.0, 0.0, 1.0);
        float3 right = cross(view, float3(0.0, 1.0, 0.0));
        right = dot(right, right) > 1.0e-8 ? normalize(right) : float3(1.0, 0.0, 0.0);
        float3 up = cross(right, view);
        // Never below the streak's own screen-space floor: a flake a hundred blocks away is far smaller
        // than a pixel, and letting it shrink past one would make distant snow flicker in and out with
        // sub-pixel coverage instead of thinning out smoothly.
        float half = max(push.snowSize * 0.5, halfWidth);
        point = centre + up * ((along - 0.5) * 2.0 * half) + right * (sideSign * half);
    } else {
        point = centre + (along - 0.5) * wp.rainDirection.xyz * push.length * nearCamera
                + side * sideSign * halfWidth;
    }
    // UNLIT INSTANCES MUST NOT DRAW. The lighting pass skips lattice cells outside its own frustum test
    // and leaves their radiance at the cleared zero, which is not "invisible" under straight-alpha over
    // -- it is a particle darker than everything behind it, so it subtracts. The cleared alpha is
    // already the signal: the pass writes w = 1 for every instance it actually shaded.
    float4 lit = ConstPtr<float4>(push.radianceAddr)[instanceId];
    float4 clip = mul(wp.curViewProj, float4(point - wp.camOffset, 1.0));
    if (exposure <= 0.0 || clip.w <= 0.0 || lit.w <= 0.0) {
        clip = float4(2.0, 2.0, 0.0, 1.0);
    }
    o.position = clip;
    o.local = float2(sideSign, along);
    o.flake = snow ? 1.0 : 0.0;
    // Snow keeps its size right up to the camera, so the streak's near-camera shrink does not apply --
    // but a flake passing through the eye should still fade rather than fill the screen.
    o.exposure = exposure * (snow ? smoothstep(0.1, 0.6, rawDistanceToCamera) : nearCamera);
    // One value per instance, written by rain_streak_light.comp. Keeping lighting out of this six-vertex
    // stage avoids tracing the same two shadow rays six times for one procedural line.
    o.radiance = lit.xyz;
    return o;
}
