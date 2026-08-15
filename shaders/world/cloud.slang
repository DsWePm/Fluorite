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

static const float HIGH_CLOUD_PATCH_TAU_RANGE = 4.0;

// The march's step bounds, in blocks. The step is now chosen from how far THIS RAY crosses the deck
// rather than from how far the deck is, so a grazing crossing is spread over the budget instead of being
// swallowed by a handful of enormous strides. See marchLayer.
static const float CLOUD_STEP_MIN = 24.0;
static const float CLOUD_STEP_MAX = 400.0;

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

/**
 * Shared geometry and authored parameters for the low volume and high sheets.
 *
 * <p>The low instance is the only one accepted by cloudDensity() and marchLayer(). The high instance is
 * only a compact carrier for two spherical-sheet intersections, texture scale, weather forcing and
 * optical coefficients. Keeping the authored data together does not imply a shared estimator.
 */
struct CloudLayer {
    float bottom;
    float thickness;
    float coverageBias;
    float extinction;
    float baseScale;
    float detailScale;
    float fieldScale;
    float densityScale;
    // Where this field sits, in absolute blocks: terrain rebase minus this field's own global-wind drift.
    float3 origin;
    // A small movement relative to origin, authored on the CPU in the same wind direction. Keeping it
    // separate is what lets cloudlets grow through the macro body without making the weather map boil.
    float2 detailOffset;
    int   sunSteps;
};

float3 worldIn(CloudLayer layer, float3 p) { return p + layer.origin; }

CloudLayer lowLayer() {
    CloudLayer l;
    l.bottom = worldPush.cloudShape.x;
    l.thickness = max(worldPush.cloudShape.y, 1.0);
    l.coverageBias = worldPush.cloudParams.x;
    l.extinction = worldPush.cloudParams.w;
    l.baseScale = max(worldPush.cloudShape.z, 1.0);
    l.detailScale = max(worldPush.cloudShape.w, 1.0);
    l.fieldScale = max(worldPush.cloudRebase.w, 1.0);
    l.densityScale = worldPush.cloudParams.y;
    l.origin = worldPush.cloudRebase.xyz;
    l.detailOffset = worldPush.cloudEvolution.xy;
    l.sunSteps = clamp(int(worldPush.cloudLighting.z + 0.5), 0, 8);
    return l;
}

/**
 * The high-cloud sheet, placed above the convective deck rather than at an authored absolute altitude.
 *
 * <p>The clamp is what keeps the two shells DISJOINT, and that is what makes ordering them by entry
 * distance correct: with the high sheet strictly outside the low volume, a ray meets one region entirely
 * before the other from every origin and direction. Overlap would require depth-interleaved estimators,
 * and getting that wrong shows up as one cloud drawn in front from some angles and behind from others.
 */
CloudLayer highLayer() {
    CloudLayer l;
    CloudLayer low = lowLayer();
    // Still the reference thickness the sheet's optical depth is authored against. It no longer also
    // means the separation between two sheets, because there is no second sheet to separate from.
    l.thickness = max(worldPush.cloudHigh.y, 1.0);
    l.bottom = max(worldPush.cloudHigh.x, low.bottom + low.thickness + 16.0);
    l.coverageBias = worldPush.cloudHigh.z;
    l.extinction = worldPush.cloudHigh.w;
    // Authored, not derived. These lanes now mean patch diameter and patch spacing.
    l.baseScale = max(worldPush.cloudHighShape.x, 1.0);
    l.detailScale = 1.0; // unused: the sheet is sprites on a grid, not a periodic field with a span
    l.fieldScale = max(worldPush.cloudHighOrigin.w, 1.0);
    l.densityScale = worldPush.cloudHighShape.z;
    l.origin = worldPush.cloudHighOrigin.xyz;
    l.detailOffset = float2(0.0, 0.0); // D163 high clouds are two 2D sheets, not evolving 3D detail
    // High sheets never enter cloudBudget()/marchLayer(); initialise the shared carrier defensively.
    l.sunSteps = 0;
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
    float2 w = worldIn(layer, p).xz / layer.fieldScale;
    float m = cloudWeather.SampleLevel(w, 0.0).r;
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
    float2 w = worldIn(lowLayer(), p).xz / cloudTypeScale();
    float m = cloudWeather.SampleLevel(w, 0.0).g;
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
 * steps along the majority of rays; coverage is one 2D fetch into the weather map and rejects most of what survives; type is a
 * second such fetch and cannot reject anything by itself, so it is deliberately NOT hoisted above the height
 * test even though the height profile now takes it as an argument. Hoisting it would charge every empty
 * step in the sky for a texture read that only matters inside a cloud.
 */
/** Cumulus and cumulonimbus get rounded sub-cells; stratus deliberately remains a broad sheet. */
float lowCloudPuffWeight(float type) {
    return smoothstep(0.12, 0.48, type) * lerp(0.55, 0.80, saturate(type));
}

/**
 * The macro body and its smaller cloudlets are two samples of our own baked Perlin-Worley field.
 *
 * <p>The second sample is not a cross-fade between unrelated frames. It is a persistent world-space
 * structure with a slightly different advection speed, so lobes enter and leave the macro envelope
 * continuously. This function belongs only to the low volumetric deck; high clouds use the two
 * relightable optical-depth textures below.
 */
float evolvingCloudShape(CloudLayer layer, float3 pw, float type, float footprint) {
    float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);
    float macro = cloudNoise.SampleLevel(pw / layer.baseScale,
                                        cloudNoiseLod(footprint, layer.baseScale)).r;
    float puffWeight = lowCloudPuffWeight(type);
    if (puffWeight <= 0.0) {
        return macro;
    }
    float puffs = cloudNoise.SampleLevel(detailPw / (layer.baseScale * 0.36),
                                        cloudNoiseLod(footprint, layer.baseScale * 0.36)).r;
    // Zero-mean displacement keeps coverage stable while moving the iso-density boundary into many
    // small rounded protrusions and hollows. The bound (0.34) is below the macro field's full range, so
    // the weather map remains the owner of where a cloud system exists.
    return saturate(macro + (puffs - 0.5) * (0.34 * puffWeight));
}

