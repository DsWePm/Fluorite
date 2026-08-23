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

/** No light. Distinguishable from index 0, which is a real emitter. */
public static const uint RESERVOIR_NO_LIGHT = 0xFFFFFFFFu;

/**
 * Which buffer a stored lightIndex points into.
 *
 * <p>The two light buffers are indexed independently, so an index alone is ambiguous the moment dynamic
 * emitters enter the candidate set — and the consequence of getting it wrong is not a crash but a
 * reservoir validated against a completely unrelated light, which is the failure the whole light-identity
 * check exists to prevent. Bit 31 because neither buffer will ever hold two billion records, and
 * RESERVOIR_NO_LIGHT stays distinct from any tagged index because it sets every other bit too.
 */
public static const uint RESERVOIR_DYNAMIC_LIGHT_BIT = 0x80000000u;
public bool reservoirLightIsDynamic(uint index) {
    return index != RESERVOIR_NO_LIGHT && (index & RESERVOIR_DYNAMIC_LIGHT_BIT) != 0u;
}
public uint reservoirLightSlot(uint index) { return index & ~RESERVOIR_DYNAMIC_LIGHT_BIT; }

public struct Reservoir {
    public float3 pos;  // light sample point (rebased world space)
    public float3 lnrm; // emitter outward normal
    public float3 le;   // emitter radiance
    public float area;  // emitter rectangle area (the area-light pdf factor)
    public float M;
    public float W;
    public float wSum;
    public float phat;
    // Which Light record the survivor came from. Single-frame RIS has no use for it — the sample carries
    // everything shading needs — and it exists so that a reservoir which OUTLIVES its frame can be asked
    // whether its emitter is still there. See restir.slang; a stored sample whose torch has been mined
    // still has a position, a radiance and an area, and lights the room from nowhere without this.
    public uint lightIndex;
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
    r.lightIndex = RESERVOIR_NO_LIGHT;
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
    // The emitter controls land HERE rather than in lightRadiance, because this is where radiance turns
    // into light. proposalPdf takes its own untinted copy of the same le -- see WorldPush.emitterTint for
    // why tinting the decode instead makes the brightness slider a no-op.
    le *= worldPush.emitterTint.xyz;
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
        // One decision for the base lobe and the rain film both, hoisted here because the film is added
        // outside the !twoSided branch below.
        bool floorAlpha = !emitterMisOn();
        float3 brdf = c.diffAlb * INV_PI;
        // twoSided callers are particle billboards, which pass rough=1/F0=0 and want diffuse-only
        // shading: fresnelSchlick still returns up to full white at grazing angles even with F0=0 (that
        // IS the Fresnel effect), so skipping the term here — not just zeroing F0 — is what keeps
        // billboards from picking up a rim highlight they were never meant to have.
        if (!c.twoSided) {
            float3 h = normalize(wi + c.v);
            float ndh = max(0.0, dot(c.n, h));
            float vdh = max(0.0, dot(c.v, h));
            // NO ALPHA FLOOR while the weight is on. It stood here because an emitter rectangle was
            // sampled by area and never weighted against BSDF sampling, so nothing else bounded the peak
            // of a very smooth lobe; shadeReservoir applies the real fix now, and flooring on top of a MIS
            // weight would blur a highlight no longer at risk of exploding while putting the target
            // function out of step with the density weighing it.
            //
            // It comes back exactly when the weight goes away, because the pair is one decision -- see
            // FLAG_EMITTER_MIS_OFF. Note which alphas it touches: the anisotropic branch floors D AND its
            // Smith term, the isotropic branch floors D ALONE. That asymmetry is the shipped behaviour and
            // is deliberate -- masking is within a fraction of a percent of 1 for any alpha this small at
            // any angle surviving ndl > 0, so flooring it there buys nothing and costs the hoisted c.g1v.
            // Reproducing it is what makes off equal to what shipped rather than merely resemble it.
            //
            // This is the one specular site that still branches on anisotropy rather than running the
            // anisotropic form for everyone. It is called M+1 times per shading vertex — the measured
            // 5.9ms loop — and both branches here are uniform across that loop: one depends only on the
            // receiver and the other only on a frame constant, so they cost a comparison and never diverge.
            float D;
            float Gs;
            if (c.aniso) {
                float ax = floorAlpha ? max(c.alphaX, UNWEIGHTED_SPEC_ALPHA_FLOOR) : c.alphaX;
                float ay = floorAlpha ? max(c.alphaY, UNWEIGHTED_SPEC_ALPHA_FLOOR) : c.alphaY;
                D = ggxDAniso(dot(h, c.t), dot(h, c.b), ndh, ax, ay);
                Gs = c.g1v * ggxG1Aniso(dot(wi, c.t), dot(wi, c.b), ndl, ax, ay);
            } else {
                D = ggxD(ndh, floorAlpha ? max(c.rough, UNWEIGHTED_SPEC_ALPHA_FLOOR) : c.rough);
                // c.g1v is the view half of Smith masking, hoisted: it depends only on the receiver, and
                // this function runs once per RIS candidate.
                Gs = c.g1v * ggxG1(ndl, c.rough);
            }
            float3 Fs = fresnelSchlick(vdh, c.F0);
            brdf += (D * Gs) * Fs / (4.0 * c.ndv * ndl);
        }
        // Same decision, same flag: the film had its own floor and lost it with the rest of S4b.
        brdf = brdf * rainBaseTransmission(c, wi) + rainFilmBrdf(c, wi, floorAlpha);
        contrib = brdf * le * (G * area);
    } else if (c.sss > 0.0) {
        float backNdl = -ndl;
        float G = backNdl * cosL / dist2;
        float cosT = dot(wi, c.rd);
        // Back lighting enters the substrate from the uncoated side and exits through the visible
        // water film once. Applying the two-interface reflection formula here makes a full film
        // annihilate all thin-shell transmission because dot(n,wi)<0 evaluates the second Fresnel at 1.
        contrib = c.diffAlb * c.sss * sssStrength() * sssPhase(cosT)
                * rainBaseExitTransmission(c) * le * (G * area);
    }
    phat = luminance(contrib);
    return contrib;
}

