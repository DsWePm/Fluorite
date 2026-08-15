// Bakes the cloud weather map once, at startup: where cloud systems stand, and which family they are.
//
// WHY THIS IS ITS OWN TEXTURE. Coverage and type are TWO-DIMENSIONAL fields -- a column of sky either has
// cloud over it or it does not, and the family it belongs to does not vary with altitude either. They used
// to be read as fixed horizontal slices of the 128^3 body volume, which meant every density evaluation
// paid a 3D fetch (eight texels spread across three dimensions of an 8 MB volume) to read data that has no
// third dimension. Two of those per call, on the two lookups that run before any of the cheap rejections.
//
// THE FIELD IS UNCHANGED, and that is deliberate rather than a limitation. Each channel is the same
// construction evaluated at the same slice height it was being read at, so the coverage remap in
// cloud.slang keeps the constants that were fitted to it. Those constants are not free parameters: the
// bake's own banner records that changing the field without re-fitting them overcast 96% of the sky at
// neutral bias. Moving the storage and changing the field are two changes, and this is only the first.
//
// It is not bit-identical, and the difference is in the honest direction: a slice of a sampled volume is
// the trilinear blend of the two nearest z-slices, while this evaluates the construction AT that height.
// Same distribution, one interpolation less.
//
// WHAT THIS UNLOCKS is the reason it was worth doing at all. As a slice of a static baked volume, coverage
// could only translate with the wind -- a cloud system could drift past but never form, deepen or clear.
// At 32 KB with its own dispatch it is small enough to re-bake, which is what a front moving through
// actually requires. Nothing here does that yet.

module cloud_weather;

import cloud_field;

// One texel per texel of the volume slice it replaces, so the field it carries is the field that shipped.
// Resolution is now a free parameter for the first time -- it was previously pinned to the body volume's
// dimension for no reason other than sharing its storage -- but changing it changes what the sky looks
// like, so it is left where it was.
public static const int CLOUD_WEATHER_DIM = 128;

// The heights the shading was reading these two fields at. They differ so that coverage and type are
// decorrelated: the same construction sampled at two well-separated z values gives two independent
// fields, which is what lets an overcast sky be either a flat sheet or a wall of towers.
static const float CLOUD_COVERAGE_SLICE = 0.37;
static const float CLOUD_TYPE_SLICE = 0.61;

[[vk::binding(0, 0)]] RWTexture2D<float2> cloudWeatherOut;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint2 id: SV_DispatchThreadID) {
    if (any(id >= uint2(CLOUD_WEATHER_DIM, CLOUD_WEATHER_DIM))) {
        return;
    }
    // Texel centres, matching every other table here: this is read with a filtered fetch, and sampling
    // corners would bias the whole map half a texel along both axes.
    float2 uv = (float2(id) + 0.5) / float(CLOUD_WEATHER_DIM);
    // Tileable for the same reason the volume is: the shading divides world coordinates by an authored
    // span and lets the sampler repeat, so the field has to close on itself at whatever period each octave
    // uses. cloud_noise wraps its lattice coordinates before hashing, which is what makes that true of the
    // noise rather than only of the texture.
    float coverage = cloudNoiseShapeAt(float3(uv.x, CLOUD_COVERAGE_SLICE, uv.y));
    float type = cloudNoiseTypeAt(float3(uv.x, CLOUD_TYPE_SLICE, uv.y));
    cloudWeatherOut[int2(id)] = float2(coverage, type);
}
