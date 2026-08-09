// The bound transmittance table and the one correct way to read it. Depends on atmosphere.
//
// Separate from atmosphere.slang on purpose: that module is imported by the BAKE (a compute shader
// whose descriptor set has exactly one binding), and a binding declared in a module reaches every
// importer. Putting set-0 binding 10 there would give the bake a binding its own layout does not
// contain. So the maths lives in atmosphere.slang, imported by everyone including the bake, and the
// BINDING lives here, imported only by the ray-tracing stages that actually have it.

import atmosphere;
import world_common; // WorldPush, for dyeCelestialLight

/** Bound by RtPipeline.setTransmittanceLut; visible to MISS (disc tint) and RAYGEN (the NEE consumers). */
[[vk::binding(10, 0)]] public Sampler2D skyTransmittanceLut;

/** Bound by RtPipeline.setMultiScatterLut; visible to MISS, which is the only stage that builds sky. */
[[vk::binding(11, 0)]] public Sampler2D skyMultiScatterLut;

// The sky-view tables (M10.3). Three, because the phase functions are applied HERE rather than baked in:
// Mie's forward spike is far narrower than a 192-wide azimuth axis can hold, so baking it would replace
// the sun's halo with a smear. Both phases depend only on the view-sun angle, which is constant along a
// ray, so pulling them out is exact. Multiple scattering is isotropic and has no phase, which is why it
// needs a table of its own rather than a ride in either of the others.
[[vk::binding(12, 0)]] public Sampler2D skyViewRayleighLut;
[[vk::binding(13, 0)]] public Sampler2D skyViewMieLut;
[[vk::binding(14, 0)]] public Sampler2D skyViewMultiLut;

/**
 * The aerial-perspective froxel (M10.4): rgb is the in-scatter gathered from the eye to this depth, a is
 * the transmittance over the same stretch. Laid over the view frustum, so its x and y are screen and its
 * z is distance — which is why it is the one table here that cannot be shared with a reflection.
 */
[[vk::binding(15, 0)]] public Sampler3D aerialPerspectiveLut;

/**
 * Where a distance lands on the froxel's depth axis.
 *
 * Delegates to froxelDepthFraction, which is the exact inverse of the curve the bake writes with, and
 * which lives beside that curve rather than opposite it. Getting an inverse wrong shows up as the fog
 * sitting at the wrong distance rather than as anything that looks like a bug in the fog, so the two
 * halves are kept where a single edit changes both.
 */
public float froxelDepthCoord(float dist, float maxDist) {
    return froxelDepthFraction(dist, maxDist);
}

/**
 * The air between the eye and `dist` along this pixel's ray: rgb added, a left of what is behind.
 *
 * <p>Screen-space, and the one table here a reflection cannot use. A froxel is indexed by where a pixel
 * is on the screen; a reflected ray is not on the screen at all, so anything seen in a mirror keeps
 * marching the analytic fog. That is not a limitation to fix later — it is what froxels are.
 */
public float4 sampleAerialPerspective(float2 screenUv, float dist, float maxDist) {
    return aerialPerspectiveLut.SampleLevel(
            float3(screenUv, froxelDepthCoord(dist, maxDist)), 0.0);
}

/**
 * Transmittance from the camera's altitude toward `dir`, read from the table.
 *
 * Every consumer should go through this rather than reconstructing the uv itself. The Bruneton/Hillaire
 * parameterisation is easy to get subtly wrong, and a subtle error in it is invisible at the zenith and
 * wrong precisely at the horizon — which is the only place any of these consumers actually look, since
 * the horizon is where a sunset happens.
 */
public float3 sampleTransmittanceToSpace(float3 dir) {
    return sampleTransmittance(PLANET_R + ATMOS_VIEW_ALTITUDE, clamp(dir.y, -1.0, 1.0));
}

/** Atmospheric colour of one finite-emitter direction from the undyed irradiance pushed by the CPU. */
public float3 sampleCelestialIrradiance(float3 undyedIrradiance, float3 sampledDirection) {
    // Non-atmosphere providers publish zero celestial energy and may never initialise these LUTs. Exit
    // before the fetch, both to save the work and to keep zero energy from multiplying undefined texels.
    if (all(undyedIrradiance <= 0.0)) {
        return float3(0.0, 0.0, 0.0);
    }
    if (!celestialDirectionVisible(sampledDirection)) {
        return float3(0.0, 0.0, 0.0);
    }
    return undyedIrradiance * sampleTransmittanceToSpace(sampledDirection);
}

