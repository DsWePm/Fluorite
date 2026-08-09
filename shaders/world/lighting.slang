// Direct lighting: the emitter light grid, RIS reservoirs, and reservoir shading.
// INDIRECT PASS ONLY — the primary pass shades nothing. Depends on trace and math.

// contribution weight, M the effective sample count. wSum/phat are streaming scratch (not persisted).

import world_common;
import world_core;
import bsdf;
import math;
import medium;
import light_sampling;
import trace;
import volume;
import water;

public struct Reservoir {
    public float3 pos;  // light sample point (rebased world space)
    public float3 lnrm; // emitter outward normal
    public float3 le;   // emitter radiance
    public float area;  // emitter rectangle area (the area-light pdf factor)
    public float M;
    public float W;
    public float wSum;
    public float phat;
};

public Reservoir resEmpty() {
    Reservoir r;
    r.pos = float3(0.0, 0.0, 0.0);
    r.lnrm = float3(0.0, 0.0, 0.0);
    r.le = float3(0.0, 0.0, 0.0);
    r.area = 0.0;
    r.M = 0.0;
    r.W = 0.0;
    r.wSum = 0.0;
    r.phat = 0.0;
    return r;
}

// Unshadowed contribution of a fixed light sample point at surface (hitPos,n,v); out p-hat = its
// luminance (the resampling target). Evaluated from the UNBIASED hitPos — SURF_BIAS is applied only to
// the shadow-ray origin in shadeReservoir, on whichever side the survivor sample lands. Three receiver
// modes share one target function because they are mutually exclusive per candidate:
//   - front hemisphere (ndl>0): the same Lambert(+GGX) BRDF split the sun NEE uses;
//   - twoSided (particles): billboards have no back face, ndl=abs(ndl) folds both sides into the front;
//   - back hemisphere with sss>0 (LabPBR leaves/grass): HG transmission phase — cosT = dot(wi,rd) peaks
//     when the ray points from the light through the slab toward the camera (same as the sun SSS term).
public float3 evalSampleContrib(BsdfContext c, float3 sp, float3 lnrm, float3 le, float area,
                         out float phat) {
    phat = 0.0;
    float3 toL = sp - c.hitPos;
    float dist2 = dot(toL, toL);
    if (dist2 < 1.0e-6) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    float cosL = max(0.0, dot(lnrm, -wi));
    if (cosL <= 0.0) {
        return float3(0.0, 0.0, 0.0); // receiver behind the emitter face
    }
    float ndl = dot(c.n, wi);
    if (c.twoSided) {
        ndl = abs(ndl);
    }
    float3 contrib = float3(0.0, 0.0, 0.0);
    if (ndl > 0.0) {
        float G = ndl * cosL / dist2; // area-light geometry term (x area)
        float3 brdf = c.diffAlb * INV_PI;
        // twoSided callers are particle billboards, which pass rough=1/F0=0 and want diffuse-only
        // shading: fresnelSchlick still returns up to full white at grazing angles even with F0=0 (that
        // IS the Fresnel effect), so skipping the term here — not just zeroing F0 — is what keeps
        // billboards from picking up a rim highlight they were never meant to have.
        if (!c.twoSided) {
            float3 h = normalize(wi + c.v);
            float ndh = max(0.0, dot(c.n, h));
            float vdh = max(0.0, dot(c.v, h));
            // Emitter rectangles are sampled by area and never weighted against BSDF sampling, so this
            // is one of the two places UNWEIGHTED_SPEC_ALPHA_FLOOR is what bounds the peak. The real fix
            // is MIS against emitter sampling, which is its own piece of work.
            //
            // The floor applies to D alone. Smith masking is within a fraction of a percent of 1 for any
            // alpha this small at any angle that survives the ndl > 0 test, so flooring it too would buy
            // nothing and would cost the hoisted c.g1v below.
            //
            // This is the one specular site that still branches on anisotropy rather than running the
            // anisotropic form for everyone. It is called M+1 times per shading vertex — the measured
            // 5.9ms loop — and the branch is uniform across that loop because it depends only on the
            // receiver, so it costs one comparison and never diverges.
            float D;
            float Gs;
            if (c.aniso) {
                float ax = max(c.alphaX, UNWEIGHTED_SPEC_ALPHA_FLOOR);
                float ay = max(c.alphaY, UNWEIGHTED_SPEC_ALPHA_FLOOR);
                D = ggxDAniso(dot(h, c.t), dot(h, c.b), ndh, ax, ay);
                Gs = c.g1v * ggxG1Aniso(dot(wi, c.t), dot(wi, c.b), ndl, ax, ay);
            } else {
                D = ggxD(ndh, max(c.rough, UNWEIGHTED_SPEC_ALPHA_FLOOR));
                // c.g1v is the view half of Smith masking, hoisted: it depends only on the receiver, and
                // this function runs once per RIS candidate.
                Gs = c.g1v * ggxG1(ndl, c.rough);
            }
            float3 Fs = fresnelSchlick(vdh, c.F0);
            brdf += (D * Gs) * Fs / (4.0 * c.ndv * ndl);
        }
        contrib = brdf * le * (G * area);
    } else if (c.sss > 0.0) {
        float backNdl = -ndl;
        float G = backNdl * cosL / dist2;
        float cosT = dot(wi, c.rd);
        contrib = c.diffAlb * c.sss * sssStrength() * sssPhase(cosT) * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}

// Select one light from the emitted-power distribution in O(1).
public void selectGlobalLight(inout uint proposalSeed, out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(worldPush.lightCount);
    uint column = min(uint(aliasSample), worldPush.lightCount - 1u);
    if (pc.lightAliasAddr != 0) {
        LightAlias a = ConstPtr<LightAlias>(pc.lightAliasAddr)[column];
        bool self = aliasSample - float(column) < a.accept;
        lightIndex = self ? column : a.aliasIndex;
    } else {
        lightIndex = column;
    }
}

public bool findLightGridCell(float3 p, out LightGridCell cell, out int3 cellCoord) {
    cell.spanOffset = 0u;
    cell.spanCount = 0u;
    cell.invWeightSum = 0.0;
    cellCoord = int3(0, 0, 0);
    if (pc.lightGridCellAddr == 0 || pc.lightGridSpanAddr == 0) return false;
    int3 coord = int3(floor((p - worldPush.lightGridOrigin.xyz) / worldPush.lightGridOrigin.w));
    if (coord.x < 0 || coord.y < 0 || coord.z < 0
            || coord.x >= worldPush.lightGridDims.x
            || coord.y >= worldPush.lightGridDims.y
            || coord.z >= worldPush.lightGridDims.z) return false;
    uint linear = (uint(coord.z) * uint(worldPush.lightGridDims.y) + uint(coord.y))
            * uint(worldPush.lightGridDims.x) + uint(coord.x);
    cell = ConstPtr<LightGridCell>(pc.lightGridCellAddr)[linear];
    cellCoord = coord;
    return cell.spanCount > 0u;
}

public void selectSectionLight(uint firstLight, uint lightCount, inout uint proposalSeed,
                        out uint lightIndex) {
    float aliasSample = rndf(proposalSeed) * float(lightCount);
    uint column = min(uint(aliasSample), lightCount - 1u);
    LightAlias alias = ConstPtr<LightAlias>(pc.lightLocalAliasAddr)[firstLight + column];
    bool self = aliasSample - float(column) < alias.accept;
    uint localIndex = self ? column : alias.aliasIndex;
    lightIndex = firstLight + localIndex;
}

public float unpackUnsignedFloat(uint bits, uint mantissaBits) {
    uint mantissaMask = (1u << mantissaBits) - 1u;
    uint mantissa = bits & mantissaMask;
    uint exponent = (bits >> mantissaBits) & 31u;
    if (exponent == 0u) {
        return float(mantissa) * exp2(float(1 - 15 - int(mantissaBits)));
    }
    return (1.0 + float(mantissa) / float(1u << mantissaBits))
            * exp2(float(int(exponent) - 15));
}

public float3 lightRadiance(Light light) {
    uint packed = light.le;
    return float3(unpackUnsignedFloat(packed & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 11u) & 0x7ffu, 6u),
            unpackUnsignedFloat((packed >> 22u) & 0x3ffu, 5u));
}

