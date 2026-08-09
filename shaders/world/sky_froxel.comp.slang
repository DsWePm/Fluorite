// Bakes what the air between the camera and everything else does to the picture, into a 64x36x64 grid
// laid over the view frustum. Each froxel holds the in-scattered light gathered from the eye out to its
// depth, and the transmittance over that same stretch.
//
// WHAT THIS IS ACTUALLY FOR, because the milestone's name is misleading. "Aerial perspective" from the
// planetary atmosphere does not exist at this game's scale: Rayleigh's blue coefficient is 22.4e-6 per
// metre, so a 512-block view runs an optical depth of 0.0115 and a transmittance of 0.989 — one percent,
// invisible. What actually fades the distance here is the world's own fog, which is denser by more than
// two orders of magnitude. So the grid carries the FOG first and the atmosphere as a rounding error that
// comes along for free, rather than the other way round.
//
// The real payoff is M13. Volumetric shadows are in-scatter modulated by visibility, and the water work
// already established what a handful of shadow rays gives instead: one coin flip near the camera applied
// to a whole segment, so any occlusion boundary the camera crosses flips the entire screen at once. A
// froxel resolves visibility in WORLD-SPACE CELLS and interpolates, so neighbouring pixels and successive
// frames read continuous values rather than independent binary answers. M13 added those rays to each
// cell; its residual 3D-density work now feeds this same prefix integrator rather than creating a third
// fog machine.
//
// Reads WorldPush through its device address rather than taking a push constant of its own. The camera
// matrix, the fog's four parameter vectors and the sun all live there already, and restating any of them
// here would be a second copy to keep in step — the exact debt M10.1 existed to pay off.

import atmosphere;
import light_sampling;
import volume_source;
import world_common;

// 64x36x64, up from the plan's 32^3, and the reason is a light shaft through a two-block window.
//
// At 32 columns across a 1600-wide frame each one owns fifty pixels, so a window that occupies a hundred
// spans two columns and its shaft can have no edge — it reads as the screen brightening as you walk past,
// which is exactly what it was reported as. Sharpness in a froxel is angular resolution and nothing else;
// no amount of care in the integral recovers an edge the grid cannot represent.
//
// 64x36 keeps the frame's aspect so a column is roughly square in angle, and the depth axis doubles
// because a shaft is long and thin — the direction it needs resolution in is the one it travels along.
// Cost scales as W*H*D in rays: 147k against the previous 32k, on a dispatch measured at 0.137 ms.
public static const int FROXEL_W = 64;
public static const int FROXEL_H = 36;
public static const int FROXEL_D = 64;

struct FroxelPush {
    uint64_t worldPushAddr;
    uint64_t lightBufAddr;
    uint64_t lightAliasAddr;
    uint64_t lightLocalAliasAddr;
    uint64_t lightGridCellAddr;
    uint64_t lightGridSpanAddr;
};
[[vk::push_constant]] FroxelPush froxelPush;

[[vk::binding(0, 0)]] Sampler2D transmittanceLut;
[[vk::binding(1, 0)]] Sampler3D fogNoise;
[[vk::binding(2, 0)]] RWTexture3D<float4> aerialPerspective;
// Set 1, its own ring: the TLAS handle changes almost every frame, and rewriting one set's binding while
// an earlier frame's command buffer may still be reading it is the hazard RtPipeline.setTlas exists to
// guard against. Reusing RtOverlayPipelines.AccelStructureSet rather than growing set 0 is what keeps
// that discipline in one place instead of two.
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

// Must equal CULL_SECONDARY_NO_SELF in trace.slang. Restated rather than imported: that module pulls in
// world_core, which declares the ray-tracing pipeline's set-0 bindings, and this compute shader's set is
// its own. The first-person player is excluded deliberately — the camera sits inside its head mesh, and
// letting it occlude the cells directly in front of the eye would put a shadow across the whole near
// field, which is the same trap the water's shadow rays fell into.
static const uint FROXEL_SHADOW_MASK = 0x01u;