/**
 * Which mip of the noise a sample of the given footprint should read.
 *
 * <p>PREFILTERING, which is the half of the distant-cloud fix that adding steps cannot do. A step is a
 * segment of the ray, and point-sampling a field whose features are finer than that segment is plain
 * aliasing -- the sample says "cloud" or "no cloud" with nothing in between, and neighbouring samples
 * disagree violently. Reading a mip whose texels are the size of the step replaces that point sample with
 * the field's average over it, which is the quantity the integral actually wants.
 *
 * <p>The high-cloud patches have selected a mip by ray distance since D163; this is the low deck finally
 * doing the same thing. It is also why the step bound above matters: prefiltering to a step LARGER than a
 * cloud averages whole clouds away, so the two have to be fixed together or neither works.
 *
 * <p>A NAMED APPROXIMATION. The correct prefilter for a medium is not a linear average of density --
 * transmittance is exp(-tau), and the mean of an exponential is not the exponential of the mean, which is
 * why the high-cloud KTX2 mips average exp(-tau) and convert back. That cannot be done here: this volume
 * is a shape field that only becomes a density after coverage, the height profile and erosion have been
 * applied, so there is no optical depth at bake time to preserve. A linear mip chain is what the technique
 * uses in practice and it is recorded as an approximation rather than presented as the exact thing.
 */
float cloudNoiseLod(float footprint, float scale) {
    // texels of this volume, at the world scale this particular lookup uses
    float blocksPerTexel = max(scale, 1.0) / float(CLOUD_NOISE_DIM);
    return max(0.0, log2(max(footprint / blocksPerTexel, 1.0)));
}

/**
 * Displace WHERE the density field is read, so the cloud's surface itself folds.
 *
 * <p>THIS IS NOT EROSION AND THE DIFFERENCE IS THE WHOLE POINT. Subtracting a noise from the density
 * removes material from a boundary: the cloud gets thinner and its edge frays, but the boundary is still
 * the base field's shape, which is why a deck driven by erosion alone reads as rounded blobs however hard
 * the erosion is pushed. Moving the sample position moves the iso-surface, so it grows folds, protrusions
 * and hollows that the base field never contained.
 *
 * <p>ITS OWN CLOCK (cloudWarp.z), and that is what makes cloud GROW rather than merely pass by. The shape
 * field advects with the layer origin, so a warp locked to it would deform each cloud once, permanently,
 * and then translate it. Walking the lookup along the warp volume's vertical axis steps through slices
 * that are uncorrelated with one another, so the displacement at a fixed point in a fixed cloud simply
 * becomes a different displacement: lobes swell and subside where they stand.
 *
 * <p>NOT a horizontal drift, which is what this tried first. Sliding the warp sideways makes the
 * deformation pattern sweep across the cloud, and a sweep reads as translation -- the very thing the warp
 * exists to stop being the only motion in the sky. Horizontally the warp now travels WITH the shape.
 *
 * <p>STRONGER HIGH IN THE CLOUD. A cumulus is turbulent where it is rising and flat where it is not, so
 * the warp is faded in with height through the deck. Warping the base as hard as the top would lift the
 * flat bottoms that make a cloud read as sitting on a layer of air.
 *
 * <p>Zero amplitude returns the position untouched, without the fetch -- bit-for-bit the field before this.
 */