public int3 lightSectionCoord(Light light) {
    uint packed = light.section;
    return int3(int(packed & 0x3ffu), int((packed >> 10u) & 0x3ffu),
            int((packed >> 20u) & 0x3ffu));
}

// The half axes share three lanes two-at-a-time; see the Light record in world_common.
public float3 lightHalfU(Light light) {
    return float3(unpackHalf2(light.halfUxy), unpackHalf2(light.halfUzVx).x);
}

public float3 lightHalfV(Light light) {
    return float3(unpackHalf2(light.halfUzVx).y, unpackHalf2(light.halfVyz));
}

// U x V drives both the emitter normal and the rectangle area, so it is computed once and shared.
public float3 lightCrossUV(Light light) {
    return cross(lightHalfU(light), lightHalfV(light));
}

// The rect spans 2U x 2V, so its area is 4|U x V| — exactly the rectArea the collector used to store.
public float lightArea(Light light) {
    return 4.0 * length(lightCrossUV(light));
}

public float3 lightGeometricNormal(Light light) {
    float3 normal = normalize(lightCrossUV(light));
    return (light.section & 0x40000000u) != 0u ? -normal : normal;
}

public float proposalPdf(Light light, float3 le, LightGridCell cell, int3 cellCoord,
                  float localProbability) {
    float power = max(0.0, lightArea(light) * luminance(le));
    float globalPdf = pc.lightAliasAddr != 0
            ? power * worldPush.lightRebase.w : 1.0 / float(worldPush.lightCount);
    float localPdf = 0.0;
    if (localProbability > 0.0 && power > 0.0) {
        int3 delta = lightSectionCoord(light) - cellCoord;
        if (all(abs(delta) <= int3(2, 2, 2))) {
            float3 deltaF = float3(delta);
            localPdf = power * cell.invWeightSum / max(1.0, dot(deltaF, deltaF));
        }
    }
    return localProbability * localPdf + (1.0 - localProbability) * globalPdf;
}