/**
 * Depth this slice's value describes.
 *
 * Texel CENTRES, matching the two 2D bakes and for the same reason: a consumer samples this grid with a
 * filtered fetch, which interpolates between centres, so writing the value for a slice's far EDGE would
 * shift the whole depth axis half a texel and put the fog nearer than the geometry it belongs to.
 *
 * The curve itself is froxelSliceDepth in atmosphere.slang, shared with the consumer's inverse rather
 * than restated here — see that function for why an exponential axis, and for the arithmetic that
 * condemned the quadratic one it replaced.
 */
float sliceDepth(int z, float maxDist) {
    return froxelSliceDepth((float(z) + 0.5) / float(FROXEL_D), maxDist);
}

float froxelFogNoiseMultiplier(WorldPush wp, float3 p, float footprint) {
    if (!volumeFogNoiseOn(wp.fogParams, wp.fogAmbient)) {
        return 1.0;
    }
    float2 packed = fogNoise.SampleLevel(volumeFogNoiseUv(wp.fogNoiseOrigin, p), 0.0).rg;
    return volumeFogNoiseMultiplier(packed, wp.fogAmbient, wp.fogNoiseOrigin.w, footprint);
}

float froxelFogDensity(WorldPush wp, float3 p, float footprint) {
    return volumeFogHeightDensity(wp.fogParams, p)
            * froxelFogNoiseMultiplier(wp, p, footprint);
}

/** Same heterogeneous source-path integral as volume.slang, expressed against this compute set. */
float3 froxelFogTransmittance(WorldPush wp, float3 sigma, float3 ro, float3 rd, float dist) {
    if (!volumeFogNoiseOn(wp.fogParams, wp.fogAmbient)) {
        return volumeAmbientTransmittance(wp.fogParams, wp.fogExtinction,
                                          sigma, ro, rd, dist);
    }
    float start = min(wp.fogExtinction.w, dist);
    float end = min(dist, wp.fogParams.w);
    float span = max(end - start, 0.0);
    int steps = volumeFogMarchSteps(span, wp.fogAmbient, wp.fogNoiseOrigin.w);
    if (steps <= 0) {
        return float3(1.0, 1.0, 1.0);
    }
    float dt = span / float(steps);
    float tau = 0.0;
    [loop]
    for (int i = 0; i < steps; ++i) {
        float t = start + (float(i) + 0.5) * dt;
        tau += froxelFogDensity(wp, ro + rd * t, dt) * dt;
    }
    return exp(-sigma * tau);
}

/**
 * Whether this cell can see the sky, asked with a ray rather than read from the grid.
 *
 * The grid was tried first and is the wrong instrument, which M9 predicted in the sentence that shipped
 * with it: its cells are eight blocks on a side, it "cannot tell three blocks outside a cave mouth from
 * deep inside one", and the note ends by saying fine volumetric occlusion is M13's job. A house is
 * shorter than one cell, so the cell holding a sealed room also holds the open sky above its roof and
 * averages to open — measured exactly that way, red everywhere indoors and out, with the terrain
 * silhouette visible at the horizon because the grid IS working, just not at a useful scale.
 *
 * A froxel already resolves what a room needs, so it answers for itself. Straight up rather than a
 * hemisphere: the ambient really is an integral over directions, but the question a ceiling asks is
 * binary and one ray settles it, where a proper hemisphere would cost as many rays per cell as the whole
 * grid costs now.
 */
float skyReachable(float3 p) {
    return froxelOccluded(p, float3(0.0, 1.0, 0.0), 4096.0) ? 0.0 : 1.0;
}

/** One shadow ray. Opaque-only and first-hit: this asks whether a sampled emitter is blocked. */
bool froxelOccluded(float3 p, float3 direction, float tMax) {
    RayDesc ray;
    ray.Origin = p;
    ray.Direction = direction;
    ray.TMin = 0.05;
    ray.TMax = tMax;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(tlas, RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH,
                     FROXEL_SHADOW_MASK, ray);
    while (q.Proceed()) {
        // FORCE_OPAQUE leaves nothing to arbitrate; the loop is here because the API is specified around
        // it and an implementation may return true once.
    }
    return q.CommittedStatus() != COMMITTED_NOTHING;
}

