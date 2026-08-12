// M14 full-sphere environment provider. Kerr geometry remains an offline nearest-sampled transfer, while
// D93A-R stores the first local chord through the largest authored disk instead of a static colour. A
// short runtime integration along that chord gives every visible/NEE/secondary query the same dynamic
// Le and transmittance. The density model is independently written: it borrows only the public reference
// idea of a finite rotating noisy volume and its broad parameter proportions, not source code or assets.
module environment;

import world_common;
import atmosphere_lut;

[[vk::binding(20, 0)]] public Sampler2DArray environmentRadiance;
[[vk::binding(21, 0)]] public Sampler2DArray environmentTransfer;
[[vk::binding(22, 0)]] public Sampler2DArray environmentDiskEntry;
[[vk::binding(23, 0)]] public Sampler2DArray environmentDiskExit;

static const float ENV_PI = 3.14159265359;
static const float ENV_KERR_SPIN = 0.9;
static const float ENV_DISK_INNER_R = 2.32088304;
static const float ENV_DISK_MAX_OUTER_R = 12.0;
static const float ENV_DISK_BASE_HALF_HEIGHT = 0.55;
static const uint ENV_DISK_STEPS = 12u;
// The LUT observer sits 60 degrees from the spin axis. Canonical +Y is projected spin-up and +Z points
// at the hole, so the actual spin axis is not canonical +Y.
static const float3 ENV_SPIN_AXIS = float3(0.0, 0.866025404, -0.5);

public struct EnvironmentSample {
    public float3 radiance;
    public float3 diskRadiance;
    public bool disk;
};

struct EnvironmentDiskSample {
    float3 radiance;
    float transmittance;
    bool present;
};

public uint environmentLayer(WorldPush push) {
    return (push.environmentFlags >> ENVIRONMENT_LAYER_SHIFT) & ENVIRONMENT_LAYER_MASK;
}

// World orientation -> the fixed Kerr observer frame used by GenerateEndEnvironment. +Z points at the
// hole and +Y is projected spin-up. Gram-Schmidt keeps resource-pack-authored axes exactly orthogonal.
public float3 environmentCanonicalDirection(WorldPush push, float3 worldDirection) {
    float3 forward = normalize(push.lightDir.xyz);
    float3 up = normalize(push.celestial.xyz - forward * dot(push.celestial.xyz, forward));
    float3 right = normalize(cross(up, forward));
    return normalize(float3(dot(worldDirection, right), dot(worldDirection, up),
            dot(worldDirection, forward)));
}

public float2 environmentEquirectUv(float3 direction) {
    float3 d = normalize(direction);
    return float2(atan2(d.x, d.z) * (0.5 / ENV_PI) + 0.5,
            acos(clamp(d.y, -1.0, 1.0)) / ENV_PI);
}

float3 environmentRotate(float3 value, float3 axis, float angle) {
    float sine = sin(angle);
    float cosine = cos(angle);
    return value * cosine + cross(axis, value) * sine
            + axis * dot(axis, value) * (1.0 - cosine);
}

float4 environmentTransferSample(WorldPush push, float3 worldDirection) {
    float3 canonical = environmentCanonicalDirection(push, worldDirection);
    return environmentTransfer.SampleLevel(
            float3(environmentEquirectUv(canonical), float(environmentLayer(push))), 0.0);
}

void environmentDiskPath(WorldPush push, float3 worldDirection, out float4 entry, out float4 exit) {
    float3 canonical = environmentCanonicalDirection(push, worldDirection);
    float3 uvLayer = float3(environmentEquirectUv(canonical), float(environmentLayer(push)));
    entry = environmentDiskEntry.SampleLevel(uvLayer, 0.0);
    exit = environmentDiskExit.SampleLevel(uvLayer, 0.0);
}

uint environmentHash(int3 cell) {
    uint h = uint(cell.x) * 0x8da6b343u;
    h ^= uint(cell.y) * 0xd8163841u;
    h ^= uint(cell.z) * 0xcb1ab31fu;
    h ^= h >> 16u;
    h *= 0x7feb352du;
    h ^= h >> 15u;
    h *= 0x846ca68bu;
    return h ^ (h >> 16u);
}

float environmentHash01(int3 cell) {
    return float(environmentHash(cell) >> 8u) * (1.0 / 16777215.0);
}

