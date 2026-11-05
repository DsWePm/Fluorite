// One cloud path kernel, statically instantiated for radiance and transmittance-only evaluation.
// Source gating is preprocessing, preserving the published full-radiance instruction stream.
// Define CLOUD_KERNEL_SOURCE and CLOUD_KERNEL_FN only while including this file.
CloudResult CLOUD_KERNEL_FN(marchLayer)(CloudLayer layer, CloudBudget budget, float3 ro, float3 rd, float tMax,
                       inout uint marchSeed) {
    CloudResult result = noClouds();
    if (!layerEnabled(layer)) {
        return result;
    }
    float2 span = cloudShellSpan(layer, ro, rd);
    float tEnter = span.x;
    float tExit = min(span.y, tMax);
    if (tExit <= tEnter) {
        return result;
    }
    tExit = min(tExit, tEnter + CLOUD_MAX_DIST);

#if CLOUD_KERNEL_SOURCE
    float3 ambient = worldPush.mediumSkyRadiance.xyz;
    float3 lightDir = worldPush.lightDir.xyz;
    float3 lightRadiance = worldPush.lightRadiance.xyz;
    // The phase is evaluated once for the whole march: it depends on the angle between the ray and the
    // light, and neither moves along a straight ray. It stays sphere-normalised because lightRadiance is
    // irradiance, not the E/(4*pi) isotropic base that a relative phase gain would require.
    float phase = cloudPhase(dot(rd, lightDir), cloudPhaseG());
    // Off must reproduce single scattering exactly rather than approximately, per the off-state contract, so it does two
    // things: it removes the diffuse lobe entirely, and it puts the sky's occlusion back on the beam's
    // own extinction. Leaving the diffuse lobe in at rate 1 would not disable multiple scattering — it
    // would add a second, isotropic copy of the direct term.
    bool multiScatter = cloudMultiScatterOn();
    float diffusionRate = multiScatter ? cloudDiffusionRate() : 1.0;

#endif
    // Jittered entry, for the same reason every other estimator here jitters: a fixed start puts the
    // step lattice at the same depth on neighbouring pixels and draws it as banding, and this renderer
    // would rather have noise a denoiser can integrate than structure it cannot.
    //
    // OVER ONE WHOLE STEP, which is what that sentence actually requires and what an absolute cap of 64
    // blocks stopped delivering the moment the clouds were far away. The step grows as t/8, so at a
    // kilometre out it is already 125 blocks and at twenty it is 2500; a 64-block offset moved the lattice
    // by 3% of a step and left it, for every practical purpose, in the same place on every pixel.
    //
    // What that looked like is worth writing down, because the cause is invisible in the symptom. Sample
    // depths are tEnter * 1.125^n, so an unjittered lattice is a set of smooth shells in the world. One
    // step at that distance is optically opaque -- 1250 blocks at the authored extinction is tau ~ 12, so
    // 1 - exp(-tau) is 1.0000 -- which means a pixel shows either a full slab of cloud or none of it,
    // with no gradient in between. Coherent shells plus a binary decision drew distant cloud as a stack
    // of discrete layers.
    //
    // Costs nothing: same schedule, same step count, different starting phase.
    // THE STEP COMES FROM THIS RAY'S CROSSING, not from how far away the deck is.
    //
    // It used to be max(24, t * 0.125) -- a cone-footprint heuristic, and a sound one about how finely
    // the field is worth SAMPLING LATERALLY at that distance. It was being used as the length of a step
    // THROUGH the medium, which is a different question with a different answer: how long a step may be
    // is set by how much optical depth it accumulates, not by how big a pixel is. At twenty kilometres it
    // gave a 2500-block step, tau around 24, and 1 - exp(-tau) = 1.0000 -- an estimator with no gradient
    // left in it, which is why distant cloud arrived as a stack of opaque slabs.
    //
    // Dividing the crossing by the budget spends the steps where the ray actually is inside the deck. A
    // grazing crossing of fourteen kilometres becomes 152-block steps at tau 1.5 instead of nine strides
    // at tau 12, and one step spans a fifth of a cloud rather than one and a half of them.
    //
    // The lateral half of the old heuristic did not go away, it moved to where it belongs: cloudNoiseLod
    // now selects a mip by this same step. Filtering and stepping are two questions and this file was
    // answering both with one number.
    float crossing = tExit - tEnter;
    float stepBase = clamp(crossing / float(max(budget.maxSteps, 1)), CLOUD_STEP_MIN, CLOUD_STEP_MAX);
    float t = tEnter + rndf(marchSeed) * min(stepBase, crossing);

    float transmittance = 1.0;
    float3 gathered = float3(0.0, 0.0, 0.0);
    int emptySteps = 0;
    // The length of the last long stride taken through empty space. Kept because a stride that lands
    // inside a cloud has crossed everything between where it started and where it ended WITHOUT
    // INTEGRATING ANY OF IT, and that has to be undone rather than accepted.
    float lastSkip = 0.0;
    for (int i = 0; i < budget.maxSteps && t < tExit; ++i) {
        // The closed-form source integration is exact only for the interval it is actually handed.
        // Charge only the interval actually traversed; this also keeps the final low-cloud step unbiased.
        float step = min(stepBase, tExit - t);
        float3 p = ro + rd * t;
        float density = cloudDensity(layer, p, true, step);
        if (density <= 0.0) {
            // Nothing here. Step long, and keep stepping long while the emptiness holds.
            emptySteps++;
            lastSkip = step * (emptySteps > 2 ? 3.0 : 1.5);
            t += lastSkip;
            continue;
        }
        // BACK UP ON RE-ENTRY. Without this the skip is not an optimisation, it is a hole: the stride
        // that discovers a cloud has already jumped past most of it, and the optical depth of everything
        // it flew over is silently lost. It shows up as clouds that do not occlude -- and asymmetrically,
        // which is what makes it hard to attribute. Looking UP from below, a ray enters at the flat cloud
        // base where density is immediately non-zero, so no skip is ever armed. Looking DOWN from above,
        // it first crosses the empty upper deck, so the stride has already tripled by the time it
        // reaches cloud and one step swallows most of the crossing.
        if (lastSkip > step) {
            t = max(tEnter, t - lastSkip + step);
            lastSkip = 0.0;
            emptySteps = 0;
            continue;
        }
        emptySteps = 0;
        lastSkip = 0.0;
        if (budget.erosion) {
            density = cloudDensity(layer, p, false, step);
        }
        if (density > 0.0) {
            float sigmaT = density * layer.extinction;
            float stepT = exp(-sigmaT * step);

#if CLOUD_KERNEL_SOURCE
            // How buried this sample is, from the light's side and from the sky's.
            float tauSun = budget.sunSteps > 0
                    ? sunOpticalDepth(layer, p, lightDir, budget.sunSteps, marchSeed) : 0.0;
            // The sky's occlusion, without a second march: how much of THIS cloud stands above the
            // sample, taking the local density as representative of the column. Bounded by the type's own
            // profile rather than by the deck, so a stratus sheet is not buried under the empty three
            // hundred blocks a cumulonimbus would have filled.
            // The top of THIS cloud, which now includes how deep this one happens to be. Reading the
            // type's full top here while the density used a shallower one would bury a shallow cloud
            // under the air above it and turn its own underside black.
            float ownType = cloudType(layer, p);
            float ownTop = layer.bottom
                    + cloudProfileTop(ownType) * cloudDepthFraction(cloudCoverage(layer, p), ownType)
                            * layer.thickness;
            float tauUp = sigmaT * max(ownTop - worldIn(layer, p).y, 0.0);

            // Isotropic, so no phase factor: light that has scattered often enough for diffusion to
            // describe it has forgotten which way it came in. That is also why this term is the same for
            // every ray through a given point, and therefore consistent between a cloud and its
            // reflection without anything having to enforce it.
            float diffuse = multiScatter
                    ? cloudDiffuseSourceScale() * exp(-diffusionRate * tauSun) : 0.0;
            float3 source = ambient * exp(-diffusionRate * tauUp)
                    + lightRadiance * (exp(-tauSun) * phase + diffuse);
            // The same closed form the fog's sub-steps use: for a source taken as constant over the step,
            // the in-scatter integrates exactly to source * (1 - T), and doing it this way keeps a long
            // step from overshooting the way a midpoint sample would.
            gathered += transmittance * source * (1.0 - stepT);
#endif
            transmittance *= stepT;
            if (transmittance < budget.transmittanceFloor) {
                transmittance = 0.0;
                break;
            }
        }
        t += step;
    }

    result.inScatter = gathered;
    result.transmittance = transmittance;
    return result;
}

