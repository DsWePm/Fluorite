// Reuse-only path-space parameters. A PDF here includes the selected lobe's discrete probability;
// it is not the continuation mixture density used by the published NEE estimator.
static const uint REUSE_LOBE_NONE = 0u;
static const uint REUSE_LOBE_DIFFUSE = 1u;
static const uint REUSE_LOBE_SPECULAR = 2u;
static const uint REUSE_LOBE_FILM = 3u;
static const uint REUSE_LOBE_DELTA = 4u;
static const uint REUSE_LOBE_WALK = 5u;
// User-approved R2 baseline (2026-10-08), subject to R6 calibration. GGX alpha stays linear.
static const float REUSE_MIN_ALPHA = 0.2;
static const float REUSE_FOOTPRINT_SCALE = 0.02;

struct ReuseContinuationSample {
    uint lobe;
    float alpha;
    float pdf;
    // Selected f*cos/pdf, independent of the caller's coloured prefix and external roulette.
    float3 weight;
};

ReuseContinuationSample emptyReuseContinuationSample() {
    ReuseContinuationSample sample;
    sample.lobe = REUSE_LOBE_NONE;
    sample.alpha = 0.0;
    sample.pdf = 0.0;
    sample.weight = float3(0.0, 0.0, 0.0);
    return sample;
}

bool reuseAnalyticContinuation(ReuseContinuationSample sample) {
    return (sample.lobe == REUSE_LOBE_DIFFUSE || sample.lobe == REUSE_LOBE_SPECULAR
            || sample.lobe == REUSE_LOBE_FILM) && sample.pdf > 0.0 && isfinite(sample.pdf);
}

// G(from -> to) uses the geometric normal at the destination, not the shading normal at the origin.
// distance and direction describe the ray actually sampled, including the tracer's surface offset.
float reuseRayGeometry(float3 direction, float distance, float3 destinationNormal) {
    if (!(distance > 0.0) || !isfinite(distance) || !all(isfinite(direction))
            || !all(isfinite(destinationNormal))) {
        return 0.0;
    }
    return max(0.0, dot(destinationNormal, -direction)) / (distance * distance);
}

// Parameterised Enhanced Eq. 5. Threshold policy is supplied by the caller, never inferred by squaring
// stored roughness. Diffuse x_k skips the inverse test (paper footnote 6). No epsilon/energy clamp.
bool reuseEventFootprintEligible(ReuseContinuationSample previous, float currentPdf, bool viewIndependent,
                            float3 edgeDirection, float edgeDistance,
                            float3 previousNormal, float3 reconNormal,
                            float primaryDistanceSq, float primaryCosine,
                            float alphaMin, float footprintScale, out float jacDenom) {
    jacDenom = 0.0;
    if (!reuseAnalyticContinuation(previous) || !(currentPdf > 0.0) || !isfinite(currentPdf)
            || !isfinite(previous.alpha) || previous.alpha < alphaMin
            || !(primaryDistanceSq > 0.0) || !isfinite(primaryDistanceSq)
            || !(primaryCosine > 0.0) || !isfinite(primaryCosine)
            || !(alphaMin >= 0.0) || !isfinite(alphaMin)
            || !(footprintScale > 0.0) || !isfinite(footprintScale)) {
        return false;
    }
    float forwardG = reuseRayGeometry(edgeDirection, edgeDistance, reconNormal);
    float forwardDensity = previous.pdf * forwardG;
    float primaryFootprint = (footprintScale / 100.0) * (4.0 * PI)
            * primaryDistanceSq / primaryCosine;
    float sourceDenominator = forwardDensity * currentPdf;
    if (!(forwardDensity > 0.0) || !isfinite(forwardDensity)
            || !(primaryFootprint > 0.0) || !isfinite(primaryFootprint)
            || !(sourceDenominator > 0.0) || !isfinite(sourceDenominator)
            || forwardDensity * primaryFootprint > 1.0) {
        return false;
    }
    if (!viewIndependent) {
        float reverseG = reuseRayGeometry(-edgeDirection, edgeDistance, previousNormal);
        float reverseDensity = currentPdf * reverseG;
        if (!(reverseDensity > 0.0) || !isfinite(reverseDensity)
                || reverseDensity * primaryFootprint > 1.0) {
            return false;
        }
    }
    jacDenom = sourceDenominator;
    return true;
}

// A continuation carries a sampled analytic lobe; local NEE and terminal events have lobe index zero.
// Their event density is independent of the incoming view, so only the forward footprint applies.
bool reuseFootprintEligible(ReuseContinuationSample previous, ReuseContinuationSample current,
                            float3 edgeDirection, float edgeDistance,
                            float3 previousNormal, float3 reconNormal,
                            float primaryDistanceSq, float primaryCosine,
                            float alphaMin, float footprintScale, out float jacDenom) {
    jacDenom = 0.0;
    if (!reuseAnalyticContinuation(current)) return false;
    return reuseEventFootprintEligible(previous, current.pdf, current.lobe == REUSE_LOBE_DIFFUSE,
            edgeDirection, edgeDistance, previousNormal, reconNormal, primaryDistanceSq, primaryCosine,
            alphaMin, footprintScale, jacDenom);
}
