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
// WHY NOT THE OTHER THREE FIXES. The coarse CPU sky-light grid (RtSkyLightGrid, retired and removed in
// M15.0) was tried and its cells
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
// AMORTISED IS NOW WHAT IT IS. This bake used to cast two rays per cell per frame and throw both answers
// away at the end of the frame: one at the sun, one straight up. Both are gone.
//
//   The SUN ray is gone because the answer it produced lost, in game, to the one stochastic shadow ray
//   the marched segments already cast (Rt.Volumetrics.SUN_SHADOW_RAYS, whose default has been 1 for some
//   time). Trilinear filtering blends the eight nearest cells and every indoor sample is within one cell
//   of a wall, so the lit peak is averaged down whatever the cell size — the grid could never carry a
//   light shaft, and it cost 131k rays a frame to not carry one. Sun visibility is a high-frequency
//   quantity and belongs on a ray.
//
//   The ZENITH ray is gone because the question it answered is not the question being asked. "Is there a
//   roof" is binary, and a binary answer is right for a sealed room and wrong for everything with gaps in
//   it: a tree canopy blocks the zenith, so every cell under every tree read zero and the fog beneath it
//   went black. What the fog wants is the FRACTION of the sky that reaches this point, which is an
//   integral over directions — and the reason one ray had to stand in for that integral was that the
//   bake had no memory. It has memory now.
//
// So the ray budget is one per cell per frame, half what it was, and it draws a new direction each frame
// and accumulates. The grid is world-anchored and its origin snaps in whole cells, so last frame's answer
// for this world point is a plain integer offset away — the same reprojection rain_history.comp does,
// for the same reason.
//
// Reads the grid's placement from WorldPush rather than taking it as a push constant, for the same reason
// the froxel does: the consumers read the same field, and two copies of a grid origin that disagree by
// one cell is a bug that looks like light leaking through walls.

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

/**
 * Samples one cell's running mean is allowed to stand on, and therefore how fast it forgets.
 *
 * <p>Past this the mean becomes an exponential moving average with weight 1/N, which is what makes the
 * field respond to a block being placed or broken at all — a true running mean over every frame since
 * the cell was born would eventually stop moving. At 60 fps, 64 is about a second to converge and about a
 * second to forget, and the quantity is geometry rather than light, so a second is fast.
 *
 * <p><b>WHICH IS WHY THE HISTORY IS FLOAT AND NOT UNORM8.</b> This is a feedback loop, and an 8-bit
 * feedback loop has a dead zone. The decay step is open/64; stored as a 0..255 code that is k/64, and
 * round() only moves the code once k/64 exceeds 0.5. So every code from 1 to 32 was a FIXED POINT: a cell
 * that had ever been open stalled at up to 32/255 = 0.125 and stayed there for good.
 *
 * <p>That is not a rounding nicety, it is a lit sealed room. Build a dirt hut around yourself and the
 * cells inside it were open a moment earlier; the average decays from 1.0 and then stops at 0.125, which
 * multiplied by full daylight sky radiance is plainly visible fog in a room with no light in it. The
 * sampled grid stays UNORM8 because reading it is one-way — quantisation there cannot accumulate.
 */
static const float VIS_ACCUM_MAX = 64.0;

struct VisPush {
    uint64_t worldPushAddr;
    // Whole CELLS the grid origin moved since the frame this history was written, so cell (i,j,k) now
    // holds what cell (i,j,k) + shift held then. The origin snaps to multiples of the cell size on the
    // CPU, which is what makes this an integer at all — a fractional shift would need a filtered fetch,
    // and a filtered fetch of an accumulator is a blur that compounds every frame.
    //
    // THREE SCALARS, NOT AN int3, AND THAT IS NOT A STYLE CHOICE. A three-component integer vector takes
    // 16-byte alignment, so declared after the 8-byte address it would begin at byte 16 while the CPU
    // writes it at byte 8. The fields then land one slot apart with no error anywhere: shift.x reads the
    // Z shift, shift.y reads the reset flag, shift.z reads padding, and reset reads padding and is
    // therefore never true.
    //
    // What that looked like was a sealed room whose fog came back whenever the player MOVED and faded
    // again when they stopped -- because standing still makes every shift zero, which is the one input
    // for which a wrong layout and a right one agree. Scalars have no alignment to get wrong, which is
    // the convention rain_history.comp already follows.
    int shiftX;
    int shiftY;
    int shiftZ;
    // 1 discards the history: first frame, a cell-size change, or a jump far enough that no cell of the
    // old grid describes any cell of the new one.
    uint reset;
};
[[vk::push_constant]] VisPush visPush;

