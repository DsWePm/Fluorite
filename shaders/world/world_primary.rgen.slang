// Primary/guide pass (pass A of the wavefront split — see docs/WAVEFRONT_PLAN.md).
//
// Traces one camera radiance ray, captures DLSS-RR guides, and writes one or two resumable
// continuations per pixel. At the first eligible dielectric it queues both post-interface branches and
// stops; every later radiance trace belongs to Pass B. Deterministic guide probes may continue through
// refraction, but Pass A does no NEE, RIS, SSS, or BSDF sampling.
//
// Perspective setup: invViewProj = inverse(proj * viewRot) maps clip to camera-relative world. Primary
// rays are reconstructed from two unprojected points (near and far plane) rather than assuming the eye
// is at the origin, because Minecraft bakes view bobbing into the projection, so the effective eye comes
// from the matrix. camOffset shifts camera-relative space into the terrain's rebased coordinates. Depth
// is reversed-Z (near=1, far=0).
//
// Pass A deliberately uses ordinary TraceRay. Its short, visually coherent direct/interface walk is
// latency-sensitive and does not amortize an SER reorder barrier.
import world_common;
import world_core;
import math;
import bsdf;
import medium;
import volume;
import segment;
import water;
import trace;
import guides;
import atmosphere;
import atmosphere_lut;