public void selectLightGridSpanLight(LightGridCell cell, inout uint proposalSeed,
                              out uint lightIndex) {
    ConstPtr<LightGridSpan> spans = ConstPtr<LightGridSpan>(pc.lightGridSpanAddr);
    float aliasSample = rndf(proposalSeed) * float(cell.spanCount);
    uint column = min(uint(aliasSample), cell.spanCount - 1u);
    LightGridSpan span = spans[cell.spanOffset + column];
    bool self = aliasSample - float(column) < span.accept;
    uint firstLight = self ? span.firstLight : span.aliasFirstLight;
    uint lightCount = self ? (span.packedLightCounts & 0xffffu)
            : (span.packedLightCounts >> 16u);
    selectSectionLight(firstLight, lightCount, proposalSeed, lightIndex);
}

// Sample the exact mixture q = alpha*qCell + (1-alpha)*qGlobal. qCell first selects a nearby section
// by emitted power and section distance, then its section-local power alias. Every light in
// the neighborhood is represented without a fixed cap; qGlobal gives distant lights full support.
// Alias spans make weighted local selection O(1). The mixture PDF is reconstructed after the single
// 32-byte Light load: section power cancels with the section-local light PDF.
public void selectLightGridLight(LightGridCell cell, bool useLocal, inout uint proposalSeed,
                          out uint lightIndex) {
    if (useLocal) {
        selectLightGridSpanLight(cell, proposalSeed, lightIndex);
    } else {
        selectGlobalLight(proposalSeed, lightIndex);
    }
}

// ---- RIS cost profile. Temporary probes pinned the candidate walk out of the shader to bound what
// memory-side RIS work could ever be worth. Against an
// 18.0ms frame at M=8, removing every dependent load (span -> alias -> record) and all lane divergence
// saved 5.9ms — a third of the frame. Split by vertex that was ~4.3ms at secondary vertices against
// ~1.1ms at the primary hit, and forcing coherent selection recovered at most 1.3ms of it. So the cost
// was chasing depth, not divergence, and it lived at secondary vertices. A presampled candidate pool
// would attack the same 5.9ms structurally, but see the note on
// proposalSeed in tracePath for why it should share the pool rather than the seed.

