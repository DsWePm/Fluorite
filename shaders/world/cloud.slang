// Volumetric clouds as a PURE RAY FUNCTION (M11, decision D33).
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
// Structure is HPVolumeCloud's (weather map x 3D noise x height profile, adaptive stepping); no constant
// is. See R20: its Intensity carries units of m^-2 and every number here is chosen for this world's scale.

module cloud;

import atmosphere;      // raySphere, PLANET_R
import atmosphere_lut;  // mediumSkyRadiance lives in WorldPush, read via world_core
import math;
import world_common;
import world_core;

[[vk::binding(17, 0)]] Sampler3D cloudNoise;

// How far along a ray clouds are still worth sampling. Beyond this the shell is below the horizon for any
// upward ray and the remaining crossing is grazing, where the march would spend its whole budget on a
// sliver of nearly transparent volume.
public static const float CLOUD_MAX_DIST = 40000.0;

// How far apart the sky's clumps and clearings are, in blocks — the FIELD, distinct from how big a single
// cloud within a clump is (cloudShape.z). Authored, because those are two different questions and
// answering them with one number was the reason neither could be adjusted.
float cloudFieldScale() { return max(worldPush.cloudRebase.w, 1.0); }
// Cloud TYPE varies far more slowly still. A storm covers a region; it does not alternate with fair
// weather every kilometre — so it is pinned to a multiple of the field rather than authored separately.
float cloudTypeScale() { return cloudFieldScale() * 2.9; }

// Everything else is authored per frame, from worldPush, and read into a CloudLayer below:
//   cloudParams   = (coverage bias, density scale, type bias, extinction per block at full density)
//   cloudShape    = (deck bottom altitude, deck thickness, base feature size, detail feature size)
//   cloudLighting = (phase g, single-scattering albedo, sun-march steps, reserved)
//   cloudHigh     = (cirrus altitude, thickness, coverage bias, extinction per block)
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

/** Bit 29: the multiple-scattering diffusion term. Off leaves single scattering exactly as it was. */
bool cloudMultiScatterOn() {
    return (worldPush.flags & (1u << 29u)) != 0u;
}

/**
 * The rate the DIFFUSE light dims at, as a multiple of ordinary optical depth.
 *
 * <p>Re-derived here rather than transcribed (§6.4). Where a medium is optically thick and almost purely
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
float3 worldOf(float3 p) { return p + worldPush.cloudRebase.xyz; }

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

/**
 * One deck of cloud: where it sits, how big its features are, and how it is lit.
 *
 * <p>Two instances exist. The LOW deck is convective — it has a cloud-type dimension, real depth, and
 * self-shadowing worth marching. The HIGH one is cirrus: ice crystals sheared into streaks, thin enough
 * that its optical depth never gets far from zero, so it has no type, no self-shadow march, and a bound
 * on how long a crossing it may hand the march.
 *
 * <p>One struct rather than two code paths because everything downstream of the density field — the
 * march, the phase function, the diffusion term, the compositing — is the same physics either way. What
 * differs is which numbers go in.
 */
struct CloudLayer {
    float bottom;
    float thickness;
    float coverageBias;
    float extinction;
    float baseScale;
    float detailScale;
    int   sunSteps;
    bool  cirrus;
};

CloudLayer lowLayer() {
    CloudLayer l;
    l.bottom = worldPush.cloudShape.x;
    l.thickness = max(worldPush.cloudShape.y, 1.0);
    l.coverageBias = worldPush.cloudParams.x;
    l.extinction = worldPush.cloudParams.w;
    l.baseScale = max(worldPush.cloudShape.z, 1.0);
    l.detailScale = max(worldPush.cloudShape.w, 1.0);
    l.sunSteps = clamp(int(worldPush.cloudLighting.z + 0.5), 0, 8);
    l.cirrus = false;
    return l;
}

