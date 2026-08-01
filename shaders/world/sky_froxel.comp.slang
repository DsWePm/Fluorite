// Bakes what the air between the camera and everything else does to the picture, into a 32x32x32 grid
// laid over the view frustum. Each froxel holds the in-scattered light gathered from the eye out to its
// depth, and the transmittance over that same stretch.
//
// WHAT THIS IS ACTUALLY FOR, because the milestone's name is misleading. "Aerial perspective" from the
// planetary atmosphere does not exist at this game's scale: Rayleigh's blue coefficient is 22.4e-6 per
// metre, so a 512-block view runs an optical depth of 0.0115 and a transmittance of 0.989 — one percent,
// invisible. What actually fades the distance here is the world's own fog, which is denser by more than
// two orders of magnitude. So the grid carries the FOG first and the atmosphere as a rounding error that
// comes along for free, rather than the other way round.
//
// The real payoff is M13. Volumetric shadows are in-scatter modulated by visibility, and the water work
// already established what a handful of shadow rays gives instead: one coin flip near the camera applied
// to a whole segment, so any occlusion boundary the camera crosses flips the entire screen at once. A
// froxel resolves visibility in WORLD-SPACE CELLS and interpolates, so neighbouring pixels and successive
// frames read continuous values rather than independent binary answers. This milestone builds the grid
// and fills it with the unshadowed integral; M13 adds the visibility term to the same cells.
//
// Reads WorldPush through its device address rather than taking a push constant of its own. The camera
// matrix, the fog's four parameter vectors and the sun all live there already, and restating any of them
// here would be a second copy to keep in step — the exact debt M10.1 existed to pay off.

import atmosphere;
import world_common;

// 32^3 as the plan specifies. The depth axis is the one that matters and 32 slices over the fog's cull
// distance is coarse, which is why the slices are distributed quadratically rather than evenly: the near
// field is where a slice boundary would be visible as a band, and where the density is highest.
public static const int FROXEL_W = 32;
public static const int FROXEL_H = 32;
public static const int FROXEL_D = 32;

struct FroxelPush {
    uint64_t worldPushAddr;
};
[[vk::push_constant]] FroxelPush froxelPush;

[[vk::binding(0, 0)]] Sampler2D transmittanceLut;
[[vk::binding(1, 0)]] Sampler2D multiScatterLut;
[[vk::binding(2, 0)]] RWTexture3D<float4> aerialPerspective;

static const float PI_F = 3.14159265359;

/**
 * Depth this slice's value describes, quadratic so the near slices are thin.
 *
 * Texel CENTRES, matching the two 2D bakes and for the same reason: a consumer samples this grid with a
 * filtered fetch, which interpolates between centres, so writing the value for a slice's far EDGE would
 * shift the whole depth axis half a texel and put the fog nearer than the geometry it belongs to.
 * froxelDepthCoord in atmosphere_lut is the exact inverse of this and has to stay so.
 */
float sliceDepth(int z, float maxDist) {
    float t = (float(z) + 0.5) / float(FROXEL_D);
    return maxDist * t * t;
}

