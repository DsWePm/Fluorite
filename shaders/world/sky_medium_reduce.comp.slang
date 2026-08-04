// M16: turn the complete sky-view LUT into one phase-integrated radiance source shared by air and water.
// One 256-lane group covers all 24,576 texels, then reduces in group memory and writes WorldPush once.
// This is a deterministic quadrature over the LUT, not a per-pixel Monte Carlo estimate.

import atmosphere;
import world_common;

[[vk::binding(0, 0)]] Sampler2D skyViewRayleighLut;
[[vk::binding(1, 0)]] Sampler2D skyViewMieLut;
[[vk::binding(2, 0)]] Sampler2D skyViewMultiLut;

struct MediumSkyReducePush {
    uint64_t worldPushAddr;
    uint64_t reserved;
    float4 sunDir;
};
[[vk::push_constant]] MediumSkyReducePush reducePush;

static const uint REDUCE_LANES = 256u;
groupshared float3 partialRadiance[REDUCE_LANES];

[shader("compute")]
[numthreads(256, 1, 1)]
void main(uint3 groupThreadId : SV_GroupThreadID) {
    uint lane = groupThreadId.x;
    float3 sunDir = normalize(reducePush.sunDir.xyz);
    float3 sum = float3(0.0);

    for (uint linear = lane; linear < uint(SKY_VIEW_W * SKY_VIEW_H); linear += REDUCE_LANES) {
        uint x = linear % uint(SKY_VIEW_W);
        uint y = linear / uint(SKY_VIEW_W);
        float2 uv = (float2(float(x), float(y)) + 0.5) / float2(SKY_VIEW_W, SKY_VIEW_H);

        // The LUT stores azimuth [0, pi] and mirrors it to the other half of the sky. In spherical
        // coordinates dOmega = dPhi*dMu, so the mirrored azimuth represented by one x cell is
        // 2*pi/W. Dividing by 4*pi for an isotropic medium leaves dMu/(2*W). Clipping mu to [0,1]
        // selects the geometric upper hemisphere even though the LUT extends below the local horizon.
        float v0 = float(y) / float(SKY_VIEW_H);
        float v1 = float(y + 1u) / float(SKY_VIEW_H);
        float mu0 = skyViewLutDir(float2(0.5, v0), sunDir).y;
        float mu1 = skyViewLutDir(float2(0.5, v1), sunDir).y;
        float dMu = max(clamp(mu1, 0.0, 1.0) - clamp(mu0, 0.0, 1.0), 0.0);
        float phaseIntegratedWeight = dMu / (2.0 * float(SKY_VIEW_W));

        float3 dir = skyViewLutDir(uv, sunDir);
        float cosT = clamp(dot(dir, sunDir), -1.0, 1.0);
        float3 rayleigh = skyViewRayleighLut.SampleLevel(uv, 0.0).rgb;
        float3 mie = skyViewMieLut.SampleLevel(uv, 0.0).rgb;
        float3 multi = skyViewMultiLut.SampleLevel(uv, 0.0).rgb;
        float3 skyRadiance = rayleigh * atmosphereRayleighPhase(cosT)
                + mie * atmosphereMiePhase(cosT) + multi;
        sum += skyRadiance * phaseIntegratedWeight;
    }

    partialRadiance[lane] = sum;
    GroupMemoryBarrierWithGroupSync();
    for (uint stride = REDUCE_LANES / 2u; stride > 0u; stride >>= 1u) {
        if (lane < stride) {
            partialRadiance[lane] += partialRadiance[lane + stride];
        }
        GroupMemoryBarrierWithGroupSync();
    }

    if (lane == 0u) {
        DevicePtr<WorldPush> world = DevicePtr<WorldPush>(reducePush.worldPushAddr);
        world[0].mediumSkyRadiance = float4(max(partialRadiance[0], float3(0.0)), 0.0);
    }
}