float3 cloudWarpPosition(CloudLayer layer, float3 pw) {
    float amplitude = worldPush.cloudWarp.x;
    if (amplitude <= 0.0) {
        return pw;
    }
    float scale = max(worldPush.cloudWarp.y, 1.0);
    float3 wp = pw + float3(0.0, worldPush.cloudWarp.z, 0.0);
    float3 warp = cloudWarp.SampleLevel(wp / scale, 0.0).xyz * 2.0 - 1.0;
    // Normalised height through the deck, faded so the base keeps its flat underside.
    float h = saturate((pw.y - layer.bottom) / max(layer.thickness, 1.0));
    return pw + warp * (amplitude * smoothstep(0.05, 0.55, h));
}

float cloudDensity(CloudLayer layer, float3 p, bool cheap, float footprint) {
    float3 pw = worldIn(layer, p);
    if (pw.y < layer.bottom || pw.y > layer.bottom + layer.thickness) {
        return 0.0; // outside the deck: no type can put cloud here
    }
    float coverage = cloudCoverage(layer, p);
    if (coverage <= 0.0) {
        return 0.0;
    }
    float type = cloudType(p);
    float profile = cloudHeightProfile(layer, pw.y, type);
    if (profile <= 0.0) {
        return 0.0;
    }
    float shape = evolvingCloudShape(layer, cloudWarpPosition(layer, pw), type, footprint);
    // Coverage raises the whole field rather than masking it, so a cloud grows from its centre outward
    // instead of appearing with hard edges where the mask crosses a threshold.
    float density = saturate(shape + coverage - 1.0) * profile;
    // Type raises density as well as height, because a cumulonimbus is not a tall cumulus — it is a tall
    // cumulus that light struggles to get through, and that opacity is most of what makes a storm read as
    // one from underneath. Applied AFTER the erosion below rather than here: the erosion term is written
    // against a density normalised to [0,1] and would invert if a gain pushed its argument past one.
    float gain = layer.densityScale * (1.0 + type * 1.6);
    if (density <= 0.0 || cheap) {
        return density * gain;
    }
    float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);
    float erosion = cloudNoise.SampleLevel(detailPw / layer.detailScale,
                                          cloudNoiseLod(footprint, layer.detailScale)).g;
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
    // ONE DECK THICKNESS MEASURED VERTICALLY, not one deck thickness along the ray.
    //
    // The reach used to be a fixed distance in the light's direction, which is right only for a sun
    // overhead. Sunlight arriving at elevation theta crosses H/sin(theta) of deck, so a fixed reach of H
    // integrates a sin(theta) FRACTION of the path: half of it at 30 degrees, a sixth at 10, a twelfth at
    // 5. Clouds were therefore least self-shadowed exactly at sunrise and sunset, where they are most
    // back-lit and where the error is most visible -- the lit face keeps a brightness it has not earned.
    //
    // The same file already does this correctly one function away: the high sheet converts its vertical
    // optical depth with a sun secant and caps it. This is that, on the deck that marches.
    //
    // PAID IN STEPS, NOT IN RESOLUTION. With a doubling schedule, reaching 2^k further costs exactly k
    // more steps at the SAME s0, so the near field -- where the boundary between a cloud's lit and shaded
    // face is decided, and the reason the first step is short -- is sampled exactly as finely as before.
    // Scaling s0 instead would have bought the reach by blurring the one part that has to stay sharp.
    // Capped at three doublings, so a sun on the horizon costs three extra density samples rather than an
    // unbounded march through air that holds no cloud.
    float secant = 1.0 / max(abs(lightDir.y), 0.125); // 0.125 -> the cap below binds first anyway
    int extra = int(clamp(ceil(log2(max(secant, 1.0))), 0.0, 3.0));
    // Overhead sun: secant is 1, extra is 0, and this is the schedule that shipped, step for step.
    int totalSteps = steps + extra;
    // Jittered start for the same reason the primary march jitters: an unjittered shadow lattice draws
    // itself as bands across a cloud's shaded face.
    float jitter = rndf(seed);
    float tau = 0.0;
    float t = 0.0;
    float step = s0;
    for (int i = 0; i < totalSteps; ++i) {
        // Sample at the step's midpoint, offset by the jitter — midpoint because a one-sample estimate of
        // an interval's average is least wrong there.
        float3 q = p + lightDir * (t + step * (0.35 + 0.3 * jitter));
        tau += cloudDensity(layer, q, true, step) * step;
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
    tExit = min(tExit, tEnter + CLOUD_MAX_DIST);

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

            // How buried this sample is, from the light's side and from the sky's.
            float tauSun = budget.sunSteps > 0
                    ? sunOpticalDepth(layer, p, lightDir, budget.sunSteps, marchSeed) : 0.0;
            // The sky's occlusion, without a second march: how much of THIS cloud stands above the
            // sample, taking the local density as representative of the column. Bounded by the type's own
            // profile rather than by the deck, so a stratus sheet is not buried under the empty three
            // hundred blocks a cumulonimbus would have filled.
            float ownTop = layer.bottom + cloudProfileTop(cloudType(p)) * layer.thickness;
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

float3 highCloudSheetNormal(float altitude, float3 ro, float3 rd, float t) {
    float3 centreRelative = ro + float3(0.0, PLANET_R + worldPush.cloudRebase.y, 0.0);
    return normalize(centreRelative + rd * t);
}

uint highCloudCellHash(int2 cell) {
    uint h = asuint(cell.x) * 0x8DA6B343u;
    h ^= asuint(cell.y) * 0xD8163841u;
    h ^= 0xCB1AB31Fu;
    return pcg(h);
}

float highCloudHash01(inout uint state) {
    state = pcg(state);
    return float(state >> 8u) * (1.0 / 16777216.0);
}

/**
 * Lower high-cloud sheet: deterministic texture bombing over a fixed world grid.
 *
 * <p>The 3x3 loop is a candidate search, not nine unconditional texture reads. Hash arithmetic rejects
 * every sprite whose rotated square does not contain this point before SampleLevel executes; at the
 * approved diameter/spacing the ordinary path reads one texture, occasionally two near an overlap.
 * No ray seed enters this function, so primary, reflection and later-frame rays ask the same world field.
 */
float highCloudPatchShape(float2 worldXZ, float t, CloudLayer high) {
    float spacing = max(high.fieldScale, 1.0);
    float2 gridPosition = worldXZ / spacing;
    int2 baseCell = int2(floor(gridPosition));
    float tau = 0.0;
    [unroll]
    for (int dz = -1; dz <= 1; ++dz) {
        [unroll]
        for (int dx = -1; dx <= 1; ++dx) {
            int2 cell = baseCell + int2(dx, dz);
            uint hash = highCloudCellHash(cell);
            float2 jitter = float2(highCloudHash01(hash), highCloudHash01(hash)) - 0.5;
            float2 centre = (float2(cell) + 0.5 + jitter * 0.56) * spacing;
            float scale = lerp(0.75, 1.25, highCloudHash01(hash));
            float diameter = max(high.baseScale * scale, 1.0);
            float angle = highCloudHash01(hash) * (2.0 * PI);
            float cosine = cos(angle);
            float sine = sin(angle);
            float2 delta = worldXZ - centre;
            float2 local = float2(cosine * delta.x + sine * delta.y,
                                  -sine * delta.x + cosine * delta.y);
            float2 uv = local / diameter + 0.5;
            if (all(uv > 0.0) && all(uv < 1.0)) {
                float layer = float(hash % 10u);
                float lod = clamp(log2(max(t / diameter, 1.0)), 0.0, 10.0);
                tau += highCloudPatchTau.SampleLevel(float3(uv, layer), lod).r
                        * HIGH_CLOUD_PATCH_TAU_RANGE;
            }
        }
    }
    return max(tau + high.coverageBias, 0.0);
}

/** Exact Beer integration through one thin sheet with the shared cloud Radiance sources. */
CloudResult shadeHighCloudSheet(float verticalTau, float altitude,
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
CloudResult traceHighClouds(float3 ro, float3 rd, float tMax) {
    CloudLayer high = highLayer();
    if (!layerEnabled(high)) {
        return noClouds();
    }
    float altitude = high.bottom;
    float t = highCloudSheetIntersection(altitude, ro, rd);
    if (t < 0.0 || t > tMax) {
        return noClouds();
    }
    float2 worldXZ = worldIn(high, ro + rd * t).xz;
    float shapeTau = highCloudPatchShape(worldXZ, t, high);
    float verticalTau = shapeTau * high.extinction * high.thickness * 0.5
            * high.densityScale * max(worldPush.cloudHighShape.w, 0.0);
    return shadeHighCloudSheet(verticalTau, altitude, ro, rd, t);
}

float highCloudFirstIntersection(float3 ro, float3 rd) {
    CloudLayer high = highLayer();
    if (!layerEnabled(high)) return CLOUD_MAX_DIST + 1.0;
    float t = highCloudSheetIntersection(high.bottom, ro, rd);
    return t < 0.0 ? CLOUD_MAX_DIST + 1.0 : t;
}

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
    float highEnter = highCloudFirstIntersection(ro, rd);

    CloudResult lowResult = marchLayer(low, cloudBudget(low, secondary), ro, rd, tMax, marchSeed);
    CloudResult highResult = traceHighClouds(ro, rd, tMax);
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
        float3 pw = worldIn(low, p);
        if (pw.y >= low.bottom && pw.y <= low.bottom + low.thickness) {
            inDeck += 1.0;
            float type = cloudType(p);
            maxCoverage = max(maxCoverage, cloudCoverage(low, p));
            maxProfile = max(maxProfile, cloudHeightProfile(low, pw.y, type));
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