LightBufferAddresses froxelLightAddresses() {
    LightBufferAddresses addresses;
    addresses.light = froxelPush.lightBufAddr;
    addresses.globalAlias = froxelPush.lightAliasAddr;
    addresses.localAlias = froxelPush.lightLocalAliasAddr;
    addresses.gridCell = froxelPush.lightGridCellAddr;
    addresses.gridSpan = froxelPush.lightGridSpanAddr;
    return addresses;
}

/** One shared Light/grid proposal evaluated with a volume phase function at this froxel. */
float3 froxelLocalEmitterSource(WorldPush wp, float3 p, float3 viewDir,
                                inout uint seed, inout uint proposalSeed) {
    EmitterSample emitter;
    if (!sampleVolumeEmitter(wp, froxelLightAddresses(), p, seed, proposalSeed, emitter)) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 toLight = emitter.pos - p;
    float dist2 = dot(toLight, toLight);
    if (dist2 <= 0.0036) {
        return float3(0.0, 0.0, 0.0);
    }
    float dist = sqrt(dist2);
    float3 wi = toLight / dist;
    float cosLight = max(0.0, dot(emitter.normal, -wi));
    if (cosLight <= 0.0 || froxelOccluded(p, wi, dist * 0.999)) {
        return float3(0.0, 0.0, 0.0);
    }
    float phase = volumeHg(dot(viewDir, wi), wp.fogScatter.w);
    float3 sourceT = froxelFogTransmittance(
            wp, wp.fogExtinction.xyz, p, wi, dist);
    return emitter.radiance * sourceT
            * (phase * cosLight * emitter.area / dist2) / emitter.sourcePdf;
}

