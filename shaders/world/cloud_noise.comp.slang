// Bakes the cloud volume's noise once, at startup, into a 3D texture.
//
// WHY BAKED. The march samples this several times per step and takes tens of steps per ray, so
// evaluating the octaves procedurally would multiply the cost of every sample by the cost of the noise.
// The plan records the measured shape of that trade on this hardware as the difference between one
// millisecond and six. A 128^3 RGBA8 volume is 8 MB and is read as a filtered fetch instead.
//
// TILEABLE, and that is a property of the bake rather than of the sampler. Cells are hashed on
// coordinates wrapped to the octave's own period, so octave k tiles at 128/k texels and the whole volume
// repeats seamlessly however far a ray travels. Relying on the sampler's REPEAT mode alone would tile the
// texture but not the noise: the lattice would step discontinuously at the seam.
//
// Channels: R is the billow that gives a cloud its shape, G the finer one that eats into its edges, B
// which KIND of cloud stands here, A a second detail band. R and G are inverted cellular noise, which is
// what makes them read as puffs rather than as smoke -- distance to the nearest feature point, subtracted
// from one, has the round-topped profile a convective cloud has.
//
// G and A are additionally CURL-WARPED. Cellular noise erodes in round bites, which reads as foam; the
// warp drags the lookup along a divergence-free field first, which turns those bites into filaments. See
// curlNoise for why divergence-free is the load-bearing property.

module cloud_noise;

import cloud_field;


[[vk::binding(0, 0)]] RWTexture3D<float4> cloudNoiseOut;

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (any(id >= uint3(CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_DIM))) {
        return;
    }
    // Texel CENTRES, for the same reason the atmosphere tables use them: this is read with a filtered
    // fetch, and sampling corners would bias the whole volume half a texel along every axis.
    float3 p = (float3(id) + 0.5) / float(CLOUD_NOISE_DIM);

    float shape = cloudNoiseShapeAt(p);

    // ---- G, what bites into the edges: high-frequency Worley, CURL-WARPED.
    //
    // The warp is the whole point. Eroding with plain cellular noise takes round bites out of a cloud,
    // which reads as foam; dragging the sample position along a divergence-free field first turns those
    // bites into filaments and swirls, which reads as a cloud edge being torn by wind shear. The strength
    // is small on purpose -- this displaces the lookup by a fraction of a detail feature, and past that
    // the detail stops belonging to the cloud it is eroding.
    float3 warp = curlNoise(p, 8) * 0.035;
    float erosion = cellularFbm(p + warp, 12, 3);

    // ---- B, the cloud type. Still written into the volume: the weather bake owns the slice the shading
    // reads, and this channel is what debug view 22's chain probe reaches for.
    float type = cloudNoiseTypeAt(p);

    // ---- A, a second detail band at a different scale and a different warp, for the march to reach for
    // when it wants finer erosion than G carries. Free: the channel exists either way.
    float3 warpFine = curlNoise(p, 16) * 0.02;
    float detail = cellularFbm(p + warpFine, 24, 2);

    cloudNoiseOut[int3(id)] = float4(shape, erosion, type, detail);
}
