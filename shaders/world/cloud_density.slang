// The cloud DENSITY FIELD: everything that decides how much cloud stands at a point, and nothing else.
//
// NO BINDINGS AND NO worldPush, ON PURPOSE, and the reason is the same one water_wave.slang gives. The
// shading holds world_core's descriptor set; the shadow-map bake is a compute pass with its own. Neither
// can import a module that reaches for set 0 without declaring a whole descriptor layout it never uses.
// So everything here is arithmetic over its arguments, and each caller wraps it with whatever resources
// it happens to hold.
//
// THE POINT IS NOT TIDINESS. A shadow is only correct if it is cast by the same density the cloud is drawn
// from. Two implementations of this field would agree on the day they were written and diverge the first
// time one of them was edited alone, and the symptom -- a shadow that is not under its cloud -- is not
// something anyone can attribute from a screenshot. Splitting the field out is what makes agreement
// structural rather than a thing to remember.
//
// CloudLayer carries the authored values these functions used to read out of worldPush directly, so the
// caller fills it once and the field never reaches past its own arguments.

module cloud_density;

import cloud_field;    // CLOUD_NOISE_DIM: the LOD below is in texels of that volume
import world_common;   // rndf, pcg, and WorldPush. Structs only -- no bindings, so a bake may import it

// Local rather than imported: PI lives in world_core, and world_core owns descriptor bindings. Same
// reason water_wave.slang and world.rmiss.slang each keep their own copy. Not public, so a file that
// imports both this and world_core still sees exactly one.
static const float PI = 3.14159265359;

// The fields the cloud is built from. Bundled so that adding one is a single signature change rather
// than a dozen, and so a caller cannot supply them in the wrong order.
public struct CloudTextures {
    public Sampler3D noise;     // R shape, G erosion, B type, A unused -- see cloud_noise.comp.slang
    public Sampler2D weather;   // R coverage, G type -- see cloud_weather.comp.slang
    public Sampler3D warp;      // curl noise, signed in RGB -- see cloud_warp.comp.slang
    public Sampler2DArray highTau;  // the high sheet's ten CC0 patch masks, as optical depth
};

// The stratus band's upper support, as a fraction of the deck. Named because TWO things depend on it:
// cloudHeightProfile draws the sheet up to here, and cloudDepthFraction uses it as the floor on how
// shallow any cloud may be. If the profile's stratus band moves, this moves with it.
public static const float CLOUD_STRATUS_TOP = 0.18;

/**
 * Shared geometry and authored parameters for the low volume and high sheets.
 *
 * <p>The low instance is the only one accepted by cloudDensity() and marchLayer(). The high instance is
 * only a compact carrier for two spherical-sheet intersections, texture scale, weather forcing and
 * optical coefficients. Keeping the authored data together does not imply a shared estimator.
 */
public struct CloudLayer {
    public float bottom;
    public float thickness;
    public float coverageBias;
    public float extinction;
    public float baseScale;
    public float detailScale;
    public float fieldScale;
    public float densityScale;
    // Where this field sits, in absolute blocks: terrain rebase minus this field's own global-wind drift.
    public float3 origin;
    // A small movement relative to origin, authored on the CPU in the same wind direction. Keeping it
    // separate is what lets cloudlets grow through the macro body without making the weather map boil.
    public float2 detailOffset;
    public int   sunSteps;
    // What the functions below used to reach into worldPush for. They are here so that the field is a
    // pure function of its arguments, which is what lets a compute bake evaluate the SAME density the
    // raygen shades -- see this file's banner.
    public float typeScale;
    public float typeBias;
    public float warpAmplitude;
    public float warpScale;
    public float warpClock;
    // High sheet only: how hard the patch masks' optical depth is driven. Carried rather than left for
    // each caller to multiply in, because the shading and the shadow bake reading a sheet at two
    // different strengths is the same undiagnosable disagreement the banner is about.
    public float highTauStrength;
};

public float3 worldIn(CloudLayer layer, float3 p) { return p + layer.origin; }

// How far apart the sky's clumps and clearings are, in blocks -- the FIELD, distinct from how big a
// single cloud within a clump is. Cloud TYPE varies far more slowly still: a storm covers a region, it
// does not alternate with fair weather every kilometre, so its span is pinned to a multiple of the
// field's rather than authored separately.
float cloudFieldScale(WorldPush wp) { return max(wp.cloudRebase.w, 1.0); }
float cloudTypeScale(WorldPush wp) { return cloudFieldScale(wp) * 2.9; }

