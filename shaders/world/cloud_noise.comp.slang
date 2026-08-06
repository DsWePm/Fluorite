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
// Channels: R is the billow that gives a cloud its shape, G the finer one that eats into its edges. Both
// are inverted cellular noise, which is what makes them read as puffs rather than as smoke -- distance to
// the nearest feature point, subtracted from one, has the round-topped profile a convective cloud has.

module cloud_noise;

public static const int CLOUD_NOISE_DIM = 128;

[[vk::binding(0, 0)]] RWTexture3D<float4> cloudNoiseOut;

uint hash3(int3 c) {
    // Three odd constants and a final avalanche. Nothing is transcribed here: cellular noise needs an
    // integer hash with no visible axis correlation, and any such hash serves.
    uint h = uint(c.x) * 0x8DA6B343u ^ uint(c.y) * 0xD8163841u ^ uint(c.z) * 0xCB1AB31Fu;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return h;
}

/** Feature point inside a cell, in that cell's local unit cube. */
float3 cellPoint(int3 cell, int period) {
    int3 wrapped = ((cell % period) + period) % period; // negative-safe wrap: this is what makes it tile
    uint h = hash3(wrapped);
    return float3(float(h & 0x3FFu) * (1.0 / 1023.0),
                  float((h >> 10) & 0x3FFu) * (1.0 / 1023.0),
                  float((h >> 20) & 0x3FFu) * (1.0 / 1023.0));
}

/** Inverted cellular noise in [0,1]: 1 at a feature point, falling to 0 between them. */
float cellular(float3 p, int period) {
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
                float3 point = float3(offset) + cellPoint(base + offset, period);
                float3 d = point - f;
                nearest = min(nearest, dot(d, d));
            }
        }
    }
    return saturate(1.0 - sqrt(nearest));
}

/** Octaves at doubling period, halving amplitude. Normalised so the result spans [0,1]. */
float cellularFbm(float3 p, int period, int octaves) {
    float sum = 0.0;
    float amplitude = 1.0;
    float total = 0.0;
    int currentPeriod = period;
    for (int i = 0; i < octaves; ++i) {
        sum += cellular(p, currentPeriod) * amplitude;
        total += amplitude;
        amplitude *= 0.5;
        currentPeriod *= 2;
    }
    return sum / max(total, 1.0e-6);
}

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (any(id >= uint3(CLOUD_NOISE_DIM, CLOUD_NOISE_DIM, CLOUD_NOISE_DIM))) {
        return;
    }
    // Texel CENTRES, for the same reason the atmosphere tables use them: this is read with a filtered
    // fetch, and sampling corners would bias the whole volume half a texel along every axis.
    float3 p = (float3(id) + 0.5) / float(CLOUD_NOISE_DIM);
    float shape = cellularFbm(p, 4, 3);   // low frequency: the cloud's body
    float erosion = cellularFbm(p, 12, 3); // high frequency: what bites into its edges
    // The weather map's second channel: which KIND of cloud stands here, sampled far more slowly than
    // coverage so a storm cell covers a region rather than alternating with fair weather every few
    // hundred blocks. One octave at the lowest period the volume can hold, deliberately smooth — this is
    // the field a height profile is selected by, and a noisy one would put a cumulonimbus tower next to a
    // sheet of stratus with nothing in between.
    float type = cellularFbm(p, 2, 1);
    cloudNoiseOut[int3(id)] = float4(shape, erosion, type, 1.0);
}
