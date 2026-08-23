// M26: draw every populated light-grid cell a small pool of emitters, once per frame, so that shading
// never walks the grid again.
//
// WHAT THIS MOVES, AND WHY MOVING IT IS THE WHOLE POINT. Sampling one emitter for a volume event used to
// walk four dependent loads -- the cell, then a span inside it, then an alias column, then the Light
// record -- and every address waited on the value before it. That is pure latency, and a ray-tracing
// shader has too little occupancy to hide it. D31 measured the walk at +20.9 ms and, decisively, showed
// that silencing the shadow ray while KEEPING the walk made the frame 5.2 ms slower still: the ray was
// never the cost, the chain was.
//
// D207 then killed the obvious mitigation. "Only the first path segment may light the fog" sounds like it
// divides the work by the segment count, but spp is 1 and 1080p is two million paths, every one of which
// has a first segment. The floor for "first segment only" is one whole extra lighting pass, which is why
// capping at one measured no better than capping at three.
//
// So the walk has to happen somewhere that is not the sample. Here it happens once per cell per slot per
// frame -- tens of thousands of threads instead of millions of samples -- and shading is left with a rank
// it computes arithmetically, one cell load, and one pool load.
//
// WHY THE POOL IS REDRAWN EVERY FRAME. The estimator is unbiased either way, because each slot carries
// the probability it was drawn with. A pool held across frames is unbiased CONDITIONED on itself, so its
// error stops being noise and becomes a fixed pattern, which temporal accumulation cannot remove -- it
// averages frames, and every frame would carry the same one. Redrawing costs one small dispatch.

import world_common;
import light_sampling;

struct LightPoolPush {
    uint64_t worldPushAddr;
    uint64_t lightAddr;
    uint64_t localAliasAddr;
    uint64_t gridCellAddr;
    uint64_t gridSpanAddr;
    uint64_t populatedCellAddr;
    uint64_t poolAddr;
    // Scalars, never a vector: an int3 or uint2 here would take its own alignment and begin at an offset
    // the CPU does not write to. That exact defect shipped once in this line of work -- the visibility
    // bake's reprojection shift read one slot across, and it only showed up while the player was moving.
    uint poolDepth;
    uint populatedCells;
    uint frameIndex;
    uint reserved;
};
[[vk::push_constant]] LightPoolPush poolPush;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    uint slotIndex = id.x;
    uint total = poolPush.populatedCells * poolPush.poolDepth;
    if (slotIndex >= total || poolPush.poolAddr == 0 || poolPush.poolDepth == 0u) {
        return;
    }
    uint rank = slotIndex / poolPush.poolDepth;
    uint slot = slotIndex - rank * poolPush.poolDepth;

    DevicePtr<PooledLight> pool = DevicePtr<PooledLight>(poolPush.poolAddr);
    // Written on every path below, because a slot that keeps last frame's contents while reporting this
    // frame's rank is a light in the wrong place rather than an absent one.
    PooledLight empty;
    empty.pos = float3(0.0, 0.0, 0.0);
    empty.pdf = 0.0;
    empty.le = 0u;
    empty.halfUxy = 0u;
    empty.halfUzVx = 0u;
    empty.halfVyz = 0u;
    empty.section = 0u;
    empty.reserved = 0u;

    if (poolPush.populatedCellAddr == 0 || poolPush.gridCellAddr == 0
            || poolPush.gridSpanAddr == 0 || poolPush.localAliasAddr == 0
            || poolPush.lightAddr == 0) {
        pool[slotIndex] = empty;
        return;
    }

    WorldPush wp = ConstPtr<WorldPush>(poolPush.worldPushAddr)[0];
    uint cellLinear = ConstPtr<uint>(poolPush.populatedCellAddr)[rank];
    LightGridCell cell = ConstPtr<LightGridCell>(poolPush.gridCellAddr)[cellLinear];
    if (cell.spanCount == 0u) {
        pool[slotIndex] = empty; // the map said populated; trust the cell, not the map
        return;
    }

    // The cell's own coordinate, recovered from its linear index rather than carried alongside it. The
    // proposal pdf weights a light by how far its section sits from this cell, so the coordinate is not
    // bookkeeping -- it is part of the density, and deriving it here keeps one definition of the layout.
    int3 dims = wp.lightGridDims.xyz;
    int3 coord;
    coord.x = int(cellLinear % uint(dims.x));
    coord.y = int((cellLinear / uint(dims.x)) % uint(dims.y));
    coord.z = int(cellLinear / (uint(dims.x) * uint(dims.y)));

    LightBufferAddresses addresses;
    addresses.light = poolPush.lightAddr;
    addresses.globalAlias = 0;  // a populated cell is always sampled locally; see emitterProposalPdf
    addresses.localAlias = poolPush.localAliasAddr;
    addresses.gridCell = poolPush.gridCellAddr;
    addresses.gridSpan = poolPush.gridSpanAddr;

    // Hashed from the CELL rather than from the dispatch index, so that a grid rebuild -- which renumbers
    // ranks -- does not reshuffle every cell's sequence at once. Same reasoning as the visibility bake.
    uint seed = cellLinear * 2654435761u ^ (slot + 1u) * 2246822519u
            ^ poolPush.frameIndex * 3266489917u;
    seed = pcg(seed);

    uint lightIndex;
    emitterSelectGrid(addresses, cell, seed, lightIndex);
    Light light = ConstPtr<Light>(poolPush.lightAddr)[lightIndex];
    float3 le = emitterRadiance(light);
    float pdf = emitterProposalPdf(wp, addresses, light, le, cell, coord, true);
    if (!(pdf > 0.0)) {
        // A zero-power or out-of-neighbourhood draw. Recorded as an empty slot rather than substituted
        // with a global proposal, which is the same refusal emitterProposalPdf itself documents: quietly
        // swapping densities would change the estimator this pool is supposed to reproduce exactly.
        pool[slotIndex] = empty;
        return;
    }

    PooledLight out;
    out.pos = light.pos;
    out.pdf = pdf;
    out.le = light.le;
    out.halfUxy = light.halfUxy;
    out.halfUzVx = light.halfUzVx;
    out.halfVyz = light.halfVyz;
    out.section = light.section;
    out.reserved = 0u;
    pool[slotIndex] = out;
}
