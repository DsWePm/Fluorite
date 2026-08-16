// M21 rain exposure, retained wetness, standing-water structure and impact ripples.
// All coordinates are world anchored; primary guides and every path vertex read the same maps.

import world_common;
import world_core;
import math;
import precipitation;

[[vk::binding(24, 0)]] Sampler2D<float> rainExposureDepth;
[[vk::binding(25, 0)]] Sampler2D<uint2> rainWetHistory;

public static const float RAIN_DEPTH_BIAS = 0.10;
public static const float RAIN_SIDE_SHELL = 1.05;
public static const float WATER_FILM_F0 = 0.020373; // ((1.333 - 1)/(1.333 + 1))^2
static const float RAIN_WORLD_PERIOD = 4096.0;
static const float RAIN_BOUNDARY_JITTER_BLOCKS = 0.42;
static const float RAIN_IMPACT_CELL_SIZE = 0.50;
static const float RAIN_IMPACT_TIME_WRAP = 3600.0;

public struct RainSurface {
    public float exposure;
    public float darkening;
    public float film;
    public float filmAlpha;
    public float puddle;
};

public RainSurface dryRainSurface() {
    RainSurface r;
    r.exposure = 0.0;
    r.darkening = 0.0;
    r.film = 0.0;
    r.filmAlpha = max(worldPush.rainSurface.y, 0.01);
    r.puddle = 0.0;
    return r;
}

float rainHash(float2 p) {
    float3 p3 = frac(float3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return frac((p3.x + p3.y) * p3.z);
}

float rainHashPeriodic(float2 p, float period) {
    return rainHash(p - floor(p / period) * period);
}

float rainValueNoisePeriodic(float2 p, float period) {
    float2 i = floor(p);
    float2 f = frac(p);
    f = f * f * (3.0 - 2.0 * f);
    return lerp(lerp(rainHashPeriodic(i, period),
                     rainHashPeriodic(i + float2(1.0, 0.0), period), f.x),
                lerp(rainHashPeriodic(i + float2(0.0, 1.0), period),
                     rainHashPeriodic(i + 1.0, period), f.x), f.y);
}

float rainSmoothFbmPeriodic(float2 p, float period) {
    float broad = rainValueNoisePeriodic(p, period);
    float detailPeriod = max(period * 2.0, 1.0);
    float detail = rainValueNoisePeriodic(p * 2.0 + float2(17.0, 43.0), detailPeriod);
    float finePeriod = max(period * 4.0, 1.0);
    float fine = rainValueNoisePeriodic(p * 4.0 + float2(71.0, 29.0), finePeriod);
    return broad * 0.62 + detail * 0.27 + fine * 0.11;
}

float2 rainWorldXZ(float3 p) {
    return frac((p.xz + worldPush.waterAnchor.xy) / RAIN_WORLD_PERIOD) * RAIN_WORLD_PERIOD;
}

/** Mean-zero broad + fine world noise used only around wet/dry association boundaries. */
float rainBoundaryJitter(float3 p) {
    float2 worldXZ = rainWorldXZ(p);
    float broad = rainValueNoisePeriodic(worldXZ * 0.65, RAIN_WORLD_PERIOD * 0.65);
    float detail = rainValueNoisePeriodic(worldXZ * 2.35 + float2(31.0, 79.0),
                                          RAIN_WORLD_PERIOD * 2.35);
    return ((broad - 0.5) * 0.68 + (detail - 0.5) * 0.32) * 2.0;
}

/** Two independent world-anchored offsets which bend a horizontal wet/dry contour in the XZ plane. */
float2 rainBoundaryWarp(float3 p) {
    float x = rainBoundaryJitter(p);
    float3 decorrelated = float3(p.x + 173.0, p.y, p.z - 269.0);
    float z = rainBoundaryJitter(decorrelated);
    return float2(x, z) * RAIN_BOUNDARY_JITTER_BLOCKS;
}

struct RainMapCoord {
    float2 texel;
    float t;
    bool valid;
};

RainMapCoord rainMapCoord(float3 p) {
    RainMapCoord c;
    float resolution = worldPush.rainExposureOrigin.w;
    c.t = (p.y - worldPush.rainExposureOrigin.y) / worldPush.rainDirection.y;
    float3 entry = p - worldPush.rainDirection.xyz * c.t;
    c.texel = entry.xz - worldPush.rainExposureOrigin.xz;
    c.valid = worldPush.rainState.w > 0.0 && resolution > 0.0
            && c.t >= 0.0 && c.t <= worldPush.rainDirection.w
            && all(c.texel >= 0.0) && all(c.texel < resolution);
    return c;
}

float rainExposureSample(int2 pixel, float t, float resolution) {
    pixel = clamp(pixel, int2(0, 0), int2(int(resolution) - 1, int(resolution) - 1));
    float depth = rainExposureDepth.Load(int3(pixel, 0));
    if (depth < 0.0) {
        return 0.0;
    }
    float sideShell = RAIN_SIDE_SHELL / max(abs(worldPush.rainDirection.y), 1.0e-3);
    float delta = depth + RAIN_DEPTH_BIAS - t;
    float side = smoothstep(-sideShell, 0.0, delta);
    return side;
}

/** Four-tap compare filtering: interpolate visibility decisions, never raw depths across a roof edge. */
float rainExposureAtWarp(float3 p, float2 texelWarp) {
    RainMapCoord c = rainMapCoord(p);
    if (!c.valid) {
        return 0.0;
    }
    float resolution = worldPush.rainExposureOrigin.w;
    c.texel += texelWarp;
    if (any(c.texel < 0.0) || any(c.texel >= resolution)) {
        return 0.0;
    }
    // ONLY RAIN WETS, and this is the single funnel every wetness query passes through -- the retained
    // history, the wet film and the diagnostic view all reach the map through here. Snow shares the
    // exposure depth because it is sheltered by the same roofs, so without this a snowstorm would soak
    // the world; putting the test at the funnel rather than at each caller is what makes that
    // impossible to forget at a fourth call site.
    if (!precipitationWets(precipitationAtTexel(pc.rainPrecipitationAddr, c.texel, resolution))) {
        return 0.0;
    }
    float2 centre = c.texel - 0.5;
    int2 base = int2(floor(centre));
    float2 f = frac(centre);
    float4 visible = float4(
            rainExposureSample(base, c.t, resolution),
            rainExposureSample(base + int2(1, 0), c.t, resolution),
            rainExposureSample(base + int2(0, 1), c.t, resolution),
            rainExposureSample(base + int2(1, 1), c.t, resolution));
    float4 bilinear = float4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y),
                             (1.0 - f.x) * f.y, f.x * f.y);
    return dot(visible, bilinear);
}

