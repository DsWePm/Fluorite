// Indirect pass (pass B of the wavefront split — see docs/WAVEFRONT_PLAN.md).
//
// Resumes the continuations the primary pass queued and runs the bounce loop: NEE toward the sun, RIS
// emitter lighting, thin-surface SSS, BSDF continuation and Russian roulette. It captures no guides and
// owns no motion vectors — guides and primary are deliberately absent from the include list
// below, so guide state cannot leak into this pass by accident.
//
// Lighting is iterative here rather than recursive from closest-hit, so maxRayRecursionDepth stays 1.
// At each surface vertex:
//   * NEE toward the sun — a single analytic directional light, gated by one hit-object visibility
//     query (TerminateOnFirstHit | SkipClosestHit). Lambertian BRDF = albedo/PI.
//   * a cosine-weighted Lambertian continuation ray (throughput *= albedo; the PI and cos cancel).
// Sky / ambient light needs no separate AO pass: continuation rays that escape land in world.rmiss,
// which returns sky radiance — so soft sky fill and contact AO both fall out of the path integral.
//
// Each frame emits a single SPP-averaged (still noisy) estimate; temporal convergence is the denoiser's
// job. With RR disabled the output is the raw noisy path trace — a reference view, not a converged
// image. Output is HDR (R16G16B16A16_SFLOAT); the HDR -> LDR tonemap happens at the display.comp seam.
//
// The build emits an ordinary TraceRay fallback and an optional EXT SER variant from this source.
import world_common;
import world_core;
import math;
import bsdf;
import rain_surface;
import medium;
import sky_light_field;
import volume;
import volume_source; // debug 25 shares D66's exact resolution and multiplier helpers
import volume_visibility; // debug view 18 reads the grid directly
import cloud;
import segment;
import water;
import trace;
#ifdef FLUORITE_ENABLE_EXT_SER
import trace_ser;
#endif
import subsurface;
import lighting;
import restir; // temporal reservoir reuse at both RIS vertices
// The transmittance table. Raygen already has binding 10 in its stage flags — the NEE consumers below
// are next in line for it — and debugView 12 reads it here.
import atmosphere;
import atmosphere_lut;
import environment;

public struct FirstSegmentTrace {
    public float3 inScatter;
    public float segmentEnd;
    public float3 transmittance;
    public float hitT;
    public float3 throughput;
    public uint material;
    public uint escaped;
    public uint mediumProfile;
};

FirstSegmentTrace emptyFirstSegmentTrace(PathSegment seg, uint activeMediumFlags) {
    FirstSegmentTrace first;
    first.inScatter = float3(0.0, 0.0, 0.0);
    first.segmentEnd = 0.0;
    first.transmittance = float3(1.0, 1.0, 1.0);
    first.hitT = -1.0;
    first.throughput = seg.throughput;
    first.material = 0xFFFFFFFFu;
    first.escaped = 1u;
    first.mediumProfile = mediumProfileFlags(activeCurrentMediumFlags(activeMediumFlags));
    return first;
}

// Slang's ordinary `in` struct parameter is lowered by transformParamsToConstRef; runtime telemetry
// showed that lowering changed nested Medium.flags from WATER (1) to WATER|AMBIENT (3). An explicit
// `ref` bypasses that copy/lowering seam. Treat the referenced record as immutable: its transport fields
// are copied once into primitive active state below, which is the only representation mutated by bounces.
// `sampleIndex` is also the reservoir store's PATH index: main() forms it as `s + leaf * spp`, which is
// exactly the plane numbering restir.slang needs to keep this invocation's history separate from the
// other spp samples of this pixel and from the refraction split's. The two uses are the same number for
// the same reason -- it identifies which of a pixel's paths this is -- so it is deliberately not a
// second parameter.
public float3 tracePath(__ref PathSegment seg, uint activeMediumFlags,
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

        // Russian roulette on deeper bounces: survive with prob = max channel, rescale survivors.
        if (bounce >= rrStart) {
            float q = clamp(max(throughput.r, max(throughput.g, throughput.b)), 0.02, 1.0);
            if (rndf(seed) > q) {
                break;
            }
            throughput /= q;
        }
    }
    return L;
}