float environmentValueNoise(float3 position) {
    int3 cell = int3(floor(position));
    float3 f = frac(position);
    f = f * f * (3.0 - 2.0 * f);
    float n000 = environmentHash01(cell);
    float n100 = environmentHash01(cell + int3(1, 0, 0));
    float n010 = environmentHash01(cell + int3(0, 1, 0));
    float n110 = environmentHash01(cell + int3(1, 1, 0));
    float n001 = environmentHash01(cell + int3(0, 0, 1));
    float n101 = environmentHash01(cell + int3(1, 0, 1));
    float n011 = environmentHash01(cell + int3(0, 1, 1));
    float n111 = environmentHash01(cell + int3(1, 1, 1));
    float x00 = lerp(n000, n100, f.x);
    float x10 = lerp(n010, n110, f.x);
    float x01 = lerp(n001, n101, f.x);
    float x11 = lerp(n011, n111, f.x);
    return lerp(lerp(x00, x10, f.y), lerp(x01, x11, f.y), f.z);
}

float environmentKerrRadius(float3 position) {
    float a2 = ENV_KERR_SPIN * ENV_KERR_SPIN;
    float radius2 = dot(position, position);
    float q = radius2 - a2;
    float discriminant = sqrt(max(q * q + 4.0 * a2 * position.z * position.z, 0.0));
    return sqrt(max(0.5 * (q + discriminant), 0.0));
}

float environmentDiskStructure(float3 position, float radius, float phase) {
    float phi = atan2(radius * position.y - ENV_KERR_SPIN * position.x,
            radius * position.x + ENV_KERR_SPIN * position.y);
    float latitude = asin(clamp(position.z / max(length(position), 1.0e-5), -1.0, 1.0));
    float product = 1.0;
    // Five scales and a base frequency of 0.8 are the approved visual starting point. Counter-moving
    // layers prevent the whole field from reading as one rigidly rotated texture.
    [unroll]
    for (uint octave = 0u; octave < 5u; ++octave) {
        float frequency = 0.8 * exp2(float(octave));
        float direction = (octave & 1u) == 0u ? 1.0 : -0.63;
        float angular = phi * 2.0 + direction * phase * (0.34 + 0.11 * float(octave));
        float3 coordinate = float3(radius, angular, latitude * 4.0) * frequency;
        coordinate += float3(13.1, -7.7, 19.3) * float(octave);
        product *= environmentValueNoise(coordinate);
    }
    return clamp(product, 0.0, 1.0);
}

float environmentDiskFlux(float radius) {
    // A bounded Newtonian thin-disk profile supplies the same inner-zero/radial ordering as the offline
    // NT curve without shipping a table into this hot path. 17.65 normalises its analytical peak near 1.
    float ratio = ENV_DISK_INNER_R / max(radius, ENV_DISK_INNER_R);
    return saturate(17.65 * ratio * ratio * ratio * max(1.0 - sqrt(ratio), 0.0));
}

float environmentDiskRedshift(float radius, float energy, float lambda) {
    float r32 = pow(max(radius, ENV_DISK_INNER_R + 1.0e-4), 1.5);
    float omega = 1.0 / (r32 + ENV_KERR_SPIN);
    float root = sqrt(max(radius * radius * radius - 3.0 * radius * radius
            + 2.0 * ENV_KERR_SPIN * r32, 1.0e-8));
    float uT = (r32 + ENV_KERR_SPIN) / root;
    float emitterEnergy = energy * uT * (1.0 - omega * lambda);
    return clamp(1.0 / max(abs(emitterEnergy), 1.0e-5), 0.08, 3.0);
}

float3 environmentDiskColour(float radius, float outerRadius, float structure) {
    float radial = saturate((radius - ENV_DISK_INNER_R)
            / max(outerRadius - ENV_DISK_INNER_R, 1.0e-4));
    float heat = saturate(1.0 - radial + 0.16 * (pow(max(structure, 1.0e-6), 0.2) - 0.5));
    float3 ember = float3(0.64, 0.035, 0.006);
    float3 orange = float3(1.0, 0.28, 0.035);
    float3 hot = float3(1.0, 0.82, 0.48);
    float3 colour = heat < 0.55
            ? lerp(ember, orange, smoothstep(0.0, 0.55, heat))
            : lerp(orange, hot, smoothstep(0.55, 1.0, heat));
    return colour / max(dot(colour, float3(0.2126, 0.7152, 0.0722)), 1.0e-4);
}

