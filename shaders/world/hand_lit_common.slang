// M29 first-person hand: the self-drawn raster pass's frame ABI. The hand is captured from the vanilla
// submit stream in VIEW space (GameRenderer.renderItemInHand applies modelView⁻¹ + bob before
// submitting), so the per-frame data carries the two matrices shading needs: the projection vanilla
// rasterizes the hand with, and the rigid view->world transform (inverse view rotation + the camera's
// REBASED position -- the same space every world-shading consumer of WorldPush lives in, dynamic lights
// and the emitter grid included).
//
// THE FRAGMENT STAGE SHARES THE RT PIPELINE'S PUSH SHAPE (world_core's WorldPushConstants) instead of
// carrying its own, so the world modules -- volume_visibility's grids, atmosphere_lut's tables,
// cloud_density, light_sampling, bsdf -- import with their binding expectations intact and the global
// worldPush resolves exactly as it does in the RT shaders. What the RT push cannot carry (the matrices)
// rides in set 0's frame-data SSBO below; nothing in this file is a push constant.

public struct HandFrameData {
    public float4x4 handProjection;   // view space -> clip, the vanilla hand/3D-HUD projection
    public float4x4 worldFromView;    // view space -> rebased world (rigid)
};

// Set 0 additions of the hand pipeline. 0..30 are the RT shaders' own numbers (0..8 world_core, 10..15
// atmosphere_lut, 16/30 the visibility grids, 29 cloud shadow) and are NOT redeclared here -- the frag
// binds the ones it uses (10, 16, 30) against the same numbers so the imported modules resolve.
public static const uint HAND_BINDING_CLOUD_SHADOW = 61u; // Sampler2D, D176's light-space transmittance
public static const uint HAND_BINDING_TLAS = 62u;         // ray queries for the hand's shadow rays
public static const uint HAND_BINDING_FRAME_DATA = 63u;   // ByteAddressBuffer of HandFrameData