// Initial RIS over M power-weighted candidates -> one resampled sample, no shadow ray yet. The chosen
// sample's W = wSum / (M * p-hat); M counts every candidate, including zero-weight ones.
public Reservoir risInitial(BsdfContext ctx, inout uint seed, inout uint proposalSeed) {
    Reservoir r = resEmpty();
    LightGridCell gridCell;
    int3 gridCellCoord;
    float3 gridLookup = ctx.hitPos;
    if (pc.lightGridCellAddr != 0) {
        // Stochastically blend across hard section boundaries. Every cell proposal retains full global
        // support, so conditioning on this independently jittered lookup remains unbiased.
        gridLookup += (float3(rndf(proposalSeed), rndf(proposalSeed), rndf(proposalSeed)) - 0.5)
                * worldPush.lightGridOrigin.w;
    }
    bool hasGridCell = findLightGridCell(gridLookup, gridCell, gridCellCoord);
    uint candidateCount = worldPush.risCandidates;
    r.M = float(candidateCount);
    // Deterministically stratify the proposal mixture. At the default M=8 this schedules exactly six
    // local and two global candidates, with every lane taking the same branch for a given candidate.
    // Counts that are not divisible by four use the nearest practical split with at least one global
    // candidate; M=1 is global-only so the estimator retains full support.
    uint globalCandidateCount = hasGridCell
            ? max(1u, (candidateCount + 2u) / 4u) : candidateCount;
    uint localCandidateCount = candidateCount - globalCandidateCount;
    float localProbability = float(localCandidateCount) / float(candidateCount);
    for (uint c = 0u; c < candidateCount; c++) {
        uint li;
        uint globalsBefore = (c * globalCandidateCount) / candidateCount;
        uint globalsAfter = ((c + 1u) * globalCandidateCount) / candidateCount;
        bool useLocal = hasGridCell && globalsAfter == globalsBefore;
        selectLightGridLight(gridCell, useLocal, proposalSeed, li);
        Light lg = ConstPtr<Light>(pc.lightBufAddr)[li];
        // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
        float s = rndf(seed) * 2.0 - 1.0;
        float t = rndf(seed) * 2.0 - 1.0;
        float3 sp = lg.pos + worldPush.lightRebase.xyz
                + s * lightHalfU(lg) + t * lightHalfV(lg);
        float3 le = lightRadiance(lg);
        float3 lightNormal = lightGeometricNormal(lg);
        float area = lightArea(lg);
        float phat;
        evalSampleContrib(ctx, sp, lightNormal, le, area, phat);
        if (phat <= 0.0) {
            continue;
        }
        float sourcePdf = proposalPdf(lg, le, gridCell, gridCellCoord, localProbability);
        float w = phat / max(sourcePdf, 1.0e-20);
        r.wSum += w;
        if (rndf(seed) * r.wSum < w) { // weighted reservoir update
            r.pos = sp;
            r.lnrm = lightNormal;
            r.le = le;
            r.area = area;
            r.phat = phat;
        }
    }
    r.W = r.phat > 0.0 ? (r.wSum / (r.M * r.phat)) : 0.0;
    return r;
}

/**
 * One emitter sample for a volume scattering event (M17), returning radiance per unit in-scatter weight.
 *
 * <p>This is the reuse M6 reserved and described: the SELECTION machinery is taken wholesale — the same
 * jittered grid cell, the same power-weighted alias tables, the same reconstructed mixture pdf — and only
 * the target changes. A point in a medium has no normal and no BRDF, so the phase function stands where
 * the cosine-weighted lobe stands on a surface, and the emitter's own cosine keeps the geometry term.
 *
 * <p><b>It stays clear of everything ReSTIR will rewrite.</b> M is one, there is no reservoir, no temporal
 * or spatial reuse, and no analytic MIS weight against emitter sampling — and it does not touch
 * evalSampleContrib, whose per-candidate cost is the measured hot path. When ReSTIR lands it should
 * replace how this sample is CHOSEN without touching what it is worth.
 *
 * <p>Unshadowed emitters are not double counted: this is the only estimator that ever samples them for a
 * volume, because the closed forms carry the sun and sky alone.
 */