/** Distance to one infinitesimally thin spherical high-cloud sheet, or -1 when it is not reached. */
#if CLOUD_KERNEL_SOURCE
float highCloudSheetIntersection(float altitude, float3 ro, float3 rd) {
    float3 centreRelative = ro + float3(0.0, PLANET_R + worldPush.cloudRebase.y, 0.0);
    float radius = PLANET_R + altitude;
    float originRadius = length(centreRelative);
    if (abs(originRadius - radius) < 0.25) {
        return 0.0;
    }
    float2 roots = raySphere(centreRelative, rd, radius);
    float t = roots.x > 0.0 ? roots.x : roots.y;
    return t >= 0.0 && t <= CLOUD_MAX_DIST ? t : -1.0;
}
#endif

#if CLOUD_KERNEL_SOURCE
float3 highCloudSheetNormal(float altitude, float3 ro, float3 rd, float t) {
    float3 centreRelative = ro + float3(0.0, PLANET_R + worldPush.cloudRebase.y, 0.0);
    return normalize(centreRelative + rd * t);
}
#endif

/** Exact Beer integration through one thin sheet with the shared cloud Radiance sources. */
CloudResult CLOUD_KERNEL_FN(shadeHighCloudSheet)(float verticalTau, float altitude,
                                float3 ro, float3 rd, float t) {
    CloudResult result = noClouds();
    if (verticalTau <= 0.0 || t < 0.0) {
        return result;
    }
    float3 normal = highCloudSheetNormal(altitude, ro, rd, t);
    // The old volumetric high layer capped a grazing crossing at 24 sheet thicknesses. The delta-sheet
    // equivalent is the same bound on sec(theta), retaining finite horizon energy without a hidden fade.
    float raySecant = min(1.0 / max(abs(dot(rd, normal)), 1.0e-5), 24.0);
    float tauRay = verticalTau * raySecant;
    float transmittance = exp(-tauRay);

#if CLOUD_KERNEL_SOURCE
    float3 lightDir = worldPush.lightDir.xyz;
    float sunSecant = min(1.0 / max(abs(dot(lightDir, normal)), 1.0e-5), 24.0);
    float tauSunMidpoint = verticalTau * sunSecant * 0.5;
    bool multiScatter = cloudMultiScatterOn();
    float diffusionRate = multiScatter ? cloudDiffusionRate() : 1.0;
    float diffuse = multiScatter
            ? cloudDiffuseSourceScale() * exp(-diffusionRate * tauSunMidpoint) : 0.0;
    float phase = cloudPhase(dot(rd, lightDir), cloudPhaseG());
    float3 source = worldPush.mediumSkyRadiance.xyz
                    * exp(-diffusionRate * verticalTau * 0.5)
            + worldPush.lightRadiance.xyz
                    * (exp(-tauSunMidpoint) * phase + diffuse);
    result.inScatter = source * (1.0 - transmittance);
#endif
    result.transmittance = transmittance;
    return result;
}

