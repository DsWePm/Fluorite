// D105/D110 directional rain exposure map.
//
// One opaque ray per world-anchored texel records the first surface reached by rain travelling along
// WorldPush.rainDirection. Every path vertex later compares its own distance from the same top plane to
// this depth. That makes primary, reflected and GI-visible surfaces agree without repeating a march or
// a shadow ray at each vertex.
//
// The CPU precipitation cache is deliberately coarse (one uint per 4x4 columns): biome precipitation
// classification is a world query, not a GPU geometry query. A zero cache cell writes -1, so snow and
// dry biomes cannot become wet merely because their geometry is open to the sky.

import world_common;
import precipitation;

struct RainExposurePush {
    uint64_t worldPushAddr;
    uint64_t precipitationAddr;
    uint64_t sectionTableAddr;
    uint resolution;
    uint coarseDim;
    uint2 tileOrigin;
    uint2 tileExtent;
};
[[vk::push_constant]] RainExposurePush rainPush;

[[vk::binding(0, 0)]] RWTexture2D<float> rainDepth;
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

// Ordinary secondary-visible geometry plus first-person self. Eye-origin shadow rays exclude self to
// avoid hitting the camera body; sky-origin rain rays have no such hazard and held items may get wet.
static const uint RAIN_SHADOW_MASK = 0x05u;

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= rainPush.tileExtent.x || id.y >= rainPush.tileExtent.y) {
        return;
    }
    uint2 updateTile = rainPush.tileOrigin + id.xy;
    if (updateTile.x >= rainPush.resolution || updateTile.y >= rainPush.resolution) {
        return;
    }
    uint coarseX = min(updateTile.x >> 2u, rainPush.coarseDim - 1u);
    uint coarseY = min(updateTile.y >> 2u, rainPush.coarseDim - 1u);
    uint precipitation = ConstPtr<uint>(rainPush.precipitationAddr)[coarseY * rainPush.coarseDim + coarseX];
    if (precipitation == PRECIP_UNKNOWN) {
        // Client chunk streaming has not supplied this biome yet. Keep history pending rather than
        // converting a temporary lack of CPU data into a permanent dry classification.
        rainDepth[int2(updateTile)] = -2.0;
        return;
    }
    // SNOW TRACES THE SAME RAY. What this map answers is "where does falling precipitation first land",
    // and that question has the same geometry whichever form is falling -- a roof shelters a doorway from
    // snow exactly as it does from rain. Snow used to be lumped in with NONE, which is why a blizzard
    // rendered as nothing at all; the difference between the two is now carried by the class, and every
    // consumer that cares reads it. In particular the WETNESS path must keep excluding snow, which is
    // precipitationWets()'s whole job -- a valid depth here is what would otherwise have started soaking
    // the world during a snowstorm.
    if (!precipitationFalls(precipitation)) {
        rainDepth[int2(updateTile)] = -1.0;
        return;
    }

    WorldPush wp = ConstPtr<WorldPush>(rainPush.worldPushAddr)[0];
    RayDesc ray;
    ray.Origin = wp.rainExposureOrigin.xyz
            + float3(float(updateTile.x) + 0.5, 0.0, float(updateTile.y) + 0.5);
    ray.Direction = wp.rainDirection.xyz;
    ray.TMin = 0.02;
    ray.TMax = wp.rainDirection.w;

    // Candidate traversal is deliberate: small cutout vegetation is wet but does not make the grass
    // block below bone dry; leaves intercept a bounded share per layer; water remains a real receiver.
    RayQuery<RAY_FLAG_FORCE_NON_OPAQUE> q;
    q.TraceRayInline(tlas, RAY_FLAG_FORCE_NON_OPAQUE, RAIN_SHADOW_MASK, ray);
    uint foliageLayers = 0u;
    while (q.Proceed()) {
        uint instanceKind = q.CandidateInstanceID() & (ENTITY_BIT | PARTICLE_BIT);
        if (instanceKind != 0u) {
            q.CommitNonOpaqueTriangleHit();
            continue;
        }
        uint bucket = q.CandidateGeometryIndex();
        if (bucket == BUCKET_CUTOUT) {
            Section sec = ConstPtr<Section>(rainPush.sectionTableAddr)[q.CandidateInstanceID()];
            uint pid = q.CandidatePrimitiveIndex() + sec.triBase[bucket];
            TerrainPrim prim = ConstPtr<TerrainPrim>(sec.primAddr)[pid];
            if ((prim.flags & TERRAIN_PRIM_RAIN_PASS) != 0u) {
                continue;
            }
            if ((prim.flags & TERRAIN_PRIM_RAIN_FOLIAGE) != 0u) {
                // Two leaves layers pass through; the third shelters the column. This is a deterministic
                // bounded approximation to canopy interception and cannot turn a single leaf card into a roof.
                foliageLayers++;
                if (foliageLayers < 3u) {
                    continue;
                }
            }
        }
        q.CommitNonOpaqueTriangleHit();
    }
    rainDepth[int2(updateTile)] = q.CommittedStatus() == COMMITTED_NOTHING
            ? ray.TMax : q.CommittedRayT();
}