public PathSegment tracePrimary(PathSegment seg,
                                DevicePtr<PackedPathSegment> queue,
                                uint splitRecord,
                                out bool hasNext) {
    float3 ro = seg.ro;
    float3 rd = seg.rd;
    float3 throughput = seg.throughput;
    MediumStack medium = seg.medium;
    float rayConeWidth = seg.rayConeWidth;
    float rayConeSpread = max(seg.rayConeSpread, RAY_CONE_MIN_SPREAD);
    uint seed = seg.seed;
    bool showCelestial = seg.showCelestial;
    bool waterWaves = (worldPush.flags & 16u) != 0u;
    hasNext = false;

    {
        int bounce = seg.bounce;
        PathSegment terminal = makePathSegment(ro, rd, throughput, medium,
                                               rayConeWidth, rayConeSpread, seed, bounce,
                                               showCelestial);
        traceRadiance(CULL_PRIMARY, ro, 0.0, rd, RAY_FAR,
                      showCelestial, rayConeWidth, rayConeSpread);

        if (payload.hitT < 0.0) {
            if (bounce == 0) {
                gv_normal = float3(0.0, 0.0, 0.0);
                gv_albedo = SKY_DIFF_ALBEDO;
                gv_rough = 1.0;
                gv_hitCamRel = rd * 1.0e6;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = float3(0.0, 0.0, 0.0);
                gv_spec = makeSpecSurface(gv_hitCamRel, gv_normal, 1.0, SKY_SPEC_ALBEDO);
            }
            return terminal;
        }

        float3 n = payload.normal;
        float3 hitPos = ro + rd * payload.hitT;
        uint material = payloadMaterial();
        bool dielectric = material == MATERIAL_WATER || material == MATERIAL_DIELECTRIC;
        // A rough dielectric is handed to Pass B whole instead of being resolved here. Two reasons, and
        // either alone would be enough: everything below refracts exactly through the interface, which
        // only describes a delta; and the split budget is two records per pixel, which an interface that
        // scatters cannot be allowed to branch out of.
        if (!dielectric || !isDeltaTransmission(clamp(payloadRoughness(), 0.0, 1.0))) {
            float rough = material == MATERIAL_PARTICLE
                    ? 1.0 : clamp(payloadRoughness(), 0.0, 1.0);
            bool exactSpecular = material == MATERIAL_OPAQUE && isDeltaAlpha(rough);
            rough = exactSpecular ? 0.0 : rough;
            float metal = material == MATERIAL_PARTICLE
                    ? 0.0 : clamp(payloadMetalness(), 0.0, 1.0);
            // A dielectric's albedo lane is its transmission tint, not a diffuse reflectance. Handing
            // that to RR as gAlbedo would have it demodulate a frosted pane by the colour of what it
            // filters, which is a different quantity that happens to share a lane.
            float3 diffAlb = dielectric
                    ? float3(0.0, 0.0, 0.0)
                    : (material == MATERIAL_PARTICLE
                            ? float3(payload.albedo) : float3(payload.albedo) * (1.0 - metal));
            if (bounce == 0) {
                gv_normal = n;
                gv_hitCamRel = hitPos - worldPush.camOffset;
                gv_motionUseRefracted = false;
                gv_motionObjDisp = payload.motionPrev;
                if (material == MATERIAL_PARTICLE) {
                    gv_albedo = payload.albedo;
                    gv_rough = 1.0;
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, 1.0,
                                              float3(0.0, 0.0, 0.0));
                } else {
                    float3 v = -rd;
                    // One roughness reaches RR, so a clearcoated surface has to nominate whichever of
                    // its two specular lobes carries the energy — see specularGuide.
                    SpecularGuide guide = specularGuide(float3(payload.f0), rough,
                            max(1.0e-4, dot(n, v)), materialExtension(payload.flags));
                    gv_albedo = diffAlb;
                    gv_rough = guide.alpha;
                    gv_spec = makeSpecSurface(gv_hitCamRel, n, float3(payload.motionPrev), guide.alpha,
                                              guide.reflectance);
                }
            }

            return terminal;
        }

        // This hit is consumed in pass A, so carry its travelled-medium absorption and cone growth into
        // the record for the next trace. The terminal hit above is deliberately not consumed here.
        if (any(medium.current.extinction > 0.0)) {
            throughput *= exp(-medium.current.extinction * payload.hitT);
        }
        rayConeWidth = max(rayConeWidth + rayConeSpread * max(payload.hitT, 0.0),
                           RAY_CONE_MIN_WIDTH);

        bool isWater = material == MATERIAL_WATER;
        bool entering = payloadDielectricEntering();
        float3 geometricNormal = n;
        float3 previousNormal = n;
        if (isWater && waterWaves) {
            float2 waterDomain = hitPos.xz + worldPush.waterAnchor.xy;
            float waterFootprint = rayConeWidth / max(abs(dot(-rd, geometricNormal)), 0.2);
            if (bounce == 0 && abs(geometricNormal.y) >= 0.5) {
                float2 currentGrad;
                float2 previousGrad;
                waterWaveGradTemporal(waterDomain, worldPush.waterParams.w,
                                      worldPush.waterAnchor.z, waterFootprint, currentGrad, previousGrad);
                float orientation = geometricNormal.y >= 0.0 ? 1.0 : -1.0;
                n = orientation * normalize(float3(-currentGrad.x, 1.0, -currentGrad.y));
                previousNormal = orientation
                        * normalize(float3(-previousGrad.x, 1.0, -previousGrad.y));
            } else {
                n = applyWaterWaves(geometricNormal, waterDomain,
                                    worldPush.waterParams.w, waterFootprint);
            }
        }

        float transmission = clamp(payloadTransmission(), 0.0, 1.0);
        Medium entered = makeDielectricMedium(isWater, max(payloadIor(), 1.0),
                                              payload.albedo, transmission);
        float etaI = medium.current.ior;
        float etaT = entering ? entered.ior : medium.outer.ior;
        float F = fresnelDielectric(clamp(dot(-rd, n), 0.0, 1.0), etaI, etaT);
        float3 transmittedDir = refract(rd, n, etaI / etaT);
        float transmitBias = isWater ? SURF_BIAS : INSET_TRANSMIT_BIAS;

        if (bounce == 0) {
            gv_normal = n;
            gv_rough = 0.0;
            gv_albedo = float3(0.0, 0.0, 0.0);
            gv_hitCamRel = hitPos - worldPush.camOffset;
            gv_motionUseRefracted = false;
            gv_motionObjDisp = isWater ? float3(0.0, 0.0, 0.0) : payload.motionPrev;
            gv_spec = makeSpecSurface(gv_hitCamRel, n, previousNormal, geometricNormal,
                                      isWater ? float3(0.0, 0.0, 0.0) : float3(payload.motionPrev),
                                      gv_rough, float3(F, F, F));
            if (dot(transmittedDir, transmittedDir) > 0.0) {
                MediumStack guideMedium = medium;
                if (entering) {
                    mediumPush(guideMedium, entered);
                } else {
                    mediumPop(guideMedium);
                }
                resolveTransmissionGuide(hitPos, transmittedDir, geometricNormal,
                        guideMedium, transmitBias, rayConeWidth, rayConeSpread,
                        !isWater && entering
                                ? float3(payload.albedo) : float3(1.0, 1.0, 1.0));
            }
        }

        // Split once, write both post-interface continuations, and stop Pass A radiance traversal.
        // Pass B owns every radiance trace after this point.
        bool splitEligible = dot(transmittedDir, transmittedDir) > 0.0
                && F > 0.0 && F < 1.0;
        if (splitEligible) {
            MediumStack transmittedMedium = medium;
            if (entering) {
                mediumPush(transmittedMedium, entered);
            } else {
                mediumPop(transmittedMedium);
            }
            float3 deferredDir = normalize(transmittedDir);
            PathSegment deferred = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), transmittedMedium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true);
            queue[splitRecord] = packPathSegment(deferred, false);
            hasNext = true;
            float3 reflectedDir = reflect(rd, n);
            PathSegment reflected = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed ^ 0xa511e9b3u, bounce + 1,
                    true);
            return reflected;
        }

        bool hasTransmission = dot(transmittedDir, transmittedDir) > 0.0;
        PathSegment continuation;
        if (!hasTransmission || F >= 1.0) {
            float3 reflectedDir = reflect(rd, n);
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, reflectedDir, SURF_BIAS),
                    reflectedDir, throughput * F, medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true);
        } else {
            float3 deferredDir = normalize(transmittedDir);
            if (entering) {
                mediumPush(medium, entered);
            } else {
                mediumPop(medium);
            }
            continuation = makePathSegment(
                    offsetSurfaceOrigin(hitPos, geometricNormal, deferredDir, transmitBias),
                    deferredDir, throughput * (1.0 - F), medium,
                    rayConeWidth, rayConeSpread, seed, bounce + 1,
                    true);
        }
        return continuation;
    }
}