/** Henyey-Greenstein, normalised over the sphere. Matches what volume.slang uses for the fog's sun lobe. */
float fogPhase(float cosT, float g) {
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * cosT;
    return (1.0 - g2) / (4.0 * PI_F * max(d * sqrt(max(d, 0.0)), 1.0e-6));
}

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(FROXEL_W) || id.y >= uint(FROXEL_H) || id.z >= uint(FROXEL_D)) {
        return;
    }
    WorldPush wp = ConstPtr<WorldPush>(froxelPush.worldPushAddr)[0];

    // The ray through this column's centre, from the same inverse projection the primary rays use. Taking
    // it from WorldPush rather than rebuilding a frustum here is what keeps the grid aligned with the
    // image it will be sampled against — a froxel column that disagreed with the pixel above it by half a
    // tile would show up as the fog sliding over geometry as the camera turns.
    float2 uv = (float2(id.xy) + 0.5) / float2(FROXEL_W, FROXEL_H);
    float2 ndc = uv * 2.0 - 1.0;
    float4 nearH = mul(wp.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(wp.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    float3 ro = nearP + wp.camOffset;
    float3 rd = normalize(farP - nearP);

    float maxDist = max(wp.fogParams.w, 1.0);
    float sigma0 = wp.fogParams.x;
    float heightScale = max(wp.fogParams.y, 1.0e-3);
    float heightBase = wp.fogParams.z;
    float startDist = wp.fogExtinction.w;

    float3 sunDir = wp.lightDir.xyz;
    float cosT = dot(rd, sunDir);
    float sunPhase = 4.0 * PI_F * fogPhase(cosT, wp.fogScatter.w);

    // March from the eye to this froxel's far edge, accumulating as we go. Each thread redoes the stretch
    // its predecessors covered, which is deliberate: the alternative is a serial scan down the column with
    // shared memory, and 32 threads each walking at most 32 steps of a cheap integrand is not worth the
    // synchronisation or the loss of a flat dispatch. It also means every froxel is self-contained, so a
    // future visibility term can be added per cell without a second pass to prefix-sum it.
    float3 transmittance = float3(1.0, 1.0, 1.0);
    float3 inScatter = float3(0.0, 0.0, 0.0);
    int steps = int(id.z) + 1;

    for (int s = 0; s < steps; ++s) {
        float tNear = s == 0 ? 0.0 : sliceDepth(s - 1, maxDist);
        float tFar = sliceDepth(s, maxDist);
        // The fog has a start distance so the near field stays readable; nothing before it contributes.
        tNear = max(tNear, startDist);
        if (tFar <= tNear) {
            continue;
        }
        float dt = tFar - tNear;
        float3 p = ro + rd * (tNear + dt * 0.5);

        // The world's fog: exponential in height, tinted per channel, with a single-scattering albedo.
        float fogDensity = sigma0 * exp(-(p.y - heightBase) / heightScale);
        float3 fogSigmaT = wp.fogExtinction.xyz * max(fogDensity, 0.0);

        // The atmosphere, at this point's altitude. Two orders of magnitude smaller than the fog at this
        // scale and kept anyway: it costs one exp and it is the term that makes a very long sightline over
        // open ground pick up the sky's own colour rather than only the fog's tint.
        float3 atmoSigmaS;
        float3 atmoSigmaT;
        atmosphereMedium(ATMOS_VIEW_ALTITUDE, atmoSigmaS, atmoSigmaT);

        float3 sigmaT = fogSigmaT + atmoSigmaT;
        float3 sigmaS = wp.fogScatter.xyz * max(fogDensity, 0.0) + atmoSigmaS;

        // Sunlight reaching this point, dimmed by the atmosphere it crossed. No occlusion test — that is
        // M13's half of this grid, and leaving it out here rather than approximating it with one ray is
        // the whole reason the grid exists.
        float r = PLANET_R + ATMOS_VIEW_ALTITUDE;
        float muS = clamp(sunDir.y, -1.0, 1.0);
        float3 sunT = muS < horizonMu(r)
                ? float3(0.0, 0.0, 0.0)
                : transmittanceLut.SampleLevel(transmittanceLutUv(r, muS), 0.0).rgb;

        float3 source = wp.lightRadiance.xyz * sunT * sunPhase
                      + wp.fogAmbient.xyz
                      + multiScatterLut.SampleLevel(multiScatterLutUv(r, muS), 0.0).rgb * SUN_INTENSITY;

        // Closed form over the step, for the same reason as the other two bakes: near the ground a step
        // can span a whole optical depth, and the midpoint rule overshoots exactly there.
        float3 stepT = exp(-sigmaT * dt);
        float3 gathered = transmittance * sigmaS * source
                        * (float3(1.0, 1.0, 1.0) - stepT)
                        / max(sigmaT, float3(1.0e-9, 1.0e-9, 1.0e-9));
        inScatter += gathered;
        transmittance *= stepT;
    }

    // Alpha carries transmittance as a single channel. The three are close enough at this scale that a
    // per-channel copy would cost a second texture to save a difference nothing can see; if a coloured
    // extinction ever matters here, that is the change to make and this comment is where to start.
    float meanT = (transmittance.r + transmittance.g + transmittance.b) * (1.0 / 3.0);
    aerialPerspective[int3(id)] = float4(inScatter, meanT);
}
