// Pure radiance-source and height-medium maths shared by the ray-tracing segment integrator and the
// aerial-perspective froxel. No bindings, globals or ray operations: both shader stages can import it
// without inheriting each other's descriptor layouts.

public static const float VOLUME_PI = 3.14159265359;
public static const int VOLUME_FOG_MARCH_LIMIT = 31;

/** Heterogeneous work exists only when both authored fog and its redistribution are non-zero. */
public bool volumeFogNoiseOn(float4 fogParams, float4 fogAmbient) {
    return fogParams.x > 0.0 && fogAmbient.x > 0.0;
}

/**
 * Ambient-profile density at one point, before the authored spectral extinction.
 * A non-positive height scale is the explicit homogeneous-profile encoding; existing height presets
 * require a positive scale, so this does not reinterpret any formerly valid value.
 */
public float volumeFogHeightDensity(float4 fogParams, float3 p) {
    if (fogParams.y <= 0.0) {
        return fogParams.x;
    }
    float H = max(fogParams.y, 1.0e-3);
    return fogParams.x * exp(-max(p.y - fogParams.z, 0.0) / H);
}

/** Stable world-space coordinate of the packed fog field. The sampler itself supplies periodic wrap. */
public float3 volumeFogNoiseUv(float4 fogNoiseOrigin, float3 p) {
    return (p + fogNoiseOrigin.xyz) / max(fogNoiseOrigin.w, 1.0);
}

/** D66's two anti-alias weights, shared by transport and debug view 25. */
public float2 volumeFogNoiseResolved(float fieldScale, float footprint) {
    float baseSize = max(fieldScale * 0.25, 1.0);       // four broad cells per tile
    float detailSize = max(fieldScale * 0.0625, 0.25); // sixteen detail cells per tile
    return float2(1.0 - smoothstep(baseSize * 0.5, baseSize, footprint),
                  1.0 - smoothstep(detailSize * 0.5, detailSize, footprint));
}

/**
 * E[exp(x*u)] for u uniform on [-1,1]. The normalisation the multiplier below divides by.
 *
 * <p>The series near zero is not an optimisation, it is the removable singularity: sinh(x)/x is 0/0 at
 * x = 0 and the value there has to be exactly 1, because that is the case where the structure has faded
 * out entirely and the density must come back to the mean it was authored at.
 */
float volumeExpMean(float x) {
    float a = abs(x);
    if (a < 1.0e-3) {
        return 1.0 + a * a * (1.0 / 6.0);
    }
    return sinh(a) / a;
}

/**
 * Combine an already decoded sample so diagnostics cannot drift from the transport answer.
 *
 * <p><b>WHY THE MULTIPLIER IS NO LONGER BOUNDED AT TWO.</b> The remap this replaces was odd and bounded:
 * f(-x) = -f(x) and f(+-1) = +-1, giving 1 + shaped over 0..2 with the mean pinned at one. That pinning
 * was exact and it was exact for a structural reason -- the baked field is PAIRED, every sample having a
 * partner with exactly the opposite variation, so the field mean of any f is the mean of
 * (f(v) + f(-v)) / 2, and holding that at 1 for every v forces f(v) + f(-v) = 2. Which forces f <= 2.
 *
 * <p>So two was not a tuning ceiling that the contrast slider had failed to reach. It was the ceiling,
 * and raising the slider from 1 to 4 only pushed the distribution toward a binary 0-or-2 field. A fog
 * bank that reads as a wall with clear pockets torn through it needs local multipliers of five and ten,
 * and no exactly mean-preserving odd remap can produce one.
 *
 * <p><b>WHAT REPLACED IT.</b> exp(contrast * variation), which is the lognormal shape real fog and cloud
 * liquid-water content actually follow, divided by the mean of that exponential. Three properties, and
 * the third is why the division tracks the fade instead of being a constant:
 *
 * <ul>
 *   <li>The peak is exp(contrast) and that is a hard bound, because |variation| <= 1 by construction --
 *       the resolved weights sum to at most one and each decoded channel is within +-1.</li>
 *   <li>At contrast 0 this is exactly 1, so switching the structure off is still exactly height fog.</li>
 *   <li>Where the footprint has faded a frequency away, its resolved weight goes to zero, and BOTH the
 *       exponent and the normalisation go to one with it. Distant fog therefore returns to its authored
 *       mean density however high the contrast is set, which is the property the fade exists for and the
 *       one a constant normalisation would have destroyed -- the horizon would have shifted density
 *       every time the structure slider moved.</li>
 * </ul>
 *
 * <p><b>WHAT IS APPROXIMATE, NAMED RATHER THAN DISCOVERED LATER.</b> The mean is now preserved only in
 * the aggregate, not pairwise, and the aggregate is computed against a UNIFORM reference distribution
 * for the decoded channels. The real field is more saturated than uniform: the bake multiplies the raw
 * paired difference by 3.8 and clamps, deliberately, because the raw differences occupied too little of
 * their range. A more saturated field has a larger true E[exp], so dividing by the uniform figure
 * under-normalises and the mean density rises somewhat with contrast rather than holding.
 *
 * <p>That residual is monotone, bounded by cosh(contrast)/(sinh(contrast)/contrast), and points the same
 * way the slider is being pushed -- thicker. The fog density knob is the intended trim. Replacing the
 * reference with a figure measured off the baked volume is the exact fix and needs a reduction pass the
 * shader has no way to ask for; it is worth building only if the drift is visible in game.
 */