/** Exact directional exposure for vegetation cards, streaks and diagnostics. */
public float rainExposureAt(float3 p) {
    return rainExposureAtWarp(p, float2(0.0, 0.0));
}

/** Horizontal receiver exposure with a true coordinate warp, not a contrast change after filtering. */
public float rainTopExposureAt(float3 p) {
    return rainExposureAtWarp(p, rainBoundaryWarp(p));
}

float rainHistoryDepth(uint packed) {
    return f16tof32(packed >> 16u);
}

float2 rainHistoryWater(uint packed) {
    return float2(float((packed >> 8u) & 0xFFu), float(packed & 0xFFu)) * (1.0 / 255.0);
}

float4 rainHistorySample(int2 pixel, float t, float resolution) {
    pixel = clamp(pixel, int2(0, 0), int2(int(resolution) - 1));
    uint2 packed = rainWetHistory.Load(int3(pixel, 0));
    float depth0 = rainHistoryDepth(packed.x);
    float depth1 = rainHistoryDepth(packed.y);
    float shell = RAIN_SIDE_SHELL / max(abs(worldPush.rainDirection.y), 1.0e-3);
    float2 current = depth0 >= 0.0 && abs(t - depth0) <= RAIN_DEPTH_BIAS + shell
            ? rainHistoryWater(packed.x) : float2(0.0, 0.0);
    float2 previous = depth1 >= 0.0 && abs(t - depth1) <= shell
            ? rainHistoryWater(packed.y) : float2(0.0, 0.0);
    return float4(current, previous);
}