EnvironmentDiskSample environmentDiskSample(WorldPush push, float3 worldDirection) {
    EnvironmentDiskSample result;
    result.radiance = float3(0.0, 0.0, 0.0);
    result.transmittance = 1.0;
    result.present = false;

    float4 entry;
    float4 exit;
    environmentDiskPath(push, worldDirection, entry, exit);
    if (!(entry.w > 0.0)) return result;

    float outerRadius = clamp(push.lightRadiance.w, 4.0, ENV_DISK_MAX_OUTER_R);
    float thicknessScale = clamp(push.moonDir.w, 0.25, 2.0);
    float halfHeight = ENV_DISK_BASE_HALF_HEIGHT * thicknessScale;
    float3 chord = exit.xyz - entry.xyz;
    float segmentLength = length(chord) / float(ENV_DISK_STEPS);
    if (!(segmentLength > 0.0)) return result;

    float phase = push.sunDir.w;
    [unroll]
    for (uint step = 0u; step < ENV_DISK_STEPS; ++step) {
        float t = (float(step) + 0.5) / float(ENV_DISK_STEPS);
        float3 position = lerp(entry.xyz, exit.xyz, t);
        float radius = environmentKerrRadius(position);
        if (radius < ENV_DISK_INNER_R || radius > outerRadius || abs(position.z) >= halfHeight) continue;

        float radialEdge = saturate(1.0 - radius / outerRadius);
        float vertical = pow(saturate(1.0 - abs(position.z) / halfHeight), 2.0);
        float inner = smoothstep(ENV_DISK_INNER_R, ENV_DISK_INNER_R * 1.1, radius);
        float structure = environmentDiskStructure(position, radius, phase);
        float density = radialEdge * vertical * inner
                * (16000.0 / max(radius * radius * radius * radius, 1.0)) * structure;
        if (density <= 1.0e-6) continue;

        float opticalDepth = min(20.0, 0.42 * density * segmentLength);
        float segmentT = exp(-opticalDepth);
        float g = environmentDiskRedshift(radius, entry.w, exit.w);
        float rawPower = environmentDiskFlux(radius) * pow(g, 4.0);
        float mappedPower = rawPower / (0.55 + rawPower);
        float authoredStructure = saturate(pow(max(structure, 1.0e-6), 0.2) * 1.35);
        float sourceLuminance = 1.65 * mappedPower * lerp(0.28, 1.12, authoredStructure);
        float3 source = environmentDiskColour(radius, outerRadius, structure) * sourceLuminance;
        result.radiance += result.transmittance * (1.0 - segmentT) * source;
        result.transmittance *= segmentT;
        result.present = true;
        if (result.transmittance < 1.0e-4) break;
    }
    return result;
}

public float3 environmentDiskRadiance(WorldPush push, float3 worldDirection, float diskScale) {
    return environmentDiskSample(push, worldDirection).radiance * max(diskScale, 0.0);
}

public EnvironmentSample sampleEnvironment(WorldPush push, float3 worldDirection,
                                             float lod, float diskScale) {
    EnvironmentSample result;
    result.radiance = float3(0.0, 0.0, 0.0);
    result.diskRadiance = float3(0.0, 0.0, 0.0);
    result.disk = false;
    float4 transfer = environmentTransferSample(push, worldDirection);
    EnvironmentDiskSample disk = environmentDiskSample(push, worldDirection);
    result.diskRadiance = disk.radiance * max(diskScale, 0.0);
    result.disk = disk.present && any(result.diskRadiance > 1.0e-6);
    result.radiance = result.diskRadiance;
    if (transfer.w < -0.5) {
        // Rotate only the escaped background around the true Kerr spin axis. Hole, disk and transfer stay
        // fixed; direct visibility, reflections and indirect misses all pass through this same lookup.
        float3 bentDirection = environmentRotate(normalize(transfer.xyz), ENV_SPIN_AXIS, push.celestial.w);
        float3 background = environmentRadiance.SampleLevel(
                float3(environmentEquirectUv(bentDirection), float(environmentLayer(push))),
                clamp(lod, 0.0, 12.0)).rgb * max(push.mediumSkyRadiance.w, 0.0);
        result.radiance += max(disk.transmittance, 0.0) * background;
    }
    return result;
}

public bool environmentLightSupportsDirection(WorldPush push, float3 direction) {
    float toward = dot(direction, push.lightDir.xyz);
    if (toward <= 1.0e-3 || push.lightDir.w <= 0.0) return false;
    float3 right;
    float3 up;
    squareLightFrame(push.lightDir.xyz, push.celestial.xyz, right, up);
    float3 tangentPoint = direction / toward;
    float extent = tan(push.lightDir.w);
    return max(abs(dot(tangentPoint, right)), abs(dot(tangentPoint, up))) < extent;
}

/** Directional NEE value: atmosphere returns E; environment returns the same dynamic Le / p(direction). */
public float3 sampleProviderCelestialIrradiance(WorldPush push, float3 undyedValue,
                                                 float3 sampledDirection) {
    if (push.skyProvider == SKY_PROVIDER_ATMOSPHERE) {
        return sampleCelestialIrradiance(undyedValue, sampledDirection);
    }
    if (push.skyProvider == SKY_PROVIDER_ENVIRONMENT && push.lightDir.w > 0.0) {
        float3 le = environmentDiskRadiance(push, sampledDirection, undyedValue.x);
        float pdf = squareLightDirectionalPdf(
                push.lightDir.xyz, push.lightDir.w, sampledDirection);
        return le / max(pdf, 1.0e-20);
    }
    return float3(0.0, 0.0, 0.0);
}
