// Surface response: the shading context a receiver builds once, and the BSDF evaluated against it.
// Depends on math and world_core.
//
// =====================================================================================================
//  ROUGHNESS IS LINEAR AND IS THE GGX ALPHA. NEVER SQUARE IT.
// =====================================================================================================
//
// This is the single easiest way to silently break the entire material pipeline, so it is stated before
// anything else. Every roughness in this renderer — the one LabPBR decodes, the one RtMaterials.Profile
// carries, the one a resource pack authors as base.roughness, the one written to gNormal.w for DLSS-RR,
// the one ggxD/ggxG1/sampleGGXVNDF/rrSpecularAlbedo consume, the one isDeltaAlpha snaps at zero — is the
// same number, and that number IS the GGX alpha. See world_core.slang for the end-to-end chain.
//
// Disney's paper does not use that convention. It uses a perceptual roughness and defines
// alpha = roughness^2. **Every formula transcribed from that paper must have its roughness^2 replaced by
// alpha as-is.** Getting it wrong shades wrongly, hands DLSS-RR the wrong filter width, and stops mirrors
// snapping to delta — all at once, and none of it raises an error.

import math;
import world_core;

/**
 * Everything about a shading point that does not change while it is being shaded.
 *
 * The RIS estimator evaluates its target function once per candidate — M+1 times per shading vertex at
 * the default M=8 — and every one of those calls was re-deriving values that depend only on the
 * receiver. `ndv` and its Smith term are the obvious ones; they are now computed once here.
 *
 * It also gives the Disney work somewhere to put lobe weights and the anisotropic frame without growing
 * the parameter list of every function in the light loop, which is already nine receiver arguments long.
 */
public struct BsdfContext {
    public float3 hitPos;
    public float3 n;
    public float3 v;      // toward the eye
    public float3 rd;     // the incoming ray direction (subsurface transmission needs it, not -v)
    public float3 diffAlb;
    public float3 F0;
    public float  rough;  // linear, == GGX alpha, never squared
    public bool   twoSided;
    public float  sss;

    // ---- Derived once from the above.
    public float  ndv;    // clamped away from zero: it divides the specular term
    public float  g1v;    // Smith masking for the view direction
};

public BsdfContext makeBsdfContext(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                                   float3 F0, float rough, bool twoSided, float sss) {
    BsdfContext c;
    c.hitPos = hitPos;
    c.n = n;
    c.v = v;
    c.rd = rd;
    c.diffAlb = diffAlb;
    c.F0 = F0;
    c.rough = rough;
    c.twoSided = twoSided;
    c.sss = sss;
    c.ndv = max(1.0e-4, dot(n, v));
    c.g1v = ggxG1(c.ndv, rough);
    return c;
}
