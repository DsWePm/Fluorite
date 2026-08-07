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

/**
 * Tileable gradient (Perlin) noise in [-1,1].
 *
 * <p>Worley alone cannot make a cloud look like a cloud. Inverted cellular noise is a field of feature
 * points with round falloff, so it produces evenly spaced bubbles -- a texture with one scale and one
 * shape. Gradient noise has the opposite character: connected, directional, no preferred spacing. The
 * two are combined below rather than chosen between.
 *
 * <p>Tileable by the same rule as the cellular noise: lattice coordinates are wrapped to the octave's own
 * period before hashing, so the field repeats exactly at that period however far a ray travels.
 */
float3 gradientVec(int3 cell, int period) {
    int3 wrapped = ((cell % period) + period) % period;
    uint h = hash3(wrapped + int3(19, 47, 101));
    // Three signed components from disjoint bit fields. Not normalised: a slightly varying length is
    // harmless here and a normalise would cost a rsqrt per corner, eight per sample.
    return float3(float(h & 0x3FFu) * (2.0 / 1023.0) - 1.0,
                  float((h >> 10) & 0x3FFu) * (2.0 / 1023.0) - 1.0,
                  float((h >> 20) & 0x3FFu) * (2.0 / 1023.0) - 1.0);
}

float perlin(float3 p, int period) {
    float3 scaled = p * float(period);
    int3 base = int3(floor(scaled));
    float3 f = scaled - float3(base);
    // Quintic fade: its first AND second derivatives vanish at the lattice, which is what keeps the
    // curl below (a derivative of this field) free of visible lattice creases.
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
                n += weight * dot(gradientVec(base + offset, period), d);
            }
        }
    }
    return n;
}

float perlinFbm(float3 p, int period, int octaves) {
    float sum = 0.0;
    float amplitude = 1.0;
    float total = 0.0;
    int currentPeriod = period;
    for (int i = 0; i < octaves; ++i) {
        sum += perlin(p, currentPeriod) * amplitude;
        total += amplitude;
        amplitude *= 0.5;
        currentPeriod *= 2;
    }
    return sum / max(total, 1.0e-6);
}

/**
 * Curl of a gradient-noise vector potential: a divergence-free field, which is what makes it look like
 * air rather than like noise.
 *
 * <p>WHY CURL SPECIFICALLY. Warping a texture by an arbitrary noise vector field stretches and compresses
 * it, because that field has sources and sinks -- clumps appear where it converges. The curl of any field
 * is divergence-free by construction, so warping by it SHEARS without compressing: detail gets dragged
 * into filaments and swirls while its density stays put. That is exactly what a cloud edge does, and it
 * is the difference between erosion that bites round holes and erosion that draws wisps.
 *
 * <p>Taken by central differences on three offset potentials. The offsets are what make the three
 * components independent; sampling one field three times would give a potential whose curl is zero.
 */
float3 curlNoise(float3 p, int period) {
    // A half-texel of the octave being differentiated. Smaller starts to read quantisation in the
    // gradients, larger smooths the swirl away.
    float e = 0.5 / float(period * CLOUD_NOISE_DIM / 32);
    float3 dx = float3(e, 0.0, 0.0);
    float3 dy = float3(0.0, e, 0.0);
    float3 dz = float3(0.0, 0.0, e);
    float3 o1 = float3(0.0, 0.0, 0.0);
    float3 o2 = float3(5.2, 1.3, 9.7);
    float3 o3 = float3(2.7, 8.3, 4.1);

    float p1y = perlin(p + o1 + dy, period) - perlin(p + o1 - dy, period);
    float p1z = perlin(p + o1 + dz, period) - perlin(p + o1 - dz, period);
    float p2x = perlin(p + o2 + dx, period) - perlin(p + o2 - dx, period);
    float p2z = perlin(p + o2 + dz, period) - perlin(p + o2 - dz, period);
    float p3x = perlin(p + o3 + dx, period) - perlin(p + o3 - dx, period);
    float p3y = perlin(p + o3 + dy, period) - perlin(p + o3 - dy, period);

    float3 curl = float3(p3y - p2z, p1z - p3x, p2x - p1y);
    return curl / (2.0 * e);
}

/**
 * Remap v from [l0,h0] onto [l1,h1]. The Perlin-Worley construction below is written in terms of it
 * because that is the shape of the idea: take the gradient field and let the cellular field decide where
 * its floor sits.
 */
float remap(float v, float l0, float h0, float l1, float h1) {
    return l1 + (v - l0) * (h1 - l1) / max(h0 - l0, 1.0e-5);
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

    // ---- R, the cloud's body: PERLIN-WORLEY.
    //
    // Inverted cellular noise on its own is a field of feature points with a round falloff, so it makes
    // evenly spaced bubbles: one size, one shape, visibly a lattice. Gradient noise is connected and has
    // no preferred spacing, but no billow either. Remapping the gradient field so that its LOW end is
    // lifted by the cellular one keeps the billowed tops and gives them irregular, connected bases.
    //
    // THEN NORMALISED, which is not cosmetic. The raw construction centres near 0.66 with a p3-p97 span
    // of 0.567 to 0.750 -- an eighth of the range, so seven eighths of the channel's byte would carry
    // nothing, and every downstream threshold would be operating inside that eighth. Measured on a CPU
    // replica of this bake before it shipped; the constants below are those percentiles, and the
    // coverage remap in cloud.slang was re-centred against the widened field in the same pass. Dropping
    // this in without both halves overcasts 96% of the sky at neutral bias.
    float worley = cellularFbm(p, 4, 3);
    float perlinLow = perlinFbm(p, 4, 4) * 0.5 + 0.5;
    float perlinWorley = remap(perlinLow, saturate(1.0 - worley) - 1.0, 1.0, 0.0, 1.0);
    float shape = saturate((perlinWorley - 0.567) / 0.183);

    // ---- G, what bites into the edges: high-frequency Worley, CURL-WARPED.
    //
    // The warp is the whole point. Eroding with plain cellular noise takes round bites out of a cloud,
    // which reads as foam; dragging the sample position along a divergence-free field first turns those
    // bites into filaments and swirls, which reads as a cloud edge being torn by wind shear. The strength
    // is small on purpose -- this displaces the lookup by a fraction of a detail feature, and past that
    // the detail stops belonging to the cloud it is eroding.
    float3 warp = curlNoise(p, 8) * 0.035;
    float erosion = cellularFbm(p + warp, 12, 3);

    // ---- B, which KIND of cloud stands here, sampled far more slowly than coverage so a storm cell
    // covers a region rather than alternating with fair weather every few hundred blocks. One octave at
    // the lowest period the volume can hold, deliberately smooth -- this is the field a height profile is
    // selected by, and a noisy one would put a cumulonimbus tower next to a sheet of stratus with nothing
    // in between.
    float type = cellularFbm(p, 2, 1);

    // ---- A, a second detail band at a different scale and a different warp, for the march to reach for
    // when it wants finer erosion than G carries. Free: the channel exists either way.
    float3 warpFine = curlNoise(p, 16) * 0.02;
    float detail = cellularFbm(p + warpFine, 24, 2);

    cloudNoiseOut[int3(id)] = float4(shape, erosion, type, detail);
}
