// Shared placement and push ABI for the post-RR rain-streak lighting, vertex and fragment stages.
// Keeping placement here is load-bearing: the compute stage shades exactly the world-space streak that
// the graphics stage later draws, rather than maintaining a second camera lattice that can drift.
module rain_streak_common;

import world_common;

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
    public float reserved;
    public float2 displaySize;
    public float2 reserved2;
};

public struct RainStreakPlacement {
    public float3 centre;
    public int2 worldCell;
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

/** One stable world-cell streak, shared verbatim by compute lighting and procedural geometry. */
public RainStreakPlacement rainStreakPlacement(
        WorldPush wp, uint instanceId, uint instanceCount, float speed) {
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
    float fallSpan = 48.0;
    float phase = frac(rainCellRandom(placement.worldCell, 0x9e3779b9u)
            + wp.rainPuddle.w * speed / fallSpan);
    float3 top = float3(worldXZ.x - wp.waterAnchor.x, wp.camOffset.y + 24.0,
                        worldXZ.y - wp.waterAnchor.y);
    placement.centre = top + wp.rainDirection.xyz * (phase * fallSpan);
    return placement;
}
