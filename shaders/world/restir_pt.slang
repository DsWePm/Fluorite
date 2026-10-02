// D231 hybrid path reuse. The record ABI lives in world_common for Slang reflection.
// R2a removes the invalid S2/S3 in-place suffix estimator. Tagged event sampling and
// density/footprint helpers arrive with the record producer; shifts arrive in R3/R4.
import world_common;
import world_core;

// ---- The path reuse's switch word (D231, G14): WorldPush.pathReplayEnabled is a bitfield now, whose
// fields arrive with the slices that consume them. RtComposite.pathReuseSwitchWord packs the same shift
// and mask; RtPathReplayContractTest pins the two spellings together.
public static const uint PATH_REUSE_NEIGHBOURS_SHIFT = 8u;
public static const uint PATH_REUSE_NEIGHBOURS_MASK = 15u;
/** Bit 0: the prefix identity self-check (R1b-3, diagnostics; stats lanes 0-1). */
public static const uint PATH_REUSE_IDENTITY_BIT = 1u;

/** The path reuse's own spatial neighbour count -- no longer M24's restirSpatialNeighbours. */
public uint pathReuseSpatialNeighbours() {
    return (worldPush.pathReplayEnabled >> PATH_REUSE_NEIGHBOURS_SHIFT) & PATH_REUSE_NEIGHBOURS_MASK;
}
