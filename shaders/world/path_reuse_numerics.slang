// Replay must observe the source tracer's FP32 position rounding and materialized half payload.
// Keep these controls local: the source tracer and the switch-off pipelines retain their arithmetic.

float3 reuseReplayPosition(float3 origin, float3 direction, float distance) {
    // The pinned SDK ignores source-level precise. Explicit decorations survive inlining and keep
    // the multiply rounded before the add, as observed in the source tracer's captured positions.
    return spirv_asm {
        %product : $$float3 = OpVectorTimesScalar $direction $distance;
        OpDecorate %product NoContraction;
        %point : $$float3 = OpFAdd $origin %product;
        OpDecorate %point NoContraction;
        result : $$float3 = OpCopyObject %point
    };
}

float reusePayloadHalfScalar(float value) {
    // Round to binary16, ties to even, while keeping the result in FP32. Native conversion pairs can
    // be contracted away; OpQuantizeToF16 may flush half subnormals. Integer rounding preserves both.
    uint bits = asuint(value);
    uint sign = bits & 0x80000000u;
    uint magnitude = bits & 0x7fffffffu;
    if (magnitude >= 0x7f800000u) {
        return asfloat(sign | (magnitude > 0x7f800000u ? 0x7fc00000u : 0x7f800000u));
    }
    if (magnitude < 0x38800000u) {
        if (magnitude <= 0x33000000u) return asfloat(sign);
        uint shift = 126u - (magnitude >> 23u);
        uint significand = (magnitude & 0x7fffffu) | 0x800000u;
        uint rounded = significand >> shift;
        uint remainder = significand & ((1u << shift) - 1u);
        uint midpoint = 1u << (shift - 1u);
        rounded += (remainder > midpoint || (remainder == midpoint && (rounded & 1u) != 0u)) ? 1u : 0u;
        // Every half subnormal is an exact, normal FP32 integer multiple of 2^-24.
        return asfloat(sign | asuint(float(rounded) * 5.9604644775390625e-8));
    }
    uint rounded = (magnitude + 0xfffu + ((magnitude >> 13u) & 1u)) & 0xffffe000u;
    return asfloat(sign | (rounded >= 0x47800000u ? 0x7f800000u : rounded));
}

float3 reuseReplayPayloadLane(float3 value) {
    return float3(reusePayloadHalfScalar(value.x), reusePayloadHalfScalar(value.y),
                  reusePayloadHalfScalar(value.z));
}

void reuseCanonicalizeReplayPayload() {
    payload.albedo = half3(reuseReplayPayloadLane(float3(payload.albedo)));
    payload.normal = half3(reuseReplayPayloadLane(float3(payload.normal)));
    payload.f0 = half3(reuseReplayPayloadLane(float3(payload.f0)));
}
