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
import world_common;   // rndf. Declares structs only -- no bindings, so a compute bake may import it

// The three fields the density is built from. Bundled so that adding a fourth is one signature change
// rather than a dozen, and so a caller cannot supply them in the wrong order.
public struct CloudTextures {
    public Sampler3D noise;     // R shape, G erosion, B type, A unused -- see cloud_noise.comp.slang
    public Sampler2D weather;   // R coverage, G type -- see cloud_weather.comp.slang
    public Sampler3D warp;      // curl noise, signed in RGB -- see cloud_warp.comp.slang
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
};

public float3 worldIn(CloudLayer layer, float3 p) { return p + layer.origin; }

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
