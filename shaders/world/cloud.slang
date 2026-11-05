// Clouds as a PURE RAY FUNCTION (M11, D33; high-cloud redesign D163-D168).
//
// WHERE THIS RUNS. On the segment that escapes to sky, before the raygen breaks out of its bounce loop.
// That placement is what puts clouds in reflections and in secondary bounces: a mirror's ray escapes too.
//
// WHY NOT INSIDE integrateSegment. The unified medium interface dispatches on a profile, and the ambient
// profile's height fog is integrated in CLOSED FORM -- clouds are the first client that genuinely needs a
// numerical march. Putting that march in the per-segment path would charge every bounce segment for it,
// including the ones near the ground that can never reach the shell, and M17 measured what per-segment
// work costs in this raygen. Same split as D2: the interface unifies, the estimators do not.
//
// THE HARD RULE (R18): THE CAMERA POSITION MUST NOT APPEAR IN THIS FILE. Everything is derived from the
// ray's own origin and direction. The reference implementation broke this twice -- flattening the high
// layer by horizontal distance to the camera, and ordering the layers by camera altitude -- and either
// one makes the clouds in a reflection sit at a different height from the clouds above it, so a lake and
// the sky above it contradict each other. There is no camera here to be tempted by.
//
// WORLD-ANCHORED, and that took a fix rather than a comment. Every coordinate the ray tracer handles is
// relative to a terrain rebase origin that follows the camera, so evaluating the density field on those
// coordinates directly anchored the clouds to the PLAYER: the deck rode along at its authored altitude
// above them, unreachable, and the whole pattern jumped whenever the origin reset. worldOf() adds the
// origin back; nothing else in this file may sample the field or test an altitude without it.
//
// The low deck retains a world-anchored 3D density march. The two high sheets are world-anchored optical
// depth fields and use analytic Beer integration, so the old high-cloud volume path must not return.

module cloud;

import atmosphere;      // raySphere, PLANET_R
import atmosphere_lut;  // mediumSkyRadiance lives in WorldPush, read via world_core
import math;
import world_common;
import world_core;
import cloud_field;   // CLOUD_NOISE_DIM: the LOD below is in texels of that volume

[[vk::binding(17, 0)]] Sampler3D cloudNoise;
[[vk::binding(26, 0)]] Sampler2DArray highCloudPatchTau;
// Where cloud systems stand (R) and which family they are (G). Two-dimensional fields, in a
// two-dimensional texture -- see cloud_weather.comp.slang for why they stopped being slices of the body
// volume. Reuses the descriptor slot the retired upper high-cloud sheet left free, so this costs no
// binding.
[[vk::binding(27, 0)]] Sampler2D cloudWeather;
// D173's domain-warp vector field: curl noise, signed, encoded into RGB. See cloud_warp.comp.slang.
[[vk::binding(28, 0)]] Sampler3D cloudWarp;
// D176's light-space sun-transmittance map, baked by cloud_shadow.comp.slang out of the same density
// field this file marches. Bound here rather than in world.rgen because it belongs to the cloud, and
// because the wrapper below is the only thing that should ever read it.
[[vk::binding(29, 0)]] Sampler2D cloudShadowMap;

// The march's step bounds, in blocks. The step is now chosen from how far THIS RAY crosses the deck
// rather than from how far the deck is, so a grazing crossing is spread over the budget instead of being
// swallowed by a handful of enormous strides. See marchLayer.
static const float CLOUD_STEP_MIN = 24.0;
static const float CLOUD_STEP_MAX = 400.0;

// How far along a ray clouds are still worth sampling. Beyond this the shell is below the horizon for any
// upward ray and the remaining crossing is grazing, where the march would spend its whole budget on a
// sliver of nearly transparent volume.
public static const float CLOUD_MAX_DIST = 40000.0;

