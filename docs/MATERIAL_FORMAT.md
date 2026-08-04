# Material overrides

Fluorite reads per-material JSON from any resource pack, at
`assets/<namespace>/fluorite/materials/*.json`. Files are applied in sorted order by identifier, so a
later pack overrides an earlier one, and an invalid file is skipped with a warning rather than taking the
pack down with it.

This exists because the formats Minecraft resource packs already use do not describe everything a
principled BSDF needs. LabPBR 1.3 carries roughness, metalness, F0, emission and a subsurface mask — and
has no channel for sheen, clearcoat, anisotropy or a subsurface radius. Rather than invent a competing
texture format, Fluorite takes those from JSON and leaves the textures alone.

## The shape of the pipeline

```
LabPBR _s / _n        ─┐
vanilla heuristics    ─┼─→  one decoder each  ─→  unified material  ─→  Disney BSDF
these JSON files      ─┘
```

Every source is decoded **once, on the CPU**, into canonical physical channels. No shader in this
renderer knows what a LabPBR channel means: the hit shader reads roughness, metalness, F0 and the rest as
physical quantities. Supporting another source format — SEUS PBR, or something a pack invents — is a
decoder beside `RtLabPbr`, not a second path through the shaders.

That is also why the defaults matter more than they look. A pack that says nothing about sheen gets no
extension record at all, and the material behaves exactly as it did before the field existed. **An
unmodified LabPBR pack renders identically**; that is a property the tests pin, not an intention.

## Format versions

`format` is required. Version 1 files remain loadable and mean what they always meant. Version 2 adds the
Disney blocks below.

## Matching

```json
{"format": 2, "match": {"sprite": "minecraft:block/oak_leaves"}}
```

`match.sprite` is required. `match.block` narrows the rule to one block state's use of that sprite —
without it the rule applies wherever the sprite appears.

## Fields

Everything is optional. Anything absent is inherited, not defaulted.

```json
{
  "format": 2,
  "match": {"sprite": "minecraft:block/oak_leaves"},

  "model": "opaque",
  "base": {"roughness": 0.6, "metalness": 0.0},
  "emission": {"strength": 2.0},
  "transmission": {"factor": 1.0, "ior": 1.52},

  "sheen":      {"amount": 0.3, "tint": 1.0},
  "clearcoat":  {"amount": 0.0, "gloss": 1.0},
  "specular":   {"tint": 0.0},
  "anisotropy": {"amount": 0.0},
  "subsurface": {"weight": 0.0, "phase": 0.0, "radius": [0.0, 0.0, 0.0]}
}
```

| field | range | meaning |
| --- | --- | --- |
| `model` | `opaque`, `water`, `dielectric` | `water` is the animated fluid surface — waves, caustics, biome-tint absorption. `dielectric` is every other transparent material. |
| `base.roughness` | 0–1 | **Linear roughness, which is the GGX alpha** — the same units LabPBR stores, *not* perceptual roughness. `alpha = (1 - smoothness)^2`. |
| `base.metalness` | 0–1 | |
| `emission.strength` | 0–5 | A **multiplier** over whatever emission the material already resolves to. It cannot light up a block that was not emissive. |
| `transmission.factor` | 0–1 | |
| `transmission.ior` | > 0 | |
| `sheen.amount` | 0–1 | Retroreflective grazing lobe. Cloth, moss, foliage silhouettes. Subtle by design; 1.0 is far more than you want. |
| `sheen.tint` | 0–1 | 0 tints the sheen white, 1 tints it toward the albedo's hue. |
| `clearcoat.amount` | 0–1 | A second, thin specular layer over the base. |
| `clearcoat.gloss` | 0–1 | |
| `specular.tint` | 0–1 | Tints the base specular toward the albedo. |
| `anisotropy.amount` | −1–1 | Sign selects the stretch axis. |
| `subsurface.weight` | 0–1 | |
| `subsurface.phase` | −1–1 | Henyey-Greenstein g. Positive scatters forward. |
| `subsurface.radius` | ≥ 0, three numbers | Mean free path per channel, in blocks. |

Unknown keys are an error, and the file is skipped with a warning naming it. That is deliberate: a typo
that silently did nothing would be worse than one that says so.

## Rough transparent materials

`base.roughness` means something on `water` and `dielectric` too — frosted glass, cloudy ice — but only
past **0.06**. Below that the interface is treated as an exact refraction, which is both what the
material physically is and what lets the renderer keep a sharp guide through it for the denoiser. Above
it, the interface scatters: reflections and refractions through it blur together, and what is behind it
stops being legible.

The threshold sits where it does because vanilla authors glass and ice at 0.0025 and water at 0.0064.
Ten times over the roughest thing in the game means nothing crosses it by accident, and anything that
does crossed it on purpose. Values between roughly 0.1 and 0.4 read as frosted; past that a pane is
closer to translucent stone than to glass.

The same applies to a LabPBR `_s` texture: a roughness authored there on a transparent block reaches the
interface, where earlier it was decoded and then discarded.

## When both a texture and this file say something

`base.roughness` and `base.metalness` **win over a LabPBR `_s` texture** for the materials a rule names.

They are not peers. LabPBR is a source format — it gets decoded into canonical channels like every other
source, and the decode happens per texel in the hit shader because that is where the texture is. These
files are an authoring decision one stage further along, applied to the already-decoded material. So they
outrank it, the same way they outrank the vanilla heuristics.

Only the fields a rule actually writes are protected. A rule that sets `sheen` and says nothing about
roughness leaves the `_s` texture in charge, because its roughness was inherited rather than chosen — and
a default must never beat a real texture. This is also why an unmodified LabPBR pack is unaffected: with
no rules, nothing is marked.

Practically: if you are testing a material override on top of a LabPBR pack and see no change, this is
the first thing to check — it used to be that the texture silently won.

## What "absent" means

A block that mentions only a tint has expressed nothing:

```json
{"format": 2, "match": {"sprite": "minecraft:block/stone"}, "sheen": {"tint": 1.0}}
```

Sheen is still zero, so no lobe is enabled and no extension record is allocated. Presence is keyed on the
four lobe **weights** — `sheen.amount`, `clearcoat.amount`, `anisotropy.amount`, `subsurface.weight` —
not on whether the block appears.

## Example

```json
{
  "format": 2,
  "match": {"sprite": "minecraft:block/white_wool"},
  "sheen": {"amount": 0.35, "tint": 0.0}
}
```

To see it: stand so the sun is behind the wool and look along the surface at a grazing angle. Sheen peaks
where light and view are on opposite sides, which is the silhouette of a backlit fibre — head-on it does
almost nothing, which is correct.

## Diagnostics

The material log line reports what was loaded:

```
RT materials: epoch=8, ... overrideRules=5, matchedOverrides=5, disneyExtensions=32, ...
```

`matchedOverrides` below `overrideRules` means a rule's sprite never appeared. `disneyExtensions` counts
the materials that ended up with Disney parameters; zero while a pack authors them means the rules
matched nothing, and the two numbers together say which half to look at.
