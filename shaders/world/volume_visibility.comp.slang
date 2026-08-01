// What a point in the air can see, solved once per world-space cell and interpolated by everyone else.
//
// WHY THIS EXISTS. The fog's in-scatter is produced by two machines. The camera's prefix segment reads
// the aerial-perspective froxel, which casts its own rays and is therefore shadowed. Every other segment
// — every bounce — runs the closed form in volume.slang, which has no occlusion at all. Standing inside a
// sealed room that showed up exactly as the room staying full of lit fog, brightest toward the sun,
// because the sun term on a bounce segment carries the phase function's forward gain and nothing was
// removing it. Measured rather than argued: Rt.Volumetrics.SEGMENT_SOURCE silences either machine, and
// silencing the marched half is what emptied the room.
//
// WHY NOT THE OTHER THREE FIXES. The coarse CPU sky-light grid (RtSkyLightGrid) was tried and its cells
// are eight blocks on a side — a house is shorter than one cell, so the cell holding a sealed room also
// holds the open sky above its roof and averages to open. Casting a shadow ray per bounce segment is the
// shape M9 measured and rejected: one binary answer near the segment's start applied to its whole length,
// so an occlusion boundary flips a whole segment at once. Doing only the first bounce reduces the error
// without changing its shape.
//
// WHY A GRID IS DIFFERENT. It solves visibility at a FIXED SET of world positions and interpolates
// between them, so the answer a segment reads varies continuously along its length, continuously across
// neighbouring pixels, and continuously from frame to frame — none of which a per-segment ray can give,
// because its sample position moves with the segment. It is also view-independent, which means it can be
// amortised over frames if it turns out to cost too much; a per-segment ray cannot be amortised at all.
//
// Reads the grid's placement and the sun from WorldPush rather than taking them as push constants, for
// the same reason the froxel does: the consumers read the same field, and two copies of a grid origin
// that disagree by one cell is a bug that looks like light leaking through walls.

import world_common;

// 64 x 32 x 64 cells. The extent in blocks is the cell size times these, so the cell size (a runtime
// knob, worldPush.visGridOrigin.w) trades reach against sharpness: at 2 blocks this covers 128 x 64 x 128
// around the camera, at 1 block half that in each axis.
//
// Flatter than it is wide because the quantity is flatter than it is wide: what occludes fog is mostly
// terrain and buildings, and a column of air 128 blocks tall has the same answer over most of its length.
public static const int VIS_GRID_W = 64;
public static const int VIS_GRID_H = 32;
public static const int VIS_GRID_D = 64;

struct VisPush {
    uint64_t worldPushAddr;
};
[[vk::push_constant]] VisPush visPush;

[[vk::binding(0, 0)]] RWTexture3D<float4> visibilityGrid;
// Set 1, its own ring, for the same reason the froxel's is: the TLAS handle changes almost every frame
// and rewriting a set an in-flight command buffer may still read is the hazard AccelStructureSet exists
// to guard against.
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

// Must equal CULL_SECONDARY_NO_SELF in trace.slang, and matches the froxel's for the same reason: the
// first-person player mesh surrounds the camera, and letting it occlude the cells nearest the eye would
// put a shadow over the whole near field.
static const uint VIS_SHADOW_MASK = 0x01u;

/** One shadow ray. Opaque-only and first-hit: this asks whether the light is blocked, not by what. */
bool occluded(float3 p, float3 dir) {
    RayDesc ray;
    ray.Origin = p;
    ray.Direction = dir;
    ray.TMin = 0.05;
    ray.TMax = 4096.0;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(tlas, RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH,
                     VIS_SHADOW_MASK, ray);
    while (q.Proceed()) {
        // FORCE_OPAQUE leaves nothing to arbitrate; the loop is here because the API is specified around
        // it and an implementation may return true once.
    }
    return q.CommittedStatus() != COMMITTED_NOTHING;
}

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(VIS_GRID_W) || id.y >= uint(VIS_GRID_H) || id.z >= uint(VIS_GRID_D)) {
        return;
    }
    WorldPush wp = ConstPtr<WorldPush>(visPush.worldPushAddr)[0];
    float cell = wp.visGridOrigin.w;

    // Cell CENTRES. The consumer samples this with a filtered fetch, which interpolates between centres;
    // writing the value for a cell's minimum corner instead would shift the whole field half a cell, which
    // shows up as shadows sitting beside the geometry that casts them rather than as anything that looks
    // like an indexing bug.
    float3 p = wp.visGridOrigin.xyz + (float3(id) + float3(0.5, 0.5, 0.5)) * cell;

    // Two rays for two quantities, and they are not interchangeable. The directional term needs the sun;
    // the isotropic terms — the fog's own ambient and the atmosphere's multiple scattering — need to know
    // whether there is a roof, which is a different question. Gating the ambient on the SUN would drain
    // the sky out of fog standing in a hillside's shadow, when what removes ambient light is a ceiling.
    //
    // Straight up rather than a hemisphere for the second: the ambient really is an integral over
    // directions, but the question a ceiling asks is binary and one ray settles it, where a hemisphere
    // would cost as many rays per cell as the whole grid costs now.
    float sunVis = occluded(p, wp.lightDir.xyz) ? 0.0 : 1.0;
    float skyVis = occluded(p, float3(0.0, 1.0, 0.0)) ? 0.0 : 1.0;

    visibilityGrid[int3(id)] = float4(sunVis, skyVis, 0.0, 0.0);
}