/** Filter matched wetness decisions, never packed depth values across an eave or new roof edge. */
float4 rainHistoryAtWarp(float3 p, float2 texelWarp) {
    RainMapCoord c = rainMapCoord(p);
    // Puddles deliberately outlive the thin wet film. Testing only rainState.x here made a still-wet
    // local puddle disappear as soon as the faster global film reservoir reached zero.
    if (!c.valid || max(worldPush.rainState.x, worldPush.rainState.y) <= 1.0e-5) {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    float resolution = worldPush.rainExposureOrigin.w;
    c.texel += texelWarp;
    if (any(c.texel < 0.0) || any(c.texel >= resolution)) {
        return float4(0.0, 0.0, 0.0, 0.0);
    }
    float2 centre = c.texel - 0.5;
    int2 base = int2(floor(centre));
    float2 f = frac(centre);
    float4 h00 = rainHistorySample(base, c.t, resolution);
    float4 h10 = rainHistorySample(base + int2(1, 0), c.t, resolution);
    float4 h01 = rainHistorySample(base + int2(0, 1), c.t, resolution);
    float4 h11 = rainHistorySample(base + int2(1, 1), c.t, resolution);
    return lerp(lerp(h00, h10, f.x), lerp(h01, h11, f.x), f.y);
}

float4 rainHistoryAt(float3 p) {
    return rainHistoryAtWarp(p, float2(0.0, 0.0));
}

float4 rainTopHistoryAt(float3 p) {
    return rainHistoryAtWarp(p, rainBoundaryWarp(p));
}

float4 resolvedRainMaterial(MaterialExtension ext) {
    return float4(ext.weather.x >= 0.0 ? ext.weather.x : worldPush.rainMaterialDefaults.x,
                  ext.weather.y >= 0.0 ? ext.weather.y : worldPush.rainMaterialDefaults.y,
                  ext.weather.z >= 0.0 ? ext.weather.z : worldPush.rainMaterialDefaults.z,
                  ext.weather.w >= 0.0 ? ext.weather.w : worldPush.rainMaterialDefaults.w);
}

float rainPuddleMaskAt(float3 p, bool topReceiver) {
    // Puddles accumulate on the macro surface, not on the normal map. Closest-hit classifies the
    // unperturbed triangle once and carries that top-face fact in the payload; using shading n.y here
    // let a strong material normal punch false dry/bright holes into otherwise level standing water.
    if (!topReceiver) {
        return 0.0;
    }
    float2 worldXZ = rainWorldXZ(p);
    float scale = max(worldPush.rainSurface.w, 1.0);
    float puddlePeriod = max(round(RAIN_WORLD_PERIOD / scale), 1.0);
    float structure = rainSmoothFbmPeriodic(worldXZ * puddlePeriod / RAIN_WORLD_PERIOD,
                                            puddlePeriod);
    float coverage = clamp(worldPush.rainSurface.z, 0.0, 1.0);
    float puddleMask = coverage <= 0.0 ? 0.0 : (coverage >= 1.0 ? 1.0
            : smoothstep(1.0 - coverage - 0.10, 1.0 - coverage + 0.10, structure));
    return puddleMask;
}

/** M21 diagnostic: R retained storage, G procedural/slope basin, B final material response. */
public float3 rainPuddleDebugAt(float3 p, MaterialExtension ext, bool topReceiver) {
    float4 history = rainTopHistoryAt(p);
    float retainedPuddle = max(history.g, history.a);
    float mask = rainPuddleMaskAt(p, topReceiver);
    float materialAffinity = resolvedRainMaterial(ext).w;
    float finalPuddle = worldPush.rainPuddle.y * retainedPuddle * materialAffinity * mask
            * worldPush.rainCalibration0.z;
    return clamp(float3(retainedPuddle, mask, finalPuddle), 0.0, 1.0);
}

public RainSurface evaluateRainSurface(float3 p, MaterialExtension ext,
                                       bool filmReceiver, bool topReceiver, bool plantReceiver) {
    if (!filmReceiver) {
        return dryRainSurface();
    }
    RainSurface r;
    float currentExposure = plantReceiver ? rainExposureAt(p) : rainTopExposureAt(p);
    float4 history = plantReceiver ? rainHistoryAt(p) : rainTopHistoryAt(p);
    float retainedWet = max(history.r, history.b);
    float retainedPuddle = max(history.g, history.a);
    // Ordinary terrain and leaves are admitted only by an upward geometric face bit. Small pass-through
    // plants are the explicit exception: their exposed cards receive film on every visible face.
    r.exposure = max(currentExposure * worldPush.rainState.x, retainedWet);

    float4 material = resolvedRainMaterial(ext);
    float saturation = r.exposure * material.x;
    r.darkening = clamp(saturation * material.y * worldPush.rainCalibration0.x, 0.0, 1.0);

    r.film = clamp(r.exposure * material.z * worldPush.rainSurface.x
            * worldPush.rainCalibration0.y, 0.0, 1.0);

    // Standing water is an independent smooth world field. There is intentionally no size clamp: broad
    // and detail octaves make connected, feathered basins instead of stamped circular islands.
    float puddleMask = rainPuddleMaskAt(p, topReceiver);
    float basePuddle = worldPush.rainPuddle.y * retainedPuddle * material.w * puddleMask;
    r.puddle = clamp(basePuddle * worldPush.rainCalibration0.z, 0.0, 1.0);
    float puddleFilm = r.puddle;
    r.film = 1.0 - (1.0 - r.film) * (1.0 - puddleFilm);
    // A millimetre-scale clear puddle has little visible Beer absorption. The layered Fresnel response
    // already removes substrate energy, so the old unitless optical depth double-darkened puddles by up
    // to ~60%. Keep one shared wet-colour endpoint and add only a bounded artistic shallow-water term.
    float puddleExtraDarkening = clamp(
            r.puddle * worldPush.rainCalibration1.x, 0.0, 0.25);
    r.darkening = 1.0 - (1.0 - r.darkening) * (1.0 - puddleExtraDarkening);
    // Do not fold puddles into film with max(). A puddle flattens and smooths its own top layer even when
    // the ordinary wet film already has a lower authored alpha.
    r.filmAlpha = lerp(max(worldPush.rainSurface.y, 0.01),
                       max(worldPush.rainCalibration0.w, 0.002), puddleFilm);
    return r;
}

float2 rainPeriodicDelta(float2 a, float2 b) {
    float2 d = a - b;
    return d - round(d / RAIN_WORLD_PERIOD) * RAIN_WORLD_PERIOD;
}

uint rainImpactMix(uint value) {
    value ^= value >> 16u;
    value *= 0x7FEB352Du;
    value ^= value >> 15u;
    value *= 0x846CA68Bu;
    value ^= value >> 16u;
    return value;
}

uint2 rainWrappedImpactCell(int2 cell) {
    // 4096 blocks / 0.5-block cells = 8192, a power of two. Casting a negative signed cell to uint
    // and masking therefore exactly matches Java floorMod(cell, 8192).
    return uint2(cell) & uint2(8191u, 8191u);
}

uint rainImpactEventHash(uint2 wrappedCell, uint eventIndex, uint salt) {
    uint value = wrappedCell.x * 0x9E3779B9u
            ^ wrappedCell.y * 0x85EBCA6Bu
            ^ eventIndex * 0xC2B2AE35u
            ^ salt * 0x27D4EB2Fu;
    return rainImpactMix(value);
}

float rainImpactEventHash01(uint2 wrappedCell, uint eventIndex, uint salt) {
    return float(rainImpactEventHash(wrappedCell, eventIndex, salt) >> 8u)
            * (1.0 / 16777216.0);
}

float2 rainImpactGradient(float3 p, float radiusScale, float footprint) {
    float2 worldXZ = rainWorldXZ(p);
    float2 q = worldXZ / RAIN_IMPACT_CELL_SIZE;
    int2 base = int2(floor(q));
    float2 gradient = float2(0.0, 0.0);
    float rainAmount = clamp(worldPush.rainState.z, 0.0, 1.0);
    float eventProbability = lerp(0.35, 0.95, sqrt(rainAmount));
    // A 3x3 neighbourhood covers the maximum authored radius. Each cell owns an independent clock and
    // changes its random landing point only during a guaranteed inactive gap, so rings neither form a
    // permanent lattice nor pop halfway through expansion.
    for (int oy = -1; oy <= 1; ++oy) {
        for (int ox = -1; ox <= 1; ++ox) {
            int2 cell = base + int2(ox, oy);
            uint2 wrappedCell = rainWrappedImpactCell(cell);
            uint cycleChoice = rainImpactEventHash(wrappedCell, 0u, 10u) & 3u;
            // All four choices divide the CPU's 3600-second animation wrap exactly. Event hashes therefore
            // remain continuous across that wrap instead of producing a once-per-hour global reset.
            float cycleSeconds = 0.75 + float(cycleChoice) * 0.25;
            uint cycleCount = uint(round(RAIN_IMPACT_TIME_WRAP / cycleSeconds));
            float phaseOffset = rainImpactEventHash01(wrappedCell, 0u, 11u);
            float eventClock = worldPush.rainPuddle.w / cycleSeconds + phaseOffset;
            uint eventIndex = uint(floor(eventClock)) % cycleCount;
            float slotPhase = frac(eventClock);

            float random0 = rainImpactEventHash01(wrappedCell, eventIndex, 0u);
            if (random0 > eventProbability) {
                continue;
            }
            float random1 = rainImpactEventHash01(wrappedCell, eventIndex, 1u);
            float activeFraction = lerp(0.36, 0.72, random1);
            if (slotPhase >= activeFraction) {
                continue;
            }
            float phase = slotPhase / activeFraction;
            float random2 = rainImpactEventHash01(wrappedCell, eventIndex, 2u);
            float random3 = rainImpactEventHash01(wrappedCell, eventIndex, 3u);
            float random4 = rainImpactEventHash01(wrappedCell, eventIndex, 4u);
            float random5 = rainImpactEventHash01(wrappedCell, eventIndex, 5u);
            float2 eventCentre = (float2(wrappedCell) + float2(random2, random3))
                    * RAIN_IMPACT_CELL_SIZE;
            float2 d = rainPeriodicDelta(worldXZ, eventCentre);
            float dist = length(d);
            float authoredRadius = clamp(worldPush.rainPuddle.z, 0.03, 0.30);
            float eventRadius = authoredRadius * radiusScale * lerp(0.65, 1.15, random4) * phase;
            float ringWidth = clamp(worldPush.rainCalibration1.z, 0.010, 0.080)
                    * lerp(0.82, 1.18, random5);
            // Preserve a small authored ring nearby, then widen it to at least one ray footprint so RR
            // receives a coherent signal instead of a sub-pixel alternating normal. Amplitude compensation
            // keeps the broadened ridge from gaining energy merely because the camera moved away.
            float antiAliasWidth = min(max(ringWidth, footprint * 0.55), 0.12);
            float radial = dist - eventRadius;
            float eventStrength = lerp(0.55, 1.30, random4) * sin(PI * phase);
            // A feedback wave solver starts from a circular SDF impulse, then its damped second-order
            // update naturally leaves alternating crests behind the leading front. Reproduce that shape
            // analytically: one smooth causal front plus a short exponentially damped wake. This retains
            // the solver's crest/trough character without persistent textures or an extra dispatch.
            float waveCoordinate = radial / antiAliasWidth;
            float frontWindow = 1.0 - smoothstep(0.0, 1.0, waveCoordinate);
            float wakeDistance = max(-waveCoordinate, 0.0);
            float waveDamping = exp2(-0.80 * wakeDistance);
            float waveSlope = cos(PI * wakeDistance) * waveDamping * frontWindow;
            waveSlope *= sqrt(ringWidth / antiAliasWidth);
            gradient += dist > 1.0e-4
                    ? d / dist * waveSlope * eventStrength : float2(0.0, 0.0);
        }
    }
    return gradient;
}

/** Independent rain-impact layer shared by standing puddles and native water. */
public float3 applyRainImpactRipples(float3 p, float3 n, float receiver, float footprint) {
    // Receiver is physical puddle depth/coverage, not an artistic contrast value. Remap shallow standing
    // water to a visible ring mask so default material affinity does not suppress the impact twice.
    float receiverMask = smoothstep(0.0, 0.25, receiver);
    float strength = worldPush.rainPuddle.x * worldPush.rainState.z * receiverMask;
    if (strength <= 1.0e-4 || n.y <= 0.55 || footprint > 1.5) {
        return n;
    }
    float lodFade = 1.0 - smoothstep(0.25, 1.5, footprint);
    float2 grad = rainImpactGradient(p, 1.0, footprint);
    return normalize(n + float3(-grad.x, 0.0, -grad.y) * strength * lodFade * 0.34);
}

public float3 applyRainLayerNormal(float3 n, RainSurface rain,
                                   bool topReceiver, bool plantReceiver) {
    // Top faces have an exact axis-aligned macro normal. Pass-through plant cards are the only wet
    // side faces; project those toward their vertical macro plane instead of incorrectly pulling them
    // toward the sky. This suppresses only texture-scale normal relief, not the actual mesh silhouette.
    float3 macroNormal = topReceiver ? float3(0.0, n.y >= 0.0 ? 1.0 : -1.0, 0.0) : n;
    if (!topReceiver && plantReceiver) {
        float horizontalLength = length(n.xz);
        macroNormal = horizontalLength > 1.0e-4
                ? float3(n.x / horizontalLength, 0.0, n.z / horizontalLength) : n;
    }
    // A thin film slightly softens the substrate relief. Standing water then adds a second, stronger
    // continuous flattening term. Multiplying their complements avoids a step where the puddle mask
    // feathers into the surrounding wet film and never exceeds a valid blend weight.
    float filmFlatten = clamp(rain.film * worldPush.rainCalibration1.w, 0.0, 0.98);
    float puddleFlatten = topReceiver
            ? clamp(rain.puddle * worldPush.rainCalibration1.y, 0.0, 0.98) : 0.0;
    float flatten = 1.0 - (1.0 - filmFlatten) * (1.0 - puddleFlatten);
    return normalize(lerp(n, macroNormal, flatten));
}

public float openWaterRainImpact(float3 p) {
    float wetSurfaceEnabled = max(worldPush.rainState.x, worldPush.rainState.y) > 1.0e-5 ? 1.0 : 0.0;
    return rainTopExposureAt(p) * wetSurfaceEnabled;
}