/**
 * The low convective deck, read out of the frame's authored parameters.
 *
 * <p>HERE RATHER THAN IN THE SHADING, and for the same reason the field itself is. A shadow cast by a
 * deck 300 blocks thick onto ground lit by a deck 400 blocks thick is wrong in a way no screenshot can
 * attribute, and two constructors reading the same lanes would agree exactly until one of them was
 * edited. WorldPush arrives as an ARGUMENT: the struct is declared in world_common and carries no
 * bindings, so a compute bake reads it through a device address while the raygen passes its global.
 * That is the whole distinction this module's banner draws -- no descriptor set, not no data.
 */
public CloudLayer lowCloudLayer(WorldPush wp) {
    CloudLayer l;
    l.bottom = wp.cloudShape.x;
    l.thickness = max(wp.cloudShape.y, 1.0);
    l.coverageBias = wp.cloudParams.x;
    l.extinction = wp.cloudParams.w;
    l.baseScale = max(wp.cloudShape.z, 1.0);
    l.detailScale = max(wp.cloudShape.w, 1.0);
    l.fieldScale = cloudFieldScale(wp);
    l.densityScale = wp.cloudParams.y;
    l.origin = wp.cloudRebase.xyz;
    l.detailOffset = wp.cloudEvolution.xy;
    l.sunSteps = clamp(int(wp.cloudLighting.z + 0.5), 0, 8);
    l.typeScale = cloudTypeScale(wp);
    l.typeBias = wp.cloudParams.z;
    l.warpAmplitude = wp.cloudWarp.x;
    l.warpScale = wp.cloudWarp.y;
    l.warpClock = wp.cloudWarp.z;
    l.highTauStrength = 0.0; // the low deck marches a density; it has no sheet
    return l;
}

/**
 * The high sheet, placed above the convective deck rather than at an authored absolute altitude.
 *
 * <p>The clamp is what keeps the two shells DISJOINT, and that is what makes ordering them by entry
 * distance correct: with the high sheet strictly outside the low volume, a ray meets one region entirely
 * before the other from every origin and direction. Overlap would require depth-interleaved estimators,
 * and getting that wrong shows up as one cloud drawn in front from some angles and behind from others.
 */
public CloudLayer highCloudLayer(WorldPush wp) {
    CloudLayer l = lowCloudLayer(wp);
    CloudLayer low = l;
    // Still the reference thickness the sheet's optical depth is authored against. It no longer also
    // means the separation between two sheets, because there is no second sheet to separate from.
    l.thickness = max(wp.cloudHigh.y, 1.0);
    l.bottom = max(wp.cloudHigh.x, low.bottom + low.thickness + 16.0);
    l.coverageBias = wp.cloudHigh.z;
    l.extinction = wp.cloudHigh.w;
    // Authored, not derived. These lanes now mean patch diameter and patch spacing.
    l.baseScale = max(wp.cloudHighShape.x, 1.0);
    l.detailScale = 1.0; // unused: the sheet is sprites on a grid, not a periodic field with a span
    l.fieldScale = max(wp.cloudHighOrigin.w, 1.0);
    l.densityScale = wp.cloudHighShape.z;
    l.origin = wp.cloudHighOrigin.xyz;
    l.detailOffset = float2(0.0, 0.0); // D163 high clouds are one 2D sheet, not evolving 3D detail
    l.highTauStrength = max(wp.cloudHighShape.w, 0.0);
    // High sheets never enter cloudBudget()/marchLayer(); initialise the shared carrier defensively.
    l.sunSteps = 0;
    return l;
}

/**
 * How deep THIS cloud is, as a fraction of the authored maximum.
 *
 * <p>FLAT BASES, VARYING TOPS, which is the shape the atmosphere actually produces. A cloud's underside
 * sits at the lifting condensation level -- the altitude where rising air reaches saturation -- and that
 * altitude is a property of the air mass, so it is the same across a region. That is why a field of
 * cumulus has famously level bases. A cloud's TOP is wherever that particular parcel ran out of buoyancy,
 * and that differs from one cell to the next. So the authored thickness is a CEILING rather than a
 * height everything is issued with.
 *
 * <p>DRIVEN BY COVERAGE, which needs no new field and is not a convenience. Vigorous convection makes a
 * cloud both deeper and broader, so depth and coverage are correlated in the real thing. The by-product is
 * the part worth having: coverage falls off toward the edge of a cloud system, so its clouds are shallow at
 * the fringe and deep in the middle -- a domed top thinning to the sides, which is what a cumulus looks
 * like from the ground.
 *
 * <p>THE FLOOR IS THE STRATUS SHEET, and that is where the physics stops rather than a number chosen to
 * look right. A stratus band is the thinnest thing this profile system can represent: below it there is no
 * shape left to have, only a slab getting thinner, and a cloud field of slabs is what this whole change
 * exists to get away from. Expressed relative to the type's own top so the floor means the same depth in
 * blocks whichever family stands here.
 */
