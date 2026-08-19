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