/**
 * D163: the CC0 alpha-mask patches, on one sheet.
 *
 * <p>ONE SHEET, NOT TWO. The upper sheet sampled a single periodic equirectangular HDRI, and the two
 * were never the same kind of thing: these patches are discrete sprites scattered over a world grid, so
 * they have edges, gaps and sky between them, while a periodic field covers everything everywhere and
 * can only vary in how thick it is. Stacking the second one behind the first meant every gap the patches
 * left was filled by the sheet above, which is the one property a cirrus sky must not have.
 *
 * <p>`thickness` stays in the optical depth below rather than being folded away: it is the reference
 * thickness this sheet's tau is authored against, and only its second use -- the separation between the
 * two sheets -- has gone.
 */
CloudResult CLOUD_KERNEL_FN(traceHighClouds)(float3 ro, float3 rd, float tMax) {
    CloudLayer high = highLayer();
    if (!layerEnabled(high)) {
        return noClouds();
    }
    float altitude = high.bottom;
    float t = highCloudSheetIntersection(altitude, ro, rd);
    if (t < 0.0 || t > tMax) {
        return noClouds();
    }
    // The footprint is this ray's own distance: the sheet is read at whatever mip a pixel this far away
    // resolves. The shadow bake passes its texel width to the same function instead.
    float verticalTau = highCloudVerticalTau(cloudTextures(), high, ro + rd * t, t);
    return CLOUD_KERNEL_FN(shadeHighCloudSheet)(verticalTau, altitude, ro, rd, t);
}

#if CLOUD_KERNEL_SOURCE
float highCloudFirstIntersection(float3 ro, float3 rd) {
    CloudLayer high = highLayer();
    if (!layerEnabled(high)) return CLOUD_MAX_DIST + 1.0;
    float t = highCloudSheetIntersection(high.bottom, ro, rd);
    return t < 0.0 ? CLOUD_MAX_DIST + 1.0 : t;
}
#endif

/**
 * Low volume and one high sheet, composited in the order THIS RAY meets them.
 *
 * <p>The convective deck and the high sheet are disjoint spherical regions around the same centre, so
 * comparing this ray's first entry distance is sufficient to order their results.
 *
 * <p>That comparison is the point. The reference implementation ordered its layers by the CAMERA's
 * altitude, which is right for the camera's own view and wrong for every other ray in the frame: a ray
 * reflected off a lake looks upward from below both layers while the camera may be sitting between them,
 * and the two then disagree about which cloud is in front. Ordering by the ray's own entry distance
 * cannot disagree with itself (R18).
 *
 * @param secondary true for anything that is not the first ray of its path — see cloudSecondaryQuality
 */
public CloudResult CLOUD_KERNEL_FN(traceClouds)(float3 ro, float3 rd, float tMax, uint seed, bool secondary) {
    if (!cloudsOn()) {
        return noClouds();
    }
    if (secondary && cloudSecondaryQuality() == 0u) {
        return noClouds();
    }
    uint marchSeed = seed ^ 0x7F4A7C15u;
    marchSeed = pcg(marchSeed);

    CloudLayer low = lowLayer();
    CloudLayer high = highLayer();
    float lowEnter = cloudShellSpan(low, ro, rd).x;
    float highEnter = highCloudFirstIntersection(ro, rd);

    CloudResult lowResult = CLOUD_KERNEL_FN(marchLayer)(low, cloudBudget(low, secondary), ro, rd, tMax, marchSeed);
    CloudResult highResult = CLOUD_KERNEL_FN(traceHighClouds)(ro, rd, tMax);
    return lowEnter <= highEnter ? overCloud(lowResult, highResult)
                                 : overCloud(highResult, lowResult);
}