/**
 * Transmittance to space from an arbitrary altitude. The general form of the above, which the camera
 * cannot use (it is pinned to ATMOS_VIEW_ALTITUDE) but the multi-scatter bake needs at every step of
 * every ray it marches.
 */
public float3 sampleTransmittance(float r, float muIn) {
    float mu = clamp(muIn, -1.0, 1.0);

    // Below the horizon the table has nothing to say, and this must be handled HERE rather than left to
    // the sampler. The parameterisation's u axis runs from the horizon-tangent direction upward, so a
    // ray that meets the ground maps outside [0,1] and CLAMP_TO_EDGE hands it the horizon's value —
    // which is not small. The visible result was the sun refusing to set: instead of being extinguished
    // as it went down it kept the horizon's red and simply hung there, and the crowded edge texels it
    // shared showed up as banding (green first, ozone being much the strongest absorber in that channel).
    //
    // The march this replaced got the same answer for free and it is worth recording why, because the
    // property looked automatic and was not: it integrated the downward chord literally, its samples
    // dropped below the surface, h went negative, exp(-h/H) exploded, and the optical depth ran away.
    // The transmittance rolled to zero because the density did. A table indexed by angle has no such
    // mechanism — the geometry has to be asked directly.
    if (mu < horizonMu(r)) {
        return float3(0.0, 0.0, 0.0);
    }
    float2 uv = transmittanceLutUv(r, mu);
    return skyTransmittanceLut.SampleLevel(uv, 0.0).rgb;
}

/**
 * The light that has scattered at least twice, per unit of the sun's incident irradiance, at radius `r`
 * with the sun at cosine `muS` from the local vertical. Multiply by the local scattering coefficient and
 * the sun's intensity to get in-scattered radiance; it is isotropic, so there is no phase function.
 *
 * <p>This is what single scattering alone cannot produce: a sky that stays blue in the direction away
 * from the sun, a twilight that glows rather than switching off, and a haze that does not go black
 * wherever the sun's own path is long. Single scattering makes each of those darker than it should be by
 * an amount that grows exactly where the air is thickest.
 */
public float3 sampleMultiScatter(float r, float muS) {
    return skyMultiScatterLut.SampleLevel(multiScatterLutUv(r, muS), 0.0).rgb;
}

/**
 * The sky along `dir`, from the tables — what world.rmiss used to build with a 16-step march wrapping an
 * 8-step march, 128 extinction evaluations for every escaping ray.
 *
 * The three tables are summed here rather than in the bake because two of them are waiting for a phase
 * function that depends on where the ray is pointing relative to the sun. That is the whole reason there
 * are three.
 */
public float3 sampleSkyView(float3 dir, float3 sunDir) {
    float2 uv = skyViewLutUv(dir, sunDir);
    float cosT = clamp(dot(dir, sunDir), -1.0, 1.0);
    float3 rayleigh = skyViewRayleighLut.SampleLevel(uv, 0.0).rgb;
    float3 mie = skyViewMieLut.SampleLevel(uv, 0.0).rgb;
    float3 multi = skyViewMultiLut.SampleLevel(uv, 0.0).rgb;
    return rayleigh * atmosphereRayleighPhase(cosT)
            + mie * atmosphereMiePhase(cosT) + multi;
}

/**
 * Dye the frame's celestial light by the atmosphere it came through, in place.
 *
 * The CPU used to do this — RtComposite.atmosphereTransmittance was a hand port of the same integral,
 * kept in step by hand so the sun's disc, the sky gradient and the light on terrain could not disagree
 * about a sunset. Deleting that port is what M10.1 is for, and this is where its work moved to.
 *
 * <p><b>This is the centre-direction compatibility convention.</b> Consumers that model the celestial
 * emitter as one direction read the centre-dyed push value. Finite-emitter estimators instead preserve
 * the undyed CPU value and call {@code sampleCelestialIrradiance} for the direction they actually sampled;
 * near the horizon, using the centre colour for the whole square would break colour/visibility correlation.
 *
 * <p>M16's {@code mediumSkyRadiance} is deliberately not touched here. It is the phase-integrated
 * radiance of the already-dyed sky-view LUT, not a fraction of the directional light. Multiplying it by
 * the sun transmittance again would double-dye sunsets.
 *
 * <p>{@code lightRadiance.w} is star brightness, not radiance. Only xyz moves.
 */
public void dyeCelestialLight(inout WorldPush push) {
    if (push.skyProvider != SKY_PROVIDER_ATMOSPHERE) {
        push.lightRadiance.xyz = float3(0.0, 0.0, 0.0);
        return;
    }
    float3 t = sampleTransmittanceToSpace(push.lightDir.xyz);
    push.lightRadiance.xyz *= t;
}