[shader("raygeneration")]
void main() {
    worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    undyedCelestialIrradiance = worldPush.lightRadiance.xyz;
    // Same dual convention as pass B: retain the undyed peak for sampled emitter directions, then centre-
    // dye the compatibility WorldPush copy. Shared helpers therefore agree on both sides of the pass split.
    dyeCelestialLight(worldPush);
    uint2 dispatchIndex = DispatchRaysIndex().xy;
    uint2 dimensions = DispatchRaysDimensions().xy;
    int2 pix = int2(dispatchIndex);
    float2 size = float2(dimensions);
    CameraRay camera = cameraRay(dispatchIndex, dimensions);
    float2 jndc = camera.jndc;
    float3 origin = camera.origin;
    float3 dir = camera.dir;
    float rayConeSpread = primaryRayConeSpread(jndc, size, dir);
    uint seed = (dispatchIndex.x * 1973u + dispatchIndex.y * 9277u + 26699u)
            ^ (worldPush.frameIndex * 2654435761u);
    MediumStack cameraMedium = makeMediumStack((worldPush.flags & 1u) != 0u
            ? makeDielectricMedium(true, WATER_IOR, worldPush.waterParams.xyz, 1.0)
            : airMedium());
    uint pixelIndex = dispatchIndex.y * dimensions.x + dispatchIndex.x;
    uint baseRecordCount = dimensions.x * dimensions.y;
    uint splitRecord = baseRecordCount + pixelIndex;
    DevicePtr<PackedPathSegment> queue = DevicePtr<PackedPathSegment>(pc.pathQueueAddr);

    seed = pcg(seed);
    PathSegment current = makePathSegment(origin, dir, float3(1.0, 1.0, 1.0),
            cameraMedium, 0.0, rayConeSpread, seed, 0, true);
    bool hasNext;
    PathSegment terminal = tracePrimary(
            current, queue, splitRecord, hasNext);
    queue[pixelIndex] = packPathSegment(terminal, hasNext);

    writeGuides(pix, dir, jndc, size, rayConeSpread);
    // The volume views (8+) describe what happens between hits, which no guide buffer records, so pass B
    // writes those. Anything below is a guide visualization and belongs here.
    if (pc.debugView != 0u && pc.debugView < 8u) {
        writeDebugView(pix);
    }
}
