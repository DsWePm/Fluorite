// Reuse-only selected-lobe evaluation. No RNG, trace, roulette or mixture-PDF substitution.
// Evaluate the same f*cos/pdf as reuseSampleContinuation at a supplied direction/view. The reduced
// VNDF weights deliberately match the sampler, including the renderer's existing numerical guards.
ReuseContinuationSample reuseEvaluateContinuation(BsdfContext c, float pf, float ps, float walkSssShare,
                                                   uint lobe, float3 wi) {
    ReuseContinuationSample result = emptyReuseContinuationSample();
    float ndl = reuseOrderedNdot(c.n, wi);
    if (!all(isfinite(wi)) || !(ndl > 0.0) || !isfinite(ndl)
            || !isfinite(pf) || pf < 0.0 || pf > 1.0
            || !isfinite(ps) || ps < 0.0 || ps > 1.0
            || !isfinite(walkSssShare) || walkSssShare < 0.0 || walkSssShare > 1.0) {
        return result;
    }
    result.lobe = lobe;
    if (lobe == REUSE_LOBE_DIFFUSE) {
        float probability = (1.0 - pf) * (1.0 - ps) * (1.0 - walkSssShare);
        if (!(probability > 0.0)) {
            return emptyReuseContinuationSample();
        }
        result.alpha = 1.0;
        result.pdf = probability * ndl * INV_PI;
        result.weight = c.diffAlb * rainBaseTransmission(c, wi) / probability;
    } else if (lobe == REUSE_LOBE_SPECULAR || lobe == REUSE_LOBE_FILM) {
        float3 sum = c.v + wi;
        float sumSq = dot(sum, sum);
        if (!(sumSq > 0.0) || !isfinite(sumSq)) {
            return emptyReuseContinuationSample();
        }
        float3 h = sum * rsqrt(sumSq);
        float ndh = max(0.0, dot(c.n, h));
        if (lobe == REUSE_LOBE_SPECULAR) {
            float probability = (1.0 - pf) * ps;
            if (!(probability > 0.0) || !(c.alphaX > 0.0) || !(c.alphaY > 0.0)) {
                return emptyReuseContinuationSample();
            }
            result.alpha = c.rough;
            result.pdf = probability * ggxReflectPdfAniso(dot(h, c.t), dot(h, c.b), ndh,
                    c.ndv, c.alphaX, c.alphaY, c.g1v);
            result.weight = reuseFresnelSchlickDirections(c.v, wi, c.F0)
                    * ggxG1Aniso(dot(wi, c.t), dot(wi, c.b), ndl, c.alphaX, c.alphaY)
                    * rainBaseTransmission(c, wi) / probability;
        } else {
            if (!(pf > 0.0) || !(c.rain.filmAlpha > 0.0) || !(c.rain.film > 0.0)) {
                return emptyReuseContinuationSample();
            }
            float alpha = c.rain.filmAlpha;
            result.alpha = alpha;
            result.pdf = pf * ggxReflectPdf(ndh, c.ndv, alpha, ggxG1(c.ndv, alpha));
            result.weight = c.rain.film * reuseFresnelSchlickDirections(c.v, wi,
                    float3(WATER_FILM_F0, WATER_FILM_F0, WATER_FILM_F0)) * ggxG1(ndl, alpha) / pf;
        }
    } else {
        // A delta measure and the random walk have no finite directional PDF in this evaluator.
        return emptyReuseContinuationSample();
    }
    if (!reuseAnalyticContinuation(result) || !all(isfinite(result.weight))
            || any(result.weight < 0.0)) {
        return emptyReuseContinuationSample();
    }
    return result;
}

// Symmetric relative errors stay defined for zero channels, underflow and a dark receiver. These are
// diagnostic measurements only: no acceptance test, correction, clamp or change to the written record.
float reuseRelativeScalarError(float a, float b) {
    float scale = max(abs(a), abs(b));
    return scale > 0.0 ? abs(a - b) / scale : 0.0;
}

float reuseRelativeRgbError(float3 a, float3 b) {
    float3 magnitude = max(abs(a), abs(b));
    float scale = max(magnitude.r, max(magnitude.g, magnitude.b));
    float3 delta = abs(a - b);
    return scale > 0.0 ? max(delta.r, max(delta.g, delta.b)) / scale : 0.0;
}