// ONE THREAD PER COLUMN, marching all FROXEL_D (64) slices and writing each as it passes.
//
// The first version gave every froxel its own thread and had each redo the stretch its predecessors
// covered — 528 steps per column instead of 32, which was a fair trade when a step was an exp and a
// texture fetch. It stops being one the moment a step casts a ray: the same shadow ray would be traced
// by every cell behind it, sixteen times over, for an answer that does not change.
[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(FROXEL_W) || id.y >= uint(FROXEL_H)) {
        return;
    }
    WorldPush wp = ConstPtr<WorldPush>(froxelPush.worldPushAddr)[0];

    // A local/environment provider has no planetary aerial perspective to preserve after its authored
    // fog is disabled. Still overwrite every slice so a dimension switch cannot leave the previous
    // world's prefix behind, but skip all density work, emitter proposals and shadow rays.
    if (wp.skyProvider != SKY_PROVIDER_ATMOSPHERE && wp.fogParams.x <= 0.0) {
        for (int s = 0; s < FROXEL_D; ++s) {
            aerialPerspective[int3(int(id.x), int(id.y), s)] = float4(0.0, 0.0, 0.0, 1.0);
        }
        return;
    }

    // The ray through this column's centre, from the same inverse projection the primary rays use. Taking
    // it from WorldPush rather than rebuilding a frustum here is what keeps the grid aligned with the
    // image it will be sampled against — a froxel column that disagreed with the pixel above it by half a
    // tile would show up as the fog sliding over geometry as the camera turns.
    float2 uv = (float2(id.xy) + 0.5) / float2(FROXEL_W, FROXEL_H);
    float2 ndc = uv * 2.0 - 1.0;
    float4 nearH = mul(wp.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(wp.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    float3 ro = nearP + wp.camOffset;
    float3 rd = normalize(farP - nearP);

    float maxDist = max(wp.fogParams.w, 1.0);
    float startDist = wp.fogExtinction.w;

    uint lightSeed = id.x * 1973u ^ id.y * 9277u ^ wp.frameIndex * 2654435761u;
    lightSeed = pcg(lightSeed);
    uint proposalSeed = lightSeed ^ 0x9E3779B9u;
    proposalSeed = pcg(proposalSeed);
    bool atmosphereProvider = wp.skyProvider == SKY_PROVIDER_ATMOSPHERE;
    bool localLights = (wp.environmentFlags & ENVIRONMENT_FROXEL_LOCAL_LIGHTS) != 0u;
    bool unoccludedAmbient = (wp.environmentFlags & ENVIRONMENT_AMBIENT_UNOCCLUDED) != 0u;

    // March once from the eye through the whole column, writing each prefix as it passes. A per-cell
    // dispatch would redo every earlier slice and, now that a step casts visibility rays and may march a
    // noisy source path, would multiply the expensive work for no new answer (D59).
    float3 transmittance = float3(1.0, 1.0, 1.0);
    float3 inScatter = float3(0.0, 0.0, 0.0);

    for (int s = 0; s < FROXEL_D; ++s) {
        float tNear = s == 0 ? 0.0 : sliceDepth(s - 1, maxDist);
        float tFar = sliceDepth(s, maxDist);
        // The fog has a start distance so the near field stays readable; nothing before it contributes.
        //
        // A slice entirely inside that dead zone still has to be WRITTEN, with the accumulator as it
        // stands — which is zero in-scatter and full transmittance. Skipping the iteration outright is
        // what the first version of this loop did, and it left those near slices holding the zeros they
        // were created with, alpha included. The camera's prefix segment reads that alpha as its
        // transmittance, so the entire picture was multiplied by zero: a black screen, from a change that
        // only meant to add shadows.
        tNear = max(tNear, startDist);
        float dt = max(tFar - tNear, 0.0);
        if (dt > 0.0) {
            float3 p = ro + rd * (tNear + dt * 0.5);

            // The world's fog: exponential in height, tinted per channel, with a single-scattering albedo.
            //
            // SATURATING below the reference height — max(h, 0) — and that is not a refinement, it is the
            // same correction volume.slang's ambientProfileIntegral already carries and this shader never
            // received. Its comment records what the unbounded form did there: "from 200 blocks up, a ray
            // that misses geometry and runs the full cull distance downward reached tau ~ 186, and the
            // whole view went opaque the moment the camera climbed. Real fog has a bottom."
            //
            // Here it was quieter and therefore worse: the froxel covers the camera's prefix segment and
            // the marched path covers every other segment, so the two disagreed about the density of the
            // SAME air — by 1.6x at y=40 and 3.6x at y=0, growing the deeper you go. Two machines added
            // together on screen must not disagree about their inputs; that is the whole reason M10.1
            // deleted the CPU atmosphere port.
            float fogDensity = froxelFogDensity(wp, p, dt);
            float3 fogSigmaT = wp.fogExtinction.xyz * max(fogDensity, 0.0);

            // The atmosphere, at this point's altitude. Two orders of magnitude smaller than the fog at this
            // scale and kept anyway: it costs one exp and it is the term that makes a very long sightline over
            // open ground pick up the sky's own colour rather than only the fog's tint.
            float3 atmoSigmaS;
            float3 atmoSigmaT;
            if (atmosphereProvider) {
                atmosphereMedium(ATMOS_VIEW_ALTITUDE, atmoSigmaS, atmoSigmaT);
            } else {
                atmoSigmaS = float3(0.0, 0.0, 0.0);
                atmoSigmaT = float3(0.0, 0.0, 0.0);
            }

            float3 sigmaT = fogSigmaT + atmoSigmaT;
            // fogScatter is single-scattering ALBEDO in both estimators. The old froxel multiplied it by
            // density alone, effectively dividing it by fogExtinction when sigmaS/sigmaT was formed and
            // then mixing planetary-atmosphere extinction into that ratio. sigma_s = albedo * sigma_t is
            // the shared definition; the tiny atmosphere contribution remains a separate additive medium.
            float3 fogAlbedo = wp.fogScatter.xyz;
            float3 sigmaS = fogAlbedo * fogSigmaT + atmoSigmaS;

            // One stochastic finite-emitter point owns atmosphere, local-fog attenuation, phase and the
            // froxel's own visibility ray together. This keeps the steep horizon colour gradient correlated
            // with the terrain test instead of switching an already-orange centre sample on as one block.
            float3 sampledLightDir = wp.lightDir.xyz;
            if (atmosphereProvider) {
                SquareLightSample lightSample = sampleSquareLight(
                        wp.lightDir.xyz, wp.celestial.xyz, wp.lightDir.w, lightSeed);
                sampledLightDir = lightSample.direction;
            }
            float3 sampledIrradiance = float3(0.0, 0.0, 0.0);
            bool celestialVisible = atmosphereProvider && celestialDirectionVisible(sampledLightDir);
            if (celestialVisible) {
                float r = PLANET_R + ATMOS_VIEW_ALTITUDE;
                float muS = clamp(sampledLightDir.y, -1.0, 1.0);
                float3 atmosphereT = transmittanceLut.SampleLevel(
                        transmittanceLutUv(r, muS), 0.0).rgb;
                sampledIrradiance = wp.lightRadiance.xyz * atmosphereT;
            }

            // M15.2: the same local-fog source attenuation the marched estimator has always applied.
            // This compute stage reads the undyed WorldPush value, so its direct path is atmosphere LUT *
            // local fog. Raygen preserves the same undyed value before centre-dyeing its compatibility copy;
            // finite-emitter paths there perform this same per-direction LUT lookup.
            float3 localFogSunT = float3(1.0, 1.0, 1.0);
            if (atmosphereProvider) {
                bool multiScatter = (wp.flags & (1u << 26u)) != 0u;
                float3 fogSunSigma = multiScatter
                        ? volumeDiffuseAttenuation(wp.fogExtinction.xyz, fogAlbedo, wp.fogScatter.w)
                        : wp.fogExtinction.xyz;
                localFogSunT = froxelFogTransmittance(
                        wp, fogSunSigma, p, sampledLightDir, maxDist);
            }

            // M13: the visibility this grid was built to hold. One ray per CELL rather than per pixel, which
            // is the entire difference between volumetric shadows and what a path tracer can afford to do
            // inline — the water work measured what a handful of per-segment rays gives instead, and it was
            // one coin flip near the camera applied to a whole segment, flipping the screen at once whenever
            // the camera crossed an occlusion boundary. A cell's answer is shared by every pixel that reads it
            // and interpolated between neighbours, so it varies smoothly in space and in time.
            float sunVis = celestialVisible
                    && !froxelOccluded(p, sampledLightDir, 4096.0) ? 1.0 : 0.0;

            // Two occlusion tests for two different quantities: a ray at the sun for the directional
            // term, a ray at the zenith for the isotropic ones. Gating the ambient on the SUN would be
            // wrong in the other direction — a hillside shadow would drain the sky out of the fog, when
            // what removes ambient light is a roof, not a hill.
            float skyOpen = unoccludedAmbient ? 1.0 : skyReachable(p);
            AmbientRadianceSource source = evaluateAmbientRadianceSource(
                    rd, sampledLightDir, wp.fogScatter.w,
                    sampledIrradiance, localFogSunT,
                    wp.mediumSkyRadiance.xyz);
            float3 visibleSource = source.sun * sunVis + source.sky * skyOpen;
            if (localLights) {
                visibleSource += froxelLocalEmitterSource(
                        wp, p, rd, lightSeed, proposalSeed);
            }

            // Closed form over the step, for the same reason as the other two bakes: near the ground a step
            // can span a whole optical depth, and the midpoint rule overshoots exactly there.
            float3 stepT = exp(-sigmaT * dt);
            // Planetary atmosphere is intentionally froxel-only: it represents camera-prefix aerial
            // perspective at kilometre scale. Bounce segments omit it; local fog source semantics above
            // are nevertheless identical on both paths.
            float3 gathered = transmittance * sigmaS * visibleSource
                            * (float3(1.0, 1.0, 1.0) - stepT)
                            / max(sigmaT, float3(1.0e-9, 1.0e-9, 1.0e-9));
            inScatter += gathered;
            transmittance *= stepT;
        }

        // Written as the march passes, rather than once at the end: this thread owns the whole column, so
        // every slice's prefix integral is simply the accumulator's value at that point.
        //
        // Alpha carries transmittance as a single channel. The three are close enough at this scale that a
        // per-channel copy would cost a second texture to save a difference nothing can see; if a coloured
        // extinction ever matters here, that is the change to make and this comment is where to start.
        float meanT = (transmittance.r + transmittance.g + transmittance.b) * (1.0 / 3.0);
        aerialPerspective[int3(int(id.x), int(id.y), s)] = float4(inScatter, meanT);
    }
}

