# Platform notes

Differences between the Fabric and NeoForge builds that are known, understood, and deliberately not
fixed. Anything not listed here is a bug.

Terrain geometry is extracted through `QuadPipeline`: on Fabric via the Fabric Renderer API's
`BlockStateModel.emitQuads`, on NeoForge via vanilla's `collectParts` + `BlockStateModelPart.getQuads`.
The two are meant to produce the same triangles. `fluorite.rt.terrainDigest` measures whether they do.

## Before capturing: give both loaders the same world

The two run directories keep their own copy of the save, and **every run mutates its copy** — chunk ticks,
fluid flow, random ticks, plants growing and popping off. Left alone the copies drift apart within a
session, and then a terrain difference has two possible causes with no way to tell them apart. This is not
hypothetical: it silently produced several "geometry differences" during the M3/M4 work before anyone
checked whether the two saves were still the same file.

Keep a pristine master of the test world outside both run directories and restore it into both before
every capture pair. Anything measured without doing that describes two worlds, not two loaders.

The one class of finding this does not affect is a decision traced at the same block with the same
neighbour — `cull-trace` output stands on its own, because it reports the input state alongside the
verdict.

## Reading a cross-loader terrain digest

Three of the digest's columns **cannot** be compared between loaders, and comparing them produces alarming
numbers that mean nothing:

- **Absolute UVs** (`uv`, `uvCoarse`, `uvMedium`). NeoForge contributes a `mod_resources` pack that Fabric
  does not, so the block atlas stitches differently and every sprite lands somewhere else. Expect 100% of
  sections to differ. Compare `uvDelta` instead — per-triangle UVs relative to that triangle's own first
  corner, which drops atlas position while keeping sprite size and orientation. Valid only while both
  atlases come out the same size; check the `Created: NxN … blocks.png-atlas` line in each log.
- **Ordered positions** (`pos`). The two quad sources emit a block's quads in different orders — the
  Renderer API in the model's own order, `collectParts` as the direction-independent list followed by the
  six cullface lists. Same triangles, different sequence. Compare `posSet`, which is commutative.
- **Material ids** (`mat`). A registry index, assigned in discovery order. Not stable across loaders.

Use `posSet`, `uvDelta`, `idx` and `light`. Restrict to sections with `builds == 1`; anything higher is
being re-meshed by a moving world (flowing fluids) and cannot be attributed to code.

## Measured residual (2026-07-29, 26.2, seed-fixed test world)

986 comparable sections, 975 geometrically identical.

**The loaders disagree about face culling, and that is the loaders' own behaviour, not Fluorite's.**
`RtTerrainMesher.QuadCapture.isCulled` calls `Block.shouldRenderFace(state, neighbour, direction)` — one
shared line of code — and gets different answers:

| block | face | neighbour | Fabric | NeoForge |
| --- | --- | --- | --- | --- |
| `-4,-13,108`, `-4,-13,109` waxed oxidized copper grate | east | waxed oxidized cut copper stairs | render | cull |
| `2,-13,108`, `2,-13,109` waxed oxidized copper grate | west | waxed oxidized cut copper stairs | render | cull |
| `64,-14,95`…`66,-14,95` waxed copper grate | up | waxed oxidized cut copper stairs | render | cull |

Where the neighbour is another grate, or tuff bricks, the two agree exactly. The divergence is confined to
a non-occluding block facing a stair, which is what NeoForge's `IBlockExtension.hidesNeighborFace` /
`supportsExternalFaceHiding` patch to `shouldRenderFace` exists to change. A NeoForge player sees the same
culling under vanilla rendering, so **each loader is individually correct** and there is nothing here to
fix. Reproduce with `diagnostics.cull-trace` (below).

Eight further sections, all at `sy == -64`, differ by a few triangles in *both* directions — NeoForge
emits more in some, fewer in others — with identical light arrays. Consistent with the same face-hiding
divergence, which is bidirectional, but not individually confirmed.

No visible artifact in any of this; it was found by digest, not by eye.

`emissive()` is a second, deliberate loss: the Renderer API exposes a per-quad emissive flag that vanilla
has no equivalent for, so the NeoForge quad view returns `false` and emission falls back to
`BlockState.getLightEmission()`. Fluorite's real emission sources — the heuristic, LabPBR `_s`, and
`emission.strength` JSON overrides — are unaffected. Only resource packs that actively drive Renderer API
materials lose anything.

## Investigating a difference

`fluorite.rt.terrainDigestSections` (`diagnostics.terrain-digest-sections`, `"sx,sy,sz;…"`) writes each
named section's triangles out individually — sorted centroids and normals, one file per section per
loader. Diffing two of those names the exact faces one side is missing. The digest can only say a section
differs.

`fluorite.rt.cullTrace` (`diagnostics.cull-trace`, `"x,y,z;…"`) then logs every culling decision for the
blocks those faces belong to: state, neighbour, direction, verdict. That is what turned "seven faces are
missing" into the table above — matching verdicts would have meant the quad sources bucket a quad under
different cullfaces, and differing verdicts meant the culling itself, which is where it actually was.

Both are empty by default. Run the pair, diff the section dumps to find the faces, feed those blocks to
the cull trace, run the pair again.