// Everything is authored per frame, from worldPush, and read into a CloudLayer below:
//   cloudParams   = (coverage bias, density scale, type bias, extinction per block at full density)
//   cloudShape    = (deck bottom altitude, deck thickness, base feature size, detail feature size)
//   cloudLighting = (phase g, single-scattering albedo, sun-march steps, diffuse source A/(4*pi))
//   cloudHigh     = (high-cloud bottom altitude, sheet separation, coverage bias, extinction per block)
// Authored rather than constant because the vanilla weather system has to be able to move them — see
// RtComposite.cloudParams, where rain drives coverage and thunder drives type.

// ---- Phase function. Cloud droplets are ~10 um across, which is large against visible wavelengths, so
// Mie scattering off them is very strongly forward-peaked -- the measured asymmetry parameter of a water
// cloud is about 0.85, and that is a physical property of the droplet size distribution rather than a
// number chosen to make a picture look right. It is authored anyway (cloudLighting.x) because the
// distribution differs between cloud types and because it is the knob that decides how hard the rim of a
// backlit cloud glows.
//
// The BACK lobe is ours and is a fit, so it is named as one: real Mie has a secondary backscatter peak
// (the same optics as a glory) that a single Henyey-Greenstein lobe cannot represent at all, and without
// it a cloud with the sun behind the viewer reads as flat grey paper. Its width is tied to the forward
// lobe rather than authored separately -- one dial for "how directional is this cloud", not two.
static const float CLOUD_BACK_LOBE_RATIO = 0.6;  // back lobe g = -0.6 * forward g
static const float CLOUD_FORWARD_WEIGHT = 0.8;   // how much of the energy stays in the forward lobe

/**
 * Two Henyey-Greenstein lobes, forward and backward.
 *
 * <p>ENERGY CONSERVING BY CONSTRUCTION, which is the whole reason it is a lerp rather than a sum: each
 * hg() already integrates to 1 over the sphere, so any convex combination of two of them does too. The
 * reference implementation this structure comes from adds its lobes and drops the 1/(4*pi) besides, which
 * is where its ~4*pi energy error and every constant tuned around that error come from (R20).
 */
float cloudPhase(float cosT, float g) {
    return lerp(hg(cosT, -g * CLOUD_BACK_LOBE_RATIO), hg(cosT, g), CLOUD_FORWARD_WEIGHT);
}

float cloudPhaseG() { return clamp(worldPush.cloudLighting.x, -0.95, 0.95); }
float cloudAlbedo() { return clamp(worldPush.cloudLighting.y, 0.0, 0.9999); }
float cloudDiffuseSourceScale() { return max(worldPush.cloudLighting.w, 0.0); }

/** Bit 29: the multiple-scattering diffusion term. Off leaves single scattering exactly as it was. */
bool cloudMultiScatterOn() {
    return (worldPush.flags & (1u << 29u)) != 0u;
}

/**
 * The rate the DIFFUSE light dims at, as a multiple of ordinary optical depth.
 *
 * <p>Re-derived here rather than transcribed; see docs/devlog/M11-clouds.md. Where a medium is optically thick and almost purely
 * scattering, the radiance field goes nearly isotropic and the transport equation degenerates into the
 * diffusion equation, whose Green's function for a point source falls off as exp(-K*r)/(4*pi*D*r) with
 *
 *   K = sqrt(3 * sigma_a * sigma_tr),  sigma_a = (1 - w) * sigma_t,  sigma_tr -> sigma_t as g -> 0
 *
 * so K = sigma_t * sqrt(3 * (1 - w)) and therefore the integral of K along ANY path is that path's
 * ordinary optical depth times a single scalar. This function is that scalar. It is the reason the
 * multiple-scattering term is nearly free: the sun march already computes the optical depth, and the
 * diffuse term is one more exp() of the same number.
 *
 * <p>Cloud droplets scatter almost without absorbing, so w is a hair under 1 and the scalar is small: at
 * the default 0.999 it is 0.055. At an optical depth of 20 the direct beam is exp(-20), which is zero,
 * while this is exp(-1.1), which is a third -- and that difference IS the appearance of a cloud. A photon
 * that scatters has not been removed from the cloud, only from the beam; only absorption removes it.
 *
 * <p>The same identity, applied to a medium that absorbs, is what volume_source.slang's
 * volumeDiffuseAttenuation computes per channel for the fog and the water. It is spelled out separately
 * here because the cloud's version collapses to a scalar (grey droplets, one shared sigma_t) and rides on
 * a march that already exists, where the fog's has to survive per-channel absorption and a clamp.
 */