// ---- Volume diagnostics (debugView 8..10), owned by this pass because the guide buffers pass A can
// visualize say nothing about what happens BETWEEN hits.
//
// Every one of them describes the same thing — the queued record's first segment — so they can be read
// against each other pixel by pixel: how long it was, what medium it was in, what the sun could see from
// its origin, and what all that produced. Written because the water's in-scatter was reasoned about three
// times from the formula and the formula was right each time.
//
// These go through exposure and the tonemap like any other frame, since they are written into the same
// image. For 8 that is what is wanted (it is radiance). For 9 and 10 read them as "black or not black"
// and as ordering, not as absolute values.
static const uint DEBUG_VIEW_VOLUME_INSCATTER = 8u;
static const uint DEBUG_VIEW_VOLUME_SEGMENT = 9u;
static const uint DEBUG_VIEW_VOLUME_SUNVIS = 10u;
// The caustic's CHROMATIC DEVIATION, amplified. Not the caustic itself: showing the raw factor could
// not distinguish "the three channels are identical" from "they differ by a few percent", and a few
// percent is invisible against a black-and-white pattern — which is exactly the ambiguity that made a
// first attempt at this view useless. Each channel is divided by the mean and the difference multiplied
// up, so identical channels are EXACTLY neutral grey and any real spread is a strong tint.
static const uint DEBUG_VIEW_WATER_CAUSTIC = 11u;
static const float CAUSTIC_DEVIATION_GAIN = 20.0;

// The aerial-perspective froxel, read at the depth the slider is parked on, across the whole screen.
//
// Left is what the air ADDS between the eye and that depth, right is what it LEAVES of whatever is behind
// it — the two halves of the same integral, and the pair a consumer will multiply and add with. Split so
// a grid that is all in-scatter and no extinction, or the reverse, is obvious before anything downstream
// starts using it.
//
// Expected at the far slice: a faint wash that follows the fog's height profile, brighter toward the sun,
// with the transmittance darkest where the sightline is longest and lowest. Expected at the NEAR slices:
// almost nothing, which is the point of distributing the depth axis exponentially (see froxelSliceDepth).
static const uint DEBUG_VIEW_FROXEL = 17u;
static const float FROXEL_DEBUG_SLICE = 0.95; // near the far edge, where there is something to see

// The volumetric visibility grid, at this pixel's own camera segment. See volumeVisibilityDebug.
static const uint DEBUG_VIEW_VOLUME_VISIBILITY = 18u;
// M27. 29 rather than 28: 12-16, 19, 25 and 28 are retired and their numbers are never reused, so that
// every note naming a view by number stays true.
static const uint DEBUG_VIEW_SKY_LIGHT = 29u;
// The value the FOG ends up with, region split included -- as opposed to the two views above, which show
// the two fields raw and cannot say which of them a given point actually reaches.
static const uint DEBUG_VIEW_FOG_SKY_OPEN = 30u;


// The exact camera-prefix in-scatter consumed by the normal compositor, before any leaf radiance, RR,
// exposure or tonemap. Unlike debug 8, this is the camera-to-first-interface segment rather than the
// queued first leaf. It exists to distinguish "the water estimator is alive somewhere in the path" from
// "the water term the normal image actually adds is alive".
static const uint DEBUG_VIEW_CAMERA_PREFIX = 20u;

// Full raw compositor with and without the camera prefix in alternating equal-screen bands. This runs
// the normal leaf loop but bypasses RR, so it answers whether the prefix survives the actual HDR sum.
// The lower quarter paints its luminance fraction (R = 8x, G = 2x, B = 1x) instead of scene colour.
static const uint DEBUG_VIEW_COMPOSITE_PREFIX_AB = 21u;
// M11: which stage of the cloud chain is empty. See cloud.slang cloudDebugProbe for the colour key.
static const uint DEBUG_VIEW_CLOUD_PROBE = 22u;
// M12: the water simulation's height field, and whether its domain is live at all.
static const uint DEBUG_VIEW_WATER_SIM = 23u;
// 24: the same field, but sampled the way the SHADING samples it. 23 proves the field has content; this
// proves the content reaches the surface. They fail independently, which is the whole reason for two.
static const uint DEBUG_VIEW_WATER_SIM_REACH = 24u;
float3 froxelDebug(float2 uv) {
    // Screen uv straight into the grid's x and y: the froxel is laid over this same frustum, and a debug
    // view that resampled it through anything else would be testing the resampling.
    // No vertical flip: the froxel's y comes from the same ndc the primary rays use, so screen up
    // is grid up. The 1.0 - uv.y that was here is the third time that flip has been copied from a view
    // whose axis meant something else.
    float3 p = float3(uv.x < 0.5 ? uv.x * 2.0 : (uv.x - 0.5) * 2.0, uv.y, FROXEL_DEBUG_SLICE);
    float4 f = aerialPerspectiveLut.SampleLevel(p, 0.0);
    if (uv.x < 0.5) {
        return f.rgb;
    }
    return float3(f.a, f.a, f.a);
}

/**
 * Four equal vertical bands through the actual raygen binding and runtime push values:
 *   1. decoded base, 2. decoded detail, 3. D66 base/detail resolved weights, 4. final multiplier.
 *
 * A fixed world-space distance makes this a spatial slice instead of allowing scene depth to scramble
 * the field. The footprint is the one a full camera-to-cull interval receives under the configured cap.
 */
