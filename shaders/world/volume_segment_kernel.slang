// One profile dispatcher: closed forms remain closed forms; source work is statically optional.
public SegmentIntegral VOLUME_KERNEL_FN(integrateSegment)(float3 currentExtinction, uint currentFlags,
                                        float3 ro, float3 rd, float tEnd, uint seed) {
    SegmentIntegral seg;
    seg.transmittance = float3(1.0, 1.0, 1.0);
    seg.inScatter = float3(0.0, 0.0, 0.0);
    seg.scatterPos = float3(0.0, 0.0, 0.0);
    seg.scatterWeight = float3(0.0, 0.0, 0.0);

    uint profile = mediumProfileFlags(currentFlags);

    // Estimator dispatch behind the one segment interface. Homogeneous enclosed media retain their
    // exact closed form; the ambient field retains its analytic-height path at strength zero and selects
    // the finite heterogeneous march otherwise. The shared parameter queries and shadow sampler are the
    // seam -- forcing both through one numerical
    // method would make the homogeneous path slower and less accurate merely to look uniform.
    if (profile != MEDIUM_PROFILE_HETEROGENEOUS_AMBIENT) {
        float3 sigmaT = mediumSigmaTFields(currentExtinction, currentFlags, ro);
        if (any(sigmaT > 0.0)) {
            seg.transmittance = exp(-sigmaT * tEnd);

#if VOLUME_KERNEL_SOURCE
            float3 scatterAlbedo = mediumScatterAlbedoFields(currentExtinction, currentFlags);
            if (profile == MEDIUM_PROFILE_HOMOGENEOUS_ENCLOSED
                    && any(scatterAlbedo > 0.0)) {
                // Its own rehash of the caller's seed, distinct from the fog's below, so the water's
                // stratum jitter and the fog's cannot draw the same numbers on the same pixel.
                uint waterSeed = seed ^ 0x85EBCA6Bu;
                waterSeed = pcg(waterSeed);
                // Sampled HERE rather than inside the estimator so the segment has exactly one event:
                // the sun term below and the caller's emitter NEE describe the same scattering, and two
                // independently drawn points would be two different scatterings sharing one segment.
                ScatterVertex vertex = noScatterVertex();
                if (scatterVertexOn()) {
                    vertex = sampleHomogeneousScatterVertex(sigmaT, scatterAlbedo, ro, rd, tEnd,
                                                            waterSeed);
                    seg.scatterPos = vertex.pos;
                    seg.scatterWeight = vertex.weight;
                }
                seg.inScatter += enclosedSingleScatter(currentExtinction, currentFlags,
                                                       scatterAlbedo, ro, rd, tEnd, vertex,
                                                       waterSeed);
            }
#endif
        }
    }

    // A path inside water is inside water — not inside water *and* the world's fog.
    if (profile == MEDIUM_PROFILE_HETEROGENEOUS_AMBIENT) {
        // Strength zero never reaches this function: the entire published analytic-height estimator below
        // stays intact as the exact A/B path. Once enabled, all view extinction, visibility-weighted
        // in-scatter, light-path attenuation and the M17 event use one canonical numerical density field.
        if (ambientNoiseOn()) {
            VOLUME_KERNEL_FN(integrateNoisyAmbient)(seg, ro, rd, tEnd, seed);
            return seg;
        }
#if VOLUME_KERNEL_SOURCE
        float3 scatterAlbedo = mediumScatterAlbedoFields(currentExtinction, currentFlags);
        float phaseG = mediumPhaseGFlags(currentFlags);
#endif
        float2 window = ambientWindow(tEnd);
        float tau = ambientHeightIntegral(ro, rd, window.x, window.y);
        if (tau > 0.0) {
            float3 fogT = exp(-worldPush.fogExtinction.xyz * tau);
            seg.transmittance *= fogT;
#if VOLUME_KERNEL_SOURCE
            // Sampled before either source is evaluated, and independently of the sun's ray budget: the
            // caller's emitter NEE wants an event even where sun-shadow-rays is 0 and the sun is reading
            // the grid. One event per segment, shared by everything that describes it.
            if (scatterVertexOn()) {
                uint vertexSeed = seed ^ 0xC2B2AE3Du; // pcg takes an inout, so it needs a variable
                vertexSeed = pcg(vertexSeed);
                ScatterVertex ambientVertex = sampleAmbientScatterVertex(
                        worldPush.fogExtinction.xyz, scatterAlbedo, ro, rd, window, tau, vertexSeed);
                seg.scatterPos = ambientVertex.pos;
                seg.scatterWeight = ambientVertex.weight;
            }
            // The sunlight lighting the fog has itself crossed the fog to get here. Without that term
            // a low sun grew a halo that brightened with exactly the depth that ought to be
            // extinguishing it.
            //
            // Measured from the segment's ORIGIN, not from a point inside it. Sampling the midpoint made
            // this depend on segment length, and segment length is whatever the ray happened to hit: a
            // ray stopping at 30 blocks and its neighbour escaping to the cull distance sampled the sun's
            // transmittance an order of magnitude apart, so the halo came out blotchy across neighbouring
            // pixels and unstable as the camera moved. Every ray leaving one point now agrees.
            // The sun's own path THROUGH the fog to this segment, and the coefficient it decays at is
            // not the one the view path uses. See diffuseAttenuation: scattering removes light from the
            // beam without removing it from the air, so at density 10 the old form extinguished the sun
            // roughly three times too fast and the fog went black exactly where real fog turns into a
            // bright formless glow. The view path below still uses the full extinction.
            float3 sunSigma = multiScatterOn()
                    ? volumeDiffuseAttenuation(worldPush.fogExtinction.xyz, scatterAlbedo, phaseG)
                    : worldPush.fogExtinction.xyz;
            float3 sunTransmittance = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
                    ? ambientTransmittanceWith(sunSigma, ro, worldPush.lightDir.xyz, RAY_FAR)
                    : float3(1.0, 1.0, 1.0);
            // The celestial buffer is irradiance E, so the normalised phase density stays normalised:
            // the directional radiance source is E*p, not E*(4*pi*p).
            // The source, split into the two halves the visibility grid answers for SEPARATELY.
            //
            // The sun's lobe is gated by whether the sun is reachable. The other two are gated by whether
            // the SKY is, which is a different question and deliberately so: a hillside's shadow must not
            // drain the ambient light out of the fog standing in it, because what removes ambient light is
            // a roof, not a hill. The froxel splits its source the same way, and the two agreeing matters
            // — they are added together on screen, so a disagreement between them would show up as a seam
            // at whatever depth the camera's prefix segment happens to end.
            //
            // M15.2/M16: both estimators call evaluateAmbientRadianceSource and read the same GPU-reduced
            // mediumSkyRadiance for the sky. The centre-direction source below remains only the grid/outside-
            // grid fallback; D20's ray and in-grid paths rebuild it from the sampled emitter direction.
            // Froxel does the same lookup through its compute-local LUT binding and additionally integrates
            // the planetary atmosphere itself over the camera prefix.
            //
            // The ad-hoc "extinguished light returns as glow" term that used to sit here is gone. It
            // returned 0.35x the sun's RADIANCE isotropically and grew with density, so at density 10 it
            // reached 0.35x while the sun's own sideways lobe was 0.16x — a flat wash twice as bright as
            // the thing it was supposed to be softening, which is why the light shaft vanished from the
            // side and the water blew out. What replaces it is the multiple-scattering table, which is
            // the same quantity computed rather than guessed.
            //
            // Named limitation, so it is not rediscovered as a bug: that table is the ATMOSPHERE's
            // multiple scattering, over kilometres. The local fog's own multiple scattering — what makes
            // real pea soup glow white — is not modelled by anything here now. A single-scattering
            // renderer cannot have it, and a constant standing in for it is what this change removed.
            // Atmospheric multiple scattering is already inside the complete sky-view LUT reduction;
            // adding sampleMultiScatter here again would count the same energy twice.
            AmbientRadianceSource radianceSource = evaluateAmbientRadianceSource(
                    rd, worldPush.lightDir.xyz, phaseG,
                    worldPush.lightRadiance.xyz, sunTransmittance,
                    worldPush.mediumSkyRadiance.xyz);
            float3 sunSource = radianceSource.sun;
            float3 skySource = radianceSource.sky;
            // Bit 16 (Rt.Volumetrics.SEGMENT_SOURCE): silence the fog in-scatter on MARCHED segments.
            //
            // Gating here rather than at the call sites is what makes the switch mean exactly "every
            // segment the froxel does not cover". The camera prefix takes the froxel whenever this branch
            // would have run at all — the one case it does not, a submerged camera, has ambient false and
            // never reaches this line — so the segments left running it are precisely the bounces.
            //
            // Only the emission moves. seg.transmittance was set above and stays, so this separates "the
            // fog is too bright" from "the fog is too thick" instead of confusing the two.
            // Bits 23-25: how many jittered shadow rays the SUN term gets. Zero keeps it on the grid.
            // The sky term stays on the grid either way — it is genuinely low frequency (a room is dark,
            // a hillside is not) and needs no edge, which is the whole reason the two are split.
            uint sunRays = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
                    ? ((worldPush.flags >> 23u) & 7u) : 0u;
            float3 gridSunSource = sunRays > 0u ? float3(0.0, 0.0, 0.0) : sunSource;
            if (((worldPush.flags >> 16u) & 1u) == 0u) {
                // Three regions, because the grid has a boundary and pretending otherwise is what puts a
                // shell around the camera: the stretch before the ray enters the grid, the stretch inside
                // it where visibility is sampled, and the stretch beyond. The two outer ones run the
                // closed form with visibility 1 — exactly what this line did before the grid existed —
                // and the inner one is the only place anything new happens.
                float2 span = visGridSpan(ro, rd);
                float tEnter = clamp(span.x, window.x, window.y);
                float tExit = clamp(max(span.y, span.x), tEnter, window.y);

                AmbientAccum acc;
                acc.inScatter = float3(0.0, 0.0, 0.0);
                acc.transmittance = float3(1.0, 1.0, 1.0);
                // The two outer stretches: D178 left them hardwired open so its clamp could be
                // attributed alone. Under FLAG_FOG_BEYOND_GRID_CLAMP they ask the clamped field at
                // their MIDPOINT -- one read per stretch, matching the closed-form one-step shape, not
                // a sub-step march -- so far cave fog stops reading as if the sky were up. The guards
                // keep the empty-region case (a camera already inside the grid makes the first
                // stretch zero length) from paying a fetch to be ignored.
                float outerOpenA = 1.0;
                float3 outerSkyA = skySource;
                if (tEnter > window.x) {
                    outerSkyA = beyondGridSky(ro + rd * ((window.x + tEnter) * 0.5),
                            skySource, outerOpenA);
                }
                ambientStep(acc, ro, rd, window.x, tEnter, scatterAlbedo,
                            gridSunSource, outerSkyA, outerOpenA);
                // Guarded, not just relying on ambientStep's own zero-length early-out: the SAMPLE happens
                // before that check, so a segment that never touches the grid would still pay six 3D
                // texture fetches to be told six times that it is outside. Most bounce segments in an open
                // scene are exactly that.
                if (tExit > tEnter) {
                    int steps = ambientVisSteps(tExit - tEnter, worldPush.visGridOrigin.w);
                    for (int i = 0; i < steps; ++i) {
                        float tA = lerp(tEnter, tExit, float(i) / float(steps));
                        float tB = lerp(tEnter, tExit, float(i + 1) / float(steps));
                        // Sampled at the sub-step's MIDPOINT, so the value describes the stretch it weights
                        // rather than one of its ends. Sampling an end biases the whole segment toward
                        // whichever side of a shadow boundary that end happened to fall on, which is the
                        // one-coin-flip failure this grid exists to avoid — at a smaller scale, but the same
                        // shape.
                        float3 midP = ro + rd * ((tA + tB) * 0.5);
                        // M28 S1: under FLAG_SKY_FIELD_DIRECTIONAL the fetch returns four SECTOR bins, and
                        // each is weighted by its own sector's phase-integrated radiance -- a cave mouth to
                        // the east lights the fog near it with the east, not with the dome's mean. The
                        // visibility is inside the per-bin weights, so the openness handed down is 1: the
                        // product ambientStep forms is unchanged, only which factor carries the occlusion.
                        // Scalar mode passes the shipped pair untouched.
                        float3 stepSky = skySource;
                        float stepOpen;
                        if ((worldPush.volumetricSwitches & FLAG_SKY_FIELD_DIRECTIONAL) != 0u) {
                            stepSky = sectorSkyTerm(volumeSkyBins(midP));
                            stepOpen = 1.0;
                        } else {
                            stepOpen = volumeSkyOpenness(midP);
                        }
                        ambientStep(acc, ro, rd, tA, tB, scatterAlbedo,
                                    gridSunSource, stepSky, stepOpen);
                    }
                }
                float outerOpenB = 1.0;
                float3 outerSkyB = skySource;
                if (window.y > tExit) {
                    outerSkyB = beyondGridSky(ro + rd * ((tExit + window.y) * 0.5),
                            skySource, outerOpenB);
                }
                ambientStep(acc, ro, rd, tExit, window.y, scatterAlbedo,
                            gridSunSource, outerSkyB, outerOpenB);
                // += rather than =: the enclosed branch above may already have deposited in-scatter.
                // Unreachable today (every enclosed medium has ambient false), but a future composite
                // profile that deposits both enclosed and ambient source would otherwise lose the first.
                seg.inScatter += acc.inScatter;
                if (sunRays > 0u) {
                    // Taken BY VALUE and rehashed rather than advanced in place. The callers include the
                    // camera prefix and the debug views, which have no path RNG stream to advance, and
                    // threading an inout through them would make every future caller carry one. The
                    // rehash is what keeps this sample from correlating with whatever the path draws next
                    // from the same seed.
                    uint fogSeed = seed ^ 0x9E3779B9u; // pcg takes an inout, so it needs a variable
                    fogSeed = pcg(fogSeed);
                    if (scatterVertexOn() && any(seg.scatterWeight > 0.0)) {
                        // One sampled event instead of the stratified sweep (M17, D29). The stratified
                        // form keeps closed-form weights per stratum and samples only the source inside
                        // each; this keeps ONE sample and pays for it with the exact f/pdf weight, so it
                        // is a strict Monte Carlo estimator where the other is a controlled approximation
                        // (D9). Noisier per frame by construction, and the reason the switch exists.
                        //
                        // The sun's transmittance is measured from the EVENT, not from the segment's
                        // origin as the closed form must. That was previously ruled out because a
                        // deterministic midpoint made the answer depend on segment length and came out as
                        // blotches; a sampled position depends on a random number instead, so the same
                        // error becomes noise the denoiser converges.
                        SquareLightSample lightSample = sampleSquareLight(
                                worldPush.lightDir.xyz, worldPush.celestial.xyz,
                                worldPush.lightDir.w, fogSeed);
                        float3 vertexSunSource = sampledAmbientSunSource(
                                seg.scatterPos, rd, lightSample.direction, phaseG, sunSigma);
                        float3 vis = visibilityMasked(CULL_SHADOW_NO_SELF, seg.scatterPos,
                                                      lightSample.direction, RAY_FAR).transmittance;
                        seg.inScatter += seg.scatterWeight * vertexSunSource * vis;
                    } else if (!scatterVertexOn()) {
                        seg.inScatter += sunInScatterStochastic(ro, rd, window, scatterAlbedo,
                                                                 phaseG, sunSigma,
                                                                 int(sunRays), fogSeed);
                    }
                }
            }
#endif
        }
    }
    return seg;
}
