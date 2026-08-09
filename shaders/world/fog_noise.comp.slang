// One startup bake for the ambient fog's heterogeneous density field.
//
// R and G are signed, zero-mean fields encoded from [-1,1] to [0,1]. R carries four broad
// Perlin/Worley cells across the tile; G carries sixteen detail cells. D67 gives each paired field a
// fixed bake-time contrast before encoding because the raw differences occupied too little of their
// authored range to be visible even when fully resolved. The runtime decodes both, gives detail one
// quarter of the variation budget, and adds the result around a multiplier of one. This is the energy
// contract: turning structure on redistributes the authored fog density, it does not raise its
// volume-wide mean.
//
// The zero mean is structural rather than a fitted subtraction. For a periodic field f, the pair
// f(p)-f(p+(0.5,0,0.5)) sums to exactly zero over every horizontal slice because the half-period xz
// shift is a permutation and applying it twice returns to p. That stronger per-height property prevents
// the exponential height profile from weighting one sign more than the other. The bake evaluates one
// canonical voxel for each xz pair and writes explicitly complementary UNORM8 codes, preserving that
// symmetry to its last bit instead of relying on implementation-defined half-code rounding.
//
// Both noises are tileable in the field itself. Wrapping each lattice coordinate before hashing is what
// makes the samples on opposite faces agree; a REPEAT sampler alone only repeats a discontinuity.

module fog_noise;

public static const int FOG_NOISE_DIM = 128;
public static const float FOG_BASE_CONTRAST = 3.8;
public static const float FOG_DETAIL_CONTRAST = 3.5;

[[vk::binding(0, 0)]] RWTexture3D<float4> fogNoiseOut;

uint fogHash3(int3 c) {
    uint h = uint(c.x) * 0x8DA6B343u ^ uint(c.y) * 0xD8163841u ^ uint(c.z) * 0xCB1AB31Fu;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return h;
}

float3 fogCellPoint(int3 cell, int period) {
    int3 wrapped = ((cell % period) + period) % period;
    uint h = fogHash3(wrapped);
    return float3(float(h & 0x3FFu) * (1.0 / 1023.0),
                  float((h >> 10) & 0x3FFu) * (1.0 / 1023.0),
                  float((h >> 20) & 0x3FFu) * (1.0 / 1023.0));
}

/** Inverted Worley field: one at a feature point and falling away between cells. */
float fogCellular(float3 p, int period) {
    float3 scaled = p * float(period);
    int3 base = int3(floor(scaled));
    float3 f = scaled - float3(base);
    float nearest = 1.0e9;
    [unroll]
    for (int dz = -1; dz <= 1; ++dz) {
        [unroll]
        for (int dy = -1; dy <= 1; ++dy) {
            [unroll]
            for (int dx = -1; dx <= 1; ++dx) {
                int3 offset = int3(dx, dy, dz);
                float3 d = float3(offset) + fogCellPoint(base + offset, period) - f;
                nearest = min(nearest, dot(d, d));
            }
        }
    }
    return saturate(1.0 - sqrt(nearest));
}

float fogCellularFbm(float3 p, int period, int octaves) {
    float sum = 0.0;
    float amplitude = 1.0;
    float total = 0.0;
    int currentPeriod = period;
    for (int i = 0; i < octaves; ++i) {
        sum += fogCellular(p, currentPeriod) * amplitude;
        total += amplitude;
        amplitude *= 0.5;
        currentPeriod *= 2;
    }
    return sum / max(total, 1.0e-6);
}

float3 fogGradient(int3 cell, int period) {
    int3 wrapped = ((cell % period) + period) % period;
    uint h = fogHash3(wrapped + int3(19, 47, 101));
    return float3(float(h & 0x3FFu) * (2.0 / 1023.0) - 1.0,
                  float((h >> 10) & 0x3FFu) * (2.0 / 1023.0) - 1.0,
                  float((h >> 20) & 0x3FFu) * (2.0 / 1023.0) - 1.0);
}