// The volumetric visibility grid (M13.2), sampled at the MIDPOINT of this pixel's camera segment.
//
// NAMED BANDS, NOT A GRADIENT, and the bottom two exist because the first version of this view could not
// tell a correct reading from the failure it was built to find. A scalar drawn as a shade asks the reader
// to judge a number through a tonemap, which eyes do not do; quantised, the answer is a colour that can be
// named and spoken aloud.
//
//   navy : sealed, below the sampled grid's own quantum   cyan : a FLOOR -- small but not zero
//   blue : a little   green : some   yellow : about half   orange : most of it   red : fully open
//
// WHY NAVY AND CYAN ARE SEPARATE. The bands were uniform fifths, so the lowest one covered 0 to 0.2 and
// painted a stalled accumulator sitting at 0.125 the same colour as a genuinely sealed cell. A sealed room
// with visibly lit fog was reported as "the grid says sealed, so the grid is fine" -- and the grid was not
// fine. A diagnostic whose resolution is coarser than the defect it is pointed at will confirm whichever
// answer it was aimed with. The split is at 1.5/255 because that is where the UNORM8 grid the consumers
// actually sample rounds to zero: below it the fog receives nothing, above it the fog receives something.
//
// Dimmed means the cell has not yet gathered its full sample count, so convergence is visible without
// another hue. Black is a point outside the grid or with no grid published.
//
// The midpoint of the camera segment rather than a fixed distance: a fixed distance would report the air
// on the far side of a wall while the player stands in a room looking at that wall, which is the exact
// case this grid was built for and the exact case a fixed distance answers wrongly.
//
// This is the grid ITSELF, with no fog arithmetic anywhere in it. If the room reads black here and its fog
// is still lit, the grid is right and the consumer is wrong; if it reads yellow here, the grid is wrong and
// nothing downstream can fix it.
/**
 * Minecraft's sky light at this pixel's own camera segment, with no fog arithmetic anywhere in it.
 *
 * <p>Deliberately the same midpoint and the same bands as the visibility view beside it, so the two can
 * be flipped between and compared directly -- they answer different questions about the same point, and
 * the whole reason this field exists is that one of them has range where the other does not. Read them
 * together: where the visibility view is navy and this one is not, the flood fill disagrees with the
 * hemisphere integral, which past 32 blocks is the only one of the two that has an opinion at all.
 *
 * <p>MIND THE MIDPOINT. Like its neighbour this samples halfway between the eye and where the primary
 * pass stopped, so a pixel showing water is sampling wherever the refracted continuation ended and will
 * jump about. Read it on stone.
 */
/**
 * Where the two volumetric field views sample, shared so that flipping between them compares one point.
 *
 * <p>Zero is the midpoint they have always used, so the published diagnostic is unchanged with the knob
 * down. Anything else is a fixed distance along the view ray, which is the reading that needs no caveat
 * about where the geometry behind the pixel happens to be.
 */
float3 debugProbePoint(uint2 dispatchIndex, uint2 dimensions, float3 hitPos) {
    CameraRay cam = cameraRay(dispatchIndex, dimensions);
    if (pc.debugProbe <= 0.0) {
        return cam.origin + (hitPos - cam.origin) * 0.5;
    }
    return cam.origin + normalize(cam.dir) * pc.debugProbe;
}

/**
 * What the marched fog actually multiplies its sky source by, at the probe point.
 *
 * <p>THE OTHER TWO VIEWS SHOW FIELDS; THIS SHOWS THE ANSWER. The fog splits its march at the visibility
 * box and reads a different field on each side, so a point can be navy in one view and irrelevant to the
 * fog at that point -- which is exactly the ambiguity that has made every reading so far need a caveat.
 * This runs the same expression integrateNoisyAmbientRegion runs, deciding the region by whether the
 * probe point is inside the box, so a bright pixel here is light the fog is genuinely being given.
 *
 * <p>Bands are the neighbours': navy is what the consumers see as nothing, cyan is a small nonzero floor,
 * red is fully open. Read it with Debug Probe Distance set, or the midpoint caveat applies here too.
 */
