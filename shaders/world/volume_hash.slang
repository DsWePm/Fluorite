// M25: addressing for the world-space volumetric hash grid. No bindings and no entry point -- this file
// answers "which slot does this point belong to", and nothing else.
//
// THE GRID IS ANCHORED TO THE WORLD, NOT THE CAMERA (D193). That is the whole reason this milestone chose
// a hash over a froxel: a froxel is frustum-bound, so fog seen in a reflection has no column to read, and
// R18 already forbids the camera position appearing in cloud code for the same reason. A world-anchored
// cell also makes temporal reuse free of reprojection -- the cell does not move, so last frame's samples
// are addressed by the same key without a motion vector.
//
// THE LEVEL, HOWEVER, DEPENDS ON DISTANCE, AND THAT IS AN HONEST COST, not an oversight. Cell edge tracks
// a constant screen footprint (D197), so a fixed world point changes level as the camera approaches it,
// and at that moment it changes cell and its accumulated reuse restarts. The boundaries are octaves, so
// there are only a handful across a frustum, but they are real and they will be visible as a faint noise
// seam if anything ever looks for one. Making the level camera-independent would mean uniform cells,
// which is the configuration D197 measured at 15.18M cells and 0.4 samples each.
import world_common;
import volume_source; // volumeLuminance: math.slang carries the RT bindings a compute bake cannot take

/**
 * The LOD constant: cell edge divided by distance. A cell one edge across at distance d subtends
 * `edge/d` radians, and we want that to be `lodPixels` worth of a pixel's `fovY/renderHeight`.
 *
 * DERIVED, NEVER BAKED (D197). k scales with render height, so a constant here would let a DLSS quality
 * change silently coarsen the grid by 1.8x -- iron law 8 forbids a switch whose off state moves.
 */
public float volumeLodConstant(float fovY, uint renderHeight, uint lodPixels) {
    return float(lodPixels) * fovY / float(max(renderHeight, 1u));
}

/**
 * Octave index for a point `dist` from the camera. Floored at zero so the finest cell is one block:
 * below that the fog is smooth enough that the extra resolution buys nothing, and the cell count is
 * cubic in the edge.
 *
 * <p>Must agree with RtComposite.volumeGridCellEdge, which sizes the table from the same law. Two
 * spellings of one formula is how they stop agreeing, so both carry this note.
 */
public int volumeCellLevel(float dist, float k) {
    float want = dist * k;
    if (want <= 1.0) {
        return 0;
    }
    return max(int(round(log2(want))), 0);
}

/** Edge length in blocks. Power of two by construction, so the quantisation below is a shift. */
public float volumeCellEdge(int level) {
    return exp2(float(level));
}

/**
 * The integer cell a world position falls in. `floor`, not truncation: truncation folds the two sides of
 * every axis onto the same cell, which would join a cell in front of the origin to one behind it.
 */
public int3 volumeCellCoord(float3 worldPos, float edge) {
    return int3(floor(worldPos / edge));
}

/**
 * Slot index. Three odd constants and an avalanche, in the shape cloud_field's hash3 already uses -- any
 * integer hash without visible axis correlation serves, and nothing here is transcribed.
 *
 * <p>The LEVEL is mixed in, not just the coordinate. Two cells at different octaves can carry the same
 * integer coordinate, and without the level they would fight over one slot at every octave boundary --
 * which is exactly where reuse is already fragile.
 */
public uint volumeCellSlot(int3 coord, int level, uint slots) {
    uint h = uint(coord.x) * 0x8DA6B343u
           ^ uint(coord.y) * 0xD8163841u
           ^ uint(coord.z) * 0xCB1AB31Fu
           ^ uint(level) * 0x9E3779B9u;
    h ^= h >> 15;
    h *= 0x2C1B3C6Du;
    h ^= h >> 12;
    return slots > 0u ? (h % slots) : 0u;
}

/**
 * The checksum a slot stores so it can say whether it belongs to this cell.
 *
 * <p>A SECOND, INDEPENDENT MIX. Deriving the key from the slot hash would make it useless: two cells that
 * collide in the slot index would then carry the same key, and the check would confirm every collision as
 * a match. The two constants sets are disjoint for that reason alone.
 *
 * <p>NEVER ZERO. The table is cleared to zeros once per allocation, so zero has to mean "empty" -- a key
 * that could legitimately be zero would let a live cell read a slot nobody had written.
 */
public uint volumeCellKey(int3 coord, int level) {
    uint h = uint(coord.x) * 0x27D4EB2Du
           ^ uint(coord.y) * 0x165667B1u
           ^ uint(coord.z) * 0x85EBCA77u
           ^ uint(level) * 0xC2B2AE3Du;
    h ^= h >> 16;
    h *= 0x7FEB352Du;
    h ^= h >> 15;
    return h == 0u ? 1u : h;
}

