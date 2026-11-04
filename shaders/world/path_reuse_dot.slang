// R2b-14, user-approved: keep the selected-lobe hemisphere and Smith inputs reproducible at grazing.
// OpDot may reduce in different orders at the sampler and evaluator. Round x first, then FMA y/z.
// This controls reuse-only FP32 rounding, not the BSDF, authored alpha, RNG or direction formula.
float reuseOrderedNdot(float3 n, float3 wi) {
    float nx = n.x;
    float wx = wi.x;
    float first = spirv_asm {
        %first : $$float = OpFMul $nx $wx;
        OpDecorate %first NoContraction;
        result : $$float = OpCopyObject %first
    };
    return fma(n.z, wi.z, fma(n.y, wi.y, first));
}