float fogPerlin(float3 p, int period) {
    float3 scaled = p * float(period);
    int3 base = int3(floor(scaled));
    float3 f = scaled - float3(base);
    float3 w = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
    float n = 0.0;
    [unroll]
    for (int dz = 0; dz <= 1; ++dz) {
        [unroll]
        for (int dy = 0; dy <= 1; ++dy) {
            [unroll]
            for (int dx = 0; dx <= 1; ++dx) {
                int3 offset = int3(dx, dy, dz);
                float3 d = f - float3(offset);
                float weight = (dx == 0 ? 1.0 - w.x : w.x)
                             * (dy == 0 ? 1.0 - w.y : w.y)
                             * (dz == 0 ? 1.0 - w.z : w.z);
                n += weight * dot(fogGradient(base + offset, period), d);
            }
        }
    }
    return n;
}

float fogPerlinFbm(float3 p, int period, int octaves) {
    float sum = 0.0;
    float amplitude = 1.0;
    float total = 0.0;
    int currentPeriod = period;
    for (int i = 0; i < octaves; ++i) {
        sum += fogPerlin(p, currentPeriod) * amplitude;
        total += amplitude;
        amplitude *= 0.5;
        currentPeriod *= 2;
    }
    return sum / max(total, 1.0e-6);
}

/** Connected Perlin structure plus rounded Worley pockets, kept in [0,1]. */
float fogFbm(float3 p, int period, int octaves) {
    float perlin = fogPerlinFbm(p, period, octaves) * 0.5 + 0.5;
    float worley = fogCellularFbm(p, period, max(octaves - 1, 1));
    return saturate(perlin * 0.65 + worley * 0.35);
}

/** Exact zero-mean periodic pairing; see the file header. */
float fogPaired(float3 p, int period, int octaves) {
    return clamp(fogFbm(p, period, octaves)
               - fogFbm(p + float3(0.5, 0.0, 0.5), period, octaves), -1.0, 1.0);
}

/** Odd contrast mapping preserves the exact xz pair cancellation, including after UNORM encoding. */
float fogContrast(float paired, float contrast) {
    return clamp(paired * contrast, -1.0, 1.0);
}

/**
 * Encode an anti-symmetric pair into complementary UNORM8 codes. There is no exact zero in an odd
 * 256-code signed mapping, so a zero pair deliberately receives 128/127 according to its canonical
 * half; decoded values are +/-1/255 and still cancel exactly.
 */
float fogEncodePaired(float paired, bool canonicalHalf) {
    float magnitudeCode = round(saturate(abs(paired)) * 127.0);
    bool positive = paired > 0.0 || (paired == 0.0 && canonicalHalf);
    float byteCode = positive ? 128.0 + magnitudeCode : 127.0 - magnitudeCode;
    return byteCode * (1.0 / 255.0);
}

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (any(id >= uint3(FOG_NOISE_DIM, FOG_NOISE_DIM, FOG_NOISE_DIM))) {
        return;
    }
    // Pairing toggles both x and z by half a tile. Evaluate only the x<half representative, then flip
    // its sign for the partner. This avoids separate floating-point evaluations landing on opposite
    // sides of a quantisation boundary.
    bool canonicalHalf = id.x < uint(FOG_NOISE_DIM / 2);
    uint3 canonicalId = id;
    if (!canonicalHalf) {
        canonicalId.x = (canonicalId.x + uint(FOG_NOISE_DIM / 2)) % uint(FOG_NOISE_DIM);
        canonicalId.z = (canonicalId.z + uint(FOG_NOISE_DIM / 2)) % uint(FOG_NOISE_DIM);
    }
    float3 p = (float3(canonicalId) + 0.5) / float(FOG_NOISE_DIM);
    float pairSign = canonicalHalf ? 1.0 : -1.0;
    float base = pairSign * fogContrast(fogPaired(p, 4, 4), FOG_BASE_CONTRAST);
    // A non-half offset decorrelates detail from the broad channel; the paired half shift still makes
    // its own mean exactly zero.
    float detail = pairSign * fogContrast(
            fogPaired(p + float3(0.173, 0.419, 0.731), 16, 3), FOG_DETAIL_CONTRAST);
    fogNoiseOut[int3(id)] = float4(fogEncodePaired(base, canonicalHalf),
                                    fogEncodePaired(detail, canonicalHalf), 0.5, 1.0);
}