float3 fogSkyOpenDebug(uint2 dispatchIndex, uint2 dimensions, float3 hitPos) {
    float3 p = debugProbePoint(dispatchIndex, dimensions, hitPos);
    float cell = worldPush.visGridOrigin.w;
    float3 lo = worldPush.visGridOrigin.xyz;
    float3 hi = lo + cell * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D));
    bool inBox = cell > 0.0 && all(p >= lo) && all(p <= hi);

    float open;
    if (inBox) {
        open = volumeSkyOpenness(p);
    } else if ((worldPush.environmentFlags & ENVIRONMENT_FAR_FOG_SKY_LIGHT) != 0u) {
        open = pow(skyLightAt(p), worldPush.skyLightField.w);
    } else {
        open = 1.0;
    }

    if (open < 1.5 / 255.0) {
        return float3(0.0, 0.0, 0.35);   // navy   : the fog is given nothing here
    }
    if (open < 0.05) {
        return float3(0.0, 0.85, 0.95);  // cyan   : a floor -- small, nonzero, and lighting the fog
    }
    int band = clamp(int((open - 0.05) * (5.0 / 0.95)), 0, 4);
    return band == 0 ? float3(0.1, 0.25, 1.0)
         : band == 1 ? float3(0.0, 0.7, 0.2)
         : band == 2 ? float3(0.9, 0.9, 0.0)
         : band == 3 ? float3(1.0, 0.45, 0.0)
                     : float3(0.9, 0.0, 0.0);   // red : fully open, the pre-M27 constant
}

float3 skyLightDebug(uint2 dispatchIndex, uint2 dimensions, float3 hitPos) {
    float3 p = debugProbePoint(dispatchIndex, dimensions, hitPos);
    float open = skyLightAt(p);
    if (open < 1.5 / 255.0) {
        return float3(0.0, 0.0, 0.35);   // navy   : sky light 0, the sealed answer
    }
    if (open < 0.05) {
        return float3(0.0, 0.85, 0.95);  // cyan   : a floor -- one step of flood fill away from sealed
    }
    int band = clamp(int((open - 0.05) * (5.0 / 0.95)), 0, 4);
    return band == 0 ? float3(0.1, 0.25, 1.0)
         : band == 1 ? float3(0.0, 0.7, 0.2)
         : band == 2 ? float3(0.9, 0.9, 0.0)
         : band == 3 ? float3(1.0, 0.45, 0.0)
                     : float3(0.9, 0.0, 0.0);   // red : sky light 15, fully open
}

float3 volumeVisibilityDebug(uint2 dispatchIndex, uint2 dimensions, float3 hitPos) {
    float3 p = debugProbePoint(dispatchIndex, dimensions, hitPos);
    float open = volumeSkyOpenness(p);
    if (open < 1.5 / 255.0) {
        return float3(0.0, 0.0, 0.35);   // navy   : sealed, and the consumers see exactly zero
    }
    if (open < 0.05) {
        return float3(0.0, 0.85, 0.95);  // cyan   : a floor -- small, nonzero, and lighting the fog
    }
    int band = clamp(int((open - 0.05) * (5.0 / 0.95)), 0, 4);
    return band == 0 ? float3(0.1, 0.25, 1.0)
         : band == 1 ? float3(0.0, 0.7, 0.2)
         : band == 2 ? float3(0.9, 0.9, 0.0)
         : band == 3 ? float3(1.0, 0.45, 0.0)
                     : float3(0.9, 0.0, 0.0);
}

float3 volumeDebug(PackedPathSegment packed, uint pixelIndex) {
    PathSegment seg = unpackPathSegment(packed);
    traceRadiance(seg.bounce == 0 ? CULL_PRIMARY : secondaryCullMask(),
                   seg.ro, 0.0, seg.rd, RAY_FAR, seg.showCelestial, false,
                   seg.rayConeWidth, seg.rayConeSpread);
    bool escaped = payload.hitT < 0.0;
    float segmentEnd = escaped ? RAY_FAR : payload.hitT;

    if (pc.debugView == DEBUG_VIEW_VOLUME_SEGMENT) {
        // R: the segment hit nothing and therefore ran the full RAY_FAR — the case whose in-scatter should
        // saturate. G: length, ramped to 64 blocks. B: the medium is water.
        return float3(escaped ? 1.0 : 0.0,
                      min(segmentEnd * (1.0 / 64.0), 1.0),
                      mediumIsWater(seg.medium.current) ? 1.0 : 0.0);
    }
    if (pc.debugView == DEBUG_VIEW_WATER_CAUSTIC) {
        // Same conditions the sun NEE applies it under: an underwater receiver whose shadow ray crossed
        // a water surface. Black therefore means "no caustic here", which is itself an answer.
        if (worldPush.skyProvider != SKY_PROVIDER_ATMOSPHERE || escaped
                || !mediumFlagsWater(unpackCurrentMediumFlags(packed.pathFlags))) {
            return float3(0.0, 0.0, 0.0);
        }
        float3 hitPos = seg.ro + seg.rd * segmentEnd;
        float3 lightDir = worldPush.lightDir.xyz;
        VisibilityResult sh = visibilityMasked(CULL_SHADOW_NO_SELF,
                hitPos - seg.rd * SURF_BIAS, lightDir, RAY_FAR);
        if (sh.waterHitT <= 0.0) {
            return float3(0.0, 0.0, 0.0);
        }
        float3 caustic = waterCaustic(hitPos + lightDir * sh.waterHitT, lightDir, sh.waterHitT);
        float mean = max((caustic.x + caustic.y + caustic.z) * (1.0 / 3.0), 1.0e-6);
        return float3(0.5, 0.5, 0.5) + (caustic / mean - 1.0) * CAUSTIC_DEVIATION_GAIN;
    }
    if (pc.debugView == DEBUG_VIEW_VOLUME_SUNVIS) {
        // The shadow ray enclosedSingleScatter casts, on its own. Black here means the scattering has no
        // sun term at all, whatever the rest of the arithmetic does.
        if (worldPush.skyProvider != SKY_PROVIDER_ATMOSPHERE) {
            return float3(0.0, 0.0, 0.0);
        }
        return visibilityMasked(CULL_SHADOW_NO_SELF, seg.ro,
                                worldPush.lightDir.xyz, RAY_FAR).transmittance;
    }
    // What the segment added, with nothing the path did afterwards. A water medium is never ambient, so
    // for a submerged view this is the enclosed single-scattering term alone.
    // Stable per-pixel noise makes the raw estimator inspectable. A frame-only seed made every pixel draw
    // the same sample and change it in lockstep each frame, turning ordinary variance into a full-screen
    // cyan flash. Keeping the frame out deliberately freezes that variance while this debug view is active.
    uint debugSeed = pixelIndex ^ (uint(pc.debugView) * 2654435761u);
    debugSeed = pcg(debugSeed);
    return integrateSegment(seg.medium.current.extinction, unpackCurrentMediumFlags(packed.pathFlags),
                            seg.ro, seg.rd, segmentEnd, debugSeed).inScatter;
}