public float volumeFogNoiseResolvedMultiplier(float2 signedNoise, float2 resolved,
                                               float4 fogAmbient) {
    float contrast = max(fogAmbient.x, 0.0);
    if (contrast <= 0.0) {
        return 1.0;
    }
    float detailShare = clamp(fogAmbient.y, 0.0, 1.0);
    // Split rather than folded, because each channel needs its own normalisation: they are independent
    // draws, so the mean of their product is the product of their means.
    float baseWeight = resolved.x * (1.0 - detailShare);
    float detailWeight = resolved.y * detailShare;
    float variation = signedNoise.x * baseWeight + signedNoise.y * detailWeight;
    return exp(contrast * variation)
            / (volumeExpMean(contrast * baseWeight) * volumeExpMean(contrast * detailWeight));
}

/**
 * Decode the packed zero-mean base/detail fields into a mean-one density multiplier.
 *
 * The footprint fades frequencies a numerical step cannot resolve back to their mean (zero variation),
 * instead of aliasing them into camera motion. Because both signed channels and every faded term have
 * mean zero, time/weather still control the expected physical density alone.
 */
public float volumeFogNoiseMultiplier(float2 packedNoise, float4 fogAmbient,
                                      float fieldScale, float footprint) {
    float2 signedNoise = packedNoise * 2.0 - 1.0;
    return volumeFogNoiseResolvedMultiplier(
            signedNoise, volumeFogNoiseResolved(fieldScale, footprint), fogAmbient);
}

/** Sanitised user-approved hard cap shared by every numerical fog interval. */
public int volumeFogMarchCap(float4 fogAmbient) {
    return clamp(int(fogAmbient.z + 0.5), 1, VOLUME_FOG_MARCH_LIMIT);
}

/** Samples wanted for an interval after the user-approved hard cap is applied. */
public int volumeFogMarchSteps(float span, float4 fogAmbient, float fieldScale) {
    if (span <= 0.0 || fogAmbient.x <= 0.0) {
        return 0;
    }
    int cap = volumeFogMarchCap(fogAmbient);
    float detailNyquist = max(fieldScale * (1.0 / 32.0), 0.25);
    return clamp(int(ceil(span / detailNyquist)), 1, cap);
}

/** Normalised Henyey-Greenstein phase function. */
public float volumeHg(float cosT, float g) {
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * cosT;
    return (1.0 - g2) / (4.0 * VOLUME_PI * pow(max(d, 0.0), 1.5));
}

/** Height profile integral, saturated below the authored base height. */
public float volumeHeightProfileIntegral(float hA, float hB, float len, float H) {
    if (len <= 0.0) {
        return 0.0;
    }
    float dh = hB - hA;
    if (abs(dh) < 1.0e-5) {
        return exp(-max(hA, 0.0) / H) * len;
    }
    float slope = dh / len;
    if (hA >= 0.0 && hB >= 0.0) {
        return (H / slope) * (exp(-hA / H) - exp(-hB / H));
    }
    if (hA <= 0.0 && hB <= 0.0) {
        return len;
    }
    float tCross = -hA / slope;
    if (hA < 0.0) {
        return tCross + (H / slope) * (1.0 - exp(-hB / H));
    }
    return (H / slope) * (exp(-hA / H) - 1.0) + (len - tCross);
}

