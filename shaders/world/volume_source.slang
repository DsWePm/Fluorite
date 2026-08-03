// Pure radiance-source and height-medium maths shared by the ray-tracing segment integrator and the
// aerial-perspective froxel. No bindings, globals or ray operations: both shader stages can import it
// without inheriting each other's descriptor layouts.

public static const float VOLUME_PI = 3.14159265359;

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
 * already represented at the call site: raygen passes local fog attenuation because its light values
 * were already atmosphere-dyed; the froxel passes atmosphere LUT attenuation times that same local fog
 * attenuation because it reads the undyed WorldPush buffer. Planetary multiple scattering is isotropic
 * sky radiance and therefore does not take the directional path's local-fog attenuation.
 */
public AmbientRadianceSource evaluateAmbientRadianceSource(
        float3 viewDir, float3 sunDir, float phaseG,
        float3 lightRadiance, float3 directTransmittance,
        float3 fogAmbient, float3 atmosphereMultiScatter) {
    AmbientRadianceSource source;
    source.sun = lightRadiance * directTransmittance
            * (4.0 * VOLUME_PI * volumeHg(dot(viewDir, sunDir), phaseG));
    source.sky = fogAmbient * directTransmittance + atmosphereMultiScatter;
    return source;
}