/**
 * Cirrus, placed above the convective deck rather than at an authored absolute altitude.
 *
 * <p>The clamp is what keeps the two shells DISJOINT, and that is what makes ordering them by entry
 * distance correct: with the high shell strictly outside the low one, a ray meets one span entirely
 * before the other from every origin and direction. Overlapping shells would need the two marches
 * interleaved by depth, and getting that wrong shows up as one layer drawn in front of the other from
 * some angles and behind it from others.
 */
CloudLayer highLayer() {
    CloudLayer l;
    CloudLayer low = lowLayer();
    l.thickness = max(worldPush.cloudHigh.y, 1.0);
    l.bottom = max(worldPush.cloudHigh.x, low.bottom + low.thickness + 16.0);
    l.coverageBias = worldPush.cloudHigh.z;
    l.extinction = worldPush.cloudHigh.w;
    // An order of magnitude coarser than the deck's: cirrus is drawn out into sheets tens of kilometres
    // across, and giving it the cumulus feature size makes it read as a second deck of small clouds.
    l.baseScale = low.baseScale * 4.0;
    l.detailScale = low.detailScale * 3.0;
    // No self-shadow march. Its optical depth barely leaves zero, so exp(-tau) is within a hair of one
    // everywhere in it, and marching to establish that would be the most expensive nothing in the frame.
    l.sunSteps = 0;
    l.cirrus = true;
    return l;
}

bool layerEnabled(CloudLayer l) {
    return l.extinction > 0.0;
}

/** Bit 30 (Rt.Clouds.ENABLED). */
public bool cloudsOn() {
    return (worldPush.flags & (1u << 30u)) != 0u;
}

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

/**
 * Vertical profile, selected by cloud TYPE — which is the difference between weather and wallpaper.
 *
 * <p>One deck, three shapes blended along a single axis, because from the ground the families that read
 * differently are a sheet, a heap and a tower:
 *
 * <ul>
 *   <li><b>0, stratus</b>: a shallow sheet lying on the deck's floor, flat above as well as below. It is
 *       a layer, not an object, so it occupies only the lowest tenth of the deck.</li>
 *   <li><b>0.5, cumulus</b>: flat-bottomed, rounded on top, filling the lower half. Fair weather.</li>
 *   <li><b>1, cumulonimbus</b>: the full depth, still flat-bottomed, and WIDENING again near the top.
 *       That late flare is the anvil — the tower reaches stable air and spreads sideways instead of
 *       continuing to climb. Without it a storm cloud reads as a column rather than as a storm.</li>
 * </ul>
 *
 * <p>All three share the flat base for one physical reason: convective cloud condenses at the altitude
 * where rising air reaches saturation, and that altitude is the same across a region, so the underside of
 * a cloud field is a plane. The tops differ because a top is wherever that particular parcel ran out of
 * lift.
 *
 * <p>Blended in two halves rather than by a single lerp between the extremes, so the middle of the range
 * is cumulus rather than a half-height average of a sheet and a tower.
 */
float cloudHeightProfile(CloudLayer layer, float altitude, float type) {
    float t = saturate((altitude - layer.bottom) / layer.thickness);
    if (layer.cirrus) {
        // Cirrus has no type dimension: it is one shape, a sheet, and the only question is how thick.
        // Symmetric, unlike every convective profile above, because it does not condense at a base and
        // grow upward — it is ice that formed where the air was already cold enough.
        return saturate(smoothstep(0.0, 0.25, t) * (1.0 - smoothstep(0.75, 1.0, t)));
    }
    float stratus = smoothstep(0.0, 0.03, t) * (1.0 - smoothstep(0.07, 0.18, t));
    float cumulus = smoothstep(0.0, 0.05, t) * (1.0 - smoothstep(0.30, 0.62, t));
    float anvil = smoothstep(0.60, 0.78, t) * (1.0 - smoothstep(0.92, 1.0, t));
    float cumulonimbus = max(smoothstep(0.0, 0.04, t) * (1.0 - smoothstep(0.68, 0.95, t)), anvil * 0.9);
    float profile = type < 0.5
            ? lerp(stratus, cumulus, saturate(type * 2.0))
            : lerp(cumulus, cumulonimbus, saturate(type * 2.0 - 1.0));
    return saturate(profile);
}