// Binding 0 is the one CONSUMERS read, and it is written every frame with the same record as the history
// half below. The alternative was to let consumers sample the ping-pong directly, which would mean the
// sampled descriptor had to alternate with the parity — and that descriptor is written once at pipeline
// bind rather than per frame. So a stable binding is bought with one extra store per cell, which is much
// the cheaper half of that trade.
[[vk::binding(0, 0)]] RWTexture3D<float4> visibilityGrid;
// The accumulator, ping-ponged. Read is last frame's, write is this frame's; they are different images,
// so a thread reading a REPROJECTED neighbour cannot race the thread writing that neighbour.
[[vk::binding(1, 0)]] RWTexture3D<float4> historyWrite;
[[vk::binding(2, 0)]] RWTexture3D<float4> historyRead;
// Set 1, its own ring, for the same reason the froxel's is: the TLAS handle changes almost every frame
// and rewriting a set an in-flight command buffer may still read is the hazard AccelStructureSet exists
// to guard against.
[[vk::binding(0, 1)]] RaytracingAccelerationStructure tlas;

// Must equal CULL_SECONDARY_NO_SELF in trace.slang, and matches the froxel's for the same reason: the
// first-person player mesh surrounds the camera, and letting it occlude the cells nearest the eye would
// put a shadow over the whole near field.
static const uint VIS_SHADOW_MASK = 0x01u;

/** One shadow ray. Opaque-only and first-hit: this asks whether the sky is blocked, not by what. */
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

/**
 * A direction over the upper hemisphere, COSINE-WEIGHTED ABOUT +Y, and the weighting is the decision.
 *
 * <p>Uniform over solid angle is what an isotropic in-scatter integral actually asks for: a point in a
 * medium has no normal, so nothing privileges the zenith physically. It is not what this uses, and the
 * reason is calibration rather than laziness. Under a uniform weight a point two blocks above an open
 * plain reads about 0.9 rather than 1 — most of the sky near the horizon is distant terrain — so every
 * outdoor scene would darken by that factor the moment this landed, and the density, albedo and tint
 * already tuned against the published look would all be tuned against the wrong number.
 *
 * <p>Cosine weighting keeps the open sky at 1 and spends its samples where the question is: a canopy or a
 * ceiling directly overhead is what removes sky from a point of air, and it is exactly what the retired
 * zenith ray was reaching for with a single sample. So this is the same question, answered over the whole
 * hemisphere instead of along one line, and it agrees with the published answer in the two cases that
 * were already right — fully open reads 1, fully sealed reads 0. What changes is everything between,
 * which is where the tree was.
 */
float3 cosineHemisphereUp(float u1, float u2) {
    float r = sqrt(u1);
    float phi = 6.28318530718 * u2;
    return float3(r * cos(phi), sqrt(max(0.0, 1.0 - u1)), r * sin(phi));
}

/** A uniform float in [0,1) from a hashed state, taking the high bits pcg mixes best. */
float rnd01(inout uint state) {
    state = pcg(state);
    return float(state >> 8u) * (1.0 / 16777216.0);
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

    // Hash WORLD cells rather than dispatch ids so moving the camera does not reshuffle every cell's
    // sequence at once: a cell that survives a reprojection goes on drawing new directions instead of
    // repeating the ones its own history already contains.
    int3 worldCell = int3(floor(p / max(cell, 1.0e-4)));
    uint seed = asuint(worldCell.x) * 1973u
            ^ asuint(worldCell.y) * 9277u
            ^ asuint(worldCell.z) * 26699u
            ^ wp.frameIndex * 2654435761u;
    float u1 = rnd01(seed);
    float u2 = rnd01(seed);
    float sample01 = occluded(p, cosineHemisphereUp(u1, u2)) ? 0.0 : 1.0;

    // Last frame's answer for THIS world point, which is a whole number of cells away.
    float open = 0.0;
    float n = 0.0;
    if (visPush.reset == 0u) {
        int3 src = int3(id) + int3(visPush.shiftX, visPush.shiftY, visPush.shiftZ);
        if (all(src >= int3(0, 0, 0))
                && src.x < VIS_GRID_W && src.y < VIS_GRID_H && src.z < VIS_GRID_D) {
            float4 prior = historyRead[src];
            open = prior.r;
            n = prior.g;
        }
    }
    // A running mean while the cell is young, an exponential moving average once it is full. One
    // expression covers both, because the cap is the only thing that differs between them.
    n = min(n + 1.0, VIS_ACCUM_MAX);
    open = open + (sample01 - open) / n;

    // G carries the sample count so a reprojected cell knows how much weight its history has earned.
    // Consumers read R alone; B and A are held at constants so a half-written record cannot be mistaken
    // for data.
    //
    // TWO RECORDS, NOT ONE, because the two images have different jobs. The history is the accumulator
    // and keeps full float precision plus the raw count. The sampled grid is UNORM8 and only ever read,
    // so it takes the count scaled into its range and loses nothing that can come back around.
    historyWrite[int3(id)] = float4(open, n, 0.0, 1.0);
    visibilityGrid[int3(id)] = float4(open, n * (1.0 / 255.0), 0.0, 1.0);
}
