// Bakes the atmosphere's transmittance-to-space into a 256x64 LUT, one texel per (altitude, view
// zenith) pair under the Bruneton/Hillaire parameterisation in atmosphere.slang.
//
// Replaces an 8-step extinction march that ran per ray. The march was cheap on its own, but it ran on
// every escaping bounce ray and was ALSO hand-ported to the CPU so the sun disc, the sky and the NEE
// sunlight could not disagree about the sunset — three evaluations of one integral, two of them in
// different languages. The LUT is the single evaluation all three now read.
//
// Rebuilt only when the atmosphere's parameters change, which today means never after the first frame:
// nothing in the dispatch depends on time of day. The sun's position enters where the LUT is SAMPLED,
// not where it is baked, which is the property that makes a once-ever bake correct rather than a stale
// cache — worth stating because "rebuild it per frame just in case" is the reflex it guards against.

import atmosphere;

// 128 steps because this runs once for 16384 texels rather than once per ray. The old per-ray march
// used 8 and had to; there is no reason to inherit its budget here, and the horizon texels are where
// the integrand varies fastest.
static const int BAKE_STEPS = 128;

[[vk::binding(0, 0)]] RWTexture2D<float4> transmittanceLut;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(SKY_TRANSMITTANCE_W) || id.y >= uint(SKY_TRANSMITTANCE_H)) {
        return;
    }
    // Texel CENTRES: sampling the LUT with a linear filter interpolates between centres, so baking at
    // corners would shift the whole table half a texel and put the horizon in the wrong place.
    float2 uv = (float2(id.xy) + 0.5) / float2(SKY_TRANSMITTANCE_W, SKY_TRANSMITTANCE_H);
    float r;
    float mu;
    transmittanceLutParams(uv, r, mu);
    transmittanceLut[int2(id.xy)] = float4(atmosphereTransmittance(r, mu, BAKE_STEPS), 1.0);
}
