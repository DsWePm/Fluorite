// The path reuse's pass B tracer (M28 D231, G15/R1). Textually included into world.rgen.slang ONLY
// when FLUORITE_PATH_REUSE is defined, so it exists only in world_reuse.rgen / world_ser_reuse.rgen --
// the pipeline the path reservoir switch binds. The shipped pass B (world.rgen's tracePath) carries none
// of this file and stays byte-for-byte the tracer that shipped before S2; a switch-off frame binds it.
//
// This is tracePath with the path reservoir recorded and reused. Everything that is NOT the path
// reservoir must stay identical to tracePath: RtPathReplayContractTest checks the shipped body survives here,
// and a divergence would make the switch compare two different tracers rather than reuse against none.
// The rebuild's slices (R1+) restructure this copy only.

public float3 tracePathReuse(__ref PathSegment seg, uint activeMediumFlags,
                        uint2 pix, uint sampleIndex, uint2 renderSize,
                        bool captureFirst, out FirstSegmentTrace firstTrace,
                        inout uint4 reuseCounts) {
    firstTrace = emptyFirstSegmentTrace(seg, activeMediumFlags);
    float3 L = float3(0.0, 0.0, 0.0);
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    // Pass A is fixed at one sample per pixel. Decorrelate each Pass B resample here so configured SPP
    // produces independent lighting/BSDF paths from the shared terminal continuation.
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    // M28 S2: this state is what the path reservoir stores -- the whole bounce loop's decision stream
    // derives from it deterministically (see the REPLAY CONTRACT in segment.slang), so a replay starts
    // from here. Snapshotted before the first draw mutates `seed`.
    uint pathSeedBase = seed;
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    int rrStart = maxBounces <= 3 ? 1 : 2;
    // RIS emitter NEE: direct lighting from block emitters is active when lights are published and the
    // candidate count is non-zero. Off => emitters are gathered only on path hits (legacy behaviour).
    bool risOn = worldPush.risCandidates > 0u && worldPush.lightCount > 0u && pc.lightBufAddr != 0;
    // Independent light-proposal RNG. Screen-space coherent selection is intentionally deferred until
    // there is a real presampled RIS tile buffer; direct sharing made tile-shaped lighting noise visible.
    // Measured: making a 16x16 tile share this seed recovers at most 1.3ms, so a future pool should share
    // the POOL rather than the seed — the win is in removing dependent loads, not in coherence.
    uint proposalSeed = pix.x * 1973u + pix.y * 9277u + 26699u
            ^ worldPush.frameIndex * 2654435761u ^ sampleIndex * 2246822519u;
    proposalSeed = pcg(proposalSeed);
    // Slang 2026.14 aliases two simultaneously-live classification values in this raygen. The caller
    // therefore decodes current+outer directly from PackedPathSegment.pathFlags into the single packed
    // activeMediumFlags word. Do not split it into persistent currentFlags/outerFlags locals: diagnostic
    // reads proved that doing so is observer-sensitive. IOR/extinction remain primitive active state.
    float currentIor = seg.medium.current.ior;
    float3 currentExtinction = seg.medium.current.extinction;
    float outerIor = seg.medium.outer.ior;
    float3 outerExtinction = seg.medium.outer.extinction;
    // Read once per path rather than per bounce: it is a frame-wide branch on a value already loaded.
    uint secondaryCull = secondaryCullMask();
    bool waterWaves = (worldPush.flags & 16u) != 0u; // animated wave-normal perturbation
    // Sky-disc gate (see world.rmiss): the sun/moon disc is shown to the primary ray and to rays spawned
    // by a specular/dielectric bounce (mirror reflections + refractions of the sun/moon), but hidden from
    // diffuse-sampled continuation rays — those are already covered by the sun NEE, so the tiny disc
    // would be a double-counted firefly. Reset true on every specular/water bounce, false on a diffuse one.
    bool showCelestial = seg.showCelestial;
    // Multiple importance sampling for the celestial light. The light is estimated twice per vertex —
    // once by NEE, once by whatever the continuation lobe happens to hit — and this is the density of
    // the continuation about to be traced, so the miss that lands on the light can be weighted against
    // the NEE sample that competed for it.
    //
    // Negative marks a delta lobe: a mirror, or a dielectric interface, which has no finite density.
    // The textbook weight for a delta strategy is 1, but the light is deliberately given ZERO on those
    // paths. There are two suns here and only by art direction: the drawn sprite is vanilla's, sixteen
    // degrees across, while the light is 0.6. A delta path already carries the sprite, and letting it
    // also carry the light would put a core a thousand times brighter into every wave facet on the
    // water. So a delta path sees the sprite and a glossy one is MIS-weighted against the light, which
    // is also exactly why this change cannot touch a mirror.
    //
    // Pass A hands over either the camera ray or a delta dielectric split, so the sentinel is the right
    // starting value too and no density has to cross the pass boundary.
    float bsdfPdf = -1.0;
    bool sunMis = (worldPush.flags & 32u) != 0u;
    // Subsurface mode and event budget, packed into the flags word rather than given lanes of their
    // own: WorldPush is loaded once per path, but every field added to it is paid by the layout test,
    // the generated record and everyone reading the struct. 0 off, 1 thin shell, 2 random walk.
    uint sssMode = (worldPush.flags >> 7u) & 3u;
    int sssMaxEvents = int((worldPush.flags >> 9u) & 7u);
    // Solid angle of the square NEE draws from, as a density. Zero half-angle is a delta light: NEE is
    // then the only strategy that can reach it, and no BSDF ray ever will.
    bool celestialOn = worldPush.skyProvider == SKY_PROVIDER_ATMOSPHERE
            || worldPush.skyProvider == SKY_PROVIDER_ENVIRONMENT;
    float lightHalfAngle = celestialOn ? worldPush.lightDir.w : 0.0;
    float pdfLight = lightHalfAngle > 0.0 ? squareLightPdf(lightHalfAngle) : 0.0;
    bool misActive = sunMis && lightHalfAngle > 0.0;
    // Pass B treats every hit it sees as one indirect-depth step, regardless of material or selected
    // continuation lobe. Deliberately start at zero instead of seg.bounce: the first hit of either
    // queued split branch is still the primary-terminal shading point. Hits 0 and 1 retain SSS; RIS
    // remains active with its full configured candidate count at every hit. Pass A's primary/interface
    // prefix is outside this SSS quality budget.
    int indirectDepth = 0;
    // M28 S2 initial candidate: this pixel's own path, recorded for next frame's experimental
    // suffix reuse. The reconnection vertex is the FIRST opaque vertex whose continuation has a
    // finite density -- dielectric chains and exact mirrors stay prefix. The suffix is recovered after
    // the loop by snapshot subtraction, so the radiance accumulation sites stay untouched and the
    // switch-off state (no buffer published) dead-codes every line this block adds.
    bool recordPath = worldPush.pathReservoirAddr != 0 && sampleIndex == 0u;
    bool reconFound = false;
    bool reconQualifies = false;
    float3 reconPos = float3(0.0, 0.0, 0.0);
    float3 reconNrm = float3(0.0, 0.0, 1.0);
    float2 reconWi = float2(0.5, 0.5);
    uint reconDepth = 0u;
    float3 reconLpre = float3(0.0, 0.0, 0.0);
    float3 reconTpre = float3(0.0, 0.0, 0.0);
    float3 reconTpost = float3(0.0, 0.0, 0.0);
    // The temporal candidate (S2), evaluated at the reconnection vertex and carried out for the
    // post-loop merge: the re-weighted history sample, its target sum (m_n * mean home target), its
    // count, and where its record was read from -- the write re-reads that slot if the history wins
    // the survivor draw, rather than keeping twelve lanes of its identity live through the loop.
    float3 temporalValue = float3(0.0, 0.0, 0.0);
    float temporalSum = 0.0;
    float temporalCount = 0.0;
    uint temporalReadPixel = 0u;
    // A-01a: the roughness of the previous shading vertex, for the reconnection gate.
    // 1.0 means the camera prefix is treated as unconditionally rough -- reconIndex == 0 has no
    // x_{k-1} surface (the paper's diffuse-case footnote), so the gate only ever bites a specular
    // chain. Dielectric interfaces set this to zero because their density is not represented.
    float prevVertexRough = 1.0;
    // The spatial candidates (S3a): accumulated values/counts/targets across accepted neighbours,
    // plus the streaming survivor's slot and home target for the post-loop write.
    float3 spatialValue = float3(0.0, 0.0, 0.0);
    float spatialCount = 0.0;
    float spatialTargetSum = 0.0;
    uint spatialWinSlot = 0u;
    float spatialWinTarget = 0.0;
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        // Radiance SBT records run any-hit only for true alpha cutout. Translucent/water go straight to
        // closest-hit for dielectric handling. Geometry is double-sided; the chit flips the normal.
        // Primary (bounce 0) is the camera ray (CULL_PRIMARY): sees particles but not the first-person
        // player. Bounce rays are secondary: exclude particles, and include the player everywhere except
        // with the eye in the water — see secondaryCullMask.
#ifdef FLUORITE_ENABLE_EXT_SER
        // SER lifetime phase: keep paths that are not roulette-eligible, paths that may terminate via
        // roulette, and paths guaranteed to end at the bounce cap in separate coherence groups.
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : secondaryCull, ro, 0.0, rd, RAY_FAR,
                               showCelestial, bounce == 0,
                               rayConeWidth, rayConeSpread, pathPhaseHint);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : secondaryCull, ro, 0.0, rd, RAY_FAR,
                      showCelestial, bounce == 0, rayConeWidth, rayConeSpread);
