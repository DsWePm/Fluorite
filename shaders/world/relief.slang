// M30 (workstream C): parallax occlusion mapping over the LabPBR _n texture's alpha channel, and the
// puddle-relief depth function the rain system reads to decide "is this texel submerged".
//
// NO BINDINGS, BY DESIGN (precipitation.slang precedent): the height texture arrives as a parameter from
// the rchit's existing sampler -- adding a binding here would reach every importer of this module, and
// the rchit already owns the descriptor set this module's caller lives in.
//
// THE HEIGHT FIELD. LabPBR stores height in _n's alpha: 255 = the flat surface, 0 = the deepest recess.
// The decode averages 2x2 blocks into the page (RtMaterialTextureData) but keeps the 0..1 range, so
// `ntex.a` here is also 0..1 with 1 = the flat surface and lower values = recesses. "Depth below
// surface" is `1 - ntex.a` -- the quantity the march actually compares against its layer count.
//
// THE MARCH. Linear POM with a post-crossing lerp refinement (PARALLAX_STEPS steps + one interpolate):
// not the steepest or the shortest, but it is the one whose step count is a single named constant and
// whose failure mode is a slightly wrong silhouette rather than a hang. The step count exists as a named
// constant because the per-hit cost is rchit's hottest add -- if it blows the budget, lower it and record
// the trade, or ask about "primary hit only" as the dispatch's upgrade item pre-authorised.
//
// OFF-STATE CONTRACT. switches bit0 clear or depth <= 0: return baseUv unchanged and reliefHeight 1.0.
// Every caller that feeds a displaced UV to subsequent fetches must see this as "nothing happened" --
// same UV, same textures, same shading, bit for bit. The march is not entered.

public static const int PARALLAX_STEPS = 16;

/**
 * Parallax-occlusion march: step the view ray through the height field in tangent space, then refine
 * the crossing by one linear interpolation between the last two steps.
 *
 * <p>Returns the displaced UV for ALL subsequent texture fetches (albedo, _n, _s -- one offset, every
 * fetch, or the normal map disagrees with the albedo about where the surface is) and the relief height
 * at the exit point. reliefHeight 1.0 = the flat surface (no recess, always above water); lower values
 * are deeper recesses.
 *
 * <p>tangentViewDir is the VIEW DIRECTION in tangent space (TBN-transformed by the caller via
 * uvTangent's frame), pointing INTO the surface (z < 0 in a frame that faces the viewer). Its z is what
 * makes the march slope-dependent: a grazing view takes proportionally longer horizontal steps.
 */
public float2 parallaxUv(float2 baseUv, float2 texelSize, Sampler2D ntex, float lod,
                         float3 tangentViewDir, float depthBlocks, uint switches,
                         out float reliefHeight) {
    reliefHeight = 1.0;
    bool parallaxOn = (switches & 0x1u) != 0u && depthBlocks > 0.0;
    if (!parallaxOn || tangentViewDir.z >= -1.0e-4) {
        // Off, or looking perfectly along the surface: no displacement to resolve. The z guard -- the
        // view must point INTO the surface for the march to make sense.
        return baseUv;
    }
    // Normalise so the march length is view-independent; dir.xy is the in-surface drift per unit depth.
    float3 dir = normalize(tangentViewDir);
    float2 deltaUv = dir.xy * depthBlocks / float(PARALLAX_STEPS);
    float layerStep = 1.0 / float(PARALLAX_STEPS);

    // March from the flat surface down: at each step, compare how deep we've gone against how deep the
    // texel under us is. The crossing is where the march goes BELOW the texel's height floor.
    float2 uv = baseUv;
    float layer = 0.0;
    float texelDepth = 1.0 - ntex.SampleLevel(uv, lod).a;
    [loop]
    for (int i = 0; i < PARALLAX_STEPS && layer < texelDepth; ++i) {
        uv -= deltaUv;
        layer += layerStep;
        texelDepth = 1.0 - ntex.SampleLevel(uv, lod).a;
    }
    // If the loop never crossed, the view is too shallow to reach a recess: the original UV is the
    // answer and the surface is flat here (which is what the initial texelDepth said). Returning the
    // swept uv would sample a texel the ray never reached.
    if (layer < texelDepth) {
        reliefHeight = 1.0 - texelDepth;
        return baseUv;
    }

    // Refine: the crossing sits between (uv + deltaUv) -- above the height floor -- and uv -- below it.
    // Interpolate so the silhouette does not step in PARALLAX_STEPS-sized terraces.
    float2 prevUv = uv + deltaUv;
    float prevTexelDepth = 1.0 - ntex.SampleLevel(prevUv, lod).a;
    float afterDelta = texelDepth - layer;                     // negative: below the floor
    float beforeDelta = prevTexelDepth - (layer - layerStep);  // positive: still above it
    float weight = saturate(beforeDelta / max(beforeDelta - afterDelta, 1.0e-5));
    uv = lerp(uv, prevUv, weight);
    texelDepth = 1.0 - ntex.SampleLevel(uv, lod).a;

    reliefHeight = ntex.SampleLevel(uv, lod).a;
    return uv;
}

/**
 * Is this texel submerged under the puddle's water level? Positive = submerged, magnitude = depth
 * underwater. Single spelling: C-2's submersion decision goes through here and nowhere else, so the
 * shoreline semantics (how deep before the surface reads "wet") live in exactly one place.
 */
public float puddleReliefDepth(float waterLevel, float reliefHeight) {
    return waterLevel - reliefHeight;
}
