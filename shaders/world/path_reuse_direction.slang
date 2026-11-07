// Reuse-only direction arithmetic. Same VNDF formula and RNG as math.slang, with explicit FP32
// rounding at each algebraic boundary so source and prefix replay cannot contract it differently.
// The published/native specialization retains its exact instruction stream. No record/ray growth.
import world_common;
float replayMul(float a,float b) {
    return spirv_asm {
        %v : $$float = OpFMul $a $b;
        OpDecorate %v NoContraction;
        result : $$float = OpCopyObject %v
    };
}
float3 replayMul(float3 a,float b) {
    return spirv_asm {
        %v : $$float3 = OpVectorTimesScalar $a $b;
        OpDecorate %v NoContraction;
        result : $$float3 = OpCopyObject %v
    };
}
float3 replayMul(float a,float3 b) { return replayMul(b,a); }
float replayAdd(float a,float b) {
    return spirv_asm {
        %v : $$float = OpFAdd $a $b;
        OpDecorate %v NoContraction;
        result : $$float = OpCopyObject %v
    };
}
float3 replayAdd(float3 a,float3 b) {
    return spirv_asm {
        %v : $$float3 = OpFAdd $a $b;
        OpDecorate %v NoContraction;
        result : $$float3 = OpCopyObject %v
    };
}
float replaySub(float a,float b) {
    return spirv_asm {
        %v : $$float = OpFSub $a $b;
        OpDecorate %v NoContraction;
        result : $$float = OpCopyObject %v
    };
}
float3 replaySub(float3 a,float3 b) {
    return spirv_asm {
        %v : $$float3 = OpFSub $a $b;
        OpDecorate %v NoContraction;
        result : $$float3 = OpCopyObject %v
    };
}
// Use the same fixed reduction already shared by selected-lobe sampling/evaluation.
#include "path_reuse_dot.slang"
float replayDot(float3 a,float3 b) { return reuseOrderedNdot(a,b); }

float3 replayCross(float3 a,float3 b) {
    return float3(replaySub(replayMul(a.y,b.z),replayMul(a.z,b.y)),
            replaySub(replayMul(a.z,b.x),replayMul(a.x,b.z)),
            replaySub(replayMul(a.x,b.y),replayMul(a.y,b.x)));
}
float3 replayNormalize(float3 a) { return replayMul(a,rsqrt(replayDot(a,a))); }

// Decode the original queue word identically at initial tracing and primary-domain restoration.
// Native octDecode's normalize was reduced differently in the two live raygen contexts. Keep the
// same octahedral formula and 16-bit coordinates, with the existing reuse-only rounding controls.
public float3 reuseDecodePrimaryDirection(uint packed) {
    float x = float(packed & 0xffffu) / 65535.0;
    float y = float(packed >> 16u) / 65535.0;
    x = replaySub(replayMul(x, 2.0), 1.0);
    y = replaySub(replayMul(y, 2.0), 1.0);
    float z = replaySub(replaySub(1.0, abs(x)), abs(y));
    float3 n = float3(x, y, z);
    if (z < 0.0) {
        n.x = replayMul(replaySub(1.0, abs(y)), x >= 0.0 ? 1.0 : -1.0);
        n.y = replayMul(replaySub(1.0, abs(x)), y >= 0.0 ? 1.0 : -1.0);
    }
    return replayNormalize(n);
}

// The same material must define the same continuation mixture in every inlined RT context.
// Native luminance/division lowering moved the captured threshold by one ulp, across its draw.
// Keep the published probability formula, with fixed FP32 reductions and arithmetic boundaries.
public float reuseSpecularProbability(float3 f0, float3 diffuse, bool exactSpecular) {
    float specularEnergy = replayDot(f0, float3(0.2126, 0.7152, 0.0722));
    float diffuseEnergy = replayDot(diffuse, float3(0.2126, 0.7152, 0.0722));
    if (exactSpecular && diffuseEnergy <= 1.0e-6) return 1.0;
    float denominator = replayAdd(replayAdd(specularEnergy, diffuseEnergy), 1.0e-4);
    float probability = spirv_asm {
        %v : $$float = OpFDiv $specularEnergy $denominator;
        OpDecorate %v NoContraction;
        result : $$float = OpCopyObject %v
    };
    return clamp(probability, 0.1, 0.9);
}

// The three intervals have widths pf, (1-pf)*ps and (1-pf)*(1-ps). Materialize a single
// FP32 CDF from the vertex mixture and compare its positive IEEE bit order, so source/replay
// use the same boundary arithmetic. No random draw here.
// Return 0 for film, 1 for base specular, 2 for the remaining base component.
public uint reuseContinuationInterval(float choice, float pf, float ps) {
    float baseWidth = max(replaySub(1.0, pf), 1.0e-6);
    float specularEnd = replayAdd(pf, replayMul(baseWidth, ps));
    uint sampleBits = asuint(choice) & 0x7fffffffu;
    if (sampleBits < (asuint(pf) & 0x7fffffffu)) return 0u;
    if (sampleBits < (asuint(specularEnd) & 0x7fffffffu)) return 1u;
    return 2u;
}

public float3 reuseReflect(float3 incident,float3 h) {
    return replaySub(incident,replayMul(h,replayMul(2.0,replayDot(incident,h))));
}
#define D_DOT(a,b) replayDot(a,b)
#define D_NORMALIZE(a) replayNormalize(a)
#define D_CROSS(a,b) replayCross(a,b)
#define D_MUL(a,b) replayMul(a,b)
#define D_ADD(a,b) replayAdd(a,b)
#define D_SUB(a,b) replaySub(a,b)
public float3 reuseSampleGGXVNDFAniso(float3 n,float3 t,float3 b,float3 ve,float ax,float ay,inout uint s) {
#include "direction_ggx_kernel.slang"
}
#undef D_DOT
#undef D_NORMALIZE
#undef D_CROSS
#undef D_MUL
#undef D_ADD
#undef D_SUB

public float3 reuseSampleGGXVNDF(float3 n,float3 ve,float alpha,inout uint s) {
    float3 t=normalize(abs(n.x) > 0.9 ? float3(0.0,1.0,0.0) : float3(1.0,0.0,0.0));
    float3 b=replayNormalize(replayCross(n,t));
    t=replayCross(b,n);
    return reuseSampleGGXVNDFAniso(n,t,b,ve,alpha,alpha,s);
}