/** Optical depth before spectral extinction over an arbitrary ambient-field interval. */
public float volumeAmbientOpticalDepth(float4 fogParams, float3 ro, float3 rd,
                                       float start, float end) {
    if (fogParams.x <= 0.0 || end <= start) {
        return 0.0;
    }
    if (fogParams.y <= 0.0) {
        return fogParams.x * (end - start);
    }
    float H = max(fogParams.y, 1.0e-3);
    float hA = ro.y + rd.y * start - fogParams.z;
    float hB = ro.y + rd.y * end - fogParams.z;
    return fogParams.x * volumeHeightProfileIntegral(hA, hB, end - start, H);
}

/** Ambient transmittance with the same near/cull window used by the segment estimator. */
public float3 volumeAmbientTransmittance(float4 fogParams, float4 fogExtinction, float3 sigma,
                                         float3 ro, float3 rd, float dist) {
    float start = min(fogExtinction.w, dist);
    float end = min(dist, fogParams.w);
    float tau = volumeAmbientOpticalDepth(fogParams, ro, rd, start, max(start, end));
    return tau > 0.0 ? exp(-sigma * tau) : float3(1.0, 1.0, 1.0);
}

/**
 * Diffusion-approximation attenuation K: how fast light dims through the medium, rather than how fast
 * the original beam dims. Scattering removes a photon from the beam but not from the medium.
 *
 *   sigma_a  = (1 - w) * sigma_t
 *   sigma_s' = w * (1 - g) * sigma_t
 *   K        = sqrt(3 * sigma_a * (sigma_a + sigma_s'))
 *
 * The clamp is the physical domain, not defensive dressing. Diffusion is invalid when absorption
 * dominates and otherwise can return K > sigma_t; light also cannot survive farther than pure
 * absorption permits. Therefore sigma_a <= K <= sigma_t. The cloud literature's fitted multi-octave
 * approximation was considered and rejected here: it broadens the beam angularly but introduces three
 * un-derived constants. Clouds retain their own approved approximation (D5).
 */
public float3 volumeDiffuseAttenuation(float3 sigmaT, float3 albedo, float g) {
    float3 w = clamp(albedo, float3(0.0, 0.0, 0.0), float3(1.0, 1.0, 1.0));
    float3 sigmaA = (float3(1.0, 1.0, 1.0) - w) * sigmaT;
    float3 reducedSigmaS = w * max(1.0 - g, 0.0) * sigmaT;
    float3 K = sqrt(max(float3(0.0, 0.0, 0.0),
                        3.0 * sigmaA * (sigmaA + reducedSigmaS)));
    return clamp(K, sigmaA, sigmaT);
}

/** The two radiance sources whose visibility is queried independently. */
public struct AmbientRadianceSource {
    public float3 sun;
    public float3 sky;
};

/**
 * One source expression for both estimators. `directTransmittance` includes every directional path
 * already represented at the call site: raygen passes local fog attenuation because its directional
 * light was already atmosphere-dyed; the froxel passes atmosphere LUT attenuation times that same local
 * fog attenuation because it reads the undyed WorldPush buffer. The sky is an independent, GPU-reduced
 * radiance source and therefore never takes the sun direction's transmittance.
 */
public AmbientRadianceSource evaluateAmbientRadianceSource(
        float3 viewDir, float3 sunDir, float phaseG,
        float3 lightRadiance, float3 directTransmittance,
        float3 mediumSkyRadiance) {
    AmbientRadianceSource source;
    // lightRadiance is irradiance E and volumeHg is a normalised phase density p(omega), so the
    // directional source is E*p. Multiplying by 4*pi here would treat E as E/(4*pi) and create energy.
    source.sun = lightRadiance * directTransmittance
            * volumeHg(dot(viewDir, sunDir), phaseG);
    source.sky = mediumSkyRadiance;
    return source;
}
