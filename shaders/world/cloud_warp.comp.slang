// Bakes the low deck's domain-warp vector field once, at startup.
//
// WHY A VECTOR FIELD AND NOT ANOTHER EROSION BAND. Subtracting a noise from a density removes material
// from a cloud's edge: it gets thinner and dimmer, but its boundary keeps the base field's shape. That is
// why the deck reads as rounded blobs no matter how hard the erosion is driven -- eroding a sphere gives
// a frayed sphere. Displacing WHERE the field is sampled moves the iso-surface itself, so the boundary
// grows folds, filaments and hollows that the base field never contained. It is the same mechanism the
// water wave field uses, and it is the one that makes a cloud billow.
//
// CURL, WHICH IS THE PART THAT MATTERS. An arbitrary noise vector field has sources and sinks, so warping
// by it compresses the field where it converges -- and a compressed density field is a clump, which is
// precisely the artefact this exists to remove. The curl of any field is divergence-free by construction,
// so it SHEARS without compressing: the boundary is dragged into swirls while the volume it encloses is
// preserved. cloud_field derives the curl and explains the central differences; this only samples it.
//
// STORED, NOT EVALUATED. One curl sample is six Perlin evaluations, and the march would want it several
// times per step. Thirty-two cubed is 128 KB and one filtered fetch.
//
// ENCODING. Measured on a CPU replica of this bake before it shipped: at period 4 the curl's magnitude has
// a median of 4.08 and a maximum of 11.07, so a divisor of 12 fits every component with nothing clipped.
// Clipping was rejected rather than merely avoided -- clamping one component of a displacement turns the
// vector, so a clipped warp would push in a subtly wrong DIRECTION exactly where it pushes hardest.
// A third of the byte range at the median is ample for a field this smooth.

module cloud_warp;

import cloud_field;

public static const int CLOUD_WARP_DIM = 32;

// The octave the field is built at. Low, because this displaces whole lobes: a high-frequency warp moves
// each point somewhere different from its neighbour, which is not a fold, it is noise.
static const int CLOUD_WARP_PERIOD = 4;

// Measured maximum |curl| at that period, rounded up. See the banner.
static const float CLOUD_WARP_RANGE = 12.0;

[[vk::binding(0, 0)]] RWTexture3D<float4> cloudWarpOut;

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (any(id >= uint3(CLOUD_WARP_DIM, CLOUD_WARP_DIM, CLOUD_WARP_DIM))) {
        return;
    }
    // Texel centres, as everywhere else here: this is read through a filter, and sampling corners would
    // bias the whole field half a texel along every axis.
    float3 p = (float3(id) + 0.5) / float(CLOUD_WARP_DIM);
    // Tileable because cloud_field wraps its lattice coordinates to each octave's period before hashing.
    // The shading divides world coordinates by an authored span and lets the sampler repeat, so the field
    // has to close on itself -- REPEAT alone would tile the texture while the noise stepped at the seam.
    float3 curl = curlNoise(p, CLOUD_WARP_PERIOD) * (1.0 / CLOUD_WARP_RANGE);
    // Signed field into an unsigned format. The shading undoes exactly this.
    cloudWarpOut[int3(id)] = float4(curl * 0.5 + 0.5, 0.0);
}
