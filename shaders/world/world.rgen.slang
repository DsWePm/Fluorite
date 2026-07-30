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
import medium;
import volume;
import segment;
import water;
import trace;
#ifdef FLUORITE_ENABLE_EXT_SER
import trace_ser;
#endif
import subsurface;
import lighting;

public float3 tracePath(PathSegment seg, uint2 pix, uint sampleIndex) {
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
    // The medium the segment starts in. For the camera segment that is water when the eye is submerged
    // (so the first ray already carries the right relative index and absorption); for a segment split off
    // a dielectric it is whatever the parent was travelling through.
    MediumStack medium = seg.medium;
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
    float lightHalfAngle = worldPush.lightDir.w;
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
        // player. Bounce rays are secondary (CULL_SECONDARY): exclude particles, include the player.
#ifdef FLUORITE_ENABLE_EXT_SER
        // SER lifetime phase: keep paths that are not roulette-eligible, paths that may terminate via
        // roulette, and paths guaranteed to end at the bounce cap in separate coherence groups.
        uint pathPhaseHint = bounce >= maxBounces ? 2u : (bounce >= rrStart ? 1u : 0u);
        traceRadianceReordered(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, RAY_FAR,
                               showCelestial, rayConeWidth, rayConeSpread, pathPhaseHint);
#else
        traceRadiance(bounce == 0 ? CULL_PRIMARY : CULL_SECONDARY, ro, 0.0, rd, RAY_FAR,
                      showCelestial, rayConeWidth, rayConeSpread);
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
        SegmentIntegral segIntegral = integrateSegment(medium, ro, rd, segmentEnd);
        // In-scatter first: it is what the segment added to the radiance arriving along it, so it is
        // weighted by the throughput reaching the segment rather than the throughput leaving it.
        L += throughput * segIntegral.inScatter;
        throughput *= segIntegral.transmittance;

        if (payload.hitT < 0.0) {
            // world.rmiss writes sky radiance (gradient + drawn sun/moon sprite + stars) into
            // payload.albedo. The packed show-celestial flag decides whether this path segment may see
            // the sprite. All of it is reached one way only, so it takes the throughput unweighted.
            float3 sky = float3(payload.albedo);
            // The celestial light, if this ray landed inside it and its lobe had a density to weigh.
            // payload.f0 carries the light's irradiance; the solid angle is what turns it into the
            // radiance a ray arriving from that direction actually sees.
            if (misActive && bsdfPdf >= 0.0 && (payload.flags & PAYLOAD_ANALYTIC_LIGHT) != 0u) {
                sky += float3(payload.f0) * pdfLight * powerHeuristic(bsdfPdf, pdfLight);
            }
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
                float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
                float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }

            // The medium this face opens into, and the one it returns to on the way out — which is what
            // the stack remembers.
            Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0), tint, transmission);
            float etaI = medium.current.ior;
            float etaT = entering ? entered.ior : medium.outer.ior;

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
                    mediumPush(medium, entered);
                } else {
                    mediumPop(medium);
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

            // No MIS here: a billboard's only lobe is Lambertian, whose density toward the light is
            // eight orders of magnitude under the light's own, so the weight is 1 to well past float.
            float3 lightDir = worldPush.lightDir.xyz;
            if (lightHalfAngle > 0.0) {
                lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
            }
            // Billboards are effectively two-sided receivers. Bias from the side facing the light; shadow
            // rays exclude particles anyway, but this keeps the origin sane when the light is behind the
            // camera side.
            float signedNdl = dot(n, lightDir);
            float ndl = abs(signedNdl);
            if (ndl > 0.0) {
                float3 shadowOrigin = hitPos + (signedNdl >= 0.0 ? n : -n) * SURF_BIAS;
                float3 vis = visibilityThroughAmbient(medium, shadowOrigin, lightDir, RAY_FAR).transmittance;
                if (max(vis.r, max(vis.g, vis.b)) > 0.0) {
                    L += throughput * albedo * INV_PI * worldPush.lightRadiance.xyz * ndl * vis;
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
                L += throughput * shadeReservoir(medium, bc, materialExtension(payload.flags), r);
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
        float3 p = hitPos + n * SURF_BIAS;
        float3 v = -rd; // view direction (toward the camera / incoming ray)

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
        MaterialExtension ext = materialExtension(payload.flags);
        float activeSss = hitDepth <= MAX_SSS_INDIRECT_DEPTH ? sss : 0.0;
        // Hoisted above the NEE because the sun's MIS needs to know how much of the non-specular half
        // the walk will take; the lobe itself is selected further down.
        // The walk runs only where a pack authored a mean free path. LabPBR's subsurface byte is a
        // MASK — "light gets through this" — with no way to say how far, and packs use it liberally:
        // SPBR marks diamond and emerald at 254 of 255. Inventing a radius for those and walking a
        // photon through them is what turns a solid block into a light leak, because the walk exits
        // through whichever face it reaches first and four of a wall's six faces are buried against a
        // neighbour. A mask alone gets the thin-shell term, which is what it was authored for.
        float3 sssRadius = ext.subsurface.xyz;
        bool walkAuthored = max(sssRadius.x, max(sssRadius.y, sssRadius.z)) > 0.0;
        float walkSssShare = 0.0;
        if (sssMode == 2u && sssMaxEvents > 0 && walkAuthored
                && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
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
        bool gateEmitter = risOn && payloadEmitterInList();
        if (emission > 0.0 && (!gateEmitter || showCelestial)) {
            L += throughput * albedo * emission;
        }

        // NEE: direct light from the dominant celestial body (sun by day, moon by night) — Lambert
        // diffuse + GGX specular, one shadow ray. Jitter the light direction within its square angular
        // extent (worldPush.lightDir.w half-angle) so the shadow ray samples the light's quad — soft penumbrae
        // over accumulation. The same sampled direction drives ndl, the shadow ray, and the specular
        // half-vector.
        float3 lightDir = worldPush.lightDir.xyz;
        if (lightHalfAngle > 0.0) {
            lightDir = sampleSquare(lightDir, lightHalfAngle, seed);
        }
        float ndl = max(0.0, dot(n, lightDir));
        if (ndl > 0.0) {
            VisibilityResult shadow = visibilityThroughAmbient(medium, p, lightDir, RAY_FAR);
            float3 vis = shadow.transmittance;
            // Underwater receiver whose shadow ray crossed a water surface: scale the direct light by
            // the wave-refraction caustic at the exit point. Focusing scales the incident irradiance,
            // so it applies to the whole NEE term (diffuse + specular).
            if (medium.current.water && waterWaves && shadow.waterHitT > 0.0) {
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
                // The two lobes the continuation actually samples: Lambertian diffuse (f = albedo/PI)
                // and Cook–Torrance specular.
                float3 sampled = diffAlb * INV_PI + (D * G) * F / (4.0 * ndv * ndl);

                // Multiple importance sampling, but only over `sampled`. MIS pairs strategies for one
                // integral, and the strategies here are NEE and the continuation mixture — so the
                // weight applies to exactly the lobes that mixture can draw, and nothing else. Sheen
                // and clearcoat are evaluated but never sampled as a continuation, so NEE is their only
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
                float diffuseShare = (1.0 - ps) * (1.0 - walkSssShare);
                float pdfB = diffuseShare * ndl * INV_PI
                        + (exactSpecular ? 0.0 : ps * ggxReflectPdfAniso(hx, hy, ndh, ndv, ax, ay, g1v));
                float wLight = misActive ? powerHeuristic(pdfLight, pdfB) : 1.0;

                float3 brdf = sampled * wLight + disneyLobes(bc, ext, lightDir);
                L += throughput * brdf * worldPush.lightRadiance.xyz * ndl * vis;
            }
        }

        // RIS direct lighting from block emitters (single-frame only, no temporal reservoir reuse).
        // Front lobe + SSS backlighting are resampled together and share ONE shadow ray — a light can
        // only be in front of n or behind it, never both, so combining them into one target function is
        // exact, not an approximation. The SSS term is gated off past MAX_SSS_INDIRECT_DEPTH by passing
        // sss=0 there (falls back to plain front-only RIS).
        if (risOn) {
            Reservoir r = risInitial(bc, seed, proposalSeed);
            L += throughput * shadeReservoir(medium, bc, ext, r);
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
        if (sssMode != 0u && sss > 0.0 && hitDepth <= MAX_SSS_INDIRECT_DEPTH) {
            float backNdl = max(0.0, dot(-n, lightDir));
            if (backNdl > 0.0) {
                VisibilityResult shadowBack = visibilityThroughAmbient(medium, hitPos - n * SURF_BIAS, lightDir, RAY_FAR);
                float3 visB = shadowBack.transmittance;
                // Same caustic as the front-face NEE — underwater kelp/seagrass transmission should
                // flicker with the same light bands as the floor around it.
                if (medium.current.water && waterWaves && shadowBack.waterHitT > 0.0) {
                    visB *= waterCaustic(hitPos + lightDir * shadowBack.waterHitT,
                                         lightDir, shadowBack.waterHitT);
                }
                if (max(visB.r, max(visB.g, visB.b)) > 0.0) {
                    float cosT = dot(lightDir, rd);
                    L += throughput * diffAlb * sss * SSS_STRENGTH * hg(cosT, SSS_G) * backNdl * worldPush.lightRadiance.xyz * visB;
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

        if (rndf(seed) < ps) {
            float3 l;
            if (exactSpecular) {
                // Authored zero is a delta distribution, not a narrow finite GGX lobe. This avoids the
                // degenerate VNDF at alpha=0 and preserves a genuinely sharp reflection without a floor.
                l = reflect(rd, n);
                throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0) / ps;
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
                throughput *= F * ggxG1Aniso(dot(l, bc.t), dot(l, bc.b), ndl2, bc.alphaX, bc.alphaY) / ps;
                // The cone grows by the wider axis: it bounds the texture footprint, and taking the
                // narrower one would sharpen mip selection along the stretched direction, which is
                // exactly where it should blur.
                rayConeSpread = max(rayConeSpread,
                        max(bc.alphaX, bc.alphaY) * RAY_CONE_GLOSSY_SPREAD_SCALE);
                bsdfPdf = ps * ggxReflectPdfAniso(dot(h, bc.t), dot(h, bc.b), max(0.0, dot(n, h)),
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
            float lobeProb = (1.0 - ps) * walkSss;
            WalkResult walk = randomWalkSubsurface(hitPos, n, diffAlb, sssRadius, ext.specular.w,
                                                   sssMaxEvents, seed);
            if (walk.absorbed) {
                break; // roulette inside the walk: an unbiased stop, so this path is simply over
            }
            if (walk.escaped) {
                throughput *= walk.weight / lobeProb;
                ro = walk.origin;
                rd = walk.dir;
            } else {
                // Ran out of events. Fall back to the cheap lobe rather than dropping the energy —
                // truncating here would darken the thickest parts of a material, which reads as dirt
                // rather than as a budget. This is the knob SUBSURFACE_MAX_EVENTS turns.
                throughput *= diffAlb / lobeProb;
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
            float lobeProb = (1.0 - ps) * (1.0 - walkSss);
            throughput *= diffAlb / lobeProb;
            ro = p;
            rd = cosineDir(n, seed);
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

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    if (pc.debugView != 0u) {
        return; // pass A owns guide visualization output
    }
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    uint spp = max(worldPush.spp, 1u);
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    ConstPtr<PackedPathSegment> queue = ConstPtr<PackedPathSegment>(pc.pathQueueAddr);
    float3 frameRadiance = float3(0.0, 0.0, 0.0);
    uint recordIndex = pixelIndex;

    // The prefix segment: camera to wherever the primary pass left off. That pass consumed it — it
    // applied the enclosed medium's absorption and queued the branches at the interface — so no record
    // describes what the ambient field did along it, and without this the fog would be missing from
    // exactly the stretch nearest the camera.
    //
    // A straight line, always. The primary pass traces one ray and resolves at most one interface, so the
    // prefix is either zero-length (it did not split, and the queued origin is the camera's) or the one
    // segment up to that interface. Both branches share it and it is additively independent of them, so
    // it is integrated once here rather than per leaf. See docs/WAVEFRONT_PLAN.md.
    CameraRay camera = cameraRay(dispatchIndex, dimensions);
    float3 prefixOrigin = camera.origin;
    float prefixLength = length(queue[pixelIndex].ro - prefixOrigin);
    // Ambient only, with zero enclosed extinction: the primary pass already applied the enclosed
    // medium's absorption to this segment and applying it twice would darken everything seen through
    // water. A submerged camera is inside water rather than inside the world's air, so it leaves the
    // ambient field too — which is why this is the flag rather than an unconditional airMedium().
    Medium prefixAir = airMedium();
    prefixAir.ambient = (worldPush.flags & 1u) == 0u;
    MediumStack prefixMedium = makeMediumStack(prefixAir);
    SegmentIntegral prefix = integrateSegment(prefixMedium, prefixOrigin, camera.dir, prefixLength);

    for (uint leaf = 0u; leaf < MAX_PATH_SEGMENTS; ++leaf) {
        PackedPathSegment packed = queue[recordIndex];
        PathSegment segment = unpackPathSegment(packed);
        for (uint s = 0u; s < spp; ++s) {
            frameRadiance += tracePath(segment, uint2(pix), s + leaf * spp);
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
    outImage[pix] = float4(prefix.inScatter + prefix.transmittance * (frameRadiance / float(spp)), 1.0);
}
