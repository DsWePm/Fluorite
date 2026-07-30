// Participating media: the tint-to-extinction mappings and the depth-2 medium stack the dielectric
// interface pushes and pops. Depends on core only.

// Per-channel Beer–Lambert extinction from a water body's biome tint (carried in payload.albedo for
// water hits, or worldPush.waterParams.xyz for the camera's own biome when starting submerged). A blue ocean
// tint (low red) absorbs red fastest → bluer with depth; swamp green-brown shifts the hue. The floor
// keeps even a white-tinted body very slightly absorbing so deep water never reads as clear vacuum.

import world_common;
import world_core;

public static const float WATER_DENSITY = 0.1;
public static const float3 WATER_ABSORB_FLOOR = float3(0.015, 0.010, 0.008);

/**
 * Absorption: what the biome tint has always meant, and where the water's colour comes from.
 *
 * Inverted from the tint, which is the part that surprises: a low red tint means red is absorbed
 * fastest, so a swamp's low red and violet read as green and an ocean's low red and green read as blue.
 * The tint and the water's apparent colour are not the same quantity.
 *
 * waterAbsorbOverride.w turns the biome off and substitutes a fixed absorption, which is there to be
 * dialled by eye — this is the channel every visible property of water descends from, so being able to
 * set it directly is the base for tuning any of them.
 */
public float3 waterAbsorption(float3 tint) {
    if (worldPush.waterAbsorbOverride.w > 0.5) {
        return worldPush.waterAbsorbOverride.xyz;
    }
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

/**
 * Total extinction: the tint's absorption plus what the suspended particles scatter.
 *
 * Scattering is added, not carved out of the absorption. Turbidity therefore also costs visibility,
 * which is the first thing anyone notices about murky water, and turbidity 0 is still exactly the
 * absorption-only water this renderer shipped with.
 */
public float3 waterExtinction(float3 tint) {
    return waterAbsorption(tint) + worldPush.waterScatter.xyz;
}

// A non-water dielectric's tint is a filter over one block of travel rather than a biome water colour, so
// it maps to extinction by inverting Beer-Lambert over that reference distance: transmittance t after one
// block means sigma = -ln(t). `transmission` scales that transmittance, so it keeps the sense the
// material's authored factor has everywhere else — 1 passes the tint through unchanged, 0 is opaque.
// Clamped away from zero so a fully saturated texel stays finite instead of producing an infinite sigma.
public static const float VOLUME_TINT_REFERENCE_DISTANCE = 1.0; // blocks
public float3 volumeExtinction(float3 tint, float transmission) {
    float3 blockTransmittance = clamp(clamp(tint, 0.0, 1.0) * clamp(transmission, 0.0, 1.0),
            1.0e-3, 1.0);
    return -log(blockTransmittance) / VOLUME_TINT_REFERENCE_DISTANCE;
}

// ---- Participating medium the path is currently inside.
//
// Refraction needs the RELATIVE index across an interface, so the renderer has to know what it is
// travelling through, not just what it is hitting. `ior` drives Snell/Fresnel; `extinction` drives the
// per-segment Beer-Lambert attenuation.
//
// The stack is depth 2 (current + the one it will return to) held in named fields, NOT an array. A
// dynamically indexed local array lands in scratch memory, and this raygen is already register-bound —
// paying an occupancy hit for nesting that Minecraft does not produce would be a bad trade. Depth 2
// covers air->water->glass and air->glass->water, which is the realistic worst case; anything deeper
// degrades to air on the way out, and because `entering` is re-derived per face from geometry rather
// than toggled, the path re-synchronises at the next crossing instead of staying corrupted.
public struct Medium {
    public float ior;
    public float3 extinction;
    public bool water; // drives the wave-refraction caustic on submerged receivers; nothing else is water-specific
    // Whether this medium is the world's open air, and so subject to the unbounded ambient field —
    // height fog and, later, noise fog and clouds. An explicit bit rather than ior == 1 && extinction ==
    // 0, because a colourless dielectric would answer that test and the ambient field must not apply
    // inside glass. mediumPop restores it through airMedium(), and the per-face re-derivation of
    // `entering` means a path that somehow loses track re-synchronises at the next interface.
    public bool ambient;
};

public struct MediumStack {
    public Medium current;
    public Medium outer;
};

public Medium airMedium() {
    Medium m;
    m.ior = 1.0;
    m.extinction = float3(0.0, 0.0, 0.0);
    m.water = false;
    m.ambient = true;
    return m;
}

/**
 * Single-scattering albedo: the fraction of an extinction event that scatters rather than absorbs.
 *
 * sigma_s / sigma_t, with sigma_s global and sigma_t already in the wavefront record — so it is
 * recoverable for `current` and `outer` alike without storing anything, and the record keeps its last
 * spare uint.
 *
 * Scattering in water is very nearly neutral while absorption is strongly wavelength-dependent, so this
 * ratio comes out heavily blue-weighted: high where the water barely absorbs, low where it absorbs
 * fast. THAT is why deep water is blue, and it is also the energy accounting — red light that has been
 * absorbed cannot be scattered back out.
 *
 * An earlier version made sigma_s proportional to sigma_a so that the ratio would be constant and
 * trivially derivable. It was derivable and it was wrong: a constant ratio is a GREY albedo, and in the
 * deep limit the extinction cancels out of the single-scattering integral and leaves the albedo as the
 * only colour there is. Every biome came out the same milky white, and it looked like it was not
 * conserving energy because nothing was being taken out of the scattered light at all.
 *
 * Per-biome variation survives, and for the right reason: sigma_t is per water body, so a strongly
 * absorbing swamp gets a low albedo and clear ocean a high one.
 */
public float3 mediumScatterAlbedo(Medium m) {
    if (!m.water) {
        return float3(0.0, 0.0, 0.0);
    }
    return worldPush.waterScatter.xyz / max(m.extinction, float3(1.0e-6, 1.0e-6, 1.0e-6));
}

public MediumStack makeMediumStack(Medium start) {
    MediumStack s;
    s.current = start;
    s.outer = airMedium();
    return s;
}

public void mediumPush(inout MediumStack stack, Medium entered) {
    stack.outer = stack.current;
    stack.current = entered;
}

public void mediumPop(inout MediumStack stack) {
    stack.current = stack.outer;
    stack.outer = airMedium();
}

// Water's tint is a biome colour whose absorption is calibrated per block of depth; any other volume
// dielectric's tint is a filter over a reference block of travel. Both end up as per-channel extinction.
public Medium makeDielectricMedium(bool isWater, float ior, float3 tint, float transmission) {
    Medium m;
    m.ior = ior;
    m.extinction = isWater ? waterExtinction(tint) : volumeExtinction(tint, transmission);
    m.water = isWater;
    m.ambient = false;
    return m;
}
