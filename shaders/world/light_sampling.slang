// Stage-independent emitter selection and decoding. Raygen and compute froxels both consume the same
// packed Light records, alias tables and local grid; only their target functions and shadow traversal
// differ. Keeping this module free of descriptor bindings lets either stage provide its own BDA bundle.

import world_common;

float emitterLuminance(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
}

public struct LightBufferAddresses {
    public uint64_t light;
    public uint64_t globalAlias;
    public uint64_t localAlias;
    public uint64_t gridCell;
    public uint64_t gridSpan;
};

public struct EmitterSample {
    public float3 pos;
    public float3 normal;
    public float3 radiance;
    public float area;
    public float sourcePdf;
};

float emitterUnpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissaMask = (1u << mantissaBits) - 1u;
    uint mantissa = bits & mantissaMask;
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) {
        return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    }
    return (1.0 + float(mantissa) / float(1u << mantissaBits))
            * exp2(float(int(exponent) - 15));
}

public float3 emitterRadiance(Light light) {
    uint packed = light.le;
    return float3(emitterUnpackUnsignedFloat(packed & 0x7ffu, 6u),
            emitterUnpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            emitterUnpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

public float3 emitterHalfU(Light light) {
    return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x);
}

public float3 emitterHalfV(Light light) {
    return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz));
}

float3 emitterCrossUV(Light light) {
    return cross(emitterHalfU(light), emitterHalfV(light));
}

public float emitterArea(Light light) {
    return 4.0 * length(emitterCrossUV(light));
}

public float3 emitterNormal(Light light) {
    float3 normal = normalize(emitterCrossUV(light));
    return (light.section & LIGHT_NORMAL_FLIP) != 0u ? -normal : normal;
}

int3 emitterSectionCoord(Light light) {
    uint packed = light.section;
    return int3(int(packed & 0x3ffu), int((packed >> 10u) & 0x3ffu),
            int((packed >> 20u) & 0x3ffu));
}

void emitterSelectGlobal(WorldPush wp, LightBufferAddresses addresses,
                         inout uint seed, out uint lightIndex) {
    float aliasSample = rndf(seed) * float(wp.lightCount);
    uint column = min(uint(aliasSample), wp.lightCount - 1u);
    if (addresses.globalAlias != 0) {
        LightAlias a = ConstPtr<LightAlias>(addresses.globalAlias)[column];
        lightIndex = aliasSample - float(column) < a.accept ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

bool emitterFindGridCell(WorldPush wp, LightBufferAddresses addresses, float3 p,
                         out LightGridCell cell, out int3 cellCoord) {
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    if (addresses.gridCell == 0 || addresses.gridSpan == 0) return false;
    int3 coord = int3(floor((p - wp.lightGridOrigin.xyz) / wp.lightGridOrigin.w));
    if (coord.x < 0 || coord.y < 0 || coord.z < 0
            || coord.x >= wp.lightGridDims.x || coord.y >= wp.lightGridDims.y
            || coord.z >= wp.lightGridDims.z) return false;
    uint linear = (uint(coord.z) * uint(wp.lightGridDims.y) + uint(coord.y))
            * uint(wp.lightGridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(addresses.gridCell)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

void emitterSelectSection(LightBufferAddresses addresses, uint firstLight, uint lightCount,
                          inout uint seed, out uint lightIndex) {
    float aliasSample = rndf(seed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(addresses.localAlias)[firstLight + column];
    uint localIndex = aliasSample - float(column) < alias.accept ? column : alias.aliasIndex;
    lightIndex = firstLight + localIndex;
}

void emitterSelectGrid(LightBufferAddresses addresses, LightGridCell cell,
                       inout uint seed, out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(addresses.gridSpan);
    float aliasSample = rndf(seed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint count = self ? (span.packedLightCounts & 0xffffu) : (span.packedLightCounts >> 16u);
    emitterSelectSection(addresses, firstLight, count, seed, lightIndex);
}

float emitterProposalPdf(WorldPush wp, LightBufferAddresses addresses, Light light, float3 le,
                         LightGridCell cell, int3 cellCoord, bool local) {
    float power = max(0.0, emitterArea(light) * emitterLuminance(le));
    float globalPdf = addresses.globalAlias != 0
            ? power * wp.lightRebase.w : 1.0 / float(wp.lightCount);
    if (!local) {
        return globalPdf;
    }
    // The selected proposal is local-only when a populated cell exists. Do not quietly substitute the
    // global PDF for a zero-power or out-of-neighbourhood local sample: that would change the estimator
    // moved out of lighting.slang and make froxels cast a pointless shadow ray for a rejected sample.
    if (power <= 0.0) {
        return 0.0;
    }
    int3 delta = emitterSectionCoord(light) - cellCoord;
    if (any(abs(delta) > int3(2, 2, 2))) {
        return 0.0;
    }
    float3 deltaF = float3(delta);
    return power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
}

/**
 * One nearby-emitter proposal for a volume event. Grid selection is preferred when available and the
 * global alias is the full-support fallback. sourcePdf is the discrete light-selection probability;
 * the caller applies the rectangle's uniform 1/area density in its own geometry term.
 */
public bool sampleVolumeEmitter(WorldPush wp, LightBufferAddresses addresses, float3 receiver,
                                inout uint seed, inout uint proposalSeed,
                                out EmitterSample sample) {
    sample.pos = float3(0.0, 0.0, 0.0);
    sample.normal = float3(0.0, 1.0, 0.0);
    sample.radiance = float3(0.0, 0.0, 0.0);
    sample.area = 0.0;
    sample.sourcePdf = 0.0;
    if (addresses.light == 0 || wp.lightCount == 0u || wp.risCandidates == 0u) {
        return false;
    }

    LightGridCell cell;
    int3 cellCoord;
    float3 gridLookup = receiver;
    if (addresses.gridCell != 0) {
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * wp.lightGridOrigin.w;
    }
    bool local = emitterFindGridCell(wp, addresses, gridLookup, cell, cellCoord);
    uint lightIndex;
    if (local) {
        emitterSelectGrid(addresses, cell, proposalSeed, lightIndex);
    } else {
        emitterSelectGlobal(wp, addresses, proposalSeed, lightIndex);
    }

    Light light = ConstPtr<Light>(addresses.light)[lightIndex];
    float s = rndf(seed) * 2.0 - 1.0;
    float t = rndf(seed) * 2.0 - 1.0;
    sample.pos = light.pos + wp.lightRebase.xyz
            + s * emitterHalfU(light) + t * emitterHalfV(light);
    sample.normal = emitterNormal(light);
    sample.radiance = emitterRadiance(light);
    sample.area = emitterArea(light);
    sample.sourcePdf = emitterProposalPdf(wp, addresses, light, sample.radiance,
                                          cell, cellCoord, local);
    return sample.sourcePdf > 0.0 && sample.area > 0.0;
}
