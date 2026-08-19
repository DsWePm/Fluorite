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

/** Combine an already decoded sample so diagnostics cannot drift from the transport answer. */
public float volumeFogNoiseResolvedMultiplier(float2 signedNoise, float2 resolved,
                                               float4 fogAmbient) {
    float contrast = max(fogAmbient.x, 0.0);
    if (contrast <= 0.0) {
        return 1.0;
    }
    float detailShare = clamp(fogAmbient.y, 0.0, 1.0);
    float variation = clamp(signedNoise.x * resolved.x * (1.0 - detailShare)
                          + signedNoise.y * resolved.y * detailShare, -1.0, 1.0);

    // D68A: increasing a linear multiplier past one and then clipping negative density would raise the
    // field's mean -- the positive half keeps growing after the negative half hits zero. This rational
    // remap is odd and bounded instead: f(-x)=-f(x), f(+-1)=+-1, and contrast=1 is the old answer. The
    // paired half-period field therefore still sums to two at every height, while 1..4 can push ordinary
    // variations progressively nearer the physical 0..2 density limits. One reciprocal, no extra fetch.
    float shaped = contrast <= 1.0
            ? contrast * variation
            : (contrast * variation) / (1.0 + (contrast - 1.0) * abs(variation));
    return 1.0 + clamp(shaped, -1.0, 1.0);
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

/**
 * Luminance, for callers that cannot import math.slang.
 *
 * <p>A SECOND SPELLING ON PURPOSE, and the codebase already had this exact split: world_common holds pcg
 * for the same reason its banner gives -- math.slang declares the ray-tracing bindings, so importing it
 * from a compute bake is a binding overlap rather than a convenience. The froxel bake is such a caller.
 */
public float volumeLuminance(float3 c) {
    return dot(c, float3(0.2126, 0.7152, 0.0722));
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
