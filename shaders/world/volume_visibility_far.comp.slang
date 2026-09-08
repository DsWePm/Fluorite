// The SECOND-LEVEL visibility grid (M28 S1.5, D214): the far-field structure the fine grid's clamp
// could only guess at.
//
// WHY. The fine grid reaches about 32 blocks horizontally; every marched stretch beyond that face
// reads the CLAMPED boundary cell (FLAG_FOG_BEYOND_GRID_CLAMP). In the open that boundary is the
// nearest measurement and the answer is right. Where the boundary sits on a mountain flank, the
// flank roofs part of the boundary cell's hemisphere, the far fog multiplies by that fraction, and
// a standing dark region appears over what is actually open sky -- D178's named cost, made prominent
// once the user's fog density went high. The boundary cell cannot distinguish "cave continues" from
// "open sky beyond the ridge"; only data past the face can, and this grid is that data.
//
// SHAPE. Same 64x32x64 lattice as the fine grid, at FAR_CELL blocks per cell: 8x the reach per axis
// (about 256 blocks horizontally, 128 vertically) for 4k-16k extra rays a frame. World-anchored, NOT
// camera-anchored: a far field that slides with the camera would recreate the leading-edge problem
// the fine grid's neighbour seeding just fixed, at eight times the cell size. The CPU recentres it
// with hysteresis and a one-frame reset, the same pattern rain exposure uses.
//
// BOOTSTRAP. A cell with no history seeds from the FINE grid's clamped field at the same position --
// which is exactly what consumers read today. So the far grid starts AS the published behaviour and
// refines toward real measurements over its refresh cycle; the worst case during convergence, recentring
// or a reset is the status quo, never a regression window. Refresh is round-robin (one thirty-second
// slice of the lattice per frame, picked by frame index): coarse openness is low-frequency geometry,
// and a cell refreshed twice a second forgets a block placement in about VIS_ACCUM_FAR_MAX refreshes.
//
// MODES. Same split as the fine bake: scalar (R open, G count) and directional (RGBA sector bins,
// shared count in the meta pair). The sector definitions are the world constants, so the marched far
// field weights bins by the same skySectorRadiance the fine grid uses -- the two levels cannot
// disagree about what a sector IS, only about how finely they sample it.

import world_common;

public static const int FAR_GRID_W = 64;
public static const int FAR_GRID_H = 32;
public static const int FAR_GRID_D = 64;

/** Blocks per far cell. 8 is what makes the same lattice reach eight times as far; the coarseness is
 * invisible at the distances the far field answers for, and the near field never reads it. */
public static const float FAR_CELL = 8.0;

// The fine grid's lattice, restated for the bootstrap seed's coordinate math. Must match
// volume_visibility{,.comp}.slang and RtSky.VIS_GRID_W/H/D, like every other restatement of these.
static const int VIS_GRID_W = 64;
static const int VIS_GRID_H = 32;
static const int VIS_GRID_D = 64;

/** Confidence the bootstrap seed carries. Without it the first update's 1/n weight would throw the
 * seed away exactly like the fine bake's old fresh-cell coin flip; 4 makes the first real draw move
 * the value a fifth of the way, and the seed IS the published answer, so slow trust costs nothing. */
static const float FAR_SEED_CONFIDENCE = 4.0;

/** How many frames a full pass over the lattice takes. 32 frames at 60 fps refreshes every far cell
 * twice a second; the round-robin slice is picked by frame index, so no CPU-side cursor exists. */
public static const uint FAR_REFRESH_FRAMES = 32u;

/** Draws a far cell's EMA stands on. Coarse visibility is low-frequency; 16 draws (about eight
 * seconds) is plenty to converge and forgets a terrain edit within the same span. */
static const float FAR_ACCUM_MAX = 16.0;

struct FarVisPush {
    uint64_t worldPushAddr;
    // Whole far CELLS the grid's centre moved since the last dispatch. The grid is world-anchored, so
    // a recentre slides the lattice over the world and cell (i,j,k) of the new window sits where cell
    // (i,j,k) + shift of the old one sat -- the SAME world point, therefore the same answer, therefore
    // a plain integer reprojection rather than a re-convergence. Only a shift wider than the lattice
    // (teleport, or the feature having been off) resets. Scalars, not an int3: the fine bake's VisPush
    // banner owns that story.
    int shiftX;
    int shiftY;
    int shiftZ;
    // 1 discards history: first frame, or a shift wider than the lattice.
    uint reset;
};
[[vk::push_constant]] FarVisPush farVisPush;

// The far grid's own storage. The SAMPLED image is what consumers read; history/meta are the
// accumulator pair, ping-ponged exactly like the fine bake's.
[[vk::binding(0, 0)]] RWTexture3D<float4> farGrid;
[[vk::binding(1, 0)]] RWTexture3D<float4> farHistoryWrite;
[[vk::binding(2, 0)]] RWTexture3D<float4> farHistoryRead;
[[vk::binding(3, 0)]] RWTexture3D<float4> farMetaWrite;
[[vk::binding(4, 0)]] RWTexture3D<float4> farMetaRead;
// The FINE grid's sampled texture, read-only: the bootstrap seed. Binding it here rather than
// re-deriving its answer keeps the two levels on one definition of openness.
[[vk::binding(5, 0)]] Sampler3D fineGrid;
// Set 1, shared ring with the fine bake: the TLAS handle changes almost every frame.
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

