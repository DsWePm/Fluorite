// The path reuse's pass B tracer (M28 D231, G15/R1). Textually included into world.rgen.slang ONLY
// when FLUORITE_PATH_REUSE is defined, so it exists only in world_reuse.rgen / world_ser_reuse.rgen --
// the pipeline the path reservoir switch binds. The shipped pass B (world.rgen's tracePath) carries none
// of this file and stays byte-for-byte the tracer that shipped before S2; a switch-off frame binds it.
//
// This is tracePath with addressable vertex streams and record-only bookkeeping. Everything outside
// reservoir must stay identical to tracePath: RtPathReplayContractTest checks the shipped body survives here
// and a divergence would make the switch compare two different tracers rather than reuse against none.
// The rebuild's slices (R1+) restructure this copy only.

#include "path_reuse_geometry.slang"
#include "path_reuse_sampling.slang"

// ---- Vertex decisions, ONE spelling for the bounce loop below and the hybrid shift's replay (R1b).
//
// A replay re-drives a path's decisions on another pixel's geometry, so it has to decide exactly as the
// loop that recorded the path did: same lobe mixture, same sampling, same medium bookkeeping, same cone.
// Two spellings of that would drift apart silently -- a replayed path that decides differently is not
// an error, it is a wrong weight (the file banner's warning, and D217's risk 3). So the decisions live
// here and both callers use them. Every line below is the bounce loop's own, moved verbatim; the random
// stream is a parameter so the caller decides where the draws come from.

/**
 * Resolve a dielectric interface (water, glass, ice): the rippled normal, the Fresnel choice between
 * reflection and refraction, the new ray, the medium stack, and the rough interface's G1 weight.
 * Reads the payload of the hit being resolved. False when the path cannot continue.
 */
