// Canonical noisy-ambient density walk; source terms are a static specialization.
void VOLUME_KERNEL_FN(integrateNoisyAmbientRegion)(inout AmbientAccum acc, inout ScatterProbe probe,
                                 float3 ro, float3 rd,
                                 float a, float b, int steps, bool gridRegion,
                                 float3 scatterAlbedo, float3 skySource,
                                 bool sourceOn) {
    if (steps <= 0 || b <= a) {
        return;
    }
    float dt = (b - a) / float(steps);
    [loop]
    for (int i = 0; i < steps; ++i) {
        float t = a + (float(i) + 0.5) * dt;
        float3 p = ro + rd * t;
        float density = ambientNoiseDensity(p, dt);
        float tau = density * dt;
        // Ahead of the zero-density skip below, because the inversion this replaces did not skip either:
        // it added a zero and walked on. A cell contributing no optical depth cannot hold the event, but
        // it still has to be stepped over or every later cell is reached at the wrong cumulative depth.
#if VOLUME_KERNEL_SOURCE
        if (probe.target >= 0.0 && !probe.found) {
            if (probe.target <= probe.cumulative + tau && tau > 0.0) {
                probe.pos = ro + rd * ((t - dt * 0.5)
                        + clamp((probe.target - probe.cumulative) / density, 0.0, dt));
                probe.found = true;
            }
            probe.cumulative += tau;
        }
#endif
        if (tau <= 0.0) {
            continue;
        }
        float3 stepT = exp(-worldPush.fogExtinction.xyz * tau);
#if VOLUME_KERNEL_SOURCE
        if (sourceOn) {
            // gridRegion=false marks the stretches outside the grid's span: the beyondGridSky seam
            // answers them (far field when published, else the clamp, else shipped-open).
            float skyOpen;
            float3 beyondSky = gridRegion ? skySource
                    : beyondGridSky(p, skySource, skyOpen);
            float3 source = beyondSky * (gridRegion ? volumeSkyOpenness(p) : skyOpen);
            acc.inScatter += acc.transmittance * scatterAlbedo * source
                    * (float3(1.0, 1.0, 1.0) - stepT);
        }
#endif
        acc.transmittance *= stepT;
    }
}

/** Stratified direct-sun estimator for the approved noisy field when M17's one-event mode is off. */
#if VOLUME_KERNEL_SOURCE
float3 noisyAmbientSunInScatter(float3 ro, float3 rd, NoisyAmbientLayout layout,
                                float baseTau, float3 scatterAlbedo,
                                float phaseG, float3 sunSigma, int rays,
                                inout uint seed) {
    float3 sigmaT = worldPush.fogExtinction.xyz;
    float sigmaBar = max(luminance(sigmaT), 1.0e-4);
    float totalScalarTau = sigmaBar * baseTau;
    float3 gathered = float3(0.0, 0.0, 0.0);
    for (int i = 0; i < rays; ++i) {
        float tauNear = SUN_VIS_TAU_STEP * float(i);
        if (tauNear >= totalScalarTau) {
            break;
        }
        float tauFar = i == rays - 1
                ? totalScalarTau
                : min(tauNear + SUN_VIS_TAU_STEP, totalScalarTau);
        float sampledTau = lerp(tauNear, tauFar, rndf(seed));
        float sampleT = noisyDistanceAtBaseTau(ro, rd, layout, sampledTau / sigmaBar);
        float3 p = ro + rd * sampleT;
        SquareLightSample lightSample = sampleSquareLight(
                worldPush.lightDir.xyz, worldPush.celestial.xyz, worldPush.lightDir.w, seed);
        float3 sunSource = sampledAmbientSunSource(
                p, rd, lightSample.direction, phaseG, sunSigma);
        float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, p,
                lightSample.direction, RAY_FAR).transmittance;
        float3 viewWeight = exp(-sigmaT * (tauNear / sigmaBar))
                          - exp(-sigmaT * (tauFar / sigmaBar));
        gathered += scatterAlbedo * viewWeight * sunSource * vis;
    }
    return gathered;
}
#endif

