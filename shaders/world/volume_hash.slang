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
import math;        // luminance, for the target function

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
 * One cell. Exactly 64 bytes, the same stride RtComposite sizes the table with -- a struct that drifted
 * from RESERVOIR_BYTES would not fail, it would address a table of the wrong length.
 *
 * <p>ITS OWN RECORD RATHER THAN PackedReservoir. A surface reservoir spends two lanes on a receiver
 * normal and an emitter normal; a point in the air has neither, and the sky has no emitter to have one.
 * Borrowing that layout would have meant two dead lanes and a name that lies about what is stored.
 */
public struct VolumeCell {
    public float4 dirW;      // xyz = the stored sky direction, w = W, the finished ReSTIR weight
    public float4 leTarget;  // xyz = sky radiance along it,    w = phat at the vertex that chose it
    public float4 stats;     // x = M,  yzw = the transmittance the shadow ray measured (D198)
    public float4 ident;     // x = key (0 = empty), y = frame stamp, zw = reserved
};

public uint volumeCellStoredKey(VolumeCell c) { return asuint(c.ident.x); }
public uint volumeCellStamp(VolumeCell c) { return asuint(c.ident.y); }

/**
 * The slot this cell lives in, or -1.
 *
 * <p>Linear probing, bounded. An unbounded walk on a saturated table degenerates into a scan of the whole
 * buffer for every sample -- the failure mode is not a wrong picture but a frame time that collapses when
 * the table fills, which is exactly when the player is somewhere new and least able to explain it.
 */
public int volumeGridFind(ConstPtr<VolumeCell> grid, uint slots, int3 coord, int level,
                          uint frame, uint evictionFrames) {
    if (slots == 0u) {
        return -1;
    }
    uint key = volumeCellKey(coord, level);
    uint slot = volumeCellSlot(coord, level, slots);
    for (uint step = 0u; step < VOLUME_PROBE_LIMIT; ++step) {
        uint at = (slot + step) % slots;
        VolumeCell c = grid[at];
        uint stored = volumeCellStoredKey(c);
        if (stored == 0u) {
            return -1; // an empty slot ends the probe: nothing past it can belong to this cell
        }
        if (stored == key && !volumeCellExpired(volumeCellStamp(c), frame, evictionFrames)) {
            return int(at);
        }
    }
    return -1;
}

/**
 * The slot to write, claiming an empty or expired one if this cell has none yet. -1 when the probe window
 * is full of live strangers, and the caller then simply does not contribute this frame.
 *
 * <p>Dropping the sample is the right answer to a full window. The alternative -- evicting a live cell to
 * make room -- trades one cell's accumulated reuse for another's first sample, and under pressure the two
 * evict each other every frame, so both stay at M=1 and the table does more work to hold less.
 */
public int volumeGridClaim(ConstPtr<VolumeCell> grid, uint slots, int3 coord, int level,
                           uint frame, uint evictionFrames) {
    if (slots == 0u) {
        return -1;
    }
    uint key = volumeCellKey(coord, level);
    uint slot = volumeCellSlot(coord, level, slots);
    for (uint step = 0u; step < VOLUME_PROBE_LIMIT; ++step) {
        uint at = (slot + step) % slots;
        VolumeCell c = grid[at];
        uint stored = volumeCellStoredKey(c);
        if (stored == key || stored == 0u
                || volumeCellExpired(volumeCellStamp(c), frame, evictionFrames)) {
            return int(at);
        }
    }
    return -1;
}

/**
 * One RIS candidate into a cell.
 *
 * <p>phat CARRIES THE TRANSMITTANCE (D198), which is the decision that separates this from the shipped
 * surface estimator. There, le and the geometry term separate emitters by orders of magnitude and the
 * target is informative without visibility. Over a cave's hemisphere the sky radiance is near-uniform and
 * the phase is smooth, so a target without transmittance is nearly constant and the reservoir accumulates
 * the one fact that is useless underground. This is the whole reason the milestone can address #41.
 *
 * <p>The estimator stays unbiased for any strictly positive target -- lighting.slang's banner says so --
 * and a fully sealed cell drives every candidate to phat = 0, leaves the reservoir empty, and reports no
 * sky light. Which is correct.
 */
public void volumeCellUpdate(inout VolumeCell c, float3 dir, float3 le, float3 transmittance,
                             float sourcePdf, inout uint seed) {
    float phat = luminance(le * transmittance);
    if (phat <= 0.0) {
        c.stats.x += 1.0; // seen and rejected still counts: M is the denominator, not a success count
        return;
    }
    float w = phat / max(sourcePdf, 1.0e-20);
    float wSumBefore = c.leTarget.w > 0.0 ? c.dirW.w * c.stats.x * c.leTarget.w : 0.0;
    float wSum = wSumBefore + w;
    c.stats.x += 1.0;
    if (wSum > 0.0 && rndf(seed) < (w / wSum)) {
        c.dirW.xyz = dir;
        c.leTarget.xyz = le;
        c.leTarget.w = phat;
        c.stats.yzw = transmittance;
    }
    c.dirW.w = c.leTarget.w > 0.0 ? (wSum / (c.stats.x * c.leTarget.w)) : 0.0;
}

/** Stamp a cell as belonging to this key and touched this frame. */
public void volumeCellClaimStamp(inout VolumeCell c, int3 coord, int level, uint frame) {
    c.ident.x = asfloat(volumeCellKey(coord, level));
    c.ident.y = asfloat(frame);
}
