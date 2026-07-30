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

/** Total extinction: what Beer-Lambert attenuates by, and the sum of the two above. */
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
 * How much a medium scatters, per channel.
 *
 * Scattering is what separates water from coloured glass. Absorption alone gives a filter — light that
 * survives is tinted, light that does not is gone — and that is why water has read as a blue pane rather
 * than as water: the turbidity, the shafts, the milky falloff with depth are all the scattered part.
 *
 * GLOBAL rather than per water body, and that is a deliberate trade. It means the single-scattering
 * albedo can be recovered wherever it is needed as sigma_s / sigma_t, from the extinction the record
 * already carries and the one water bit beside it — so the wavefront record does not grow, and the last
 * spare uint in it stays spare. Per-biome character survives regardless, because it lives in the
 * absorption, which is still driven by the tint.
 */
public float3 mediumScattering(Medium m) {
    return m.water ? worldPush.waterScatter.xyz : float3(0.0, 0.0, 0.0);
}

/**
 * Single-scattering albedo: the fraction of an extinction event that scatters rather than absorbs.
 *
 * Derived, not stored. sigma_t is in the record, sigma_s is global, and the ratio is the albedo — which
 * is why M9 costs no bytes at all despite the plan having budgeted the last lane for it.
 */
public float3 mediumScatterAlbedo(Medium m) {
    float3 sigmaS = mediumScattering(m);
    return sigmaS / max(m.extinction, float3(1.0e-6, 1.0e-6, 1.0e-6));
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