SegmentIntegral cameraPrefixIntegral(uint2 dispatchIndex, uint2 dimensions,
                                     PackedPathSegment firstLeaf, uint pixelIndex) {
    // The prefix segment: camera to wherever the primary pass left off. Pass A consumed the interface and
    // queued its Fresnel leaves, but deliberately left this segment's radiometry untouched. The prefix is
    // shared by those leaves, so integrating it once here applies both in-scatter and transmittance once,
    // through the same SegmentIntegral contract used by every other travelled medium segment.
    //
    // A straight line, always. The primary pass traces one ray and resolves at most one interface, so the
    // prefix is either zero-length (it did not split, and the queued origin is the camera's) or the one
    // segment up to that interface. Both branches share it and it is additively independent of them, so
    // it is integrated once here rather than per leaf. See docs/WAVEFRONT_PLAN.md.
    CameraRay camera = cameraRay(dispatchIndex, dimensions);
    float3 prefixOrigin = camera.origin;
    float prefixLength = length(firstLeaf.ro - prefixOrigin);
    bool cameraInWater = (worldPush.flags & 1u) != 0u;
    SegmentIntegral prefix;
    if (!cameraInWater) {
        // The froxel answers for this stretch, because this stretch is exactly what it covers: the eye,
        // this pixel's ray, out to a distance. Everything else in the renderer still marches, and has to —
        // a bounce ray is not on the screen, so it has no column here to read.
        //
        // A lateral move on its own. The closed form it replaces is an exact integral of the same
        // unshadowed medium, and 32 interpolated slices are not more accurate than exact. What the grid
        // has that the closed form cannot is a place to put VISIBILITY, resolved in world-space cells and
        // interpolated, which is the whole of M13 and the reason this is wired now rather than then: if 64
        // slices band, or the exponential axis shows shells in the near field, that has to surface while
        // the closed form is still here to compare against.
        float2 screenUv = (float2(dispatchIndex) + 0.5) / float2(dimensions);
        float4 aerial = sampleAerialPerspective(screenUv, prefixLength, worldPush.fogParams.w);
        // Bit 17 (Rt.Volumetrics.SEGMENT_SOURCE): silence the froxel's half of the fog. The alpha is
        // kept — what this stretch of air HIDES is not in question, only what it emits.
        prefix.inScatter = ((worldPush.flags >> 17u) & 1u) != 0u
                ? float3(0.0, 0.0, 0.0)
                : aerial.rgb;
        prefix.transmittance = float3(aerial.a, aerial.a, aerial.a);
    } else {
        // Submerged: this is a real water segment, not ambient fog and not a zero-extinction placeholder.
        // Its closed-form adapter returns the water in-scatter and the Beer transmittance together; Pass A
        // no longer pre-applies either half, so the result below is the prefix's sole energy accounting.
        // The prefix runs outside tracePath and so has no path seed of its own. Derived from the pixel
        // and the frame so it decorrelates the same way every other sample in the renderer does.
        uint prefixSeed = pixelIndex ^ (worldPush.frameIndex * 2654435761u);
        prefixSeed = pcg(prefixSeed);
        prefix = integrateSegment(waterExtinction(worldPush.waterParams.xyz), MEDIUM_FLAG_WATER,
                                  prefixOrigin, camera.dir, prefixLength, prefixSeed);
    }
    return prefix;
}

