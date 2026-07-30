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
