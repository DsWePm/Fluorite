// Included after path_reuse_dot.slang. Preserve the source ray's FP32 direction rather than
// recovering it by subtracting two rounded world positions. These operations also serve shifted
// receiver domains; there is no special branch for a self-connection.
float3 reuseConnectionSubtract(float3 a, float3 b) {
    return spirv_asm {
        %r : $$float3 = OpFSub $a $b;
        OpDecorate %r NoContraction;
        result : $$float3 = OpCopyObject %r
    };
}

float3 reuseConnectionDivide(float3 a, float value) {
    float3 b = float3(value, value, value);
    return spirv_asm {
        %r : $$float3 = OpFDiv $a $b;
        OpDecorate %r NoContraction;
        result : $$float3 = OpCopyObject %r
    };
}

float3 reuseConnectionMultiply(float3 a, float value) {
    float3 b = float3(value, value, value);
    return spirv_asm {
        %r : $$float3 = OpFMul $a $b;
        OpDecorate %r NoContraction;
        result : $$float3 = OpCopyObject %r
    };
}

bool reuseConnectionDirection(float3 sourceOrigin, float3 sourceDirection, float3 receiverOrigin,
                               float3 point, out float3 direction, out float rayDistance) {
    direction = float3(0.0, 0.0, 0.0);
    rayDistance = 0.0;
    float sourceSq = reuseOrderedNdot(sourceDirection, sourceDirection);
    if (!(sourceSq > 0.0) || !isfinite(sourceSq)) return false;
    float sourceT = reuseOrderedNdot(reuseConnectionSubtract(point, sourceOrigin), sourceDirection) / sourceSq;
    if (!(sourceT > 0.0) || !isfinite(sourceT)) return false;
    float3 shifted = reuseConnectionSubtract(sourceDirection,
            reuseConnectionDivide(reuseConnectionSubtract(receiverOrigin, sourceOrigin), sourceT));
    float shiftedSq = reuseOrderedNdot(shifted, shifted);
    if (!(shiftedSq > 0.0) || !isfinite(shiftedSq)) return false;
    // In real arithmetic this is the usual fixed-endpoint direction, retaining the source ray's
    // almost-unit norm. At equal origins the original direction survives bit for bit.
    direction = reuseConnectionMultiply(shifted, sqrt(sourceSq / shiftedSq));
    float directionSq = reuseOrderedNdot(direction, direction);
    rayDistance = reuseOrderedNdot(reuseConnectionSubtract(point, receiverOrigin), direction) / directionSq;
    return all(isfinite(direction)) && rayDistance > 0.0 && isfinite(rayDistance);
}
