// Which cells of the water simulation grid have a block in them (M12).
//
// ONE SHORT RAY PER CELL, STRAIGHT DOWN, against the TLAS the renderer already builds.
//
// This is where the design parts company with the reference implementation, which renders the scene
// twice from an orthographic camera above the water to capture obstacles into a texture. That approach
// needs a second camera, its own culling, its own render targets and its own idea of what counts as an
// obstacle -- an entire parallel pipeline whose only output is a boolean per cell. A ray tracer already
// has an acceleration structure over exactly that geometry, and asking it "is there something here" is a
// single traceRayEXT. Not a cleverness: it is the same question with the machinery already present.
//
// The ray starts slightly ABOVE the water plane and is short, so it reports the block occupying this cell
// at the surface rather than the seabed several metres down. A shoreline is a cell whose ray hits stone;
// open water is a cell whose ray hits nothing within that short distance.

module water_obstacle;

import world_common;

public static const int WATER_SIM_DIM = 256;

[[vk::binding(0, 0)]] RWTexture2D<float> obstacleOut;
[[vk::binding(1, 0)]] RaytracingAccelerationStructure tlas;

struct WaterObstaclePush {
    float2 originXZ;   // rebased world position of cell (0,0)'s centre
    float  cellSize;   // blocks per cell
    float  surfaceY;   // rebased water surface height the rays are cast from
    int4   dirtyRect;  // xy min, zw max (exclusive) in cells -- only this region is rewritten
};
[[vk::push_constant]] WaterObstaclePush pc;

// How far above and below the surface the ray looks. Above, so a block sitting proud of the water is
// caught; below, only far enough to find the block forming the shoreline rather than the bottom of a
// deep ocean, which is not an obstacle to a surface ripple at all.
static const float PROBE_ABOVE = 0.55;
static const float PROBE_BELOW = 0.95;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id3: SV_DispatchThreadID) {
    int2 id = int2(id3.xy);
    if (any(id >= int2(WATER_SIM_DIM, WATER_SIM_DIM))) {
        return;
    }
    // The dirty rect is what makes this incremental. When the domain slides by a few texels only the
    // newly exposed strip is unknown; when terrain changes only the changed neighbourhood is. Cells
    // outside it keep whatever they held, which is why the mask is persistent rather than rebuilt.
    if (any(id < pc.dirtyRect.xy) || any(id >= pc.dirtyRect.zw)) {
        return;
    }

    float3 origin = float3(pc.originXZ.x + (float(id.x) + 0.5) * pc.cellSize,
                           pc.surfaceY + PROBE_ABOVE,
                           pc.originXZ.y + (float(id.y) + 0.5) * pc.cellSize);

    RayDesc ray;
    ray.Origin = origin;
    ray.Direction = float3(0.0, -1.0, 0.0);
    ray.TMin = 0.0;
    ray.TMax = PROBE_ABOVE + PROBE_BELOW;

    // Terminate on the first hit and skip the closest-hit shader entirely: this asks whether anything is
    // there, not what or how far, so there is nothing for a hit shader to compute.
    RayQuery<RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH | RAY_FLAG_SKIP_CLOSEST_HIT_SHADER> q;
    q.TraceRayInline(tlas, RAY_FLAG_NONE, 0xFFu, ray);
    q.Proceed();
    bool blocked = q.CommittedStatus() != COMMITTED_NOTHING;

    obstacleOut[id] = blocked ? 0.0 : 1.0;
}