public float cloudDepthFraction(float coverage, float type) {
    float top = max(cloudProfileTop(type), 1.0e-3);
    return lerp(saturate(CLOUD_STRATUS_TOP / top), 1.0, saturate(coverage));
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
public float cloudHeightProfile(CloudLayer layer, float altitude, float type, float depth) {
    // Dividing the DEPTH rather than offsetting the base is what keeps the underside flat: t still starts
    // at zero on the deck floor, it just reaches the profile's top sooner.
    float t = saturate((altitude - layer.bottom) / (layer.thickness * max(depth, 1.0e-3)));
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
public float cloudProfileTop(float type) {
    return type < 0.5 ? lerp(0.18, 0.62, saturate(type * 2.0))
                      : lerp(0.62, 1.0, saturate(type * 2.0 - 1.0));
}

/**
 * Coverage, from a world-anchored slice of the same volume.
 *
 * <p>Anchored to world coordinates rather than to anything view-dependent, so a cloud stays over the
 * same ground as the ray that finds it moves — which is the property the whole file exists to protect.
 */
public float cloudCoverage(CloudTextures tex, CloudLayer layer, float3 p) {
    float2 w = worldIn(layer, p).xz / layer.fieldScale;
    float m = tex.weather.SampleLevel(w, 0.0).r;
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
public float cloudType(CloudTextures tex, CloudLayer layer, float3 p) {
    float2 w = worldIn(layer, p).xz / layer.typeScale;
    float m = tex.weather.SampleLevel(w, 0.0).g;
    // Scaled below 1 so fair weather stays in the sheet-to-heap half of the range and the tower is
    // something the weather has to ask for.
    return saturate(m * 0.75 + layer.typeBias);
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
public float lowCloudPuffWeight(float type) {
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
float evolvingCloudShape(CloudTextures tex, CloudLayer layer, float3 pw, float type, float footprint) {
    float3 detailPw = pw + float3(layer.detailOffset.x, 0.0, layer.detailOffset.y);
    float macro = tex.noise.SampleLevel(pw / layer.baseScale,
                                        cloudNoiseLod(footprint, layer.baseScale)).r;
    float puffWeight = lowCloudPuffWeight(type);
    if (puffWeight <= 0.0) {
        return macro;
    }
    float puffs = tex.noise.SampleLevel(detailPw / (layer.baseScale * 0.36),
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
public float cloudNoiseLod(float footprint, float scale) {
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
float3 cloudWarpPosition(CloudTextures tex, CloudLayer layer, float3 pw) {
    float amplitude = layer.warpAmplitude;
    if (amplitude <= 0.0) {
        return pw;
    }
    float scale = max(layer.warpScale, 1.0);
    float3 wp = pw + float3(0.0, layer.warpClock, 0.0);
    float3 warp = tex.warp.SampleLevel(wp / scale, 0.0).xyz * 2.0 - 1.0;
    // Normalised height through the deck, faded so the base keeps its flat underside.
    float h = saturate((pw.y - layer.bottom) / max(layer.thickness, 1.0));
    return pw + warp * (amplitude * smoothstep(0.05, 0.55, h));
}

public float cloudDensity(CloudTextures tex, CloudLayer layer, float3 p, bool cheap, float footprint) {
    float3 pw = worldIn(layer, p);
    if (pw.y < layer.bottom || pw.y > layer.bottom + layer.thickness) {
        return 0.0; // outside the deck: no type can put cloud here
    }
    float coverage = cloudCoverage(tex, layer, p);
    if (coverage <= 0.0) {
        return 0.0;
    }
    float type = cloudType(tex, layer, p);
    float profile = cloudHeightProfile(layer, pw.y, type, cloudDepthFraction(coverage, type));
    if (profile <= 0.0) {
        return 0.0;
    }
    float shape = evolvingCloudShape(tex, layer, cloudWarpPosition(tex, layer, pw), type, footprint);
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
    float erosion = tex.noise.SampleLevel(detailPw / layer.detailScale,
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
public float sunOpticalDepth(CloudTextures tex, CloudLayer layer, float3 p, float3 lightDir,
                             int steps, inout uint seed) {
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
        tau += cloudDensity(tex, layer, q, true, step) * step;
        t += step;
        step *= 2.0;
    }
    return tau * layer.extinction;
}

// ---------------------------------------------------------------------------------------------------
// The high sheet. Not a density -- an optical depth field on an infinitesimally thin shell, which is why
// it has its own pair of functions rather than another branch inside cloudDensity.

// What a fully opaque patch texel means, in optical depth. The masks are stored normalised.
static const float HIGH_CLOUD_PATCH_TAU_RANGE = 4.0;

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
 * Deterministic texture bombing over a fixed world grid.
 *
 * <p>The 3x3 loop is a candidate search, not nine unconditional texture reads. Hash arithmetic rejects
 * every sprite whose rotated square does not contain this point before SampleLevel executes; at the
 * approved diameter/spacing the ordinary path reads one texture, occasionally two near an overlap.
 * No ray seed enters this function, so primary, reflection, later-frame and SHADOW-BAKE lookups all ask
 * the same world field -- which is what lets the shadow map be built from it without a second opinion.
 *
 * @param footprint how wide an area this sample stands for, in blocks: a ray's distance for the shading,
 *                  a texel's width for the shadow bake. Only selects a mip.
 */
public float highCloudPatchShape(CloudTextures tex, CloudLayer high, float2 worldXZ, float footprint) {
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
                float lod = clamp(log2(max(footprint / diameter, 1.0)), 0.0, 10.0);
                tau += tex.highTau.SampleLevel(float3(uv, layer), lod).r * HIGH_CLOUD_PATCH_TAU_RANGE;
            }
        }
    }
    return max(tau + high.coverageBias, 0.0);
}

/**
 * The sheet's optical depth measured STRAIGHT DOWN through it, before any ray's or the sun's secant.
 *
 * <p>Assembled here rather than at each call site so that the shading and the shadow bake cannot end up
 * multiplying the same mask by different strengths -- which would put a cirrus shadow on the ground with
 * no cirrus above it, or the reverse.
 */
public float highCloudVerticalTau(CloudTextures tex, CloudLayer high, float3 p, float footprint) {
    float shapeTau = highCloudPatchShape(tex, high, worldIn(high, p).xz, footprint);
    return shapeTau * high.extinction * high.thickness * 0.5 * high.densityScale * high.highTauStrength;
}

// ---------------------------------------------------------------------------------------------------
// D176: the light-space frame the cloud shadow map is baked into and read back out of.
//
// LIGHT SPACE, WHICH IS THE ONE CHOICE THAT SURVIVES TERRAIN. A cloud's shadow lands a long way from the
// cloud -- 655 blocks at a 30-degree sun, 2144 at 10 -- so a map parameterised by world XZ has to assume
// a receiving altitude, and every block the real receiver stands above it displaces the shadow by
// h/tan(elevation): 173 blocks at 30 degrees for a hundred-block hill, 373 at 15. Minecraft terrain
// clears a hundred blocks without trying, and a mountainside is exactly where a moving cloud shadow is
// worth having. Projecting along the light instead makes the lookup independent of how far along the
// light ray the receiver sits, so there is no reference altitude to be wrong about. It costs one dot
// product more per lookup.
//
// The frame is DERIVED, not pushed. Both the bake and every consumer call the function below with lanes
// they already hold, so there is no third copy on the CPU to drift out of step and no new push lane.
//
// WHERE THE FIXED TEXEL SIZE STANDS AGAINST THE PHYSICS, in numbers, because "16 blocks is about the
// penumbra" is only true at one sun elevation and it is worth knowing which way it errs at the others.
// A cloud sits about 378 blocks up, so its shadow's penumbra measured ACROSS the light is
// 378/sin(elevation) * 0.0209 = 7.9/sin(elevation) blocks, and the texel is a flat 16. The ratio is
// therefore 2.0*sin(elevation): 1.75 at a 60-degree sun, 1.0 at 30, 0.35 at 10, 0.18 at 5.
//
// So the map is AT PARITY at a 30-degree sun, at worst about 1.75x softer than physical when the sun is
// high, and steadily sharper than it needs to be as the sun drops. The low-sun end looks alarming in
// world coordinates -- a texel covers 16/sin = 184 blocks of ground at 5 degrees -- but the shadow is
// stretched by exactly the same 1/sin over the same ground, which is the property light space has and
// world XZ does not. There is no cascade to add here and D176 ruled them out for the separate reason
// that a cloud shadow has no near detail to gain from one.
//
// The one direction this is genuinely lossy is a HIGH sun, where the fixed texel blurs a 9-block
// penumbra to 16. That is a decision to revisit with a smaller texel, not a bug to fix here.

public static const int CLOUD_SHADOW_DIM = 512;
// 16 blocks a texel, 8192 blocks across. Not a resolution chosen for looks: the sun's 0.6-degree angular
// radius already makes a real cloud shadow's penumbra about 16 blocks wide at a 30-degree sun, so a
// finer map would be resolving a sharpness the physics does not contain. It is also why the bake takes
// ONE sun sample -- the texel is already softer than the penumbra.
public static const float CLOUD_SHADOW_BLOCKS_PER_TEXEL = 16.0;
public static const float CLOUD_SHADOW_EXTENT = CLOUD_SHADOW_BLOCKS_PER_TEXEL * float(CLOUD_SHADOW_DIM);
// Below this the sun is within about a degree of the horizon, where its own transmittance has taken the
// light to nothing and the crossing this map would integrate is fifty deck thicknesses long. The map
// reports clear sky there rather than spending the march to prove it.
public static const float CLOUD_SHADOW_MIN_SUN_Y = 0.02;

/** Bit 30 (Rt.Clouds.ENABLED), which RtComposite sets only when the preset AND the toggle allow it. */
public bool cloudsOn(WorldPush wp) {
    return (wp.flags & (1u << 30u)) != 0u;
}

public struct CloudShadowFrame {
    public float3 right;      // horizontal
    public float3 up;         // carries all of the sun's tilt
    public float3 lightDir;   // toward the sun
    public float2 origin;     // light-space coordinates of texel (0,0)'s corner
    public bool   valid;      // false when the sun is at or below the horizon
};

/**
 * @param lightDir toward the sun, normalised
 * @param camera   the rebased position the map centres on
 */
public CloudShadowFrame cloudShadowFrame(float3 lightDir, float3 camera) {
    CloudShadowFrame f;
    f.lightDir = lightDir;
    // A HORIZONTAL first axis, so u runs along the ground and v carries the whole tilt. The alternative
    // basis is not wrong, only harder to reason about; the fallback branch matters solely for a sun
    // exactly overhead, where the cross product vanishes and any horizontal axis is as good as another.
    float3 axis = abs(lightDir.y) > 0.999 ? float3(1.0, 0.0, 0.0) : float3(0.0, 1.0, 0.0);
    f.right = normalize(cross(lightDir, axis));
    f.up = cross(f.right, lightDir);
    // SNAPPED TO WHOLE TEXELS, for R22's reason: a grid that slides continuously resamples the density
    // field at a different phase every frame, and a field this noisy at texel scale shows that as
    // crawling rather than as motion. Snapping removes the camera's contribution to the slide. It does
    // NOT remove the sun's -- the basis itself rotates as the sun moves, so the far edge of the map
    // still travels about a texel a second -- and that is accepted rather than overlooked, because the
    // shadows themselves are crossing the ground nine blocks a second at the same time.
    float2 centre = float2(dot(camera, f.right), dot(camera, f.up));
    f.origin = floor(centre / CLOUD_SHADOW_BLOCKS_PER_TEXEL) * CLOUD_SHADOW_BLOCKS_PER_TEXEL
            - 0.5 * CLOUD_SHADOW_EXTENT;
    f.valid = lightDir.y > CLOUD_SHADOW_MIN_SUN_Y;
    return f;
}

/** Where a rebased world position falls on the map. Outside [0,1]^2 means the map does not cover it. */
public float2 cloudShadowUv(CloudShadowFrame f, float3 p) {
    float2 ls = float2(dot(p, f.right), dot(p, f.up));
    return (ls - f.origin) / CLOUD_SHADOW_EXTENT;
}

/**
 * The point texel (x,y) integrates through, on the plane through the rebased origin.
 *
 * <p>WHICH plane does not matter and that is the property being relied on: every point on a given light
 * ray projects to the same texel, so any plane transverse to the light names the same set of rays. The
 * crossing of the deck is then found from altitude in the bake, not from this point's position.
 */
public float3 cloudShadowTexelPoint(CloudShadowFrame f, uint2 texel) {
    float2 ls = f.origin + (float2(texel) + 0.5) * CLOUD_SHADOW_BLOCKS_PER_TEXEL;
    return f.right * ls.x + f.up * ls.y;
}