float cloudDiffusionRate() {
    return sqrt(3.0 * (1.0 - cloudAlbedo()));
}

/**
 * A rebased position in ABSOLUTE world blocks.
 *
 * <p>Everything the ray tracer handles is expressed relative to the terrain rebase origin, and that
 * origin FOLLOWS THE CAMERA. Sampling the density field at rebased coordinates therefore anchors the
 * clouds to the player rather than to the world: the deck climbs with them, so an altitude of 192 means
 * "192 blocks above wherever you are" and can never be reached or flown above, and the whole cloud
 * pattern jumps sideways every time the origin is reset. This one addition is what the file's
 * world-anchored claim actually rests on.
 */
float3 worldOf(float3 p) { return p + worldPush.cloudRebase.xyz; } // low deck; see worldIn


public struct CloudResult {
    public float3 inScatter;
    public float transmittance;
};

public CloudResult noClouds() {
    CloudResult r;
    r.inScatter = float3(0.0, 0.0, 0.0);
    r.transmittance = 1.0;
    return r;
}

/**
 * Front-to-back composite of two layers.
 *
 * <p>What the FRONT one adds, plus what survives it of the back one. The order is decided by which one
 * this ray reaches first — see traceClouds, where that comparison replaces the reference implementation's
 * camera-altitude test.
 */
CloudResult overCloud(CloudResult front, CloudResult back) {
    CloudResult r;
    r.inScatter = front.inScatter + front.transmittance * back.inScatter;
    r.transmittance = front.transmittance * back.transmittance;
    return r;
}

import cloud_density;   // CloudLayer, worldIn and the whole density field

// Both layers are constructed in cloud_density, from a WorldPush handed in as an argument, so that the
// shadow bake builds the SAME deck out of the same lanes. See that module.
CloudLayer lowLayer() { return lowCloudLayer(worldPush); }
CloudLayer highLayer() { return highCloudLayer(worldPush); }

bool layerEnabled(CloudLayer l) {
    return l.extinction > 0.0;
}

/** Bit 30 (Rt.Clouds.ENABLED). The test itself is in cloud_density, so the shadow bake shares it. */
public bool cloudsOn() { return cloudsOn(worldPush); }

/**
 * Where a ray enters and leaves the cloud shell, as distances along it.
 *
 * <p>A SPHERICAL shell, not a slab, and the difference is the horizon: on a slab the deck would extend
 * flat to infinity and meet the sky at a hard line, while on a shell it curves away and sets. The sphere
 * is centred a planet radius below the ray's own origin altitude, so the world is tangent to it -- and
 * that construction reads the RAY's origin, never the camera's.
 */
