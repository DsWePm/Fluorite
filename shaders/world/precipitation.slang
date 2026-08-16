// What is falling on a given column of the world, and nothing else.
//
// ONE PLACE, because the answer is consumed by four things that have no other code in common: the
// exposure bake decides whether to trace a ray for that column, the streak lighting decides how a flake
// moves and how brightly it scatters, the streak geometry decides what shape to draw, and the wet-surface
// path decides whether anything gets wet at all. Those live in three pipelines and cannot share a
// descriptor set, so the class travels as a device address and the decoding lives here.
//
// NO BINDINGS, for the same reason cloud_density has none: a compute bake, a graphics stage and the
// raygen all import this, and none of them can afford to inherit another's descriptor layout.
//
// WHY A CLASS AND NOT A BOOLEAN. Rain and snow are not two strengths of one thing. They fall at
// different speeds -- roughly 9 m/s against 1 m/s -- so one is a motion-blurred streak and the other is
// very nearly a point; and only one of them makes a surface wet. A single "is precipitating" flag would
// have to be reinterpreted at every one of those sites, which is how the two end up disagreeing.

module precipitation;

import world_common;

// Must match RAIN_PRECIP_* in RtSky.java, which builds the buffer these read.
public static const uint PRECIP_NONE = 0u;
public static const uint PRECIP_RAIN = 1u;
// The chunk has not streamed in yet. NOT the same as NONE, and the distinction is load-bearing: a column
// classified dry because its chunk was late would stay dry forever, since the CPU cache never revisits a
// resolved cell. Everything downstream must treat this as "ask again later", not as "no".
public static const uint PRECIP_UNKNOWN = 2u;
public static const uint PRECIP_SNOW = 3u;

/** Blocks per coarse cell. Must equal RAIN_PRECIP_CELL in RtSky.java. */
public static const uint PRECIP_CELL = 4u;

/**
 * The class for one texel of the exposure map.
 *
 * @param texel      position within the exposure square, in blocks from its origin -- the same value the
 *                   depth lookup uses, which for slanted precipitation is where the fall ray ENTERED the
 *                   top plane rather than where the receiver stands
 * @param resolution the square's side in blocks (worldPush.rainExposureOrigin.w)
 */
public uint precipitationAtTexel(uint64_t addr, float2 texel, float resolution) {
    if (addr == 0 || resolution <= 0.0 || any(texel < 0.0) || any(texel >= resolution)) {
        return PRECIP_UNKNOWN;
    }
    uint coarseDim = max(uint(resolution) / PRECIP_CELL, 1u);
    uint2 cell = min(uint2(texel) / PRECIP_CELL, uint2(coarseDim - 1u, coarseDim - 1u));
    return ConstPtr<uint>(addr)[cell.y * coarseDim + cell.x];
}

/** Something visible is falling here, so the exposure ray and the flakes are both worth having. */
public bool precipitationFalls(uint precipitation) {
    return precipitation == PRECIP_RAIN || precipitation == PRECIP_SNOW;
}

/**
 * ...and it makes surfaces wet, which only rain does.
 *
 * <p>Snow lies on a surface as a dry solid; Minecraft places its own snow-layer blocks for that, so the
 * ground whitens without the renderer doing anything. What must NOT happen is the wet film and the
 * puddles treating a blizzard as a downpour -- which is exactly what would happen the moment snow gained
 * a valid exposure depth, since the wetness path reads that depth and nothing else.
 */
public bool precipitationWets(uint precipitation) {
    return precipitation == PRECIP_RAIN;
}