/**
 * The density, per unit AREA on an emitter, that the emitter strategy carries for a point of radiance
 * `le` — as used by BOTH sides of M24 S4's MIS weight.
 *
 * <p>THE AREA CANCELS, and that is what makes this computable at a BSDF hit where nothing knows which
 * light was struck. The proposal picks a light in proportion to its power, area times luminance, and then
 * a point on it uniformly with density one over that same area — so the joint density of a point is its
 * radiance times the inverse total power, and the emitter's extent drops out of both factors at once.
 *
 * <p>It is the GLOBAL stratum only; the real mixture also has the light grid's local term. That makes it
 * an approximation of the true density and NOT a source of bias: a MIS weight may be built from any pair
 * of positive functions, and what unbiasedness requires is that the two sides use the SAME pair — which is
 * why this exists as one function rather than as two expressions that happen to match today.
 */
public float emitterAreaDensity(float3 le) {
    return luminance(le) * worldPush.lightRebase.w;
}

/** The same for the competing strategy: a solid-angle density carried onto the emitter's surface. */
public float bsdfAreaDensity(float solidAnglePdf, float cosAtEmitter, float distanceSquared) {
    return solidAnglePdf * max(cosAtEmitter, 0.0) / max(distanceSquared, 1.0e-6);
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

/** A dynamic sphere's radius, from the low half of the lane a rectangle spends on its U axis. */
public float lightSphereRadius(Light light) {
    return unpackHalf2(light.halfUxy).x;
}

/**
 * One uniform point on the half of a sphere emitter that faces the receiver, in the AREA measure.
 *
 * <p>The hemisphere rather than the whole sphere: the far half contributes nothing -- its outward normal
 * points away, so the emitter cosine zeroes it -- and sampling it anyway would throw away half of every
 * dynamic candidate inside the loop that measurement showed is the expensive one. Uniform over 2*pi*r^2
 * with density 1/(2*pi*r^2) is exactly the contract evalSampleContrib's area argument expresses, so this
 * needs no second pdf path beside the rectangle's.
 *
 * <p>IT IS EXACT, AND THE WIDER DOMAIN COSTS SAMPLES RATHER THAN ACCURACY. The set actually visible from a
 * point at distance D is the cap where dot(n, d) > r, a cap strictly smaller than this hemisphere -- but
 * the band between them needs no test of its own, because it is precisely where evalSampleContrib's
 * emitter cosine already goes negative: for x = c + r*n the term is dot(n, d) - r, which is what that cap
 * is defined by. So the silhouette band contributes exactly zero and nothing is over-counted; sampling it
 * merely wastes a fraction r/D of the dynamic candidates. Negligible for the small radii an entity, a held
 * torch and a flame particle have, and largest exactly when the receiver is nearly touching the emitter.
 *
 * <p>A receiver INSIDE the sphere has no facing half at all and is rejected outright.
 *
 * <p>No lightRebase. See WorldPush.dynamicLightAddr: this buffer is authored in current rebased space.
 */
public bool sampleSphereEmitter(Light light, float3 receiver, inout uint seed,
                                out float3 sp, out float3 lnrm, out float area) {
    sp = float3(0.0, 0.0, 0.0);
    lnrm = float3(0.0, 0.0, 1.0);
    area = 0.0;
    float radius = lightSphereRadius(light);
    float3 toReceiver = receiver - light.pos;
    float dist2 = dot(toReceiver, toReceiver);
    if (!(radius > 0.0) || dist2 <= radius * radius) {
        return false;
    }
    float3 axis = toReceiver * rsqrt(dist2);
    // Uniform on the hemisphere about `axis`: z uniform in [0,1] is what makes it uniform in AREA rather
    // than crowding the pole, which is the same identity that makes a sphere's zones equal-area.
    float z = rndf(seed);
    float phi = rndf(seed) * 6.28318530718;
    float r = sqrt(max(0.0, 1.0 - z * z));
    float3 t;
    float3 b;
    canonicalTangentFrame(axis, t, b);
    lnrm = normalize(t * (r * cos(phi)) + b * (r * sin(phi)) + axis * z);
    sp = light.pos + lnrm * radius;
    area = 6.28318530718 * radius * radius;
    return true;
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
    // M18's dynamic emitters take their own stratum, and the reason is structural rather than a
    // preference: they live in a separate per-frame buffer because the alias tables and the dense grid are
    // built with the terrain and would have to be rebuilt whole every frame to carry entities. Reserving
    // candidates for them is what gets them proposed at all without touching either structure.
    //
    // Held back from the total BEFORE the local/global split, so the existing stratification keeps
    // working on whatever is left; and capped a candidate short of the whole budget, because a vertex that
    // spent every candidate on entities would stop seeing the world's own lights.
    uint dynamicCandidateCount = worldPush.dynamicLightCount > 0u && worldPush.dynamicLightAddr != 0
            ? min(worldPush.dynamicRisCandidates, candidateCount - 1u) : 0u;
    uint staticCandidateCount = candidateCount - dynamicCandidateCount;
    // Deterministically stratify the proposal mixture. At the default M=8 this schedules exactly six
    // local and two global candidates, with every lane taking the same branch for a given candidate.
    // Counts that are not divisible by four use the nearest practical split with at least one global
    // candidate; M=1 is global-only so the estimator retains full support.
    uint globalCandidateCount = hasGridCell
            ? max(1u, (staticCandidateCount + 2u) / 4u) : staticCandidateCount;
    uint localCandidateCount = staticCandidateCount - globalCandidateCount;
    float localProbability = float(localCandidateCount) / float(staticCandidateCount);
    for (uint c = 0u; c < candidateCount; c++) {
        float3 sp;
        float3 lightNormal;
        float3 le;
        float area;
        float sourcePdf;
        uint li;
        // The dynamic candidates come first. Uniform across c, since both counts are frame constants, so
        // this branch stays wave-coherent the way the local/global one already is.
        if (c < dynamicCandidateCount) {
            li = min(uint(rndf(proposalSeed) * float(worldPush.dynamicLightCount)),
                     worldPush.dynamicLightCount - 1u);
            Light lg = ConstPtr<Light>(worldPush.dynamicLightAddr)[li];
            if (!sampleSphereEmitter(lg, ctx.hitPos, seed, sp, lightNormal, area)) {
                continue; // receiver inside the emitter, or a radius that never got authored
            }
            le = lightRadiance(lg);
            // Uniform over the buffer, and the mixture needs no other term: the two buffers hold disjoint
            // shapes, so nothing here could have been proposed by the local or global stratum. Power
            // weighting would want an alias table rebuilt every frame, which is the cost this stratum
            // exists to avoid; it is the obvious next refinement and is not free.
            sourcePdf = (float(dynamicCandidateCount) / float(candidateCount))
                    / float(worldPush.dynamicLightCount);
            li |= RESERVOIR_DYNAMIC_LIGHT_BIT;
        } else {
            uint sc = c - dynamicCandidateCount;
            uint globalsBefore = (sc * globalCandidateCount) / staticCandidateCount;
            uint globalsAfter = ((sc + 1u) * globalCandidateCount) / staticCandidateCount;
            bool useLocal = hasGridCell && globalsAfter == globalsBefore;
            // M26 slice two: a LOCAL candidate can come out of the presampled pool, which replaces the
            // span, the alias column and the Light load with one read. The cell was already found once
            // for the whole vertex, so what this removes is three dependent loads PER CANDIDATE -- at
            // the default eight candidates that is the bulk of this loop's memory latency.
            //
            // Global candidates keep the walk: the pool is per cell and has nothing to say about a light
            // outside the neighbourhood, which is exactly the support the global stratum exists to give.
            Light lg;
            bool fromPool = false;
            float pooledLocalPdf = 0.0;
            if (useLocal && (worldPush.environmentFlags & ENVIRONMENT_LIGHT_POOL_SURFACE) != 0u
                    && worldPush.lightPoolAddr != 0 && worldPush.lightPoolDepth != 0u) {
                uint slot = min(uint(rndf(proposalSeed) * float(worldPush.lightPoolDepth)),
                                worldPush.lightPoolDepth - 1u);
                PooledLight pooled = ConstPtr<PooledLight>(worldPush.lightPoolAddr)[
                        gridCell.poolRank * worldPush.lightPoolDepth + slot];
                // ONLY a negative density sends this back to the walk. A ZERO one is a real answer: the
                // build drew a light whose LOCAL density is zero, and proposalPdf gives exactly such a
                // light (1-alpha)*globalPdf and keeps it as a candidate. Treating zero as "empty" here
                // replaced a candidate the walk would have kept, which is the surface half of the same
                // bug the volume path had -- and it needs no special case to fix, because the mixture
                // below already does the right thing with a zero local half.
                if (pooled.pdf >= 0.0) {
                    lg = pooledLightRecord(pooled);
                    li = pooled.lightIndex;
                    pooledLocalPdf = pooled.pdf;
                    fromPool = true;
                }
            }
            if (!fromPool) {
                selectLightGridLight(gridCell, useLocal, proposalSeed, li);
                lg = ConstPtr<Light>(pc.lightBufAddr)[li];
            }
            // Soft shadows: uniform point on the emitter rectangle ((s,t) in [-1,1]^2, pdf 1/area).
            float s = rndf(seed) * 2.0 - 1.0;
            float t = rndf(seed) * 2.0 - 1.0;
            sp = lg.pos + worldPush.lightRebase.xyz
                    + s * lightHalfU(lg) + t * lightHalfV(lg);
            le = lightRadiance(lg);
            lightNormal = lightGeometricNormal(lg);
            area = lightArea(lg);
            // Scaled by this stratum's share of the budget, for the same reason the dynamic one is: the
            // mixture is over all M candidates, and holding some back changed what fraction of them this
            // density describes.
            //
            // THE POOL STORES HALF THE MIXTURE. Its slot carries the LOCAL density, computed by the build
            // against this same cell; the surface estimator samples alpha*local + (1-alpha)*global and
            // needs both. The missing half is reconstructed rather than looked up -- power is area times
            // luminance, and both are already in hand -- so the pooled candidate costs no extra load and
            // lands on exactly the density proposalPdf would have returned.
            float mixedPdf;
            if (fromPool) {
                float power = max(0.0, area * luminance(le));
                float globalPdf = pc.lightAliasAddr != 0
                        ? power * worldPush.lightRebase.w : 1.0 / float(worldPush.lightCount);
                mixedPdf = localProbability * pooledLocalPdf + (1.0 - localProbability) * globalPdf;
            } else {
                mixedPdf = proposalPdf(lg, le, gridCell, gridCellCoord, localProbability);
            }
            sourcePdf = mixedPdf * (float(staticCandidateCount) / float(candidateCount));
        }
        float phat;
        evalSampleContrib(ctx, sp, lightNormal, le, area, phat);
        if (phat <= 0.0) {
            continue;
        }
        float w = phat / max(sourcePdf, 1.0e-20);
        r.wSum += w;
        if (rndf(seed) * r.wSum < w) { // weighted reservoir update
            r.pos = sp;
            r.lnrm = lightNormal;
            r.le = le;
            r.area = area;
            r.phat = phat;
            r.lightIndex = li;
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
    // M26: the pool answers when it has been built, and the walk answers otherwise. Both produce the
    // same proposal with the same density -- the pool's slots were filled by running the walk -- so this
    // is a choice of WHERE the loads happen, not of what is sampled.
    EmitterSample emitter;
    uint poolStatus = sampleVolumeEmitterPooled(worldPush, addresses, pos, seed, proposalSeed, emitter);
    // A REJECTED pooled draw is a zero, not a reason to draw again. The walk this pool stands in for
    // returns false for exactly these lights and volumeNee turns that into black; redrawing would give
    // the rejection a second chance and leave the pooled fog brighter than the walk it replaces.
    if (poolStatus == EMITTER_POOL_REJECT) {
        return float3(0.0, 0.0, 0.0);
    }
    if (poolStatus == EMITTER_POOL_MISS
            && !sampleVolumeEmitter(worldPush, addresses, pos, seed, proposalSeed, emitter)) {
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
    // Tinted here and not at emitter.radiance, which sampleVolumeEmitter also hands to its proposal
    // density -- the same cancellation the surface path avoids.
    float3 contrib = (emitter.radiance * worldPush.emitterTint.xyz)
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
                             BsdfContext c, MaterialExtension ext, Reservoir s,
                             float lobeFilmShare, float lobeSpecShare, float lobeWalkShare) {
    if (s.W <= 0.0 || s.phat <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    // M24 S4's MIS weight against the continuation that could have found this same emitter by chance.
    //
    // Only for emitters the BSDF ray can actually hit. A DYNAMIC sample is a sphere standing in for a
    // mesh: the two strategies sample different domains, their densities never meet, and a weight built
    // from them would not sum to one. Those keep the outright partition the raygen applies instead.
    float misWeight = 1.0;
    if (emitterMisOn() && !reservoirLightIsDynamic(s.lightIndex)) {
        float3 toL = s.pos - c.hitPos;
        float dist2 = max(dot(toL, toL), 1.0e-6);
        float3 wi = toL * rsqrt(dist2);
        float continuationPdf = bsdfAreaDensity(
                bsdfContinuationPdf(c, wi, lobeFilmShare, lobeSpecShare, lobeWalkShare),
                dot(s.lnrm, -wi), dist2);
        misWeight = powerHeuristic(emitterAreaDensity(s.le * worldPush.emitterTint.xyz),
                                   continuationPdf);
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
            contrib += disneyLobes(c, ext, wi) * rainBaseTransmission(c, wi)
                    * (s.le * worldPush.emitterTint.xyz)
                    * (dot(c.n, wi) * cosL / dist2 * s.area);
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
    return contrib * vis * s.W * misWeight;
}

// TLAS instance-mask bits (set on the Java side, ANDed against these per-ray cull masks):
//   bit 0 (0x01) — secondary rays: shadows, GI bounces, reflections.
//   bit 1 (0x02) — the primary camera ray.
// Terrain / entities / block entities keep 0xFF (both bits). Particles use 0x02: primary-visible