#endif

        // Beer-Lambert: attenuate along the segment just travelled by the medium it lay inside. Applies
        // to every hit reached while inside a volume dielectric (its own exit face, or whatever content
        // lies within it), shifting the transmitted radiance with distance. Air's extinction is zero, so
        // the ordinary case costs one comparison.
        //
        // This runs before the escape test, not after. A ray that reaches the sky still travelled through
        // whatever it was in to get there, and attenuating only the segments that end on geometry meant
        // looking up from underwater showed an unattenuated sky. A segment that escaped covered the whole
        // ray, hence RAY_FAR — the same distance the trace above used as its tmax.
        //
        // It matters more than the underwater case: the sky-facing segment is exactly the one an
        // environment medium has the most of, so any volumetric term added here has to sit on this side of
        // the break or it will be missing from precisely the direction it is most visible in.
        float segmentEnd = payload.hitT < 0.0 ? RAY_FAR : payload.hitT;
        SegmentIntegral segIntegral = integrateSegment(
                currentExtinction, activeCurrentMediumFlags(activeMediumFlags), ro, rd, segmentEnd, seed);
        // Fog light is dropped past the configured segment, and only the light: the transmittance above
        // stays, so this air still hides what is behind it. The emitter weight goes with the in-scatter
        // because it is the same quantity arriving by a different route.
        uint inScatterSegments = (worldPush.environmentFlags >> ENVIRONMENT_INSCATTER_SEGMENTS_SHIFT)
                & ENVIRONMENT_INSCATTER_SEGMENTS_MASK;
        if (inScatterSegments != 0u && uint(max(bounce, 0)) >= inScatterSegments) {
            segIntegral.inScatter = float3(0.0, 0.0, 0.0);
            segIntegral.scatterWeight = float3(0.0, 0.0, 0.0);
        }
        // Capture the quantities from THIS trace before payload or throughput advances. The previous
        // diagnostic re-traced the same geometric ray after tracePath returned; that is not evidence for
        // what tracePath integrated when streamed geometry, alpha any-hit, or mutable payload state can
        // make two traversals disagree. This copy is therefore the ground truth used by the runtime log.
        if (captureFirst && bounce == seg.bounce) {
            bool escaped = payload.hitT < 0.0;
            firstTrace.inScatter = segIntegral.inScatter;
            firstTrace.segmentEnd = segmentEnd;
            firstTrace.transmittance = segIntegral.transmittance;
            firstTrace.hitT = payload.hitT;
            firstTrace.throughput = throughput;
            firstTrace.material = escaped ? 0xFFFFFFFFu : payloadMaterial();
            firstTrace.escaped = escaped ? 1u : 0u;
            firstTrace.mediumProfile = mediumProfileFlags(
                    activeCurrentMediumFlags(activeMediumFlags));
        }
        // In-scatter first: it is what the segment added to the radiance arriving along it, so it is
        // weighted by the throughput reaching the segment rather than the throughput leaving it.
        L += throughput * segIntegral.inScatter;
        // Emitter light reaching the segment's sampled scattering event (M17). Wired here rather than
        // inside integrateSegment because lighting.slang imports volume.slang: this pass sees both, and
        // the dependency runs one way only. The weight the segment handed out already carries the
        // albedo, the view transmittance and the sampling pdf, so this multiplies a plain radiance.
        if (volumeEmitterNeeOn() && any(segIntegral.scatterWeight > 0.0)) {
            L += throughput * segIntegral.scatterWeight
                    * volumeNee(currentExtinction, activeCurrentMediumFlags(activeMediumFlags),
                                segIntegral.scatterPos, rd,
                                mediumPhaseGFlags(activeCurrentMediumFlags(activeMediumFlags)),
                                seed, proposalSeed);
        }
        throughput *= segIntegral.transmittance;

        // Clouds over the segment this ray ACTUALLY TRAVELLED, whether or not it ended on geometry
        // (M11, D33). This used to sit inside the escape-to-sky branch below, which quietly assumed the
        // camera is always under the deck: a downward ray then ends on terrain with no cloud in between,
        // so skipping it cost nothing. Fly up to cloud altitude and the assumption fails -- looking down
        // at the ground from inside or above the deck, the clouds between eye and ground were simply not
        // there, while looking up still worked. Integrating over the segment covers both, and it is the
        // same thing the participating medium above already does.
        //
        // Composited HERE rather than in the miss shader because this is the only place that knows the
        // ray's own origin, which is what the shell intersection and the step schedule are measured from
        // — and it is what puts clouds in a reflection: a mirror's ray passes through this same line.
        {
            // bounce > 0 is every ray that is not the first of its path — a reflection, a refraction, a
            // diffuse bounce. It buys a reduced march rather than a different sky: the shells and their
            // contents are the same world-anchored field either way, so only the budget moves.
            // Keep escaped sky clouds inside the established ray budget. D160's experiment extended
            // only this path to 40k, but the original in-game ring remained unchanged while the extra
            // horizon march increased the possible cost; user validation therefore rejected it.
            float cloudMaxT = payload.hitT < 0.0 ? RAY_FAR : payload.hitT;
            CloudResult clouds = traceClouds(ro, rd, cloudMaxT, seed, bounce > 0);
            L += throughput * clouds.inScatter;
            throughput *= clouds.transmittance;
        }

        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + drawn sun/moon sprite + stars) into
            // payload.albedo. The packed show-celestial flag decides whether this path segment may see
            // the sprite. All of it is reached one way only, so it takes the throughput unweighted.
            float3 sky = float3(payload.albedo);
            // The celestial light, if this ray landed inside it and its lobe had a density to weigh.
            // payload.f0 carries the light's irradiance; the solid angle is what turns it into the
            // radiance a ray arriving from that direction actually sees.
            if ((payload.flags & PAYLOAD_ANALYTIC_LIGHT) != 0u) {
                float landedLightPdf = worldPush.skyProvider == SKY_PROVIDER_ENVIRONMENT
                        ? squareLightDirectionalPdf(worldPush.lightDir.xyz, lightHalfAngle, rd)
                        : pdfLight;
                float3 analyticLe = float3(payload.f0) * landedLightPdf;
                if (worldPush.skyProvider == SKY_PROVIDER_ENVIRONMENT) {
                    // Environment miss excludes disk Le from payload.albedo whenever the NEE proposal
                    // supports it. Primary/delta paths take Le once; finite glossy paths MIS-weight it.
                    sky += misActive && bsdfPdf >= 0.0
                            ? analyticLe * powerHeuristic(bsdfPdf, landedLightPdf)
                            : analyticLe;
                } else if (misActive && bsdfPdf >= 0.0) {
                    sky += analyticLe * powerHeuristic(bsdfPdf, pdfLight);
                }
            }
            // The throughput already carries what the clouds let through.
            L += throughput * sky; // escaped to sky
            break;
        }

        float3 n = payload.normal; // oriented toward the incoming ray by the closest-hit
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        int hitDepth = indirectDepth++;

        // ---- Dielectric interface: water, stained glass, ice. ONE handler and one behaviour — every
        // dielectric is a volume. Fresnel from the relative index across the face, then reflect or
        // refract; no diffuse response and no sun NEE, because the surface is specular. Transmission
        // pushes or pops a participating medium, and the segment travelled inside it is attenuated by
        // that medium's extinction rather than by a tint multiply at the interface.
        //
        // Water is the only material that adds anything: the animated wave normal, and a medium marked
        // for the caustic term. Which side of the face we are on comes from the geometry
        // (payloadDielectricEntering), so a stray or missing face cannot desync the medium for the rest
        // of the path.
        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            // A dielectric predecessor is outside the opaque reconnection density model.
            // Keep the next opaque hit from treating this edge as the camera prefix.
            prevVertexRough = 0.0;
            // A dielectric has no local shading term. At the path cap its continuation cannot be traced,
            // so avoid computing an unused Fresnel choice, medium update, and roulette step.
            if (bounce >= maxBounces) {
                break;
            }
            bool isWater = material == MATERIAL_WATER;
            bool entering = payloadDielectricEntering();
            float3 tint = payload.albedo;
            float transmission = clamp(payloadTransmission(), 0.0, 1.0);

            // Ripple the (near-flat) surface normal before any Fresnel/guide use → glints, broken
            // reflections, wobbling refraction. The wave domain is world-stable: rebased hit XZ plus the
            // pushed anchor (terrain origin mod 4096) survives the per-move terrain rebase.
            float3 geometricNormal = n; // unrippled; ray origins offset along this
            if (isWater && waterWaves) {
                // The hit's own y rides along: the field is two-dimensional and has to be told which
                // surface it belongs to, or every water in this column ripples together.
                float3 waterDomain = float3(hitPos.x + worldPush.waterAnchor.x, hitPos.y,
                                            hitPos.z + worldPush.waterAnchor.y);
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.12);
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }
            if (isWater) {
                float impactFootprint = surfaceFootprint(rayConeWidth, rd, geometricNormal);
                n = applyRainImpactRipples(hitPos, n, openWaterRainImpact(hitPos), impactFootprint);
            }

            // The medium this face opens into, and the one it returns to on the way out — which is what
            // the stack remembers.
            float enteredIor = max(payloadIor(), 1.0);
            float3 enteredExtinction = dielectricMediumExtinction(isWater, tint, transmission);
            uint enteredFlags = dielectricMediumFlags(isWater);
            float etaI = currentIor;
            float etaT = entering ? enteredIor : outerIor;

            // The normal the interface refracts about. A smooth dielectric uses its own surface normal
            // and stays an exact delta — which is water, glass and ice, everything vanilla ships. A
            // rough one draws a microfacet from the GGX visible-normal distribution instead, and that
            // is the whole of what turns a pane into frosted glass: same Fresnel, same medium push and
            // pop, same bias choice, one different normal.
            //
            // Resolving both here rather than as two materials is what keeps the medium bookkeeping
            // singular. A second code path would be a second place for a push to go missing.
            float interfaceAlpha = clamp(payloadRoughness(), 0.0, 1.0);
            bool deltaInterface = isDeltaTransmission(interfaceAlpha);
            float3 m = deltaInterface ? n : sampleGGXVNDF(n, -rd, interfaceAlpha, seed);

            float cosI = clamp(dot(-rd, m), 0.0, 1.0);
            float F = fresnelDielectric(cosI, etaI, etaT);
            float3 transmittedDir = refract(rd, m, etaI / etaT);
            // The translucent terrain layer is recessed by TRANSLUCENT_INSET (RtTerrainMesher), so a
            // glass/ice face touching a slab or stair sits a hair behind that neighbour's surface. A full
            // SURF_BIAS would restart the transmitted ray past the neighbour and see through it. Water
            // comes from the fluid mesher, which applies no inset, so it takes the ordinary bias.
            float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

            bool chooseReflection = rndf(seed) < F;
            if (chooseReflection) {
                rd = reflect(rd, m);
                // A microfacet can reflect back into the surface it sits on. The delta case cannot, so
                // this only ever fires for a rough interface.
                if (!deltaInterface && dot(rd, geometricNormal) <= 0.0) {
                    break;
                }
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
            } else {
                if (dot(transmittedDir, transmittedDir) <= 0.0) break; // TIR with F < 1 cannot happen
                rd = normalize(transmittedDir);
                ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, transmitBias);
                // Crossing into or out of the volume. Absorption is the medium's job from here, so the
                // tint is NOT also multiplied into the throughput — that would double-count it.
                if (entering) {
                    outerIor = currentIor;
                    outerExtinction = currentExtinction;
                    currentIor = enteredIor;
                    currentExtinction = enteredExtinction;
                    activeMediumFlags = enterActiveMediumFlags(activeMediumFlags, enteredFlags);
                } else {
                    currentIor = outerIor;
                    currentExtinction = outerExtinction;
                    outerIor = 1.0;
                    outerExtinction = float3(0.0, 0.0, 0.0);
                    activeMediumFlags = exitActiveMediumFlags(activeMediumFlags);
                }
            }
            if (!deltaInterface) {
                // VNDF weight. The Fresnel split is already paid by the stochastic branch above, and
                // the sampling density cancels the distribution, leaving Smith's shadowing on the
                // outgoing direction — the same reduction the opaque specular lobe gets, and it holds
                // for the refracted branch too under the separable Smith approximation. abs() because
                // the transmitted direction is on the far side of the normal.
                throughput *= ggxG1(abs(dot(n, rd)), interfaceAlpha);
                rayConeSpread = max(rayConeSpread, interfaceAlpha * RAY_CONE_GLOSSY_SPREAD_SCALE);
            }
            showCelestial = true; // specular interface: the continuation ray may see the sun/moon disc
            // A dielectric runs no NEE at all, so there is no second estimate of the light for MIS to
            // combine with and the sentinel is right for a rough interface as well as a delta one: it
            // means "this path takes the drawn sprite, not the light".
            bsdfPdf = -1.0;
            if (bounce >= rrStart) {
                float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
                if (rndf(seed) > q) {
                    break;
                }
                throughput /= q;
            }
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
            float3 albedo = payload.albedo;

            // D78A's explicit non-physical readability floor. A constant isotropic radiance reflected
            // by Lambert is exactly albedo*L; no visibility is sampled because UNOCCLUDED is the preset's
            // declared approximation. Atmosphere providers never set this capability.
            if ((worldPush.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u) {
                L += throughput * albedo * worldPush.mediumSkyRadiance.xyz;
            }

            // No MIS here: a billboard's only lobe is Lambertian, whose density toward the light is
            // eight orders of magnitude under the light's own, so the weight is 1 to well past float.
            float3 lightDir = worldPush.lightDir.xyz;
            float3 sampledLightIrradiance = float3(0.0, 0.0, 0.0);
            if (celestialOn) {
                if (lightHalfAngle > 0.0) {
                    lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
                }
                sampledLightIrradiance = sampleProviderCelestialIrradiance(
                        worldPush, undyedCelestialIrradiance, lightDir);
                // D176's cloud shadow, on the same terms as the terrain's below. A particle standing in
                // a cloud's shadow that stayed lit while the ground around it went dark would read as
                // the particle glowing, so this is not an optional extension of the routing.
                sampledLightIrradiance *= cloudSunTransmittance(hitPos);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = celestialOn ? abs(signedNdl) : 0.0;
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibilityThroughAmbient(
                        currentExtinction, activeCurrentMediumFlags(activeMediumFlags),
                        shadowOrigin, lightDir, RAY_FAR).transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * sampledLightIrradiance * ndl * vis;
                }
            }

            // RIS direct lighting from block emitters (lava, glowstone, torches, ...) — same reservoir
            // sampler as terrain/entities, but two-sided: a billboard has no back face, so light
            // striking either side of the quad should still land (matches the sun/moon NEE above).
            if (risOn) {
                float3 v = -rd;
                BsdfContext bc = makeBsdfContext(hitPos, n, v, rd, albedo,
                                                 float3(0.0, 0.0, 0.0), 1.0, true, 0.0);
                Reservoir r = risInitial(bc, seed, proposalSeed);
                r = restirReuse(bc, r, uint(hitDepth), sampleIndex, pix, renderSize,
                                surfaceFootprint(rayConeWidth, rd, n), seed, reuseCounts);
                L += throughput * shadeReservoir(
                        currentExtinction, activeCurrentMediumFlags(activeMediumFlags),
                        bc, materialExtension(payload.flags), r,
                        // A billboard's only lobe is Lambertian, so every share is zero and the shared
                        // continuation density reduces to cos/pi -- which is what cosineDir samples.
                        0.0, 0.0, 0.0);
            }

            if (bounce >= maxBounces) {
                break;
            }
            throughput *= albedo;
            ro = hitPos + n * SURF_BIAS;
            rd = cosineDir(n, seed);
            bsdfPdf = max(0.0, dot(n, rd)) * INV_PI;
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse receiver: direct sun/moon was handled by NEE above
            continue;
        }

        float3 albedo = payload.albedo;
        float sss = payloadSss(); // LabPBR SSS strength (0 when absent)
        float3 v = -rd; // view direction (toward the camera / incoming ray)
        MaterialExtension ext = materialExtension(payload.flags);
        RainSurface rain = evaluateRainSurface(hitPos, ext,
                payloadRainFilmReceiver(), payloadRainTopFace(), payloadRainPlant());
        n = applyRainLayerNormal(n, rain, payloadRainTopFace(), payloadRainPlant());
        float impactFootprint = surfaceFootprint(rayConeWidth, rd, n);
        n = applyRainImpactRipples(hitPos, n, rain.puddle, impactFootprint);
        albedo *= 1.0 - rain.darkening;
        float3 p = hitPos + n * SURF_BIAS;

        // Material split. Metals tint F0 by albedo and have no diffuse lobe; dielectrics use a fixed
        // 0.04 F0, sourced from the chit (LabPBR custom/metal F0, or the dielectric default it applies).
        float rough = clamp(payloadRoughness(), 0.0, 1.0);
        float metal = clamp(payloadMetalness(), 0.0, 1.0);
        // Unresolvably tight lobes become exact delta mirrors; everything else keeps its authored value.
        // Snapping to 0 also propagates into the guide buffers, which is what tells RR to hold the
        // reflection sharp instead of accumulating it as a glossy lobe.
        bool exactSpecular = isDeltaAlpha(rough);
        rough = exactSpecular ? 0.0 : rough;
        // This vertex reconnects if it survives to trace a continuation: a delta lobe has no sampling
        // density for a shift's Jacobian to weigh, so only finite lobes qualify. Nothing between here
        // and the lobe selection below touches throughput, so the pre-selection value snapshots here.
        // A-01a: the gate looks at x_{k-1}'s roughness (below), never this vertex's -- a rough
        // continuation is exactly what makes this vertex reconnectable; it is a mirror BEHIND the
        // reconnection that the suffix transfer cannot survive.
        reconQualifies = recordPath && !reconFound && !exactSpecular
                && prevVertexRough >= PATH_RECONNECT_MIN_ALPHA;
        if (reconQualifies) {
            reconTpre = throughput;
        }
        // This vertex is x_{k-1} for the next one.
        prevVertexRough = rough;
        float3 diffAlb = albedo * (1.0 - metal);
        float3 F0 = payload.f0;
        // Probability of continuing along the specular lobe rather than the diffuse one. Hoisted above
        // the NEE below, which needs it to know the density of the ray it is competing with — the
        // continuation is sampled from this mixture, so the mixture is what MIS has to weight against.
        float ps = exactSpecular && luminance(diffAlb) <= 1.0e-6
                       ? 1.0
                       : clamp(luminance(F0) / (luminance(F0) + luminance(diffAlb) + 1.0e-4), 0.1, 0.9);
        // One shading context for the whole vertex: the sun NEE, the emitter RIS and the continuation
        // all have to agree on the specular frame, and rebuilding it three times was how they could
        // quietly stop agreeing. It also carries the anisotropic tangent, decoded once here.
        float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
        // Hoisted above the NEE because the sun's MIS needs to know how much of the non-specular half
        // the walk will take; the lobe itself is selected further down.
        // LabPBR's subsurface byte is a mask with no length in it, and a walk needs a length, so a
        // material that carries only the mask gets SSS_DEFAULT_RADIUS. Requiring an authored radius was
        // tried for one commit — it was meant to explain diamond blocks rendering black, which turned
        // out to be a NaN in the anisotropic lobe and nothing to do with subsurface at all. What the
        // restriction actually did was leave every LabPBR pack with no volumetric subsurface, since
        // none of them can express a radius.
        float3 sssRadius = ext.subsurface.xyz;
        if (max(sssRadius.x, max(sssRadius.y, sssRadius.z)) <= 0.0) {
            sssRadius = float3(SSS_DEFAULT_RADIUS, SSS_DEFAULT_RADIUS, SSS_DEFAULT_RADIUS);
        }
        float walkSssShare = 0.0;
        if (sssMode == 2u && sssMaxEvents > 0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float authoredSss = ext.specular.z;
            // Clamped away from 1 for the same reason ps is clamped away from 0 and 1 a few lines up:
            // a lobe whose selection probability collapses still has to be divided by that probability,
            // so the rare time it IS chosen it carries the reciprocal. At the 0.995 SPBR writes, the
            // diffuse lobe would be picked 0.4% of the time and weighted 250x — a firefly generator,
            // and one that a denoiser turns into a blown-out patch rather than a speckle.
            walkSssShare = clamp(authoredSss > 0.0 ? authoredSss : sss, 0.0, MAX_WALK_LOBE_SHARE);
        }
        BsdfContext bc = makeBsdfContextAniso(hitPos, n, v, rd, diffAlb, F0, rough, false, activeSss,
                                              ext, payload.flags);
        bc.rain = rain;
        // Third continuation lobe: the top water film. Its selection tracks view Fresnel so it becomes
        // common at grazing angles, where the layer actually carries the energy, without wasting half
        // the paths on a two-percent normal-incidence reflection.
        float filmEnergy = rain.film * rainFilmFresnel(bc.ndv);
        float baseEnergy = (1.0 - filmEnergy) * (luminance(F0) + luminance(diffAlb));
        float pf = rain.film > 1.0e-4
                ? clamp(filmEnergy / (filmEnergy + baseEnergy + 1.0e-4), 0.02, 0.90) : 0.0;

        // Same D78A floor as particles. Diffuse only: inventing an all-direction specular environment
        // response would be a second, stronger approximation and was not approved. Metals therefore
        // still need an actual local emitter or traced environment path.
        if ((worldPush.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u) {
            L += throughput * diffAlb * rainBaseTransmission(bc, n)
                    * worldPush.mediumSkyRadiance.xyz;
        }

        // Emissive surfaces (lava, glowstone, torches, ...) add radiance directly, colored by albedo.
        // RIS emitter NEE: the direct-hit emission term is gated only for emitters RIS actually samples
        // (payload emitter-in-list bit) — and then only off diffuse continuation rays (showCelestial
        // false), which the previous vertex's RIS already accounted for; the primary ray and
        // specular/dielectric bounces (showCelestial true) still add it, since no emitter NEE happened
        // along those. Emitters NOT in the light buffer (sparse/sub-threshold footprints, entities) are
        // never NEE-sampled, so they always gather — bit-identical to the no-NEE path, no energy lost.
        // Emission is already the full HDR strength (EMISSIVE_STRENGTH baseline * any override
        // multiplier baked in Java at material-compile time — see MaterialHeader.features packing).
        float emission = payloadEmission();
        // One gate drives both the RIS call below and the direct-hit emission suppression here. They must
        // agree: gateEmitter exists only because RIS already accounted for this emitter, so a bounce where
        // RIS is skipped must also gather emission directly or that light is lost outright.
        // How much of this emitter's own radiance THIS strategy is entitled to, now that the emitter
        // estimator competes for it. Three cases, and they are not interchangeable:
        //
        //   * not in the light buffer -- nothing else samples it, so all of it (entities without a proxy,
        //     sub-threshold footprints; bit-identical to the no-NEE path, no energy lost);
        //   * PROXIED -- what samples it is a sphere standing in for this mesh. Two different domains, so
        //     there is no shared density to weigh and the estimators are partitioned outright: the proxy
        //     owns the diffuse continuation, this owns everything else. That is the 1/0 gate S4a added;
        //   * in the light buffer and NOT proxied -- the emitter strategy samples THIS surface, so the two
        //     densities meet and MIS weighs them. This is what replaces the gate for terrain, and it is
        //     what removes the double count on a specular bounce: RIS at the previous vertex estimated
        //     this emitter through the specular lobe too, and until now both were counted in full.
        float emitterShare = 1.0;
        if (risOn && payloadEmitterInList()) {
            // The 1/0 gate is also what OFF means: FLAG_EMITTER_MIS_OFF sends the second case down the
            // first one's path, which is bit for bit the gate that stood here before M24 S4b.
            if (payloadEmitterProxied() || !emitterMisOn()) {
                emitterShare = showCelestial ? 1.0 : 0.0;
            } else {
                // bsdfPdf is the density of the ray that arrived here; negative marks a DELTA lobe, which
                // has no finite density for the emitter strategy to compete with and therefore takes all
                // of it -- a mirror shows the emitter, and RIS's own specular term vanishes at alpha 0.
                float cosAtEmitter = max(0.0, dot(n, -rd));
                float travelled = max(dot(hitPos - ro, hitPos - ro), 1.0e-6);
                float3 emitted = albedo * emission * worldPush.emitterTint.xyz;
                emitterShare = bsdfPdf < 0.0 ? 1.0
                        : powerHeuristic(bsdfAreaDensity(bsdfPdf, cosAtEmitter, travelled),
                                         emitterAreaDensity(emitted));
            }
        }
        if (emission > 0.0 && emitterShare > 0.0) {
            L += throughput * albedo * emission * worldPush.emitterTint.xyz * emitterShare
                    * rainBaseExitTransmission(bc);
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (worldPush.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = worldPush.lightDir.xyz;
        float3 sampledLightIrradiance = float3(0.0, 0.0, 0.0);
        if (celestialOn) {
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // The emitter spans the steep atmospheric horizon gradient. Sampling only its visibility while
            // reusing the centre's colour is biased precisely at dawn/dusk, so direction and irradiance are
            // one sample just as direction, BRDF and shadow already are.
            sampledLightIrradiance = sampleProviderCelestialIrradiance(
                    worldPush, undyedCelestialIrradiance, lightDir);
            // D176: what the cloud leaves of the sun here. ON THE IRRADIANCE rather than on the shadow
            // ray's transmittance, which is the difference between "less sun arrives" and "something is
            // in the way", and it is the reason one line covers every consumer of this quantity --
            // diffuse, specular, and the back-face transmission term two hundred lines down.
            //
            // NOT applied to the fog or to the cloud itself. The fog's segments routinely run ABOVE the
            // deck, where a map that integrates the whole light ray would shadow a point with cloud that
            // is beneath it; the cloud's own sun term already marches the deck from inside it, at the
            // sample's own altitude, which is exactly what this map cannot do.
            //
            // The same map serves an underwater receiver, at the receiver's own position rather than at
            // the water exit above it, and the refraction offset that ignores is worth a number: at 30
            // blocks depth under a 45-degree sun it is 11 blocks, against a 16-block texel. It only
            // reaches a texel's width past about 45 blocks of water, by which point the sun term this
            // multiplies has been absorbed to nothing anyway.
            sampledLightIrradiance *= cloudSunTransmittance(p);
        }
        float ndl = celestialOn ? max(0.0, dot(n, lightDir)) : 0.0;
        if (ndl > 0.0) {
            VisibilityResult shadow = visibilityThroughAmbient(
                    currentExtinction, activeCurrentMediumFlags(activeMediumFlags),
                    p, lightDir, RAY_FAR);
            float3 vis = shadow.transmittance;
            // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
            // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
            // so it applies to the whole NEE term (diffuse + specular).
            if (mediumFlagsWater(activeCurrentMediumFlags(activeMediumFlags))
                    && waterWaves && shadow.waterHitT > 0.0) {
                vis *= waterCaustic(p + lightDir * shadow.waterHitT, lightDir, shadow.waterHitT);
            }
            if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                float3 h = normalize(lightDir + v);
                float ndh = max(0.0, dot(n, h));
                float ndv = bc.ndv;
                float vdh = max(0.0, dot(v, h));
                // With MIS off, nothing weights this peak down and the floor that the old ggxD epsilon
                // amounted to comes back — otherwise the switch would be comparing MIS against a
                // firefly storm rather than against what the renderer actually shipped. A delta lobe is
                // exempt: alpha stays 0, so a mirror keeps having no specular NEE term at all.
                bool floorAlpha = !(misActive || exactSpecular);
                float ax = floorAlpha ? max(bc.alphaX, UNWEIGHTED_SPEC_ALPHA_FLOOR) : bc.alphaX;
                float ay = floorAlpha ? max(bc.alphaY, UNWEIGHTED_SPEC_ALPHA_FLOOR) : bc.alphaY;
                // The anisotropic form unconditionally: with ax == ay it IS the isotropic one, term for
                // term, so an isotropic material pays four dot products and there is no second code
                // path to fall out of step. This site runs once per vertex, unlike the RIS loop.
                float hx = dot(h, bc.t);
                float hy = dot(h, bc.b);
                float lx = dot(lightDir, bc.t);
                float ly = dot(lightDir, bc.b);
                float g1v = ggxG1Aniso(dot(v, bc.t), dot(v, bc.b), ndv, ax, ay);
                float D = ggxDAniso(hx, hy, ndh, ax, ay);
                float G = g1v * ggxG1Aniso(lx, ly, ndl, ax, ay); // separable Smith masking
                float3 F = fresnelSchlick(vdh, F0);
                // The three lobes the continuation actually samples: Lambertian diffuse, base
                // Cook–Torrance specular, and M21's top water-film Cook–Torrance lobe.
                float3 baseBrdf = diffAlb * INV_PI + (D * G) * F / (4.0 * ndv * ndl);
                float3 sampled = baseBrdf * rainBaseTransmission(bc, lightDir)
                        + rainFilmBrdf(bc, lightDir, floorAlpha);

                // Multiple importance sampling, but only over `sampled`. MIS pairs strategies for one
                // integral, and the strategies here are NEE and the continuation mixture — so the
                // weight applies to exactly the lobes that mixture can draw, and nothing else.
                // Sheen and clearcoat are evaluated but never sampled as a continuation, so NEE is their only
                // estimator and they keep the full contribution; weighting them down would simply lose
                // that energy, because no BSDF ray would ever arrive to make it up. (It is also why the
                // clearcoat highlight stays noisy on a very smooth coat: adding the coat to the
                // continuation mixture is what would fix that, and it is not this change.)
                //
                // A delta specular lobe contributes no density at any direction but its own, so it
                // drops out of the mixture — and D is identically zero there anyway, so the whole
                // specular half of `sampled` vanishes with it. Mirrors are untouched by this.
                // The diffuse share shrinks when a subsurface lobe is in the mixture, and the walk
                // itself contributes no analytic density, so this is the whole of what the continuation
                // can put toward the sun. Getting it wrong loses energy quietly on smooth materials.
                // The same density M24 S4 weighs the emitter estimator against, and deliberately the
                // same function: two spellings of a density that must agree exactly is how a MIS weight
                // silently stops summing to one. Where it matters -- misActive -- floorAlpha is false, so
                // the unfloored form below IS the number the floored expression produced here.
                float pdfB = bsdfContinuationPdf(bc, lightDir, pf, ps, walkSssShare);
                float wLight = misActive ? powerHeuristic(pdfLight, pdfB) : 1.0;

                float3 brdf = sampled * wLight
                        + disneyLobes(bc, ext, lightDir) * rainBaseTransmission(bc, lightDir);
                L += throughput * brdf * sampledLightIrradiance * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters, then M24's temporal reuse of the reservoir it built.
        // Front lobe + SSS backlighting are resampled together and share ONE shadow ray — a light can
        // only be in front of n or behind it, never both, so combining them into one target function is
        // exact, not an approximation. The SSS term is gated off past MAX_SSS_INDIRECT_DEPTH by passing
        // sss=0 there (falls back to plain front-only RIS).
        //
        // hitDepth, not bounce, keys the reservoir: it counts SHADING vertices from zero on both queued
        // branches, which is the thing a reservoir is one of. bounce starts wherever pass A left the
        // split record, so it would leave slot 0 unused on that branch and run off the end of a store
        // sized by depth.
        if (risOn) {
            Reservoir r = risInitial(bc, seed, proposalSeed);
            r = restirReuse(bc, r, uint(hitDepth), sampleIndex, pix, renderSize,
                            impactFootprint, seed, reuseCounts);
            L += throughput * shadeReservoir(
                    currentExtinction, activeCurrentMediumFlags(activeMediumFlags), bc, ext, r,
                    pf, ps, walkSssShare);
        }

        // Thin-surface SSS transmission. Light entering from the back face scatters through toward the
        // viewer via a forward-biased HG phase (leaves/grass backlit by the sun glow when you look
        // through them toward the light source). Shadow ray fires from the back face to avoid
        // self-occlusion. cosT = dot(lightDir, rd): rd tracks the ray toward the sun when the sun is
        // behind the slab, giving cosT ≈ 1 → forward-scatter peak.
        // The thin-shell transmission term stays live in random-walk mode too, and deliberately. It is
        // the DIRECT lighting estimate — one shadow ray toward the sun — while the walk is a
        // CONTINUATION lobe. They estimate different things and do not double count: the walk carries a
        // path onward into the scene, the term below answers "what does the sun put through this slab
        // right now". Next-event estimation for a random walk is a research problem, and the reduced
        // estimator is exactly where a cheap approximation belongs.
        if (celestialOn && sssMode != 0u && sss > 0.0
                && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibilityThroughAmbient(
                        currentExtinction, activeCurrentMediumFlags(activeMediumFlags),
                        hitPos - n * SURF_BIAS, lightDir, RAY_FAR);
                float3 visB = shadowBack.transmittance;
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (mediumFlagsWater(activeCurrentMediumFlags(activeMediumFlags))
                        && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowBack.waterHitT,
                                         lightDir, shadowBack.waterHitT);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * sssStrength() * sssPhase(cosT) * backNdl
                            * rainBaseExitTransmission(bc)
                            * sampledLightIrradiance * visB;
                }
            }
        }

        // Preserve all local lighting at the terminal hit, but do not sample a continuation that the
        // bounce loop cannot trace.
        if (bounce >= maxBounces) {
            break;
        }

        // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
        // relative reflectance (ps, hoisted above the NEE that had to weigh against it).
        // How much of the non-specular half goes to the walk. The JSON weight, when a pack authored one,
        // overrides the LabPBR mask outright rather than scaling it — same precedence as every other
        // authored channel, and it means a rule that mentions only sheen leaves the texel mask alone
        // instead of silently zeroing subsurface (weight 0 falls through to sss).
        float walkSss = walkSssShare;

        float lobeChoice = rndf(seed);
        if (lobeChoice < pf) {
            float alpha = rain.filmAlpha;
            float3 h = sampleGGXVNDF(n, v, alpha, seed);
            float3 l = reflect(rd, h);
            float ndl2 = dot(n, l);
            if (ndl2 <= 0.0) {
                break;
            }
            float3 F = fresnelSchlick(max(0.0, dot(v, h)),
                    float3(WATER_FILM_F0, WATER_FILM_F0, WATER_FILM_F0));
            throughput *= rain.film * F * ggxG1(ndl2, alpha) / pf;
            rayConeSpread = max(rayConeSpread, alpha * RAY_CONE_GLOSSY_SPREAD_SCALE);
            bsdfPdf = pf * ggxReflectPdf(max(0.0, dot(n, h)), bc.ndv, alpha,
                    ggxG1(bc.ndv, alpha));
            ro = p;
            rd = l;
            showCelestial = true;
        } else if ((lobeChoice - pf) / max(1.0 - pf, 1.0e-6) < ps) {
            float3 l;
            if (exactSpecular) {
                // Authored zero is a delta distribution, not a narrow finite GGX lobe. This avoids the
                // degenerate VNDF at alpha=0 and preserves a genuinely sharp reflection without a floor.
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0)
                        * rainBaseTransmission(bc, l) / ((1.0 - pf) * ps);
                bsdfPdf = -1.0;
            } else {
                float3 h = sampleGGXVNDFAniso(n, bc.t, bc.b, v, bc.alphaX, bc.alphaY, seed);
                l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
                float ndl2 = dot(n, l);
                if (ndl2 <= 0.0) {
                    break; // microfacet reflects below the surface: terminate this path
                }
                // VNDF + separable Smith ⇒ weight = F · G2/G1(v) = F · G1(l); divide by the lobe pdf ps.
                float3 F = fresnelSchlick(max(0.0, dot(v, h)), F0);
                throughput *= F * ggxG1Aniso(dot(l, bc.t), dot(l, bc.b), ndl2, bc.alphaX, bc.alphaY)
                        * rainBaseTransmission(bc, l) / ((1.0 - pf) * ps);
                // The cone grows by the wider axis: it bounds the texture footprint, and taking the
                // narrower one would sharpen mip selection along the stretched direction, which is
                // exactly where it should blur.
                rayConeSpread = max(rayConeSpread,
                        max(bc.alphaX, bc.alphaY) * RAY_CONE_GLOSSY_SPREAD_SCALE);
                bsdfPdf = (1.0 - pf) * ps * ggxReflectPdfAniso(
                                                  dot(h, bc.t), dot(h, bc.b), max(0.0, dot(n, h)),
                                                  bc.ndv, bc.alphaX, bc.alphaY, bc.g1v);
            }
            ro = p;
            rd = l;
            showCelestial = true; // specular lobe: this ray is a (glossy) mirror — show the disc
        } else if (walkSss > 0.0 && rndf(seed) < walkSss) {
            // The subsurface lobe. Everything about it happens inside this one shading vertex — the
            // walk enters, scatters and leaves before the path continues — so nothing here reaches the
            // continuation record or the pass boundary.
            //
            reconQualifies = false; // the walk's exit direction has no analytic density to reconnect on
            float lobeProb = (1.0 - pf) * (1.0 - ps) * walkSss;
            WalkResult walk = randomWalkSubsurface(hitPos, n, diffAlb, sssRadius, ext.specular.w,
                                                   sssMaxEvents, seed);
            if (walk.absorbed) {
                break; // roulette inside the walk: an unbiased stop, so this path is simply over
            }
            if (walk.escaped) {
                throughput *= walk.weight * rainBaseTransmission(bc, walk.dir) / lobeProb;
                ro = walk.origin;
                rd = walk.dir;
            } else {
                // Ran out of events. Fall back to the cheap lobe rather than dropping the energy —
                // truncating here would darken the thickest parts of a material, which reads as dirt
                // rather than as a budget. This is the knob SUBSURFACE_MAX_EVENTS turns.
                throughput *= diffAlb * rainBaseTransmission(bc, n) / lobeProb;
                ro = p;
                rd = cosineDir(n, seed);
            }
            // The exit direction has no analytic density — it depends on where inside the material the
            // photon happened to turn around — so there is nothing for the sun's MIS to weigh against
            // and this path takes the drawn sprite rather than the light.
            bsdfPdf = -1.0;
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false;
        } else {
            float lobeProb = (1.0 - pf) * (1.0 - ps) * (1.0 - walkSss);
            float3 l = cosineDir(n, seed);
            throughput *= diffAlb * rainBaseTransmission(bc, l) / lobeProb;
            ro = p;
            rd = l;
            bsdfPdf = lobeProb * max(0.0, dot(n, rd)) * INV_PI;
            rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
            showCelestial = false; // diffuse lobe: sprite covered by NEE, hide it (anti-firefly)
        }

        // The continuation committed: this is the reconnection vertex. Snapshot what the suffix needs
        // -- everything L holds NOW is prefix, everything added AFTER this point is the suffix; the
        // post-selection throughput is what divides that tail into an incident-radiance estimate.
        bool firstReconThisBounce = reconQualifies;
        if (firstReconThisBounce) {
            reconFound = true;
            reconQualifies = false;
            reconPos = hitPos;
            reconNrm = n;
            reconWi = octEncode(rd);
            reconDepth = uint(hitDepth);
            reconLpre = L;
            reconTpost = throughput;
        }

        // ---- M28 S2/S3 candidates: last frame's record at this point (temporal shift) and K
        // neighbours' records (spatial shift), re-weighted by THIS vertex. Validation and evaluation
        // are ONE spelling -- restir_pt.slang's evalPathSuffixCandidate -- with only the surface
        // predicate differing between the two shifts. Values accumulate; the pixel takes the weighted
        // average once the history carries PATH_RESERVOIR_APPLY_MIN_M independent candidates (D221),
        // and the survivor write happens after the loop, where the own suffix finally exists.
        //
        // The temporal read is reprojected exactly like M24's d0 -- but only where that is
        // meaningful: the reprojection answers "where was THIS point on screen", which is the
        // primary hit's question. A deeper reconnection vertex is off the camera ray, so it reads its
        // own pixel, which is the arrangement M24's bounce vertices already live under. Spatial
        // neighbours are searched around that same reprojected centre, from the same (previous) half,
        // for the reason restir.slang's spatial loop gives: there is no seam in this dispatch where
        // every record is written and nothing is shaded yet.
        //
        // Evaluate history only at the vertex whose prefix/suffix state was snapshotted above.
        // Later bounces belong to the same suffix and must not merge that history a second time.
        if (recordPath && firstReconThisBounce) {
            uint pixelIndex = pix.y * renderSize.x + pix.x;
            uint readPixel = pixelIndex;
            bool haveHistory = true;
            if (reconDepth == 0u) {
                haveHistory = restirPreviousPixel(hitPos, renderSize, readPixel);
            }
            // Acceptance attribution (the D211 rule): one pixel in sixteen reports its outcome, so
            // "the switch did nothing" can never mean "the merge silently never fired".
            bool sampled = worldPush.pathReservoirStatsAddr != 0u
                    && ((pixelIndex ^ worldPush.frameIndex) & 15u) == 0u;
            if (sampled && haveHistory) {
                InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[0], 1u);
            }
            DevicePtr<PackedPathReservoir> store =
                    DevicePtr<PackedPathReservoir>(worldPush.pathReservoirAddr);
            uint prevParity = (worldPush.frameIndex & 1u) ^ 1u;
            // The footprint one pixel covers at this vertex, in world units -- the spatial lateral
            // bound is measured in these, for the same reason M24's is.
            float footprint = surfaceFootprint(rayConeWidth, -v, n);

            // ---- temporal candidate: last frame's record at the reprojected pixel.
            if (haveHistory) {
                PackedPathReservoir stored = store[pathReservoirSlot(readPixel,
                        renderSize.x * renderSize.y, prevParity)];
                float3 candValue;
                float candCount;
                float candMeanTarget;
                if (evalPathSuffixCandidate(bc, diffAlb, F0, pf, ps, walkSssShare, v,
                                            hitPos, n, reconTpre, footprint, reconDepth, false,
                                            seed, stored, candValue, candCount, candMeanTarget)) {
                    temporalValue = candValue;
                    temporalCount = candCount;
                    // A-02b: selection weights live in the RECEIVER domain (this vertex's
                    // re-evaluated luminance), matching Enhanced §6.3's survivor-representativeness.
                    // Unbiasedness needs draw/sums/t_chosen in ONE domain -- all three switched
                    // together below; the record's home-domain targetSum/m stays a next-frame hint.
                    temporalSum = candCount * luminance(candValue);
                    temporalReadPixel = readPixel;
                    if (sampled) {
                        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[1], 1u);
                        // A-01a's plate: how many accepted candidates sit behind a specular chain.
                        // Decides whether the replay sub-loop has an estimator role here at all.
                        if (reconDepth > 0u) {
                            InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[5], 1u);
                        }
                    }
                }
            }

            // ---- spatial candidates (S3a): the independent samples that push a history past the
            // application gate. Accumulate values, counts and target sums; stream a survivor draw
            // among the accepted neighbours (target-proportional) for the post-loop write.
            for (uint i = 0u; i < pathReuseSpatialNeighbours()
                              && temporalCount + spatialCount < PATH_RESERVOIR_M_CAP; i++) {
                int2 centre = int2(int(readPixel % renderSize.x), int(readPixel / renderSize.x));
                int2 q = centre + spatialNeighbourOffset(seed);
                if (any(q < int2(0, 0)) || any(q >= int2(renderSize))) {
                    continue;
                }
                uint neighbourPixel = uint(q.y) * renderSize.x + uint(q.x);
                if (neighbourPixel == readPixel) {
                    continue; // that is the temporal candidate; counting it twice would double its say
                }
                if (sampled) {
                    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[2], 1u);
                }
                uint neighbourSlot = pathReservoirSlot(neighbourPixel,
                        renderSize.x * renderSize.y, prevParity);
                PackedPathReservoir candidate = store[neighbourSlot];
                float3 candValue;
                float candCount;
                float candMeanTarget;
                // composite.path-replay's reject branch retired with the switch (D231): what remains
                // is the in-place evaluation its off state always ran, until R2 retires that too.
                bool accepted = evalPathSuffixCandidate(bc, diffAlb, F0, pf, ps, walkSssShare, v,
                        hitPos, n, reconTpre, footprint, reconDepth, true,
                        seed, candidate, candValue, candCount, candMeanTarget);
                if (!accepted) {
                    continue;
                }
                if (sampled) {
                    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[3], 1u);
                    if (reconDepth > 0u) {
                        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[5], 1u);
                    }
                }
                // A-02b: same receiver-domain switch as the temporal candidate above -- one spelling.
                float candWeight = candCount * luminance(candValue);
                spatialValue += candCount * candValue;
                spatialCount += candCount;
                spatialTargetSum += candWeight;
                if (rndf(seed) * spatialTargetSum < candWeight) {
                    spatialWinSlot = neighbourSlot;
                    spatialWinTarget = luminance(candValue);
                }
            }
        }

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    // The path reservoir post-loop (M28 S2/S3): the suffix is this path's tail past the reconnection
    // vertex, recovered exactly by subtraction -- the adds into L never changed. Then the merge: the
    // pixel takes the weighted average of its own sample and every accepted candidate, once the
    // candidates carry PATH_RESERVOIR_APPLY_MIN_M independent samples in total (D221: under RR a
    // single-sample history is strictly worse than no merge). The surviving representative is then
    // written for next frame.
    //
    // SURVIVOR, NOT SUM, goes to storage: a path suffix cannot be averaged (two directions are not one
    // direction), so the reservoir keeps one representative drawn target-proportionally, with
    // W = targetSum / (m * t_chosen). That W is what keeps W * target(y') unbiased wherever the record
    // is re-evaluated -- the standard RIS identity, and the reason the count m can grow while the
    // weight stays O(1). t_chosen is the chosen sample's target WITHOUT its own stored W: the stored W
    // multiplies whatever this vertex's re-evaluation gives next frame, so a winner's receiver-domain
    // luminance(candValue) -- which already carries its old W -- has that W multiplied back out below
    // (D230, audit B03: leaving it in divided every carried history by its previous W, and a static
    // chain in the merged regime came out +16% to +5600% bright depending on the lighting's range).
    // D219's fresh-single-sample rule still governs the INERT regime: with fewer
    // candidates than the gate, nothing is applied and the record is written fresh, so no stale
    // single sample ever gets re-broadcast through the temporal link.
    if (recordPath && reconFound) {
        float3 ownSuffix = max(L - reconLpre, float3(0.0, 0.0, 0.0));
        float3 tRel = max(reconTpost, float3(1.0e-6, 1.0e-6, 1.0e-6));
        float ownTarget = luminance(ownSuffix);
        float extraCount = temporalCount + spatialCount;
        bool merged = extraCount >= PATH_RESERVOIR_APPLY_MIN_M;
        // The applied lane lives here, not in the candidate loop: the gate is what it counts. D223
        // note -- S3a's restructure dropped this increment and the acceptance log then read applied
        // zero while the merge was quietly crossing the gate on a few percent of pixels.
        if (merged && worldPush.pathReservoirStatsAddr != 0u
                && (((pix.y * renderSize.x + pix.x) ^ worldPush.frameIndex) & 15u) == 0u) {
            InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[4], 1u);
        }
        if (merged) {
            // The pixel sees the candidate-weighted average (each candidate standing for its count);
            // own luminance above stays the fresh sample's own target, unbias either way.
            float3 applied = (ownSuffix + temporalCount * temporalValue + spatialValue)
                    / (1.0 + extraCount);
            L = reconLpre + applied;
        }
        // Survivor: target-proportional over own and the two shift representatives.
        float totalTarget = ownTarget + temporalSum + spatialTargetSum;
        bool temporalWins = false;
        bool spatialWins = false;
        if (merged) {
            float draw = rndf(seed) * totalTarget;
            temporalWins = temporalSum > 0.0 && draw >= ownTarget
                    && draw < ownTarget + temporalSum;
            spatialWins = !temporalWins && spatialTargetSum > 0.0
                    && draw >= ownTarget + temporalSum;
        }
        DevicePtr<PackedPathReservoir> store =
                DevicePtr<PackedPathReservoir>(worldPush.pathReservoirAddr);
        uint writeSlot = pathReservoirSlot(pix.y * renderSize.x + pix.x,
                                           renderSize.x * renderSize.y, worldPush.frameIndex & 1u);
        uint prevParity = (worldPush.frameIndex & 1u) ^ 1u;
        if (temporalWins) {
            PackedPathReservoir prev = store[pathReservoirSlot(temporalReadPixel,
                    renderSize.x * renderSize.y, prevParity)];
            float writtenM = 1.0 + extraCount;
            float chosenTarget = temporalSum / temporalCount;
            // chosenTarget is luminance(candValue), which already carries prev's W -- multiply it back
            // out (see the banner above: the stored W must not be divided by itself).
            store[writeSlot] = packPathReservoir(prev.pathSeed, prev.reconPos,
                    octDecode(unpackUnorm16x2(prev.reconNrm)),
                    unpackUnorm16x2(prev.wi), unpackRgb9e5(prev.li),
                    writtenM, totalTarget,
                    chosenTarget > 0.0
                            ? asfloat(prev.W) * totalTarget / (writtenM * chosenTarget) : 0.0,
                    prev.reconIndex);
        } else if (spatialWins) {
            PackedPathReservoir prev = store[spatialWinSlot];
            float writtenM = 1.0 + extraCount;
            // Same correction as the temporal winner: spatialWinTarget carries the neighbour's W.
            store[writeSlot] = packPathReservoir(prev.pathSeed, prev.reconPos,
                    octDecode(unpackUnorm16x2(prev.reconNrm)),
                    unpackUnorm16x2(prev.wi), unpackRgb9e5(prev.li),
                    writtenM, totalTarget,
                    spatialWinTarget > 0.0
                            ? asfloat(prev.W) * totalTarget / (writtenM * spatialWinTarget) : 0.0,
                    prev.reconIndex);
        } else {
            // Own survives, or nothing merged: a fresh single-sample record (D219), except that a
            // merged write carries the accepted candidates' counts in m and their targets in
            // targetSum -- the accumulation the application gate and next frame's weights read.
            float writtenM = merged ? 1.0 + extraCount : 1.0;
            float writtenTarget = merged ? totalTarget : ownTarget;
            store[writeSlot] = packPathReservoir(pathSeedBase, reconPos, reconNrm, reconWi,
                    ownSuffix / tRel, writtenM, writtenTarget,
                    writtenTarget > 0.0 ? writtenTarget / (writtenM * ownTarget) : 0.0,
                    reconDepth);
        }
    } else if (recordPath) {
        // No reconnection vertex this frame -- sky, a mirror to the sky, the bounce cap, the walk lobe,
        // a microfacet reflected below the surface. Write an EMPTY record rather than leave the slot
        // alone: unlike M24's DI store, whose reuse re-traces visibility, a path record carries radiance,
        // and a slot this frame does not reach would hand its neighbours and the temporal link a suffix
        // of arbitrary age (D230, audit B05). Only leaf 0 / sample 0 ever gets here, once per frame.
        DevicePtr<PackedPathReservoir> store =
                DevicePtr<PackedPathReservoir>(worldPush.pathReservoirAddr);
        store[pathReservoirSlot(pix.y * renderSize.x + pix.x, renderSize.x * renderSize.y,
                                worldPush.frameIndex & 1u)] = emptyPathReservoir();
    }
    return L;
}
