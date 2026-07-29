# The wavefront split

World rendering runs as two ray-tracing dispatches over the same render-resolution grid, with a buffer of
continuation records between them. `world_primary.rgen.slang` is pass A, `world.rgen.slang` is pass B, and
both name this document in their first line.

The split exists because a dielectric interface produces two continuations — a reflected and a refracted
branch — and tracing both from one raygen would mean instantiating the path tracer twice inside a single
shader. Instead the second branch is written out as **data**. `throughput` already carries the branch
weight, so the two are simply summed at the end; no reweighting happens across the boundary.

## What each pass does

**Pass A** traces exactly one camera ray and captures the DLSS-RR guides — normal, albedo, roughness,
depth, motion, specular albedo and specular motion — from its first hit. It then walks the *visually
primary* dielectric chain: water, stained glass, ice. At each interface it evaluates Fresnel, follows one
branch, and queues the other. It does no NEE and no RIS, deliberately: pass A owns the guide buffers, and
radiance written here would land in a pass whose whole job is to describe surfaces rather than light them.

**Pass B** reads the queue and runs the full bounce loop: sun NEE, RIS over block emitters, subsurface,
Russian roulette, the bounce cap. It resamples each record at the configured `spp`.

## The queue contract

The buffer holds `2 * width * height` records of `PackedPathSegment`. Record indices are fixed, not
allocated:

| index | holds |
| --- | --- |
| `pixelIndex` | the pixel's terminal record — always written |
| `width * height + pixelIndex` | the split record — written only if pass A split |

`pixelIndex` is `y * width + x`. Both passes derive these from their own dispatch dimensions and never
exchange an index; that agreement is structural rather than carried in the record, which is why
`PackedPathSegment` has no `nextRecord` field. Whether the second record exists is one bit,
`PATH_HAS_NEXT`, in `pathFlags`.

`MAX_PATH_SEGMENTS` is 2 and pass B's leaf loop is bounded by it. That constant and the queue's `2 *`
sizing are the same fact stated twice; they have to change together.

## Split eligibility

**Only the material model decides**, never the lobe set. Pass A splits at `MATERIAL_WATER` and
`MATERIAL_DIELECTRIC` and nothing else — `payload.flags` bits 0-1 are the sole classifier. Pass A can
therefore split at most once, which is what bounds the queue at two records per pixel.

This matters more than it looks. If "has a transmission lobe" became a per-lobe property of ordinary
materials, any number of surfaces could split and the queue budget would collapse. When the Disney BSDF
work introduces a specular transmission lobe, it must keep eligibility keyed to the model, and should
narrow it further: split only when `isDeltaAlpha(alpha)`. Rough dielectrics — frosted glass — return as a
terminal record and let pass B resolve the whole interface stochastically as one branch. That also
preserves the guide contract exactly, because `gv_rough = 0.0` and the `resolveTransmissionGuide` chain
were written to describe delta interfaces and nothing else.

## What the terminal record means

Pass A writes the record describing the state **immediately before** the trace it did not consume. The
terminal hit — a non-dielectric surface, or a miss — is re-traced by pass B from the same origin and
direction.

That is deliberate, and two things depend on it:

- closest-hit payload data never has to be packed into the queue
- the segment's medium absorption is applied exactly once, by pass B

So pass A applies Beer-Lambert only to segments it *consumes* (the dielectric continuations), and leaves
the terminal one alone. Pass B applies it to every segment including the one that escapes to the sky —
see the ordering note in its bounce loop, and note that an escaping segment travelled `RAY_FAR`, the same
distance the trace used as its tmax.

## The record ABI

`PackedPathSegment` lives in `world_common.slang`, not beside its pack/unpack in `segment.slang`, so that
`world_layout_probe.slang` can reflect it — `segment.slang` reaches `world_core` and its set-0 bindings
would collide with the probe's. `PackedPathSegmentData.BYTE_SIZE` is generated from that reflection and
sizes the queue allocation.

The record is 48 bytes and there is room for exactly one more uint before that becomes 64. Read
`RtPathSegmentLayoutTest` before spending it; the participating-media work has a claim on it.

## Where the prefix segment goes

Pass A consumes the camera-to-first-interface segment: it applies that segment's transmittance and queues
the branches at the interface. Any in-scattering along that prefix is therefore not represented by either
record.

Volumetrics has to reconstruct it in pass B, before the leaf loop: rebuild the camera origin from
`invViewProj` and the jitter exactly as pass A does, take `length(first.ro - camOrigin)` as the prefix
length, and integrate the ambient medium over it. This is sound because pass A splits at most once, so the
prefix is always a single straight segment, common to both branches and additively independent of them.
When pass A did not split, the length is zero and the common path pays one `length()`.
