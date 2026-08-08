// Move a terrain section's water vertices onto the wave surface (M12.5), so the water is a shape rather
// than a flat quad wearing a normal.
//
// READS FROM A REST COPY, WRITES TO THE LIVE BUFFER, and that is not a stylistic choice. Displacing in
// place would mean this frame's output is next frame's input, and the surface would walk away from the
// block face within a second. The rest copy is made once, at section build time, by a straight
// buffer-to-buffer copy -- which is only possible because the mesher packs each bucket's vertices in one
// contiguous run, so the water bucket is a single range with nothing of the terrain's inside it.
//
// WHY NOT SUBDIVIDE (D48). A Minecraft water quad is one metre across, so this mesh band-limits the field
// at about two metres -- and the long components carry 96% of its rms height, so the geometry loses
// almost nothing of the SHAPE. What it cannot carry is the interactive ripples, which live at half a
// metre to two metres; those stay in the normal until the subdivided path lands on top of this one. The
// band limit is not approximated here, it is the same waterWaveLodWeight the shading LOD uses, handed the
// cell size instead of a pixel footprint.
//
// The normal is NOT computed here and must not be. Shading keeps evaluating the analytic normal of the
// whole field and REPLACES the facet normal with it, so this mesh carrying the low frequencies does not
// steepen anything, and near and far water are shaded by one expression.

import world_common;
import water_wave;

public struct WaterDeformPush {
    // The section's live positions (float3 per vertex) and its untouched copy of the water range. The
    // rest buffer is indexed from zero; the live one from vertBase.
    public uint64_t positionsAddr;
    public uint64_t restAddr;
    // THE SAME WorldPush THE SHADING READS, by address, rather than a copy of the handful of lanes this
    // pass happens to need.
    //
    // Copying them was the earlier design and it was wrong twice over. It made every wave parameter a
    // thing that had to be written into two places and kept in step -- exactly the shape that produced
    // the acceleration-structure flag bug (8.2c-2) and that water_wave.slang's header warns about, since
    // geometry and shading disagreeing about the field is undiagnosable from a screenshot. And it does
    // not scale: push constants are only guaranteed to be 128 bytes, the copy had already reached 112,
    // and the wave field is about to gain six more parameters.
    //
    // Reading worldPush also removes simDomain, simPlane and time from this struct, because they were
    // copies of lanes too. What is left is genuinely per-section.
    public uint64_t worldPushAddr;
    // Added to a vertex's section-local xz to reach the world-stable wave coordinate: the section origin
    // relative to the terrain rebase, plus the wave anchor. Assembled on the CPU because that is where
    // both halves are known, and because a vertex position plus a rebase origin plus an anchor is three
    // chances to be in the wrong space (F21).
    public float2 worldOffset;
    // Where the deformation fades out, in the same space as worldOffset + local xz. Beyond fadeEnd the
    // vertex is left exactly at rest, so the deformed band meets the flat far field with no step.
    public float2 fadeCentre;
    public float fadeStart;
    public float fadeEnd;
    public float cellSize;      // the mesh's spacing: this is the band limit, not a quality dial
    public uint vertBase;
    public uint vertCount;
    public uint pad;
};

[[vk::push_constant]] WaterDeformPush pc;

// The simulated height field. Bound here rather than reached through water.slang, which would drag
// world_core's whole descriptor set into a compute pass that needs one texture.
[[vk::binding(0, 0)]] Sampler2D<float> waterSimHeightTex;

[shader("compute")]
[numthreads(64, 1, 1)]
void main(uint3 tid: SV_DispatchThreadID) {
    uint i = tid.x;
    if (i >= pc.vertCount) {
        return;
    }
    DevicePtr<float> live = DevicePtr<float>(pc.positionsAddr);
    ConstPtr<float> rest = ConstPtr<float>(pc.restAddr);

    uint restBase = 3u * i;
    float3 p = float3(rest[restBase], rest[restBase + 1u], rest[restBase + 2u]);

    WorldPush wp = ConstPtr<WorldPush>(pc.worldPushAddr)[0];

    float2 worldXZ = p.xz + pc.worldOffset;
    float2 d = worldXZ - pc.fadeCentre;
    float fade = 1.0 - smoothstep(pc.fadeStart, pc.fadeEnd, length(d));

    // Zero fade still writes. The vertex may have been displaced last frame and the region may have moved
    // since, and a vertex left holding a stale displacement is a permanent lump in the water.
    float h = fade <= 0.0
            ? 0.0
            : fade * waterSurfaceHeight(waterSimHeightTex, wp.waterSimDomain, wp.waterSimPlane,
                                        float3(worldXZ.x, wp.waterSimPlane.x, worldXZ.y),
                                        wp.waterParams.w, pc.cellSize,
                                        wp.waterAmbient.x, wp.waterAmbient.y,
                                        float2(cos(wp.waterAmbient.z), sin(wp.waterAmbient.z)));

    // Y only. Real deep-water particles move in circles, so a physical surface also shifts horizontally
    // (that is what sharpens the crests), but moving xz here would shear the quads away from the block
    // grid they tile and open gaps at every shoreline. The crest sharpening is already in the profile.
    uint liveBase = 3u * (pc.vertBase + i);
    live[liveBase + 1u] = p.y + h;
}