float2 cloudShellSpan(CloudLayer layer, float3 ro, float3 rd) {
    // Vertically anchored to the WORLD: the centre sits a planet radius below world y = 0, so the radii
    // below are absolute altitudes and a deck stays where it was authored however far the player climbs.
    // Horizontally it stays under the rebase origin, which is within a rebase distance of the camera --
    // that is what gives each ray its own horizon, and the curvature error from those hundred-odd blocks
    // is a thousandth of a block.
    float3 centreRelative = ro + float3(0.0, PLANET_R + worldPush.cloudRebase.y, 0.0);
    float rBottom = PLANET_R + layer.bottom;
    float rTop = rBottom + layer.thickness;
    float2 inner = raySphere(centreRelative, rd, rBottom);
    float2 outer = raySphere(centreRelative, rd, rTop);
    if (outer.y <= 0.0) {
        return float2(0.0, -1.0); // the shell is entirely behind this ray
    }
    // BRANCH ON THE ORIGIN'S RADIUS, not on the signs of the roots. This was the bug that made the sky
    // empty at every setting: the old test asked whether the inner sphere's NEAR root was ahead, meaning
    // to detect "below the deck, looking up" -- but a ray starting below cloud base is INSIDE the inner
    // sphere, so its near root is always behind it and that test never fired. Every such ray fell into
    // the "already inside the deck" case and got the span from the camera up to the underside: hundreds
    // of blocks of clear air, sampled dutifully, all of it below the deck, density zero everywhere.
    float radius = length(centreRelative);
    float enter;
    float exit;
    if (radius < rBottom) {
        // Below the deck. The crossing starts where the ray passes UP through the underside, which is
        // the FAR root of the inner sphere. A downward ray has no such crossing within reach: its far
        // root is on the other side of the planet, and the distance clamp below turns that into an
        // empty span on its own.
        if (inner.y <= 0.0) {
            return float2(0.0, -1.0);
        }
        enter = inner.y;
        exit = outer.y;
    } else if (radius <= rTop) {
        // Inside the deck: from here to whichever boundary is met first. Looking down, that is the
        // underside, and the inner sphere is genuinely ahead so its near root is positive. Looking up,
        // the inner sphere is behind and the ray leaves through the top.
        enter = 0.0;
        exit = inner.x > 0.0 ? inner.x : outer.y;
    } else {
        // Above the deck. Enter through the top, then leave either through the underside or, for a
        // grazing ray that never reaches it, back out through the top.
        if (outer.x <= 0.0) {
            return float2(0.0, -1.0);
        }
        enter = outer.x;
        exit = inner.x > 0.0 ? inner.x : outer.y;
    }
    return float2(max(enter, 0.0), min(exit, CLOUD_MAX_DIST));
}

// ---- The density field lives in cloud_density, which has no bindings so the shadow bake can share it.
// These wrappers are the only place set 0 and worldPush meet it. See that module's banner for why.

CloudTextures cloudTextures() {
    CloudTextures t;
    t.noise = cloudNoise;
    t.weather = cloudWeather;
    t.warp = cloudWarp;
    t.highTau = highCloudPatchTau;
    return t;
}

float cloudCoverage(CloudLayer layer, float3 p) { return cloudCoverage(cloudTextures(), layer, p); }
float cloudType(CloudLayer layer, float3 p) { return cloudType(cloudTextures(), layer, p); }
float cloudDensity(CloudLayer layer, float3 p, bool cheap, float footprint) {
    return cloudDensity(cloudTextures(), layer, p, cheap, footprint);
}
float sunOpticalDepth(CloudLayer layer, float3 p, float3 lightDir, int steps, inout uint seed) {
    return sunOpticalDepth(cloudTextures(), layer, p, lightDir, steps, seed);
}

/**
 * D176: how much of the sun reaches a point through the cloud, for a surface to attenuate its sun term.
 *
 * <p>NOT for the cloud's own shading. A cloud that dimmed its own sun term by this map would shadow
 * itself twice -- sunOpticalDepth above already integrates the deck along the light from inside it and
 * at the sample's own altitude, which is precisely what a map indexed by light-space position alone
 * cannot do.
 */
public float cloudSunTransmittance(float3 p) {
    return cloudSunTransmittance(cloudShadowMap, worldPush, p);
}

/**
 * How much march a ray may spend. Secondary rays get less of it — see cloudSecondaryQuality.
 *
 * <p>A budget rather than a quality level threaded through every function, because the three things that
 * actually cost anything — how many steps, whether the erosion fetch happens, when to give up on a nearly
 * opaque path — are independent, and each wants its own answer.
 */
struct CloudBudget {
    int   maxSteps;
    int   sunSteps;
    bool  erosion;
    float transmittanceFloor;
};

/**
 * Bits 2-3 (Rt.Volumetrics.CLOUD_SECONDARY): what a ray that is not the first of its path gets.
 *
 * <p>0 no clouds at all, 1 a reduced march, 2 the same march as the primary ray.
 *
 * <p>This is the only place in the file that treats one ray differently from another, and it is a COST
 * decision rather than a geometric one — nothing here consults where the camera is, only whether this
 * particular ray is the first of its path. A reflection still finds its clouds by intersecting the same
 * world-anchored shells from its own origin, so a lake and the sky above it cannot disagree about WHERE a
 * cloud is. They can only disagree about how finely it was integrated.
 */