/**
 * Where this type's profile runs out, as a fraction of the deck — the top of THIS cloud rather than of
 * the deck it stands in.
 *
 * <p>MUST TRACK cloudHeightProfile, and the two are adjacent so a change to one is visibly a change to
 * both. It is the upper support of each shape above: the stratus sheet ends at 0.18, the cumulus at 0.62,
 * the cumulonimbus's anvil at 1. Blended in the same two halves for the same reason.
 *
 * <p>Exists so the ambient term can ask how much cloud stands above a point without marching for the
 * answer. Using the deck's own top instead would bury a stratus sheet under three hundred blocks of
 * nothing and turn it black.
 */
float cloudProfileTop(float type) {
    return type < 0.5 ? lerp(0.18, 0.62, saturate(type * 2.0))
                      : lerp(0.62, 1.0, saturate(type * 2.0 - 1.0));
}

/**
 * Coverage, from a world-anchored slice of the same volume.
 *
 * <p>Anchored to world coordinates rather than to anything view-dependent, so a cloud stays over the
 * same ground as the ray that finds it moves — which is the property the whole file exists to protect.
 */
float cloudCoverage(CloudLayer layer, float3 p) {
    // The high layer reads the same weather map from a different slice: same map because a cirrus sheet
    // and the cumulus under it belong to the same weather system, different slice because they are not
    // the same clouds and reading it identically would paste a cirrus shadow over every cumulus. The
    // PERIOD is the layer's own now rather than a multiple of the deck's.
    float2 w = worldOf(p).xz / cloudFieldScale();
    float m = cloudNoise.SampleLevel(float3(w.x, layer.cirrus ? 0.81 : 0.37, w.y), 0.0).r;
    // Re-centred for the Perlin-Worley body. The old (m - 0.42) * 2.6 was fitted to pure Worley, whose
    // mean sits at 0.486; the normalised Perlin-Worley field is wider and centred differently, and
    // keeping the old numbers against it overcasts 96% of the sky at neutral bias. Both halves were
    // fitted together on a CPU replica of the bake: at bias zero this leaves 38% of columns carrying
    // cloud against the old body's 20%, with density inside a cloud reaching 0.69 at p90 against 0.44.
    //
    // The authored bias then shifts the whole field, which is how rain overcasts a sky: one number
    // rather than a second field.
    return saturate((m - 0.30) * 1.6 + layer.coverageBias);
}

/**
 * Which family of cloud stands over this column, in [0,1] — the argument to cloudHeightProfile.
 *
 * <p>Its own slice of the volume, at its own much longer period, so type and coverage are independent:
 * an overcast sky can be a flat sheet or a wall of towers, and those are different weather.
 *
 * <p>The authored bias is ADDITIVE and saturating rather than a replacement, so a thunderstorm pushes the
 * whole sky toward towers while the field still decides where the individual cells stand. Replacing the
 * field would make every cloud in the sky identical the moment the weather changed, which reads as a
 * switch being thrown rather than as a storm arriving.
 */
float cloudType(float3 p) {
    float2 w = worldOf(p).xz / cloudTypeScale();
    float m = cloudNoise.SampleLevel(float3(w.x, 0.61, w.y), 0.0).b;
    // Scaled below 1 so fair weather stays in the sheet-to-heap half of the range and the tower is
    // something the weather has to ask for.
    return saturate(m * 0.75 + worldPush.cloudParams.z);
}

/**
 * Density at a point. `cheap` skips the erosion fetch, which is what makes the empty-space skip pay for
 * itself: most steps along most rays are outside any cloud, and they only need to know that.
 *
 * <p>ORDERED BY COST, and that ordering is load-bearing rather than tidy. Every factor below multiplies,
 * so any one of them reaching zero ends the function — which makes it worth asking the free question
 * first and the expensive one last. The deck bounds are arithmetic on p.y and reject the majority of
 * steps along the majority of rays; coverage is one fetch and rejects most of what survives; type is a
 * second fetch and cannot reject anything by itself, so it is deliberately NOT hoisted above the height
 * test even though the height profile now takes it as an argument. Hoisting it would charge every empty
 * step in the sky for a texture read that only matters inside a cloud.
 */