static const uint FAR_SHADOW_MASK = 0x01u; // must equal the fine bake's VIS_SHADOW_MASK

bool occluded(float3 p, float3 dir) {
    RayDesc ray;
    ray.Origin = p;
    ray.Direction = dir;
    ray.TMin = 0.05;
    ray.TMax = 4096.0;
    RayQuery<RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH> q;
    q.TraceRayInline(tlas, RAY_FLAG_FORCE_OPAQUE | RAY_FLAG_ACCEPT_FIRST_HIT_AND_END_SEARCH,
                     FAR_SHADOW_MASK, ray);
    while (q.Proceed()) {
    }
    return q.CommittedStatus() != COMMITTED_NOTHING;
}

float rnd01(inout uint state) {
    state = pcg(state);
    return float(state >> 8u) * (1.0 / 16777216.0);
}

// Same sampler and same weighting as the fine bake -- one definition of the quantity across levels.
float3 cosineHemisphereUp(float u1, float u2) {
    float r = sqrt(u1);
    float phi = 6.28318530718 * u2;
    return float3(r * cos(phi), sqrt(max(0.0, 1.0 - u1)), r * sin(phi));
}

[shader("compute")]
[numthreads(4, 4, 4)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(FAR_GRID_W) || id.y >= uint(FAR_GRID_H) || id.z >= uint(FAR_GRID_D)) {
        return;
    }
    WorldPush wp = ConstPtr<WorldPush>(farVisPush.worldPushAddr)[0];
    if (wp.visFarGridOrigin.w <= 0.0) {
        return;
    }

    // Round-robin: this cell refreshes only on frames where its linear index agrees with the frame.
    // A cell's history read below is last refresh's, by construction at least one slice old.
    uint linear = id.x + FAR_GRID_W * (id.y + FAR_GRID_H * id.z);
    if (linear % FAR_REFRESH_FRAMES != wp.frameIndex % FAR_REFRESH_FRAMES) {
        return;
    }

    float3 p = wp.visFarGridOrigin.xyz + (float3(id) + float3(0.5, 0.5, 0.5)) * FAR_CELL;

    bool directional = (wp.volumetricSwitches & FLAG_SKY_FIELD_DIRECTIONAL) != 0u;
    float4 sample01;
    float nSample;
    if (directional) {
        sample01 = float4(0.0, 0.0, 0.0, 0.0);
        int3 worldCell = int3(floor(p / FAR_CELL));
        uint seed = asuint(worldCell.x) * 1973u
                ^ asuint(worldCell.y) * 9277u
                ^ asuint(worldCell.z) * 26699u
                ^ wp.frameIndex * 2654435761u;
        for (uint sector = 0u; sector < 4u; ++sector) {
            float u1 = rnd01(seed);
            float u2 = rnd01(seed);
            sample01[sector] = occluded(p, cosineHemisphereUp(u1, (float(sector) + u2) * 0.25)) ? 0.0 : 1.0;
        }
        nSample = 4.0;
    } else {
        int3 worldCell = int3(floor(p / FAR_CELL));
        uint seed = asuint(worldCell.x) * 1973u
                ^ asuint(worldCell.y) * 9277u
                ^ asuint(worldCell.z) * 26699u
                ^ wp.frameIndex * 2654435761u;
        float u1 = rnd01(seed);
        float u2 = rnd01(seed);
        sample01 = float4(occluded(p, cosineHemisphereUp(u1, u2)) ? 0.0 : 1.0, 0.0, 0.0, 0.0);
        nSample = 1.0;
    }

    // Prior: this cell's reprojected accumulator when one exists, otherwise the fine grid's clamped
    // answer -- the published behaviour, which is what the far grid refines FROM. A recentred cell
    // reads its old-window self at the same world point, so sliding the grid costs nothing.
    float4 prior = float4(0.0, 0.0, 0.0, 0.0);
    float n = 0.0;
    if (farVisPush.reset == 0u) {
        int3 src = int3(id) + int3(farVisPush.shiftX, farVisPush.shiftY, farVisPush.shiftZ);
        if (all(src >= int3(0, 0, 0))
                && src.x < FAR_GRID_W && src.y < FAR_GRID_H && src.z < FAR_GRID_D) {
            prior = farHistoryRead[src];
            n = farMetaRead[src].r;
        }
    }
    if (n <= 0.0) {
        prior = saturate(fineGrid.SampleLevel((p - wp.visGridOrigin.xyz)
                / (wp.visGridOrigin.w * float3(float(VIS_GRID_W), float(VIS_GRID_H), float(VIS_GRID_D))), 0.0));
        n = FAR_SEED_CONFIDENCE;
    }

    n = min(n + nSample, FAR_ACCUM_MAX);
    prior += (sample01 * (nSample > 1.0 ? 0.25 : 1.0) - prior) * (nSample / n);

    farHistoryWrite[int3(id)] = float4(prior.x, prior.y, prior.z, prior.w);
    farMetaWrite[int3(id)] = float4(n, 0.0, 0.0, 1.0);
    farGrid[int3(id)] = prior;
}