bool reuseResolveDielectric(uint material, float3 hitPos, float3 n, float rayConeWidth, bool waterWaves,
                            inout float3 ro, inout float3 rd, inout float3 throughput,
                            inout float currentIor, inout float3 currentExtinction,
                            inout float outerIor, inout float3 outerExtinction,
                            inout uint activeMediumFlags, inout float rayConeSpread, inout uint stream) {
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
    float3 m = deltaInterface ? n : sampleGGXVNDF(n, -rd, interfaceAlpha, stream);

    float cosI = clamp(dot(-rd, m), 0.0, 1.0);
    float F = fresnelDielectric(cosI, etaI, etaT);
    float3 transmittedDir = refract(rd, m, etaI / etaT);
    // The translucent terrain layer is recessed by TRANSLUCENT_INSET (RtTerrainMesher), so a
    // glass/ice face touching a slab or stair sits a hair behind that neighbour's surface. A full
    // SURF_BIAS would restart the transmitted ray past the neighbour and see through it. Water
    // comes from the fluid mesher, which applies no inset, so it takes the ordinary bias.
    float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

    bool chooseReflection = rndf(stream) < F;
    if (chooseReflection) {
        rd = reflect(rd, m);
        // A microfacet can reflect back into the surface it sits on. The delta case cannot, so
        // this only ever fires for a rough interface.
        if (!deltaInterface && dot(rd, geometricNormal) <= 0.0) {
            return false;
        }
        ro = offsetSurfaceOrigin(hitPos, geometricNormal, rd, SURF_BIAS);
    } else {
        if (dot(transmittedDir, transmittedDir) <= 0.0) return false; // TIR with F < 1 cannot happen
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
    return true;
}

/** An opaque vertex's decoded material and lobe mixture: everything the vertex's shading and its
 * continuation read, decoded once from the payload of the hit. */
public struct ReuseOpaqueVertex {
    public float3 albedo;
    public float sss;
    public float3 v;
    public MaterialExtension ext;
    public RainSurface rain;
    public float3 n;
    public float impactFootprint;
    public float3 p;
    public float rough;
    public float metal;
    public bool exactSpecular;
    public float3 diffAlb;
    public float3 F0;
    public float ps;
    public float activeSss;
    public float3 sssRadius;
    public float walkSssShare;
    public BsdfContext bc;
    public float pf;
};

/** Decode an opaque hit (the payload of the hit just traced) into its material and lobe mixture. */
ReuseOpaqueVertex reuseOpaqueVertex(float3 hitPos, float3 n, float3 rd, float rayConeWidth, int hitDepth,
                                    uint sssMode, int sssMaxEvents) {
    ReuseOpaqueVertex o;
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

    o.albedo = albedo;
    o.sss = sss;
    o.v = v;
    o.ext = ext;
    o.rain = rain;
    o.n = n;
    o.impactFootprint = impactFootprint;
    o.p = p;
    o.rough = rough;
    o.metal = metal;
    o.exactSpecular = exactSpecular;
    o.diffAlb = diffAlb;
    o.F0 = F0;
    o.ps = ps;
    o.activeSss = activeSss;
    o.sssRadius = sssRadius;
    o.walkSssShare = walkSssShare;
    o.bc = bc;
    o.pf = pf;
    return o;
}

/**
 * Sample an opaque vertex's continuation from its lobe mixture: the film, specular, walk or diffuse
 * lobe, the new ray, the throughput weight, the density the sun's MIS weighs against, and the cone.
 * False when the path ends here (a microfacet reflected below the surface, or the walk absorbed it).
 * {@code walked} reports the walk lobe, whose exit direction has no analytic density.
 */
bool reuseSampleContinuation(__ref ReuseOpaqueVertex vtx, float3 hitPos, int sssMaxEvents,
                             inout float3 ro, inout float3 rd, inout float3 throughput,
                             inout float bsdfPdf, inout float rayConeSpread, inout bool showCelestial,
                             inout uint stream, out bool walked, out ReuseContinuationSample sampleInfo) {
    walked = false;
    sampleInfo = emptyReuseContinuationSample();
    float3 n = vtx.n;
    float3 v = vtx.v;
    float3 p = vtx.p;
    RainSurface rain = vtx.rain;
    BsdfContext bc = vtx.bc;
    float3 F0 = vtx.F0;
    float3 diffAlb = vtx.diffAlb;
    float pf = vtx.pf;
    float ps = vtx.ps;
    bool exactSpecular = vtx.exactSpecular;

    // Indirect continuation: importance-sample the diffuse OR the GGX specular lobe, chosen by their
    // relative reflectance (ps, hoisted above the NEE that had to weigh against it).
    // How much of the non-specular half goes to the walk. The JSON weight, when a pack authored one,
    // overrides the LabPBR mask outright rather than scaling it — same precedence as every other
    // authored channel, and it means a rule that mentions only sheen leaves the texel mask alone
    // instead of silently zeroing subsurface (weight 0 falls through to sss).
    float walkSss = vtx.walkSssShare;

    float lobeChoice = rndf(stream);
    if (lobeChoice < pf) {
        float alpha = rain.filmAlpha;
        sampleInfo.lobe = REUSE_LOBE_FILM;
        sampleInfo.alpha = alpha;
        float3 h = sampleGGXVNDF(n, v, alpha, stream);
        float3 l = reflect(rd, h);
        float ndl2 = dot(n, l);
        if (ndl2 <= 0.0) {
            return false;
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
            sampleInfo.lobe = REUSE_LOBE_DELTA;
            // Authored zero is a delta distribution, not a narrow finite GGX lobe. This avoids the
            // degenerate VNDF at alpha=0 and preserves a genuinely sharp reflection without a floor.
            l = reflect(rd, n);
            throughput *= fresnelSchlick(clamp(dot(n, v), 0.0, 1.0), F0)
                    * rainBaseTransmission(bc, l) / ((1.0 - pf) * ps);
            bsdfPdf = -1.0;
        } else {
            sampleInfo.lobe = REUSE_LOBE_SPECULAR;
            sampleInfo.alpha = vtx.rough;
            float3 h = sampleGGXVNDFAniso(n, bc.t, bc.b, v, bc.alphaX, bc.alphaY, stream);
            l = reflect(rd, h); // rd = -v: reflect the incoming ray about the microfacet normal
            float ndl2 = dot(n, l);
            if (ndl2 <= 0.0) {
                return false; // microfacet reflects below the surface: terminate this path
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
    } else if (walkSss > 0.0 && rndf(stream) < walkSss) {
        // The subsurface lobe. Everything about it happens inside this one shading vertex — the
        // walk enters, scatters and leaves before the path continues — so nothing here reaches the
        // continuation record or the pass boundary.
        walked = true; // the walk's exit direction has no analytic density to reconnect on
        sampleInfo.lobe = REUSE_LOBE_WALK;
        float lobeProb = (1.0 - pf) * (1.0 - ps) * walkSss;
        WalkResult walk = randomWalkSubsurface(hitPos, n, diffAlb, vtx.sssRadius, vtx.ext.specular.w,
                                               sssMaxEvents, stream);
        if (walk.absorbed) {
            return false; // roulette inside the walk: an unbiased stop, so this path is simply over
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
            rd = cosineDir(n, stream);
        }
        // The exit direction has no analytic density — it depends on where inside the material the
        // photon happened to turn around — so there is nothing for the sun's MIS to weigh against
        // and this path takes the drawn sprite rather than the light.
        bsdfPdf = -1.0;
        rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        showCelestial = false;
    } else {
        sampleInfo.lobe = REUSE_LOBE_DIFFUSE;
        sampleInfo.alpha = 1.0;
        float lobeProb = (1.0 - pf) * (1.0 - ps) * (1.0 - walkSss);
        float3 l = cosineDir(n, stream);
        throughput *= diffAlb * rainBaseTransmission(bc, l) / lobeProb;
        ro = p;
        rd = l;
        bsdfPdf = lobeProb * max(0.0, dot(n, rd)) * INV_PI;
        rayConeSpread = max(rayConeSpread, RAY_CONE_DIFFUSE_SPREAD);
        showCelestial = false; // diffuse lobe: sprite covered by NEE, hide it (anti-firefly)
    }
    sampleInfo.pdf = bsdfPdf;
    return true;
}

// ---- The hybrid shift's replay (R1b-3), and the self-check that proves it re-drives a path.
//
// A replay re-traces a recorded path from a pixel's first pass-B vertex with the record's seed: the same
// rays as the bounce loop, the same shared vertex functions, each vertex's decisions drawn from
// vertexStream(pathSeed, hitDepth). What it leaves out is everything that is not a vertex decision --
// shading, NEE, the path reservoir -- and Russian roulette, which Enhanced section 6.2.4 removes from
// replay because a roulette kill during a shift would reject a perfectly valid path.

/** How many vertices the identity self-check replays: enough to cross a dielectric or a mirror into
 * the vertex a reconnection would hang from, and no more -- every one is a traced ray. */
public static const int PATH_REUSE_IDENTITY_DEPTH = 3;

/**
 * Replay a recorded path's first {@code vertices} vertices from {@code seg} with {@code pathSeed}.
 * Returns how many it reached; {@code lastPos} is the last one's position. A particle vertex stops the
 * replay: a billboard's continuation draws from the path's sequential stream, not a vertex stream.
 */
int reuseReplayPrefix(__ref PathSegment seg, uint activeMediumFlags, uint pathSeed, int vertices,
                      out float3 lastPos, bool queryGeometry, out ReuseHitGeometry lastGeometry,
                      out uint geometryStatus) {
    lastPos = float3(0.0, 0.0, 0.0);
    lastGeometry = emptyReuseHitGeometry();
    geometryStatus = REUSE_GEOMETRY_MISSING;
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    int maxBounces = int(worldPush.maxBounces);
    float currentIor = seg.medium.current.ior;
    float3 currentExtinction = seg.medium.current.extinction;
    float outerIor = seg.medium.outer.ior;
    float3 outerExtinction = seg.medium.outer.extinction;
    uint secondaryCull = secondaryCullMask();
    bool waterWaves = (worldPush.flags & 16u) != 0u;
    bool showCelestial = seg.showCelestial;
    float bsdfPdf = -1.0;
    uint sssMode = (worldPush.flags >> 7u) & 3u;
    int sssMaxEvents = int((worldPush.flags >> 9u) & 7u);
    int indirectDepth = 0;
    int reached = 0;
    for (int bounce = seg.bounce; bounce <= maxBounces; bounce++) {
        traceRadiance(bounce == 0 ? CULL_PRIMARY : secondaryCull, ro, 0.0, rd, RAY_FAR,
                      showCelestial, bounce == 0, rayConeWidth, rayConeSpread);
        if (payload.hitT < 0.0) {
            break;
        }
        float3 n = payload.normal;
        float3 hitPos = ro + rd * payload.hitT;
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0), RAY_CONE_MIN_WIDTH);
        uint material = payloadMaterial();
        int hitDepth = indirectDepth++;
        lastPos = hitPos;
        reached = hitDepth + 1;
        if (reached >= vertices || bounce >= maxBounces) {
            if (queryGeometry && reached == vertices) {
                geometryStatus = reuseQueryHitGeometry(bounce == 0 ? CULL_PRIMARY : secondaryCull,
                                                       ro, rd, payload.hitT, lastGeometry);
            }
            break;
        }
        uint vertexSeed = vertexStream(pathSeed, uint(hitDepth));
        if (material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC) {
            if (!reuseResolveDielectric(material, hitPos, n, rayConeWidth, waterWaves, ro, rd, throughput,
                                        currentIor, currentExtinction, outerIor, outerExtinction,
                                        activeMediumFlags, rayConeSpread, vertexSeed)) {
                break;
            }
            showCelestial = true;
            bsdfPdf = -1.0;
            continue;
        }
        if (material == MATERIAL_PARTICLE) {
            break;
        }
        ReuseOpaqueVertex vtx = reuseOpaqueVertex(hitPos, n, rd, rayConeWidth, hitDepth, sssMode, sssMaxEvents);
        bool walked;
        ReuseContinuationSample continuation;
        if (!reuseSampleContinuation(vtx, hitPos, sssMaxEvents, ro, rd, throughput, bsdfPdf,
                                     rayConeSpread, showCelestial, vertexSeed, walked, continuation)) {
            break;
        }
    }
    return reached;
}

public float3 tracePathReuse(__ref PathSegment seg, uint activeMediumFlags,
                        uint2 pix, uint sampleIndex, uint2 renderSize,
                        bool captureFirst, out FirstSegmentTrace firstTrace,
                        inout uint4 reuseCounts) {
    firstTrace = emptyFirstSegmentTrace(seg, activeMediumFlags);
    // The medium word this path entered pass B with: the loop pushes and pops it, the replay
    // (identity self-check, R1b-3) starts again from here.
    uint entryMediumFlags = activeMediumFlags;
    float3 L = float3(0.0, 0.0, 0.0);
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    // Pass A is fixed at one sample per pixel. Decorrelate each Pass B resample here so configured SPP
    // produces independent lighting/BSDF paths from the shared terminal continuation.
    uint seed = seg.seed ^ sampleIndex * 2246822519u;
    seed = pcg(seed);
    // M28: the seed the path reservoir stores. A RECORDED path's vertex decisions are drawn from
    // vertexStream(pathSeedBase, hitDepth), addressable per vertex, so a replay needs only this and the
    // vertex index (REPLAY CONTRACT, segment.slang). Snapshotted before the first draw mutates `seed`.
    uint pathSeedBase = seed;
    // And the path reuse's own draws come from a stream of their own, never from a vertex decision's.
    uint reuseSeed = reuseStream(pathSeedBase);
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
    // Sample 0 of leaf 0 owns the record. R2a retires the invalid in-place estimator;
    // supported path-tree events arrive in R2b, and the pixel keeps its full traced radiance.
    bool recordPath = worldPush.pathReservoirAddr != 0 && sampleIndex == 0u;
    // R1b-3, the prefix identity self-check: one pixel in sixteen replays its own recorded path from
    // its first vertex and must land exactly on the vertex it recorded -- the proof that the vertex
    // streams and the shared vertex functions re-drive a path. Diagnostics only (switch word bit 0).
    bool identityCheck = recordPath && (worldPush.pathReplayEnabled & PATH_REUSE_IDENTITY_BIT) != 0u
            && worldPush.pathReservoirStatsAddr != 0u
            && (((pix.y * renderSize.x + pix.x) ^ worldPush.frameIndex) & 15u) == 0u;
    float3 identityPos = float3(0.0, 0.0, 0.0);
    int identityVertices = 0;
    bool identityParticle = false;
    ReuseHitGeometry identityGeometry = emptyReuseHitGeometry();
    uint identityGeometryStatus = REUSE_GEOMETRY_MISSING;
    ReuseHitGeometry footprintPrimary = emptyReuseHitGeometry();
    ReuseHitGeometry footprintPrevious = emptyReuseHitGeometry();
    ReuseContinuationSample footprintPreviousSample = emptyReuseContinuationSample();
    bool footprintPrimaryValid = false;
    bool footprintPreviousValid = false;
    bool footprintPrefixWalk = false;
    bool footprintFound = false;
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

        float footprintEdgeDistance = payload.hitT;
        float3 footprintEdgeDirection = rd;
        // R2b geometry diagnostic: capture metadata for this actual radiance hit before any lighting
        // trace can touch the payload. No query when the diagnostic is off; full records remain empty
        // until path-tree selection consumes this helper. Query calls draw no random numbers.
        if (identityCheck && indirectDepth < PATH_REUSE_IDENTITY_DEPTH && payload.hitT >= 0.0) {
            identityGeometryStatus = REUSE_GEOMETRY_MISSING;
            if (payloadMaterial() != MATERIAL_PARTICLE) {
                identityGeometryStatus = reuseQueryHitGeometry(bounce == 0 ? CULL_PRIMARY : secondaryCull,
                                                               ro, rd, payload.hitT, identityGeometry);
                reuseCountSourceGeometry(identityGeometryStatus);
                if (indirectDepth == 0 && seg.bounce == 0 && payloadMaterial() == MATERIAL_OPAQUE
                        && identityGeometryStatus == REUSE_GEOMETRY_OK) {
                    footprintPrimary = identityGeometry;
                    footprintPrimaryValid = true;
                }
            }
        }

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
        if (identityCheck && hitDepth < PATH_REUSE_IDENTITY_DEPTH) {
            identityPos = hitPos;
            identityVertices = hitDepth + 1;
        }

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
            // The interface itself -- normal, Fresnel choice, new ray, medium stack -- is the shared
            // vertex decision the replay re-drives (reuseResolveDielectric, above).
            // R1b: a recorded path draws this vertex's decisions from its addressable vertex stream;
            // any other path keeps the shipped sequential stream, draw for draw.
            uint vertexSeed = recordPath ? vertexStream(pathSeedBase, uint(hitDepth)) : seed;
            if (!reuseResolveDielectric(material, hitPos, n, rayConeWidth, waterWaves, ro, rd, throughput,
                                        currentIor, currentExtinction, outerIor, outerExtinction,
                                        activeMediumFlags, rayConeSpread, vertexSeed)) {
                break;
            }
            if (!recordPath) {
                seed = vertexSeed;
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
            footprintPreviousValid = false; // no analytic dielectric continuation PDF is exposed
            continue;
        }

        // Particles (material == 2): camera-facing receiver billboard. The instance mask keeps particles
        // off secondary rays, so this only fires at bounce 0; they receive direct light and one diffuse
        // path continuation into the scene, but never appear in shadows/reflections/GI or emit radiance.
        if (material == MATERIAL_PARTICLE) {
            identityParticle = true; // a billboard draws its continuation from `seed`: not replayable
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

        // The material decode is the shared vertex decision the replay re-drives (reuseOpaqueVertex,
        // above); the names below are the ones the shading that follows has always used.
        ReuseOpaqueVertex vtx = reuseOpaqueVertex(hitPos, n, rd, rayConeWidth, hitDepth, sssMode, sssMaxEvents);
        float3 albedo = vtx.albedo;
        float sss = vtx.sss;
        float3 v = vtx.v;
        MaterialExtension ext = vtx.ext;
        RainSurface rain = vtx.rain;
        n = vtx.n;
        float impactFootprint = vtx.impactFootprint;
        float3 p = vtx.p;
        float rough = vtx.rough;
        float metal = vtx.metal;
        bool exactSpecular = vtx.exactSpecular;
        float3 diffAlb = vtx.diffAlb;
        float3 F0 = vtx.F0;
        float ps = vtx.ps;
        float activeSss = vtx.activeSss;
        float3 sssRadius = vtx.sssRadius;
        float walkSssShare = vtx.walkSssShare;
        BsdfContext bc = vtx.bc;
        float pf = vtx.pf;

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

        // The continuation is the shared vertex decision the replay re-drives
        // (reuseSampleContinuation, above): lobe choice, direction, weight, density, cone.
        // R1b: the same vertex-stream rule as the dielectric branch. Everything above this point at the
        // vertex (NEE, emitter RIS, M24's reuse) keeps drawing from `seed`: none of it is a vertex decision.
        uint vertexSeed = recordPath ? vertexStream(pathSeedBase, uint(hitDepth)) : seed;
        bool walked;
        ReuseContinuationSample continuation;
        if (!reuseSampleContinuation(vtx, hitPos, sssMaxEvents, ro, rd, throughput, bsdfPdf,
                                     rayConeSpread, showCelestial, vertexSeed, walked, continuation)) {
            break;
        }
        // D-event qualification probe: evaluate the first analytic continuation pair before roulette.
        // This is eligibility, not a produced path-tree event: a later RR kill may leave no D radiance.
        if (identityCheck && hitDepth < PATH_REUSE_IDENTITY_DEPTH) {
            if (!footprintFound && hitDepth >= 1 && footprintPrimaryValid && footprintPreviousValid
                    && !footprintPrefixWalk && identityGeometryStatus == REUSE_GEOMETRY_OK
                    && reuseAnalyticContinuation(footprintPreviousSample)
                    && reuseAnalyticContinuation(continuation)) {
                InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[9], 1u);
                float3 primaryToCamera = seg.ro - footprintPrimary.position;
                float primaryDistanceSq = dot(primaryToCamera, primaryToCamera);
                float primaryCosine = primaryDistanceSq > 0.0
                        ? dot(footprintPrimary.normal, primaryToCamera * rsqrt(primaryDistanceSq)) : 0.0;
                float jacDenom;
                if (reuseFootprintEligible(footprintPreviousSample, continuation,
                        footprintEdgeDirection, footprintEdgeDistance,
                        footprintPrevious.normal, identityGeometry.normal,
                        primaryDistanceSq, primaryCosine,
                        REUSE_MIN_ALPHA, REUSE_FOOTPRINT_SCALE, jacDenom)) {
                    footprintFound = true;
                    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[10], 1u);
                    InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[hitDepth == 1 ? 11 : 12], 1u);
                }
            }
            footprintPrefixWalk = footprintPrefixWalk || walked;
            footprintPrevious = identityGeometry;
            footprintPreviousSample = continuation;
            footprintPreviousValid = identityGeometryStatus == REUSE_GEOMETRY_OK;
        }
        if (!recordPath) {
            seed = vertexSeed;
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
    // Record-only rebuild: no temporal/spatial reads and no change to L. Until the tagged event
    // producer is installed, explicitly clear every owned slot rather than publish an old suffix.
    if (recordPath) {
        DevicePtr<PackedPathReservoir> store =
                DevicePtr<PackedPathReservoir>(worldPush.pathReservoirAddr);
        store[pathReservoirSlot(pix.y * renderSize.x + pix.x, renderSize.x * renderSize.y,
                                worldPush.frameIndex & 1u)] = emptyPathReservoir();
    }
    // Two vertices at least: the first one is the segment's own ray and involves no decision, so a
    // one-vertex replay would pass whatever the streams did and only dilute the rate.
    if (identityCheck && identityVertices >= 2 && !identityParticle) {
        float3 replayedPos;
        ReuseHitGeometry replayedGeometry;
        uint replayGeometryStatus;
        bool queryGeometry = identityGeometryStatus == REUSE_GEOMETRY_OK;
        int replayed = reuseReplayPrefix(seg, entryMediumFlags, pathSeedBase, identityVertices, replayedPos,
                                        queryGeometry, replayedGeometry, replayGeometryStatus);
        InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[0], 1u);
        if (queryGeometry) {
            InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[7], 1u);
            if (replayed == identityVertices && replayGeometryStatus == REUSE_GEOMETRY_OK
                    && reuseSameHitGeometry(identityGeometry, replayedGeometry)) {
                InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[8], 1u);
            }
        }
        // A decision replayed differently lands on another surface; the same decisions land on the same
        // point up to the arithmetic of a second inlined copy -- a few ulps per bounce, some 1e-6 of the
        // position, where this tolerance is 1e-4 of it.
        float3 miss = replayedPos - identityPos;
        if (replayed == identityVertices
                && dot(miss, miss) <= 1.0e-8 * (1.0 + dot(identityPos, identityPos))) {
            InterlockedAdd(DevicePtr<uint>(worldPush.pathReservoirStatsAddr)[1], 1u);
        }
    }
    return L;
}