float cloudDensity(CloudLayer layer, float3 p, bool cheap) {
    float3 pw = worldOf(p);
    if (pw.y < layer.bottom || pw.y > layer.bottom + layer.thickness) {
        return 0.0; // outside the deck: no type can put cloud here
    }
    float coverage = cloudCoverage(layer, p);
    if (coverage <= 0.0) {
        return 0.0;
    }
    float type = layer.cirrus ? 0.0 : cloudType(p);
    float profile = cloudHeightProfile(layer, pw.y, type);
    if (profile <= 0.0) {
        return 0.0;
    }
    // Cirrus is SHEARED. Ice crystals falling out of a sheet are drawn into streaks by the wind gradient
    // they fall through, which is why cirrus looks combed and cumulus looks heaped — so the volume is
    // sampled anisotropically, stretched along one horizontal axis and compressed vertically. A fixed
    // world axis stands in for a wind direction the renderer does not model; it is world-anchored, which
    // is the property that matters here, and a per-frame direction would make the sky crawl.
    float3 shapeUv = layer.cirrus
            ? pw / (layer.baseScale * float3(3.5, 0.35, 1.0))
            : pw / layer.baseScale;
    float shape = cloudNoise.SampleLevel(shapeUv, 0.0).r;
    // Coverage raises the whole field rather than masking it, so a cloud grows from its centre outward
    // instead of appearing with hard edges where the mask crosses a threshold.
    float density = saturate(shape + coverage - 1.0) * profile;
    // Type raises density as well as height, because a cumulonimbus is not a tall cumulus — it is a tall
    // cumulus that light struggles to get through, and that opacity is most of what makes a storm read as
    // one from underneath. Applied AFTER the erosion below rather than here: the erosion term is written
    // against a density normalised to [0,1] and would invert if a gain pushed its argument past one.
    float gain = worldPush.cloudParams.y * (1.0 + type * 1.6);
    if (density <= 0.0 || cheap) {
        return density * gain;
    }
    float erosion = cloudNoise.SampleLevel(pw / layer.detailScale, 0.0).g;
    // Erosion only bites where the cloud is already thin, so the body keeps its shape while the edges
    // break up. Subtracting everywhere would hollow the cloud out.
    return saturate(density - erosion * 0.35 * (1.0 - density)) * gain;
}

/**
 * Optical depth from a point toward the light, which is how a cloud shadows itself.
 *
 * <p>GEOMETRICALLY GROWING STEPS. The first is short because the shading a viewer reads — the boundary
 * between the lit face and the shaded one — is decided within a few tens of blocks of the sample, and the
 * last is long because past that only the bulk matters. Doubling reaches about one deck thickness in five
 * steps; a linear schedule with the same reach would need thirty-one, and one with the same step count
 * would sample the near field four times too coarsely.
 *
 * <p>The reach is one deck thickness on purpose rather than "until the ray leaves the shell": for a low
 * sun the path through the shell is kilometres long, almost all of it outside any cloud, and marching it
 * would spend the whole budget proving that.
 *
 * <p>CHEAP density (no erosion). Erosion is a high-frequency detail on a cloud's edge, and this integral
 * is a low-frequency quantity — it decides how dark the inside of a cloud is, not where its edge falls.
 * Halving the fetches on the one path that runs several times per march step is worth more than the
 * detail is.
 */
