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

/** Absorption only: what the biome tint has always meant. Colour comes from here. */
public float3 waterAbsorption(float3 tint) {
    return WATER_ABSORB_FLOOR + WATER_DENSITY * (float3(1.0, 1.0, 1.0) - clamp(tint, 0.0, 1.0));
}

/**
 * Total extinction: the tint's absorption, raised by whatever is suspended in the water.
 *
 * Turbidity ADDS here rather than merely redistributing. An earlier version kept sigma_t fixed and only
 * changed how much of it scattered, which made turbidity 0 an exact reproduction of the old water — but
 * it also meant no amount of turbidity could stop you seeing the bottom, and not being able to see far
 * is the first thing anyone notices about murky water. Turbidity 0 is still exact, because k is 0 there.
 *
 * Scattering is proportional to absorption, so a biome that absorbs more also scatters more: the same
 * suspended particles do both, and the murky-looking biomes are the murky ones. That proportionality is
 * also what keeps the single-scattering albedo derivable without storing it — see mediumScatterAlbedo.
 */
public float3 waterExtinction(float3 tint) {
    return waterAbsorption(tint) * (float3(1.0, 1.0, 1.0) + worldPush.waterScatter.xyz);
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
 * Scattering is what separates water from coloured glass. Absorption alone gives a filter — what
 * survives is tinted, what does not is gone — and that is why water read as a blue pane: the turbidity,
 * the shafts, the milky way depth closes in are all the scattered part.
 *
 * With sigma_s = k * sigma_a, total extinction is sigma_a (1 + k), so the albedo is k / (1 + k) — a
 * function of the turbidity alone, with the tint cancelling out entirely. Nothing about the water body
 * needs storing to recover it, which is why this milestone costs the wavefront record nothing and
 * leaves its last spare uint spare.
 *
 * The per-biome part lives in the amount rather than the ratio, which is the right place for it: the
 * fraction of collisions that scatter is a property of what is suspended in the water, while how much
 * of it there is varies from swamp to open ocean. So both sigma_a and sigma_s rise together and murky
 * biomes come out both darker and milkier.
 */
public float3 mediumScatterAlbedo(Medium m) {
    if (!m.water) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 k = worldPush.waterScatter.xyz;
    return k / (float3(1.0, 1.0, 1.0) + k);
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