/** How far linear probing walks before it gives the cell up. */
public static const uint VOLUME_PROBE_LIMIT = 8u;

/**
 * Whether a stored frame stamp is old enough to recycle (D197: N=45, about 0.75 s at 60 fps).
 *
 * <p>Unsigned subtraction, so a wrapped frame counter still measures the right distance. Writing this as
 * `stamp < frame - n` would recycle the entire table for one frame every time the counter wraps.
 */
public bool volumeCellExpired(uint stamp, uint frame, uint evictionFrames) {
    return (frame - stamp) > evictionFrames;
}

/**
 * One cell. Exactly 64 bytes, the stride RtComposite sizes the table with.
 *
 * <p>ITS OWN RECORD RATHER THAN PackedReservoir: a point in the air has no receiver normal and the sky
 * has no emitter to have one, so borrowing that layout would mean two dead lanes and a name that lies.
 *
 * <p>wSum IS STORED. Reconstructing it from W was a real defect and not a shortcut -- W is defined as
 * wSum/(M*phat), so recovering wSum from W is circular, and it drifts. restir.slang says wSum "only
 * exists while a reservoir is being filled", which is true for a surface reservoir that is filled and
 * consumed in one pass. A cell is filled across frames, so here it has to persist.
 */
public struct VolumeCell {
    public float4 dirW;      // xyz = the chosen sky direction, w = W, the finished weight
    public float4 leT;       // xyz = radiance TIMES transmittance along it, w = phat
    public float4 stats;     // x = M, y = wSum, z = summed visibility, w = reserved
    public uint4  ident;     // x = key (0 = empty), y = frame stamp, zw = reserved
};

/**
 * Weight ceiling, and M ceiling.
 *
 * <p>Both bound the same failure. In a mostly-occluded cell one candidate survives and phat collapses, so
 * W = wSum/(M*phat) spikes -- and unlike a surface reservoir this value multiplies a RADIANCE that then
 * lights the whole cell, for as long as the cell lives. Unbounded, the first lucky sample in a cave paints
 * a bright blob that persists for the eviction window. Capping M additionally stops a cell that has been
 * looked at for a thousand frames from refusing all new information.
 */
public static const float VOLUME_W_MAX = 8.0;
public static const float VOLUME_M_MAX = 64.0;

/**
 * How many candidates a cell must have seen before its EMPTINESS is believed.
 *
 * <p>W = 0 in a young cell means "no data yet"; in a mature one it means "every candidate was occluded".
 * Believing the young case deleted the near-field fog outright, since every cell is young when born.
 */
public static const float VOLUME_M_MATURE = 16.0;

public uint volumeCellStoredKey(VolumeCell c) { return c.ident.x; }
public uint volumeCellStamp(VolumeCell c) { return c.ident.y; }

/**
 * Where a half begins. Two halves rather than two buffers, exactly as the reservoir store does it: one
 * device address, and the parity picked from the frame.
 *
 * <p>THIS IS WHAT MAKES THE GRID SAFE TO READ. Every reader takes the other parity, which no thread is
 * writing this frame, so a read can never observe a half-written 64-byte record. Reading and writing one
 * half in a single dispatch is precisely the defect that made the first wiring flicker.
 */
public uint volumeHalfBase(uint slots, uint frame) {
    return (frame & 1u) * slots;
}

/** The slot this cell occupies within a half, or -1. */
public int volumeGridFind(ConstPtr<VolumeCell> grid, uint slots, uint base, int3 coord, int level,
                          uint frame, uint evictionFrames) {
    if (slots == 0u) {
        return -1;
    }
    uint key = volumeCellKey(coord, level);
    uint slot = volumeCellSlot(coord, level, slots);
    for (uint step = 0u; step < VOLUME_PROBE_LIMIT; ++step) {
        uint at = (slot + step) % slots;
        VolumeCell c = grid[base + at];
        uint stored = volumeCellStoredKey(c);
        if (stored == 0u) {
            return -1; // an empty slot ends the probe: nothing past it can belong to this cell
        }
        if (stored == key && !volumeCellExpired(volumeCellStamp(c), frame, evictionFrames)) {
            return int(base + at);
        }
    }
    return -1;
}

/**
 * The slot to write, claiming an empty or expired one. -1 when the probe window is full of live strangers.
 *
 * <p>Dropping the sample is the right answer to a full window: evicting a live cell trades its accumulated
 * reuse for another's first sample, and under pressure the two evict each other every frame, so both stay
 * at M=1 and the table does more work to hold less.
 */