void VOLUME_KERNEL_FN(integrateNoisyAmbient)(inout SegmentIntegral seg, float3 ro, float3 rd,
                           float tEnd, uint seed) {
    float2 window = ambientWindow(tEnd);
    NoisyAmbientLayout layout = makeNoisyAmbientLayout(ro, rd, window);
#if VOLUME_KERNEL_SOURCE
    float baseTau = noisyAmbientLayoutTau(ro, rd, layout);
    if (baseTau <= 0.0) {
        return;
    }

    float3 scatterAlbedo = worldPush.fogScatter.xyz;
    float phaseG = worldPush.fogScatter.w;
    uint sunRays = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
            ? ((worldPush.flags >> 23u) & 7u) : 0u;
    bool sourceOn = ((worldPush.flags >> 16u) & 1u) == 0u;
    float3 sunSigma = multiScatterOn()
            ? volumeDiffuseAttenuation(worldPush.fogExtinction.xyz, scatterAlbedo, phaseG)
            : worldPush.fogExtinction.xyz;

    // The event's WEIGHT is known before the march because it depends only on the total; its POSITION
    // comes out of the march itself. Splitting them that way is what removes the second traversal.
    ScatterProbe probe = noScatterProbe();
    if (scatterVertexOn()) {
        uint vertexSeed = seed ^ 0xC2B2AE3Du;
        vertexSeed = pcg(vertexSeed);
        float3 sigmaT = worldPush.fogExtinction.xyz;
        float sigmaBar = max(luminance(sigmaT), 1.0e-4);
        float tauTotal = sigmaBar * baseTau;
        if (tauTotal > 1.0e-6) {
            float tauScalar = sampleScatterTau(tauTotal, vertexSeed);
            probe.target = tauScalar / sigmaBar;
            seg.scatterWeight = scatterVertexWeight(sigmaT, scatterAlbedo, sigmaBar,
                                                    tauScalar, tauTotal);
        }
    }

#else
    float3 scatterAlbedo = float3(0.0, 0.0, 0.0);
    bool sourceOn = false;
    ScatterProbe probe = noScatterProbe();
#endif
    AmbientAccum acc;
    acc.inScatter = float3(0.0, 0.0, 0.0);
    acc.transmittance = float3(1.0, 1.0, 1.0);
#if VOLUME_KERNEL_SOURCE
    float3 skySource = worldPush.mediumSkyRadiance.xyz;
#else
    float3 skySource = float3(0.0, 0.0, 0.0);
#endif
    VOLUME_KERNEL_FN(integrateNoisyAmbientRegion)(acc, probe, ro, rd, layout.edge.x, layout.edge.y, layout.steps.x,
            false, scatterAlbedo, skySource, sourceOn);
    VOLUME_KERNEL_FN(integrateNoisyAmbientRegion)(acc, probe, ro, rd, layout.edge.y, layout.edge.z, layout.steps.y,
            true, scatterAlbedo, skySource, sourceOn);
    VOLUME_KERNEL_FN(integrateNoisyAmbientRegion)(acc, probe, ro, rd, layout.edge.z, layout.edge.w, layout.steps.z,
            false, scatterAlbedo, skySource, sourceOn);
#if VOLUME_KERNEL_SOURCE
    // The far edge when the walk never crossed the target, which is what the inversion returned too:
    // rounding in a sum can leave the last cell just short of a total assembled from the same terms, and
    // the segment's end is the only defensible answer there.
    if (probe.target >= 0.0) {
        seg.scatterPos = probe.found ? probe.pos : (ro + rd * layout.edge.w);
    }
#endif
    seg.transmittance *= acc.transmittance;
#if VOLUME_KERNEL_SOURCE
    seg.inScatter += acc.inScatter;

    if (sourceOn && sunRays > 0u) {
        uint fogSeed = seed ^ 0x9E3779B9u;
        fogSeed = pcg(fogSeed);
        if (scatterVertexOn() && any(seg.scatterWeight > 0.0)) {
            SquareLightSample lightSample = sampleSquareLight(
                    worldPush.lightDir.xyz, worldPush.celestial.xyz,
                    worldPush.lightDir.w, fogSeed);
            float3 vertexSunSource = sampledAmbientSunSource(
                    seg.scatterPos, rd, lightSample.direction, phaseG, sunSigma);
            float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, seg.scatterPos,
                    lightSample.direction, RAY_FAR).transmittance;
            seg.inScatter += seg.scatterWeight * vertexSunSource * vis;
        } else if (!scatterVertexOn()) {
            seg.inScatter += noisyAmbientSunInScatter(ro, rd, layout, baseTau,
                    scatterAlbedo, phaseG, sunSigma, int(sunRays), fogSeed);
        }
    }
#endif

}