float sunOpticalDepth(CloudLayer layer, float3 p, float3 lightDir, int steps, inout uint seed) {
    // reach = s0 * (2^steps - 1) with doubling steps, so s0 is chosen to keep the reach fixed as the
    // step count moves. Quality then costs steps without also changing what is being integrated.
    float s0 = layer.thickness / max(exp2(float(steps)) - 1.0, 1.0);
    // Jittered start for the same reason the primary march jitters: an unjittered shadow lattice draws
    // itself as bands across a cloud's shaded face.
    float jitter = rndf(seed);
    float tau = 0.0;
    float t = 0.0;
    float step = s0;
    for (int i = 0; i < steps; ++i) {
        // Sample at the step's midpoint, offset by the jitter — midpoint because a one-sample estimate of
        // an interval's average is least wrong there.
        float3 q = p + lightDir * (t + step * (0.35 + 0.3 * jitter));
        tau += cloudDensity(layer, q, true) * step;
        t += step;
        step *= 2.0;
    }
    return tau * layer.extinction;
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
 * How long a crossing this layer may hand the march, in blocks.
 *
 * <p>NOT A FADE, and the difference matters. An earlier version dissolved the cirrus sheet as a ray
 * approached it, taken from the plan's "camera-proximity fade" -- but that was describing vanilla's flat
 * cloud TEXTURE, which vanishes when you fly into it. A volumetric cloud has no reason to disappear when
 * approached, and being able to fly into one is most of the point of having them.
 *
 * <p>What the fade was covering for is real and stays, in the shape the problem actually has: a ray that
 * begins its crossing at its own origin is grazing ALONG the sheet rather than passing through it, so the
 * crossing is enormously long, the adaptive step grows to match, and neighbouring pixels disagree wildly
 * about a sheet that is only tens of blocks thick. That is a march-budget problem and it gets a budget --
 * the crossing is capped at a multiple of the layer's own thickness, which is as far through a sheet as a
 * ray can travel and still be describing that sheet.
 */
float layerCrossingLimit(CloudLayer layer) {
    return layer.cirrus ? layer.thickness * 24.0 : CLOUD_MAX_DIST;
}

/**
 * March one layer and return what it added and what of the sky survives it.
 *
 * <p>The source is the three terms a cloud actually has, all expressed as a GAIN OVER ISOTROPIC to match
 * evaluateAmbientRadianceSource — the phase function times 4*pi, the sky source bare, because
 * mediumSkyRadiance is already the 1/(4*pi) sphere integral. Getting that convention right in one term
 * and wrong in another is a 12x error that looks like an art choice, which is exactly how the reference
 * implementation's constants came to be what they are.
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
CloudResult marchLayer(CloudLayer layer, CloudBudget budget, float3 ro, float3 rd, float tMax,
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
    // Grazing rays get a bounded crossing rather than a dissolve -- see layerCrossingLimit.
    tExit = min(tExit, tEnter + layerCrossingLimit(layer));

    float3 ambient = worldPush.mediumSkyRadiance.xyz;
    float3 lightDir = worldPush.lightDir.xyz;
    float3 lightRadiance = worldPush.lightRadiance.xyz;
    // The phase evaluated once for the whole march: it depends on the angle between the ray and the
    // light, and neither moves along a straight ray. 4*pi converts the phase from a density over
    // directions into a gain over isotropic, which is the convention every source term here shares.
    float phase = 4.0 * PI * cloudPhase(dot(rd, lightDir), cloudPhaseG());
    // Off must reproduce single scattering exactly rather than approximately (§3, rule 8), so it does two
    // things: it removes the diffuse lobe entirely, and it puts the sky's occlusion back on the beam's
    // own extinction. Leaving the diffuse lobe in at rate 1 would not disable multiple scattering — it
    // would add a second, isotropic copy of the direct term.
    bool multiScatter = cloudMultiScatterOn();
    float diffusionRate = multiScatter ? cloudDiffusionRate() : 1.0;

    // Jittered entry, for the same reason every other estimator here jitters: a fixed start puts the
    // step lattice at the same depth on neighbouring pixels and draws it as banding, and this renderer
    // would rather have noise a denoiser can integrate than structure it cannot.
    float t = tEnter + rndf(marchSeed) * min(64.0, (tExit - tEnter) * 0.1);

    float transmittance = 1.0;
    float3 gathered = float3(0.0, 0.0, 0.0);
    int emptySteps = 0;
    // The length of the last long stride taken through empty space. Kept because a stride that lands
    // inside a cloud has crossed everything between where it started and where it ended WITHOUT
    // INTEGRATING ANY OF IT, and that has to be undone rather than accepted.
    float lastSkip = 0.0;
    for (int i = 0; i < budget.maxSteps && t < tExit; ++i) {
        float step = max(24.0, t * 0.125);
        float3 p = ro + rd * t;
        float density = cloudDensity(layer, p, true);
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
            density = cloudDensity(layer, p, false);
        }
        if (density > 0.0) {
            float sigmaT = density * layer.extinction;
            float stepT = exp(-sigmaT * step);

            // How buried this sample is, from the light's side and from the sky's.
            float tauSun = budget.sunSteps > 0
                    ? sunOpticalDepth(layer, p, lightDir, budget.sunSteps, marchSeed) : 0.0;
            // The sky's occlusion, without a second march: how much of THIS cloud stands above the
            // sample, taking the local density as representative of the column. Bounded by the type's own
            // profile rather than by the deck, so a stratus sheet is not buried under the empty three
            // hundred blocks a cumulonimbus would have filled.
            float ownTop = layer.cirrus
                    ? layer.bottom + layer.thickness
                    : layer.bottom + cloudProfileTop(cloudType(p)) * layer.thickness;
            float tauUp = sigmaT * max(ownTop - worldOf(p).y, 0.0);

            // Isotropic, so no phase factor: light that has scattered often enough for diffusion to
            // describe it has forgotten which way it came in. That is also why this term is the same for
            // every ray through a given point, and therefore consistent between a cloud and its
            // reflection without anything having to enforce it.
            float diffuse = multiScatter ? exp(-diffusionRate * tauSun) : 0.0;
            float3 source = ambient * exp(-diffusionRate * tauUp)
                    + lightRadiance * (exp(-tauSun) * phase + diffuse);
            // The same closed form the fog's sub-steps use: for a source taken as constant over the step,
            // the in-scatter integrates exactly to source * (1 - T), and doing it this way keeps a long
            // step from overshooting the way a midpoint sample would.
            gathered += transmittance * source * (1.0 - stepT);
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

/**
 * Both layers, composited in the order THIS RAY meets them.
 *
 * <p>SLICE THREE OF THREE (D35). The convective deck and the cirrus above it are two shells around the
 * same centre, kept disjoint by highLayer()'s clamp, so a ray crosses one entirely before the other and
 * comparing entry distances is enough to order them.
 *
 * <p>That comparison is the point. The reference implementation ordered its layers by the CAMERA's
 * altitude, which is right for the camera's own view and wrong for every other ray in the frame: a ray
 * reflected off a lake looks upward from below both layers while the camera may be sitting between them,
 * and the two then disagree about which cloud is in front. Ordering by the ray's own entry distance
 * cannot disagree with itself (R18).
 *
 * @param secondary true for anything that is not the first ray of its path — see cloudSecondaryQuality
 */
public CloudResult traceClouds(float3 ro, float3 rd, float tMax, uint seed, bool secondary) {
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
    float highEnter = cloudShellSpan(high, ro, rd).x;

    CloudResult lowResult = marchLayer(low, cloudBudget(low, secondary), ro, rd, tMax, marchSeed);
    CloudResult highResult = marchLayer(high, cloudBudget(high, secondary), ro, rd, tMax, marchSeed);
    return lowEnter <= highEnter ? overCloud(lowResult, highResult)
                                 : overCloud(highResult, lowResult);
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
        float3 pw = worldOf(p);
        if (pw.y >= low.bottom && pw.y <= low.bottom + low.thickness) {
            inDeck += 1.0;
            float type = cloudType(p);
            maxCoverage = max(maxCoverage, cloudCoverage(low, p));
            maxProfile = max(maxProfile, cloudHeightProfile(low, pw.y, type));
            maxShape = max(maxShape, cloudNoise.SampleLevel(pw / low.baseScale, 0.0).r);
        }
        float d = cloudDensity(low, p, true);
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