public int volumeGridClaim(ConstPtr<VolumeCell> grid, uint slots, uint base, int3 coord, int level,
                           uint frame, uint evictionFrames) {
    if (slots == 0u) {
        return -1;
    }
    uint key = volumeCellKey(coord, level);
    uint slot = volumeCellSlot(coord, level, slots);
    for (uint step = 0u; step < VOLUME_PROBE_LIMIT; ++step) {
        uint at = (slot + step) % slots;
        VolumeCell c = grid[base + at];
        uint stored = volumeCellStoredKey(c);
        if (stored == key || stored == 0u
                || volumeCellExpired(volumeCellStamp(c), frame, evictionFrames)) {
            return int(base + at);
        }
    }
    return -1;
}

/** An empty reservoir, for a cell that is being started rather than continued. */
public VolumeCell volumeCellEmpty() {
    VolumeCell c;
    c.dirW = float4(0.0, 1.0, 0.0, 0.0);
    c.leT = float4(0.0, 0.0, 0.0, 0.0);
    c.stats = float4(0.0, 0.0, 0.0, 0.0);
    c.ident = uint4(0u, 0u, 0u, 0u);
    return c;
}

/**
 * One RIS candidate into a cell.
 *
 * <p>phat CARRIES THE TRANSMITTANCE (D198), which is what separates this from the shipped surface
 * estimator. There, le and the geometry term separate emitters by orders of magnitude and the target is
 * informative without visibility. Over a cave hemisphere the sky radiance is near-uniform and the phase is
 * smooth, so a target without transmittance is nearly constant and the reservoir would settle on an
 * arbitrary direction -- accumulating, at the cost of a ray, the one fact that is useless underground.
 *
 * <p>The estimator stays unbiased for any strictly positive target (lighting.slang's banner), and a sealed
 * cell drives every candidate to phat = 0, leaves the reservoir empty, and reports no sky light. Correct.
 */
/**
 * ONE RAY FEEDS BOTH CONSUMERS, which is what lets the three visibility sources collapse into one.
 *
 * <p>The marched path wants a DIRECTION -- D198 put transmittance in the target precisely so the
 * reservoir settles on the direction the sky actually arrives from. The froxel wants a SCALAR openness,
 * because its in-scatter sum is isotropic (`source.sky * skyOpen`) and a direction has nowhere to go in
 * that expression. Those are two different questions about the same sample, so the sample answers both:
 * the reservoir keeps the direction, and stats.z keeps the running sum of the scalar visibility.
 *
 * <p>Scaled with M when M is capped, so the mean it divides out stays the mean.
 */
public void volumeCellUpdate(inout VolumeCell c, float3 dir, float3 leT, float visibility,
                             float sourcePdf, inout uint seed) {
    float phat = volumeLuminance(leT);
    c.stats.x += 1.0;
    c.stats.z += saturate(visibility);
    if (phat > 0.0) {
        float w = phat / max(sourcePdf, 1.0e-20);
        c.stats.y += w;
        if (c.stats.y > 0.0 && rndf(seed) < (w / c.stats.y)) {
            c.dirW.xyz = dir;
            c.leT.xyz = leT;
            c.leT.w = phat;
        }
    }
    // M IS CAPPED BY SCALING BOTH, NEVER BY CLAMPING M ALONE. W is wSum/(M*phat); clamping M while wSum
    // keeps accumulating makes W grow without bound, every cell ramping to VOLUME_W_MAX at its own rate
    // while eviction restarts others on a stagger. That reads as the whole screen pulsing, and it is what
    // it did. Scaling holds the ratio, which is the only thing W is made of.
    if (c.stats.x > VOLUME_M_MAX) {
        float scale = VOLUME_M_MAX / c.stats.x;
        c.stats.x *= scale;
        c.stats.y *= scale;
        c.stats.z *= scale;
    }
    c.dirW.w = (c.leT.w > 0.0 && c.stats.x > 0.0)
            ? min(c.stats.y / (c.stats.x * c.leT.w), VOLUME_W_MAX)
            : 0.0;
}

/** Stamp a cell as belonging to this key and touched this frame. */
/**
 * The cell's answer to "how much of the sky reaches here", in [0,1].
 *
 * <p>A plain Monte Carlo mean of the visibility of uniformly drawn hemisphere directions, which is the
 * definition of sky openness. What replaces the shipped answer is not the averaging but the QUESTION: the
 * froxel asks one ray straight up and gets 0 or 1, so a cell under an overhang but open to the side reads
 * fully dark. Accumulating a random direction each frame costs the same one ray and answers the integral.
 */
public float volumeCellOpenness(VolumeCell c) {
    return c.stats.x > 0.0 ? saturate(c.stats.z / c.stats.x) : 1.0;
}

public void volumeCellClaimStamp(inout VolumeCell c, int3 coord, int level, uint frame) {
    c.ident.x = volumeCellKey(coord, level);
    c.ident.y = frame;
}