public float3 volumeNee(float3 mediumExtinction, uint mediumFlags, float3 pos, float3 rd,
                        float phaseG, inout uint seed, inout uint proposalSeed) {
    LightBufferAddresses addresses;
    addresses.light = pc.lightBufAddr;
    addresses.globalAlias = pc.lightAliasAddr;
    addresses.localAlias = pc.lightLocalAliasAddr;
    addresses.gridCell = pc.lightGridCellAddr;
    addresses.gridSpan = pc.lightGridSpanAddr;
    EmitterSample emitter;
    if (!sampleVolumeEmitter(worldPush, addresses, pos, seed, proposalSeed, emitter)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 toL = emitter.pos - pos;
    float dist2 = max(dot(toL, toL), 1.0e-6);
    float dist = sqrt(dist2);
    float3 wi = toL / dist;
    // The emitter's own foreshortening. No receiver cosine: an isotropic point in a medium has no facing.
    float cosL = max(0.0, dot(emitter.normal, -wi));
    if (cosL <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // The area sample below estimates irradiance at the event, so its directional source is E*p. hg()
    // is already the normalised phase density; multiplying it by 4*pi would create that much energy.
    float phase = hg(dot(rd, wi), phaseG);
    float3 contrib = emitter.radiance
            * (phase * cosL * emitter.area / dist2) / emitter.sourcePdf;
    if (all(contrib <= 0.0)) {
        return float3(0.0, 0.0, 0.0);
    }
    // D31 measured which half of this function is expensive, and the answer was NOT the ray: silencing
    // it made the frame 5.2 ms SLOWER (62.85 against 57.67), a negative cost that can only be the
    // observer effect F15 records -- removing the trace changed register live ranges and cost occupancy.
    // What that rules out is worth keeping: optimising the RAY cannot help this function. Distance
    // culling, an irradiance early-out and a shorter tmax are all answers to the wrong question. The
    // cost is the walk above and the pressure this function creates, and both say the same thing --
    // invoke it less often rather than make the invocation cheaper.
    //
    // Through the ambient wrapper like every other shadow ray, so an emitter beyond the fog is behind it.
    // No surface bias to apply: the origin is a point in a medium, not on a face.
    VisibilityResult shadow = visibilityThroughAmbient(
            mediumExtinction, mediumFlags, pos, wi, dist * 0.999);
    return contrib * shadow.transmittance;
}

// Shade a finalized reservoir: recompute the survivor's contribution at (hitPos,n,v), cast ONE shadow
// ray, and return throughput-free radiance contrib*vis*W. The one ray serves whichever term fired for
// the survivor (front BRDF, twoSided billboard, or SSS backscatter) — the origin is biased toward the
// sample's side of the surface.
public float3 shadeReservoir(float3 mediumExtinction, uint mediumFlags,
                             BsdfContext c, MaterialExtension ext, Reservoir s) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float phat;
    float3 contrib = evalSampleContrib(c, s.pos, s.lnrm, s.le, s.area, phat);
    // The survivor is shaded with the FULL response while the RIS target above stayed reduced. That is
    // deliberate and unbiased: the estimator is f(y)*W with W = (1/M)*sum(w)/phat(y), which is unbiased
    // for any strictly positive target phat — the target only shapes the variance. Keeping sheen out of
    // the target keeps M+1 evaluations per vertex cheap; putting it in f keeps the picture right.
    if (contrib.x + contrib.y + contrib.z > 0.0) {
        float3 wi = normalize(s.pos - c.hitPos);
        if (dot(c.n, wi) > 0.0) {
            float dist2 = max(dot(s.pos - c.hitPos, s.pos - c.hitPos), 1.0e-6);
            float cosL = max(0.0, dot(s.lnrm, -wi));
            contrib += disneyLobes(c, ext, wi) * s.le * (dot(c.n, wi) * cosL / dist2 * s.area);
        }
    }
    if (phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // Pick the shadow-ray origin on the sample's side of the surface FIRST, then measure the ray from
    // that origin — measuring dist from hitPos while tracing from the biased origin shifts the ray tip
    // up to SURF_BIAS*ndl toward the emitter plane, beating the 0.001*dist stop-short margin for lights
    // closer than ~SURF_BIAS/0.001 blocks -> shadow ray hits the emitter's own face -> per-texel black.
    float side = dot(c.n, s.pos - c.hitPos) >= 0.0 ? 1.0 : -1.0;
    float3 origin = c.hitPos + side * c.n * SURF_BIAS;
    float3 toL = s.pos - origin;
    float dist = length(toL);
    // Stop just short of the sample point so the ray doesn't self-occlude on the emitter's own face.
    // Through the ambient wrapper like every other shadow ray: an emitter 200 blocks away is behind the
    // same fog the sun is, and this one call site quietly missed that when the ambient medium landed.
    float3 wiShadow = toL / dist;
    VisibilityResult shadow = visibilityThroughAmbient(
            mediumExtinction, mediumFlags, origin, wiShadow, dist * 0.999);
    float3 vis = shadow.transmittance;
    // The other half of R23. The ambient wrapper above fixed this call site's fog; the caustic was the
    // part still missing, so a lantern over a pond lit the bottom through a surface that visibly bends
    // its light while the sun through the same surface did not. Focusing scales incident irradiance, so
    // like the sun's it multiplies the whole term rather than any one lobe.
    //
    // Approximate for a NEAR emitter in a way the sun is not: waterCaustic finite-differences a single
    // refracted direction, which is a collimated beam's Jacobian. A point source's rays reach
    // neighbouring surface points from different directions, so the true focusing differs — the error
    // shrinks with distance and is bounded by the same CAUSTIC_MAX either way. An emitter below the
    // surface never crosses it, leaves waterHitT at the -1 sentinel, and takes no caustic at all.
    if (mediumFlagsWater(mediumFlags) && (worldPush.flags & 16u) != 0u && shadow.waterHitT > 0.0) {
        vis *= waterCaustic(origin + wiShadow * shadow.waterHitT, wiShadow, shadow.waterHitT);
    }
    return contrib * vis * s.W;
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
