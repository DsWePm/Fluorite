// D176: the sun-direction transmittance of the sky, baked once per frame into a light-space 2D map, so
// that ground, water and caustics can ask "how much sun reaches here" for one texture read instead of a
// cloud march apiece.
//
// WHY A MAP AND NOT A MARCH PER QUERY. The alternative backend (8.10, backend one) marches the real
// volume from every shaded point. It is strictly more accurate -- it carries parallax and local thickness
// -- and it costs a cloud march per surface light sample, which is the most expensive thing this renderer
// could add to the shading loop. A cloud's shadow has almost no high-frequency content to lose: the sun's
// 0.6-degree angular radius smears every edge over 16 blocks at a 30-degree sun and 46 blocks at 10, so
// the map's 16-block texels are already finer than the penumbra they carry. That is also why ONE sun
// sample is enough here -- the softness is in the filter, not in the sampling.
//
// THE SAME DENSITY THE CLOUD IS DRAWN FROM. Every field this reads comes from cloud_density, which the
// shading imports too. That is not tidiness: a shadow map built from its own copy of the density would
// agree with the sky on the day it was written and drift the first time one of them was edited, and
// "the shadow is not under its cloud" is not something anyone can attribute from a screenshot. The whole
// reason cloud_density exists as a bindings-free module is so this pass can share it -- see its banner,
// and water_wave.slang's, which is the same argument for the same reason.
//
// EVERY FRAME, NO TEMPORAL FILTER. What moves this map is mostly the SUN: a shadow crosses the ground at
// about 9.3 blocks a second, so it clears a texel in 1.7 seconds, where cloud advection alone would take
// 16. The bake is about a hundredth of the frame's ray tracing, and paying it every frame removes an
// entire category of bug -- which frame is this texture from, and what happens on the one where the
// answer changes. It is deterministic and unjittered, so there is nothing to accumulate either.

import world_common;    // WorldPush, by address
import cloud_density;   // the field, the layers, and the light-space frame the consumers also use

public struct CloudShadowPush {
    // THE SAME WorldPush THE SHADING READS, by address, rather than a copy of the lanes this pass needs.
    // water_deform.comp.slang's header spells out why at length: a copy is a second place every cloud
    // parameter has to be written, and geometry and shading disagreeing about a field is undiagnosable
    // from a screenshot. Here it would be worse -- the disagreement would be between a cloud and its own
    // shadow.
    public uint64_t worldPushAddr;
};

[[vk::push_constant]] CloudShadowPush pc;

// This pass's own descriptor set. It holds exactly the four fields the cloud is built from and the map
// it writes; it does not and must not see the raygen's set 0.
[[vk::binding(0, 0)]] Sampler3D cloudNoise;
[[vk::binding(1, 0)]] Sampler2D cloudWeather;
[[vk::binding(2, 0)]] Sampler3D cloudWarp;
[[vk::binding(3, 0)]] Sampler2DArray highCloudPatchTau;
[[vk::binding(4, 0)]] RWTexture2D<float> cloudShadowOut;

// How many density samples one texel's crossing of the deck gets. Eight, and the number is a budget
// rather than a quality: 512^2 texels times eight is 2.1 M density evaluations a frame, which is about
// one per cent of what a 1920x1080 primary trace at 96 steps already spends. Doubling it would be
// affordable and would buy very little -- the crossing is one deck thickness at a high sun, so eight
// steps already resolve it at 40 blocks against a 16-block texel.
static const int CLOUD_SHADOW_STEPS = 8;

// Threads per group, both axes. 8x8 = 64 lanes, so a group is one or two waves on every vendor.
static const int CLOUD_SHADOW_GROUP = 8;

CloudTextures shadowTextures() {
    CloudTextures t;
    t.noise = cloudNoise;
    t.weather = cloudWeather;
    t.warp = cloudWarp;
    t.highTau = highCloudPatchTau;
    return t;
}

/**
 * Optical depth of the low deck along one light ray, integrated over its WHOLE crossing.
 *
 * <p>A FLAT SLAB, where the shading marches a spherical shell, and the difference is measured rather
 * than assumed: over this map's 4096-block half-extent the shell drops 4096^2/(2R) = 1.3 blocks against
 * a plane. The curvature in cloud.slang exists so that a VIEWING ray forty kilometres long meets a
 * horizon; nothing here is forty kilometres from anything, and a slab makes the crossing two divisions
 * instead of four sphere roots and three branches.
 *
 * <p>THE WHOLE LINE, not a forward ray, which is why the entry distance is allowed to come out negative.
 * The map's plane passes through the rebased origin and is transverse to the light, so a texel's plane
 * point can sit above the deck, below it, or inside it depending on where the sun is; what the map has to
 * store is the total extinction along that light ray, and every point of it projects to this same texel.
 */
