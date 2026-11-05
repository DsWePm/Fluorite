// Diagnostic isolation: run both directions through the same compiled evaluator loop body.
// Two independently inlined calls may choose different floating-point contractions even for equal bits.
void reuseEvaluateDirectionPair(BsdfContext context, float pf, float ps, float walkShare, uint lobe,
        float3 originalDirection, float3 decodedDirection,
        out ReuseContinuationSample original, out ReuseContinuationSample decoded) {
    original = emptyReuseContinuationSample();
    decoded = emptyReuseContinuationSample();
    [loop]
    for (uint iteration = 0u; iteration < 2u; iteration++) {
        float3 direction = iteration == 0u ? originalDirection : decodedDirection;
        ReuseContinuationSample evaluated = reuseEvaluateContinuation(context, pf, ps, walkShare, lobe, direction);
        if (iteration == 0u) original = evaluated;
        else decoded = evaluated;
    }
}