uint cloudSecondaryQuality() {
    return (worldPush.flags >> 2u) & 3u;
}

CloudBudget cloudBudget(CloudLayer layer, bool secondary) {
    CloudBudget b;
    b.maxSteps = 96;
    b.sunSteps = layer.sunSteps;
    b.erosion = true;
    b.transmittanceFloor = 0.01;
    if (secondary && cloudSecondaryQuality() < 2u) {
        // Reduced: fewer steps, no erosion, half the shadow march, and give up on a nearly opaque path
        // much sooner. Each of those is close to invisible at the sizes a reflection is read at, and
        // together they are most of the march's cost.
        b.maxSteps = 40;
        b.sunSteps = layer.sunSteps / 2;
        b.erosion = false;
        b.transmittanceFloor = 0.05;
    }
    return b;
}

/**
 * March the low convective layer and return what it added and what of the sky survives it.
 *
 * <p>The source is the three terms a cloud actually has, all in radiance units. The celestial buffer is
 * irradiance E, so direct single scattering is E*p(omega), where the normalised phase already contains
 * 1/(4*pi). The isotropic diffusion source is E*A/(4*pi), with A derived on the CPU from the approved
 * thick-slab reflectance. mediumSkyRadiance is already a 1/(4*pi) sphere integral and is therefore used
 * directly. Mixing E with a relative-to-isotropic 4*pi*p gain creates exactly the 12.566x error D61
 * removed.
 *
 * <ol>
 *   <li><b>Direct sun</b>, through the self-shadow march and a two-lobe phase function. This is what
 *       gives a cloud a lit side and a shaded side, and the back lobe is what lights the rim of one
 *       standing in front of the sun.</li>
 *   <li><b>Multiple scattering</b>, the diffusion term — the same sun, attenuated at cloudDiffusionRate()
 *       times the optical depth instead of at the optical depth, and isotropic because by the time light
 *       has scattered often enough for diffusion to describe it, it has forgotten where it came from.
 *       This is what keeps the inside of a thick cloud bright; without it a cumulonimbus is a black
 *       silhouette with a lit edge.</li>
 *   <li><b>Sky</b>, the shared mediumSkyRadiance the fog and the water have read since M16, occluded by
 *       however much cloud stands above the sample.</li>
 * </ol>
 *
 * <p>The single-scattering albedo does not appear as a multiplier on the in-scatter. It is a hair under
 * one for water droplets, and where it genuinely matters — how fast light dies inside the cloud — it
 * already enters through the diffusion rate.
 *
 * <p>Step length grows with distance FROM THE RAY'S ORIGIN, so a cloud a long way off is integrated
 * coarsely and one overhead finely. Measured from the origin rather than the camera for the usual reason,
 * and it is also what lets a secondary ray degrade gracefully: a reflected ray starts at the water, so
 * its budget is spent where its own clouds are.
 */
#define CLOUD_KERNEL_SOURCE 1
#define CLOUD_KERNEL_FN(name) name
#include "cloud_path_kernel.slang"
#undef CLOUD_KERNEL_FN
#undef CLOUD_KERNEL_SOURCE

#define CLOUD_KERNEL_SOURCE 0
#define CLOUD_KERNEL_FN(name) name##Transmittance
#include "cloud_path_kernel.slang"
#undef CLOUD_KERNEL_FN
#undef CLOUD_KERNEL_SOURCE

public float cloudPathTransmittance(float3 ro, float3 rd, float tMax, uint seed, bool secondary) {
    return traceCloudsTransmittance(ro, rd, tMax, seed, secondary).transmittance;
}

