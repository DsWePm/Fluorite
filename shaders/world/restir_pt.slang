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
import segment; // octEncode / packUnorm16x2 / packRgb9e5: the same packing idioms the queue record uses

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
 * the pixel is the whole key. With spp > 1 the samples of a pixel share the slot and the last write
 * wins; the published SPP is 1, and per-path planes would quadruple a buffer the switch already has to
 * justify. Revisit only if an spp A/B ever shows the sharing mattering. */
public uint pathReservoirSlot(uint pixelIndex, uint pixelCount, uint parity) {
    return pixelIndex + parity * pixelCount;
}

/** An unwritten or unusable record. `m` counts the candidates a reservoir merged; the initial write
 * stores 1, and cleared/stale memory reads as 0 -- the same emptiness test restir.slang uses. */
public bool pathReservoirEmpty(PackedPathReservoir p) { return !(p.m > 0.0); }

/**
 * The one form the bounce loop's write takes. Everything is packed here, next to the struct it
 * translates, so a packing change cannot drift from the record layout it feeds.
 *
 * <p>`li` is the suffix's OWN estimate of incident radiance at the reconnection vertex -- the
 * throughput that carried it is deliberately NOT folded in, because whoever merges this record
 * re-weights it with their own prefix. `targetSum` is the candidate's contribution at its own vertex
 * (the luminance of the suffix as this frame's path actually carried it), which is the target function
 * value a GRIS merge needs from the producing domain.
 */
public PackedPathReservoir packPathReservoir(uint pathSeed, float3 reconPos, float3 reconNrm,
                                             float2 wiOct, float3 li, float m, float targetSum,
                                             uint reconIndex) {
    PackedPathReservoir r;
    r.pathSeed = pathSeed;
    r.reconPos = reconPos;
    r.reconNrm = packUnorm16x2(octEncode(reconNrm));
    r.li = packRgb9e5(li);
    r.wi = packUnorm16x2(wiOct);
    r.m = m;
    r.targetSum = targetSum;
    r.reconIndex = reconIndex;
    r.spare = 0u;
    return r;
}
