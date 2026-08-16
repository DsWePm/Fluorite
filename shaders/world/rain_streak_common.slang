// Shared placement and push ABI for the post-RR rain-streak lighting, vertex and fragment stages.
// Keeping placement here is load-bearing: the compute stage shades exactly the world-space streak that
// the graphics stage later draws, rather than maintaining a second camera lattice that can drift.
module rain_streak_common;

import world_common;
import precipitation;
import cloud_field;   // curlNoise: the snow's flutter, evaluated rather than fetched -- see snowDrift

public struct RainStreakPush {
    public uint64_t worldPushAddr;
    public uint64_t lightBufAddr;
    public uint64_t lightAliasAddr;
    public uint64_t lightLocalAliasAddr;
    public uint64_t lightGridCellAddr;
    public uint64_t lightGridSpanAddr;
    public uint64_t radianceAddr;
    public uint instanceCount;
    public uint frameIndex;
    public float speed;
    public float length;
    public float density;
    public float snowSpeed;
    public float2 displaySize;
    // Where the lighting pass publishes each flake's finished world position and its precipitation
    // class. THE GEOMETRY READS IT RATHER THAN RECOMPUTING IT, which is the only way the two stages can
    // be guaranteed to agree now that a snowflake's path is not a closed form of its phase -- see
    // rainStreakPlacement.
    public uint64_t placementAddr;
    public uint64_t precipitationAddr;
    public float snowSize;
    public float pad;
};

public struct RainStreakPlacement {
    public float3 centre;
    public int2 worldCell;
    public uint precipitation;
};

public uint rainPcg(uint v) {
    v = v * 747796405u + 2891336453u;
    uint word = ((v >> ((v >> 28u) + 4u)) ^ v) * 277803737u;
    return (word >> 22u) ^ word;
}

public uint rainCellSeed(int2 cell, uint salt) {
    uint x = asuint(cell.x);
    uint z = asuint(cell.y);
    return rainPcg(x * 0x8da6b343u ^ z * 0xd8163841u ^ salt);
}

public float rainCellRandom(int2 cell, uint salt) {
    return float(rainCellSeed(cell, salt)) * (1.0 / 4294967296.0);
}

/**
 * Where a fall ray through {@code p} crosses the exposure map's top plane, in blocks from its origin.
 *
 * <p>The same value for every point on one ray, which is what makes it a usable column key: the depth
 * lookup and the precipitation class must name the same column or a flake will be sheltered by one
 * texel's roof while being classified by another's biome.
 */
public float2 rainStreakEntryTexel(WorldPush wp, float3 p) {
    float t = (p.y - wp.rainExposureOrigin.y) / wp.rainDirection.y;
    return (p - wp.rainDirection.xyz * t).xz - wp.rainExposureOrigin.xz;
}

// ---- Snow's flutter.
//
// CURL, EVALUATED RATHER THAN FETCHED. D173 bakes a 32^3 curl volume precisely to avoid recomputing six
// Perlin evaluations several times per march step; here the field is wanted once per flake per frame, at
// most sixteen thousand times, and at that rate binding a 3D texture to two more pipelines costs more --
// in descriptors, in plumbing and in things to get wrong -- than the arithmetic does.
//
// CURL SPECIFICALLY, for the reason cloud_warp.comp.slang spells out: an arbitrary noise vector field has
// sources and sinks, so drifting by it would pile flakes up where the field converges. The curl of any
// field is divergence-free, so it shears without compressing and the snow stays evenly spread.
//
// THE FIELD IS WORLD-ANCHORED AND STATIC, and that is the property that makes it read as snow rather than
// as jitter. Neighbouring flakes sample nearly the same vector, so they sweep and swirl TOGETHER, which
// is what a gust actually looks like; a per-flake sine wave gives each one an independent wobble and
// reads as a cloud of separately twitching dots. The flake's own descent through the static field is
// what animates it -- no per-flake state, which these stateless procedural instances could not carry
// anyway -- and the field drifts slowly downwind so it is not nailed to the world.
static const float SNOW_EDDY_SCALE = 64.0;   // blocks per noise tile; period 4 puts eddies at ~16 blocks
static const int   SNOW_EDDY_PERIOD = 4;
// Measured median |curl| at this period (D173's CPU replica), so the constant below reads in blocks.
static const float SNOW_CURL_MEDIAN = 4.08;
static const float SNOW_DRIFT_BLOCKS = 1.8;

float3 snowDrift(WorldPush wp, float3 p) {
    float3 field = p + wp.rainDirection.xyz * (wp.rainPuddle.w * 0.35);
    float3 curl = curlNoise(field / SNOW_EDDY_SCALE, SNOW_EDDY_PERIOD);
    return curl * (SNOW_DRIFT_BLOCKS / SNOW_CURL_MEDIAN);
}

/**
 * One stable world-cell flake or streak.
 *
 * <p>NO LONGER SHARED VERBATIM BY BOTH STAGES, and the change is deliberate. Rain's position is a closed
 * form of its phase, so the geometry could recompute it and be certain of matching the lighting. Snow's
 * is not: it needs the column's precipitation class, which is a buffer read, and a curl evaluation on
 * top of it. Rather than pay both twice and hope they agree, the lighting pass computes this once and
 * publishes it, and the geometry reads what was published.
 */
public RainStreakPlacement rainStreakPlacement(
        WorldPush wp, uint64_t precipitationAddr, uint instanceId, uint instanceCount,
        float rainSpeed, float snowSpeed) {
    RainStreakPlacement placement;
    uint gridDim = uint(ceil(sqrt(float(max(instanceCount, 1u)))));
    uint gx = instanceId % gridDim;
    uint gz = instanceId / gridDim;
    float cell = 64.0 / float(gridDim);
    float2 cameraWorld = wp.camOffset.xz + wp.waterAnchor.xy;
    int2 gridOriginCell = int2(floor(cameraWorld / cell)) - int2(gridDim / 2u);
    placement.worldCell = gridOriginCell + int2(gx, gz);
    float2 worldXZ = (float2(placement.worldCell)
            + float2(rainCellRandom(placement.worldCell, 0x68bc21ebu),
                     rainCellRandom(placement.worldCell, 0x02e5be93u))) * cell;
    float3 top = float3(worldXZ.x - wp.waterAnchor.x, wp.camOffset.y + 24.0,
                        worldXZ.y - wp.waterAnchor.y);

    // Classified from the COLUMN, before the fall is applied, so every flake in a column agrees and the
    // answer cannot depend on the speed that the answer itself selects.
    placement.precipitation = precipitationAtTexel(precipitationAddr,
            rainStreakEntryTexel(wp, top), wp.rainExposureOrigin.w);
    bool snow = placement.precipitation == PRECIP_SNOW;

    float fallSpan = 48.0;
    // Snow crosses the span in about half a minute against rain's two seconds, which is the whole reason
    // its flutter matters: a flake is on screen long enough to be watched.
    float speed = snow ? max(snowSpeed, 0.01) : rainSpeed;
    float phase = frac(rainCellRandom(placement.worldCell, 0x9e3779b9u)
            + wp.rainPuddle.w * speed / fallSpan);
    placement.centre = top + wp.rainDirection.xyz * (phase * fallSpan);
    if (snow) {
        placement.centre += snowDrift(wp, placement.centre);
    }
    return placement;
}