float lowDeckOpticalDepth(CloudTextures tex, CloudLayer low, float3 p0, float3 lightDir) {
    // Absolute altitude of the plane point. worldIn adds the field origin, which is the terrain rebase
    // minus this deck's own wind drift -- so the deck stands where the sky says it stands.
    float a0 = worldIn(low, p0).y;
    float invY = 1.0 / lightDir.y;   // the caller has already established lightDir.y > 0
    float tEnter = (low.bottom - a0) * invY;
    float crossing = low.thickness * invY;
    float step = crossing / float(CLOUD_SHADOW_STEPS);

    // What one sample stands for: a texel across, and a step along. Prefiltering to the LARGER of the
    // two is the same correction D172 made to the march -- point-sampling a field whose features are
    // finer than the sample is aliasing, and here it would show up as a shadow that boils between
    // frames rather than as one that moves.
    float footprint = max(step, CLOUD_SHADOW_BLOCKS_PER_TEXEL);

    float tau = 0.0;
    for (int i = 0; i < CLOUD_SHADOW_STEPS; ++i) {
        // Midpoint, unjittered. The shading jitters because a fixed lattice across NEIGHBOURING PIXELS
        // draws itself as banding; a texel here has no neighbours it shares a lattice with -- every one
        // integrates its own crossing at its own altitude -- and an unjittered bake is stable frame to
        // frame, which is what lets this map skip temporal filtering entirely.
        float3 q = p0 + lightDir * (tEnter + step * (float(i) + 0.5));
        // FULL density, erosion included. The self-shadow march inside a cloud takes the cheap path
        // because it runs several times per march step and only decides how dark an interior is; this
        // runs once per texel per frame and decides how dark the GROUND is. Erosion only ever removes
        // material, so skipping it would darken every cloud shadow by a bias nothing else corrects.
        tau += cloudDensity(tex, low, q, false, footprint) * step;
    }
    return tau * low.extinction;
}

/**
 * Optical depth of the high sheet along the same light ray.
 *
 * <p>The sheet is infinitesimally thin, so there is nothing to march: find where the light ray crosses
 * its altitude, read the patch field's vertical optical depth there and apply the sun's secant. The cap
 * of 24 is the same one shadeHighCloudSheet uses, and for the same reason -- it keeps a grazing sun's
 * path finite without a fade that would have to be explained.
 */
float highSheetOpticalDepth(CloudTextures tex, CloudLayer high, float3 p0, float3 lightDir) {
    if (high.extinction <= 0.0) {
        return 0.0;
    }
    float a0 = worldIn(high, p0).y;
    float3 crossingPoint = p0 + lightDir * ((high.bottom - a0) / lightDir.y);
    // The footprint is a texel rather than a ray distance: this sample stands for 16 blocks of ground,
    // not for a pixel at some remove. Same argument as the low deck's, one function up.
    float verticalTau = highCloudVerticalTau(tex, high, crossingPoint, CLOUD_SHADOW_BLOCKS_PER_TEXEL);
    return verticalTau * min(1.0 / lightDir.y, 24.0);
}

[shader("compute")]
[numthreads(CLOUD_SHADOW_GROUP, CLOUD_SHADOW_GROUP, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (any(id.xy >= uint2(CLOUD_SHADOW_DIM, CLOUD_SHADOW_DIM))) {
        return;
    }
    WorldPush wp = ConstPtr<WorldPush>(pc.worldPushAddr)[0];

    // Clear sky is the identity for every consumer, so it is what an unavailable map reports: clouds
    // off, or a sun so low that its own transmittance has already taken the light to nothing. Writing
    // rather than returning early matters -- a texel left holding last frame's value would be a shadow
    // that stayed on the ground after the cloud was switched off.
    CloudShadowFrame frame = cloudShadowFrame(wp.lightDir.xyz, wp.camOffset);
    CloudLayer low = lowCloudLayer(wp);
    // The feature's own switch is tested HERE as well as at the lookup, and the two are not redundant.
    // Here it skips the march, which is what makes the off state cost nothing; there it skips the read,
    // which is what makes an A/B measure the whole feature. Both call the same function.
    if (!frame.valid || !cloudsOn(wp) || !cloudShadowsOn(wp) || low.extinction <= 0.0) {
        cloudShadowOut[int2(id.xy)] = 1.0;
        return;
    }

    CloudTextures tex = shadowTextures();
    float3 p0 = cloudShadowTexelPoint(frame, id.xy);
    float tau = lowDeckOpticalDepth(tex, low, p0, frame.lightDir)
            + highSheetOpticalDepth(tex, highCloudLayer(wp), p0, frame.lightDir);

    // Beer, and only Beer. This is the DIRECT beam's survival, which is exactly what a receiving surface
    // needs to attenuate its sun term by. The light a cloud scatters rather than absorbs is not missing
    // from the world -- it arrives as skylight, and mediumSkyRadiance already carries it.
    cloudShadowOut[int2(id.xy)] = exp(-tau);
}