void writeWaterMediumProbe(uint2 dispatchIndex, uint2 dimensions, PackedPathSegment firstLeaf,
                           SegmentIntegral prefix, float3 leafRadiance, float3 compositeRadiance,
                           FirstSegmentTrace firstTrace) {
    if (pc.waterProbeAddr == 0 || any(dispatchIndex != dimensions / 2u)) {
        return;
    }

    CameraRay camera = cameraRay(dispatchIndex, dimensions);
    bool submerged = (worldPush.flags & 1u) != 0u;
    float prefixLength = length(firstLeaf.ro - camera.origin);
    float prefixLum = luminance(max(prefix.inScatter, float3(0.0, 0.0, 0.0)));
    float leafLum = luminance(max(leafRadiance, float3(0.0, 0.0, 0.0)));
    float compositeLum = luminance(max(compositeRadiance, float3(0.0, 0.0, 0.0)));

    float measuredUpWater = 0.0;
    float fallbackDepth = max(worldPush.waterAnchor.w - camera.origin.y, 0.0);
    float skyDepth = fallbackDepth;
    float skyOpen = 1.0;
    if (submerged) {
        // Repeats the sky term's representative up-ray for one pixel only. Recording it independently is
        // intentional: if streamed geometry shortens this measurement, the log must show the input that
        // changed rather than merely the resulting blue value.
        VisibilityResult up = visibilityMasked(CULL_SECONDARY_NO_SELF, camera.origin,
                                               float3(0.0, 1.0, 0.0), RAY_FAR);
        measuredUpWater = up.waterHitT;
        skyDepth = measuredUpWater > 0.0 ? measuredUpWater : fallbackDepth;
        // The gate point itself is NOT re-derived here, deliberately, and it is the opposite call from the
        // ray above: the ray is duplicated so a change in the measurement is visible, the gate point is
        // shared so the probe cannot drift into reporting a query the shading never makes.
        skyOpen = waterSurfaceSkyOpenness(camera.origin, skyDepth);
    }

    bool finiteOutput = all(prefix.inScatter == prefix.inScatter)
            && all(prefix.transmittance == prefix.transmittance)
            && all(leafRadiance == leafRadiance)
            && all(compositeRadiance == compositeRadiance);
    WaterMediumProbe probe;
    probe.frameIndex = worldPush.frameIndex;
    probe.flags = 1u | (submerged ? 2u : 0u) | (prefixLength > 1.0e-3 ? 4u : 0u)
            | (finiteOutput ? 8u : 0u);
    probe.pathFlags = firstLeaf.pathFlags;
    probe.debugView = pc.debugView;
    probe.prefixScatterLength = float4(prefix.inScatter, prefixLength);
    probe.prefixTransmittance = float4(prefix.transmittance, luminance(prefix.transmittance));
    probe.leafRadiance = float4(leafRadiance, leafLum);
    probe.compositeRadiance = float4(compositeRadiance, prefixLum / max(compositeLum, 1.0e-8));
    probe.skySourceOpen = float4(worldPush.mediumSkyRadiance.xyz, skyOpen);
    probe.upwardWater = float4(measuredUpWater, skyDepth, fallbackDepth, worldPush.waterAnchor.w);

    // These values are copied out of tracePath's ACTUAL first TraceRay, not reconstructed with a second
    // traversal. firstWeightedScatter is the exact non-prefix term added to L on that iteration and gives
    // the log a conservation check: leaf radiance cannot be below it unless later code loses energy.
    probe.firstSegmentScatter = float4(firstTrace.inScatter, firstTrace.segmentEnd);
    probe.firstSegmentT = float4(firstTrace.transmittance,
                                 luminance(firstTrace.transmittance));
    probe.firstHit = float4(firstTrace.hitT, float(firstTrace.material), float(firstTrace.escaped),
                            float(firstTrace.mediumProfile));
    probe.firstThroughput = float4(firstTrace.throughput,
                                   luminance(max(firstTrace.throughput, float3(0.0, 0.0, 0.0))));
    float3 weightedFirstScatter = firstTrace.throughput * firstTrace.inScatter;
    probe.firstWeightedScatter = float4(weightedFirstScatter,
            luminance(max(weightedFirstScatter, float3(0.0, 0.0, 0.0))));
    DevicePtr<WaterMediumProbe>(pc.waterProbeAddr)[0] = probe;
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    undyedCelestialIrradiance = worldPush.lightRadiance.xyz;
    // Preserve the CPU-pushed UNDYED peak for finite-emitter samples. The compatibility WorldPush copy is
    // then dyed at the centre for legacy consumers; stochastic NEE applies the LUT at its sampled direction.
    dyeCelestialLight(worldPush);
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    // Read outside the probe guard: line ~1582 splits the frame on it, and a release build
    // must still resolve it to false rather than fail to compile.
    bool compositePrefixDebug = pc.debugView == DEBUG_VIEW_COMPOSITE_PREFIX_AB;
#ifdef FLUORITE_PROBES
    // ---- DIAGNOSTIC SURFACE. Present only in a probe build; -Pfluorite.probes=false keeps every line
    // between here and the matching #endif out of the SPIR-V entirely.
    //
    // Stripped rather than branched because this is a ray generation shader: a branch the driver never
    // takes still costs the registers its live range reserves and the instruction cache its body fills,
    // and occupancy is what pass B is short of. The debug chain below is the largest such body in the
    // renderer.
    if (pc.debugView != 0u && !compositePrefixDebug) {
        // Pass A owns both rain diagnostics -- the direct-hit material/film/exposure view and the puddle
        // storage/basin/result view -- so this stage must not write over either.
        //
        // BOTH, and it used to be only 26. The chain below opens with `>= DEBUG_VIEW_SKY_TRANSMITTANCE`,
        // which 27 satisfies; no ternary arm claims it, so it fell through to the froxel default and this
        // pass painted the froxel over pass A's puddle image every frame. The view was simply wrong for
        // as long as it has existed, and it reads as a plausible picture, which is why nothing caught it.
        // Naming the two constants is what surfaced it: with bare literals there was nothing to notice
        // that 26 was handled here and 27 was not.
        if (pc.debugView == DEBUG_VIEW_RAIN_SURFACE || pc.debugView == DEBUG_VIEW_RAIN_PUDDLE) {
            return;
        }
        if (pc.debugView == DEBUG_VIEW_CAMERA_PREFIX) {
            SegmentIntegral prefix = cameraPrefixIntegral(
                    dispatchIndex, dimensions, queue[pixelIndex], pixelIndex);
            float3 prefixDebug = prefix.inScatter;
            // Right-edge status strip keeps a black radiance result unambiguous:
            //   green = submerged and a non-zero camera-to-interface prefix was reconstructed;
            //   red   = submerged but this ray reached no consumed interface (the leaf owns the segment);
            //   blue  = the CPU did not classify the camera as submerged.
            if (dispatchIndex.x >= dimensions.x - max(dimensions.x / 12u, 1u)) {
                CameraRay debugCamera = cameraRay(dispatchIndex, dimensions);
                float debugPrefixLength = length(queue[pixelIndex].ro - debugCamera.origin);
                bool debugCameraInWater = (worldPush.flags & 1u) != 0u;
                prefixDebug = !debugCameraInWater
                        ? float3(0.0, 0.0, 1.0)
                        : debugPrefixLength > 1.0e-3
                        ? float3(0.0, 1.0, 0.0)
                        : float3(1.0, 0.0, 0.0);
            }
            outImage[pix] = float4(prefixDebug, 1.0);
        } else if (pc.debugView >= DEBUG_VIEW_FROXEL) {
            // Screen-space, not a path property, so these take the pixel rather than the queued record.
            //
            // OPENS AT FROXEL (17), and 12-16 are now unclaimed. They were the four atmosphere tables and
            // the celestial dye -- retired once the atmosphere stopped changing, along with 25's fog noise
            // field. The numbers were NOT reused: every devlog, every comment and every note that names a
            // view by number stays true, and renumbering would have made all of them quietly wrong.
            //
            // WHICH IS WHY THE DEFAULT IS LOUD. This chain used to end in `: froxelDebug(screenUv)`, so a
            // number no arm claimed painted the froxel -- and that is not hypothetical: view 27 fell
            // through to it and painted over pass A's puddle image for as long as the view existed,
            // because a froxel is a plausible-looking picture and nobody reads it as an error. Retiring
            // six numbers creates six more chances to make exactly that mistake, so an unclaimed number
            // now returns magenta, which is not a picture of anything.
            float2 screenUv = (float2(dispatchIndex) + 0.5) / float2(dimensions);
            float3 table = pc.debugView == DEBUG_VIEW_FROXEL
                    ? froxelDebug(screenUv)
                    : pc.debugView == DEBUG_VIEW_VOLUME_VISIBILITY
                    // The queued record's origin is where the primary pass stopped, which is this pixel's
                    // camera segment's far end. Taken from the queue rather than re-traced: a debug view
                    // that traces its own ray is testing its own ray.
                    ? volumeVisibilityDebug(dispatchIndex, dimensions, queue[pixelIndex].ro)
                    : pc.debugView == DEBUG_VIEW_SKY_LIGHT
                    ? skyLightDebug(dispatchIndex, dimensions, queue[pixelIndex].ro)
                    : pc.debugView == DEBUG_VIEW_FOG_SKY_OPEN
                    ? fogSkyOpenDebug(dispatchIndex, dimensions, queue[pixelIndex].ro)
                    : pc.debugView == DEBUG_VIEW_WATER_SIM
                    ? waterSimDebug(screenUv)
                    : pc.debugView == DEBUG_VIEW_WATER_SIM_REACH
                    ? waterSimReachDebug(unpackPathSegment(queue[pixelIndex]).ro)
                    : pc.debugView == DEBUG_VIEW_CLOUD_PROBE
                    // The queued record carries the ray pass A stopped on, which for a pixel looking at
                    // sky is the camera ray itself. Taken from the queue rather than rebuilt here: a
                    // diagnostic that constructs its own ray is testing its own ray.
                    ? cloudDebugProbe(unpackPathSegment(queue[pixelIndex]).ro,
                                      unpackPathSegment(queue[pixelIndex]).rd)
                    : float3(1.0, 0.0, 1.0); // unclaimed view number
            outImage[pix] = float4(table, 1.0);
        } else if (pc.debugView >= DEBUG_VIEW_VOLUME_INSCATTER) {
            outImage[pix] = float4(volumeDebug(queue[pixelIndex], pixelIndex), 1.0);
        }
        return; // pass A owns the guide visualizations
    }
#endif
    uint spp = max(worldPush.spp, 1u);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    uint recordIndex = pixelIndex;
    PathSegment firstQueuedSegment = unpackPathSegment(queue[pixelIndex]);
    FirstSegmentTrace actualFirstTrace = emptyFirstSegmentTrace(
            firstQueuedSegment, unpackActiveMediumFlags(queue[pixelIndex].pathFlags));
    bool captureProbe = pc.waterProbeAddr != 0 && all(dispatchIndex == dimensions / 2u);

    SegmentIntegral prefix = cameraPrefixIntegral(
            dispatchIndex, dimensions, queue[pixelIndex], pixelIndex);

    // One nibble per reuse depth: .x counts where a stored reservoir was looked at, .y where it was
    // accepted. Accumulated across every path of this pixel and folded into the frame's totals once,
    // below, so the per-pixel cost of the diagnostic is register arithmetic and nothing else.
    uint4 reuseCounts = uint4(0u, 0u, 0u, 0u);
    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        // tracePath owns primitive active state and leaves this transport record unchanged, so all SPP
        // can share one unpack without inheriting a previous sample's dielectric transitions.
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            bool captureFirst = captureProbe && leaf == 0u && s == 0u;
            FirstSegmentTrace tracedFirst;
            frameRadiance += tracePath(segment, unpackActiveMediumFlags(packed.pathFlags),
                                       uint2(pix), s + leaf * spp, dimensions,
                                       captureFirst, tracedFirst, reuseCounts);
            if (captureFirst) {
                actualFirstTrace = tracedFirst;
            }
        }
        if ((packed.pathFlags & PATH_HAS_NEXT) == 0u) {
            break;
        }
        // Recomputed rather than stored. world_primary builds the split record's index from exactly this
        // expression, so the two have to keep agreeing — which they do structurally, both being derived
        // from the same dispatch dimensions, rather than because a uint was carried across the pass
        // boundary to say it.
        recordIndex = dimensions.x * dimensions.y + pixelIndex;
    }
    float3 leafRadiance = prefix.transmittance * (frameRadiance / float(spp));
    float3 compositeRadiance = prefix.inScatter + leafRadiance;
    if (compositePrefixDebug) {
        if (dispatchIndex.y >= dimensions.y * 3u / 4u) {
            float prefixLum = luminance(max(prefix.inScatter, float3(0.0, 0.0, 0.0)));
            float compositeLum = luminance(max(compositeRadiance, float3(0.0, 0.0, 0.0)));
            float fraction = prefixLum / max(compositeLum, 1.0e-8);
            compositeRadiance = saturate(float3(fraction * 8.0, fraction * 2.0, fraction));
        } else {
            // Neighbouring 32-pixel bands compare almost the same view direction. A visible seam means the
            // prefix materially changes the raw HDR composite before RR/exposure/tonemap.
            bool includePrefix = ((dispatchIndex.x / 32u) & 1u) == 0u;
            compositeRadiance = includePrefix ? compositeRadiance : leafRadiance;
        }
    }
#ifdef FLUORITE_PROBES
    restirStatsAccumulate(reuseCounts, pixelIndex);
    writeWaterMediumProbe(dispatchIndex, dimensions, queue[pixelIndex], prefix,
                          leafRadiance, prefix.inScatter + leafRadiance, actualFirstTrace);
#endif
    outImage[pix] = float4(compositeRadiance, 1.0);
}