/**
 * Debug view 22: name the first stage of the cloud chain that fails, as a flat colour.
 *
 * <p>Exists because "there are no clouds" was diagnosed twice by reading call graphs, and the second
 * diagnosis was wrong. Every stage below can independently produce an empty sky, they all look identical
 * on screen, and none of them logs anything. One launch and one screenshot should say which it is.
 *
 * <ul>
 *   <li><b>RED</b> — the enable bit never reached the shader. Config, flags packing, or push upload.</li>
 *   <li><b>MAGENTA</b> — the noise volume reads zero everywhere. Bake never ran, descriptor never
 *       written, or the binding index disagrees between pipeline layout and shader.</li>
 *   <li><b>BLUE</b> — the ray never enters the shell. Geometry: altitude, thickness, or the span.</li>
 *   <li><b>YELLOW</b> — inside the shell, but density is zero along the whole crossing. The density
 *       field's own balance: coverage, shape, profile or the type axis.</li>
 *   <li><b>GREY</b> — clouds exist here; brightness is 1 - transmittance, so white is opaque cloud.
 *       If this view is grey and the world is not, the failure is in the LIGHTING, not the shape.</li>
 * </ul>
 */
public float3 cloudDebugProbe(float3 ro, float3 rd) {
    if (!cloudsOn()) {
        return float3(1.0, 0.0, 0.0);
    }
    // Four widely separated taps rather than one: a single tap could legitimately land on a zero, and
    // reporting "the texture is dead" because of that would send the search to exactly the wrong place.
    float noise = cloudNoise.SampleLevel(float3(0.11, 0.37, 0.23), 0.0).r
            + cloudNoise.SampleLevel(float3(0.61, 0.13, 0.79), 0.0).r
            + cloudNoise.SampleLevel(float3(0.29, 0.83, 0.41), 0.0).g
            + cloudNoise.SampleLevel(float3(0.73, 0.51, 0.07), 0.0).b;
    if (noise <= 0.0) {
        return float3(1.0, 0.0, 1.0);
    }

    CloudLayer low = lowLayer();
    float2 span = cloudShellSpan(low, ro, rd);
    if (span.y <= span.x) {
        return float3(0.0, 0.0, 1.0);
    }

    // A fixed uniform walk, deliberately unlike the real march: this has to be able to disagree with it.
    // If the field has density and the real march still shows nothing, the fault is in the march.
    //
    // The three factors are tracked SEPARATELY as well as multiplied. They all multiply, so any one of
    // them being zero empties the sky and the product alone cannot say which -- and a CPU replica of the
    // density field said all three should be healthy, which was wrong about something. This reports the
    // maxima instead of asserting them.
    float maxDensity = 0.0;
    float maxCoverage = 0.0;
    float maxProfile = 0.0;
    float maxShape = 0.0;
    float inDeck = 0.0;
    float tau = 0.0;
    float step = (span.y - span.x) / 64.0;
    for (int i = 0; i < 64; ++i) {
        float3 p = ro + rd * (span.x + step * (float(i) + 0.5));
        float3 pw = worldIn(low, p);
        if (pw.y >= low.bottom && pw.y <= low.bottom + low.thickness) {
            inDeck += 1.0;
            float type = cloudType(low, p);
            // The SAME depth the shading uses. A probe that reported the type's full profile while the
            // density used a per-cloud shallower one would disagree with the thing it exists to explain.
            float coverageHere = cloudCoverage(low, p);
            maxCoverage = max(maxCoverage, coverageHere);
            maxProfile = max(maxProfile, cloudHeightProfile(low, pw.y, type,
                                                           cloudDepthFraction(coverageHere, type)));
            maxShape = max(maxShape, cloudNoise.SampleLevel(pw / low.baseScale, 0.0).r);
        }
        float d = cloudDensity(low, p, true, 0.0);
        maxDensity = max(maxDensity, d);
        tau += d * low.extinction * step;
    }
    if (maxDensity <= 0.0) {
        // Not a flat yellow any more: R is the best coverage seen, G the best height profile, B the best
        // shape sample. Black means the walk never landed inside the deck at all, which would make the
        // shell span and the density field disagree about where the deck is.
        return inDeck <= 0.0 ? float3(0.0, 0.0, 0.0)
                             : float3(maxCoverage, maxProfile, maxShape);
    }
    return float3(1.0 - exp(-tau));
}
