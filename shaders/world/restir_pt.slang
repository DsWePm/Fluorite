// The S2 path reservoir: where a pixel's reusable INDIRECT suffix lives between frames, for ReSTIR PT's
// hybrid shift. Companion to restir.slang, which holds M24's DIRECT-lighting reservoirs -- this module is
// about the PATH estimate, and keeping the two apart is deliberate: their lifetimes, their consumers and
// their failure modes are different, and M24's store predates this one by three milestones.
//
// WHAT ONE RECORD HOLDS. The reservoir stands for a path SUFFIX: everything beyond the reconnection
// vertex x_s. Concretely:
//
//   pathSeed   -- the RNG state the path was traced with. Replay copies this stream onto the shifted
//                 primary hit: the first K draws regenerate the base path's decisions and are applied to
//                 the shifted geometry. SEEDED REPLAY IS A CONTRACT: the seed advances deterministically
//                 (rndf/pcg chains only), so any edit to segment.slang's or world.rgen's draw ORDER
//                 silently changes which decision each replayed random number drives. The contract test
//                 pins the idiom; edit the derivation and update that test in the same commit.
//   reconPos   -- x_s, where the shift reconnects: the first rough vertex pair of the stored path.
//   reconNrm   -- x_s's shading normal, for the connection G term.
//   li         -- incident radiance arriving at x_s from beyond it (Rgb9e5, HDR).
//   wi         -- its direction, oct16x2.
//   m          -- effective sample count for GRIS weights.
//   targetSum  -- accumulated target-function sum the merge produced.
//   reconIndex -- which path vertex x_s was (replay depth).
//   spare      -- one lane; the reservoir is 48 bytes because that is the queue record's cache line and
//                 a straddled access on a per-frame read/write buffer buys nothing.
//
// Double-buffered by parity like restir.slang: read is last frame's, write is this frame's.

import world_common;
import math;

public static const uint PATH_RESERVOIR_BYTES = 48u;

public struct PackedPathReservoir {
    public uint    pathSeed;
    public float3  reconPos;
    public uint    reconNrm;      // oct16x2 packed
    public uint    li;            // Rgb9e5
    public uint    wi;            // oct16x2 packed
    public float   m;
    public float   targetSum;
    public uint    reconIndex;
    public uint    spare;         // held 0; a nonzero value means a half-written record
}

/** Slot address: one reservoir per pixel per parity. S2 is temporal-only, so no depth or path planes --
 * the pixel is the whole key. */
public uint pathReservoirSlot(uint pixelIndex, uint pixelCount, uint parity) {
    return pixelIndex + parity * pixelCount;
}
