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
