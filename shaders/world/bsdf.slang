// Surface response: the shading context a receiver builds once, and the BSDF evaluated against it.
// Depends on math, world_common and world_core.
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
import world_common;
import world_core;
import rain_surface;

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

    // ---- The specular frame. alphaX/alphaY are equal unless the material authored anisotropy, and
    // every anisotropic function reduces exactly to its isotropic twin when they are — which is why the
    // sun NEE and the continuation can use one code path for both. The RIS candidate loop still
    // branches, because there the difference is paid M+1 times per vertex rather than once.
    public float3 t;      // surface tangent (canonical basis when isotropic — see canonicalTangentFrame)
    public float3 b;
    public float  alphaX;
    public float  alphaY;
    public bool   aniso;

    // Optional M21 top water layer. Dry callers initialise the exact zero state below; opaque world
    // shading replaces it once after evaluating the shared directional exposure map.
    public RainSurface rain;

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
    canonicalTangentFrame(n, c.t, c.b);
    c.alphaX = rough;
    c.alphaY = rough;
    c.aniso = false;
    c.rain = dryRainSurface();
    return c;
}

/** True when the anisotropic lobe is switched on for this frame. */
public bool anisotropyEnabled() {
    return (worldPush.flags & 64u) != 0u;
}

/**
 * As above, but honouring an authored anisotropy.
 *
 * The tangent arrives as an angle in the payload's spare bits and is rebuilt here against a frame
 * derived from the same normal the closest-hit wrote — see PAYLOAD_TANGENT_SHIFT for why it travels that
 * way. A material that authored nothing takes the early return and is bit-identical to the isotropic
 * constructor, which is what keeps this off everyone else's cost sheet.
 *
 * twoSided is excluded outright: those callers are particle billboards, which have no tangent frame
 * worth the name and no business having a grain.
 */
public BsdfContext makeBsdfContextAniso(float3 hitPos, float3 n, float3 v, float3 rd, float3 diffAlb,
                                        float3 F0, float rough, bool twoSided, float sss,
                                        MaterialExtension ext, uint payloadFlags) {
    BsdfContext c = makeBsdfContext(hitPos, n, v, rd, diffAlb, F0, rough, twoSided, sss);
    float anisotropy = ext.specular.y;
    if (twoSided || abs(anisotropy) <= 1.0e-3 || !anisotropyEnabled()) {
        return c;
    }
    unpackTangentFrame(n, payloadFlags, c.t, c.b);
    anisoAlphas(rough, anisotropy, c.alphaX, c.alphaY);
    c.g1v = ggxG1Aniso(dot(v, c.t), dot(v, c.b), c.ndv, c.alphaX, c.alphaY);
    c.aniso = true;
    return c;
}

/**
 * The same material, asked what it would do at a DIFFERENT point and normal.
 *
 * <p>ReSTIR's MIS weights need each participating domain's target function evaluated at every candidate
 * sample, and a domain here is a shading vertex. A stored reservoir carries its vertex's position and
 * normal but not its material, so this is the reconstruction: the geometry is the other vertex's, the
 * material is the caller's. That approximation is why it lives next to the constructors rather than being
 * spelled out at the call site — every normal-dependent derived term is re-derived by the same
 * expressions the constructors use, so there is one place where the frame is built.
 *
 * <p>What the approximation costs depends on the caller. Temporal reuse validates the two vertices to
 * within 0.05 blocks and 26 degrees, so the material is almost certainly literally the same one. Spatial
 * reuse across a material boundary is where it can be wrong, and the error lands in the MIS weight rather
 * than in the estimate, so it shows up as variance rather than as a wrong picture.
 *
 * <p>The anisotropic grain is re-seated on the new normal rather than rebuilt, because the payload bits it
 * was decoded from belong to the other vertex and are not carried. Gram-Schmidt is well conditioned for
 * any normal agreement worth reusing across (26 degrees leaves the tangent at least 64 off the new
 * normal); the degenerate branch is there for callers that loosen that.
 */
public BsdfContext retargetBsdfContext(BsdfContext c, float3 hitPos, float3 n) {
    BsdfContext o = c;
    o.hitPos = hitPos;
    o.n = n;
    o.ndv = max(1.0e-4, dot(n, c.v));
    // The frame, mirroring whichever constructor built the original: canonical when isotropic (where t/b
    // are unused by every lobe anyway), the re-seated grain when not.
    float3 tangent = c.aniso ? c.t - n * dot(c.t, n) : float3(0.0, 0.0, 0.0);
    float tangentLength = length(tangent);
    if (tangentLength > 1.0e-4) {
        o.t = tangent / tangentLength;
        o.b = cross(n, o.t);
    } else {
        canonicalTangentFrame(n, o.t, o.b);
    }
    // Selected by c.aniso, not by which branch above ran: a degenerate grain falls back to the canonical
    // frame but keeps its anisotropic alphas, and evalSampleContrib branches on aniso alone. Pairing an
    // isotropic g1v with anisotropic alphas is the kind of mismatch that shades a surface nobody authored.
    o.g1v = c.aniso
            ? ggxG1Aniso(dot(c.v, o.t), dot(c.v, o.b), o.ndv, c.alphaX, c.alphaY)
            : ggxG1(o.ndv, c.rough);
    return o;
}

/**
 * The density, in solid angle, with which the CONTINUATION would have sampled direction `wi`.
 *
 * <p>One definition for both users. The sun's NEE has computed this inline since it landed; M24 S4 needs
 * the identical number to weigh the emitter estimator against BSDF sampling, and two spellings of a
 * density that has to agree exactly is how a MIS weight silently stops summing to one.
 *
 * <p>The three shares are path state rather than material: `pf` is the rain film's, `ps` the specular
 * lobe's within what the film leaves, and `walkSssShare` the subsurface walk's within the diffuse half.
 * The walk contributes no analytic density of its own, which is why it only ever subtracts here.
 *
 * <p>A delta specular lobe contributes nothing at any direction but its own, so it drops out — and its
 * alphas are zero, which is what `c.alphaX` being zero means and what the branch tests.
 */
public float bsdfContinuationPdf(BsdfContext c, float3 wi, float pf, float ps, float walkSssShare) {
    float ndl = dot(c.n, wi);
    if (!(ndl > 0.0)) {
        return 0.0;
    }
    float3 h = normalize(wi + c.v);
    float ndh = max(0.0, dot(c.n, h));
    float baseShare = 1.0 - pf;
    float pdf = baseShare * (1.0 - ps) * (1.0 - walkSssShare) * ndl * INV_PI;
    if (c.alphaX > 0.0 && c.alphaY > 0.0) {
        pdf += baseShare * ps * ggxReflectPdfAniso(dot(h, c.t), dot(h, c.b), ndh, c.ndv,
                                                   c.alphaX, c.alphaY, c.g1v);
    }
    if (pf > 0.0) {
        pdf += pf * ggxReflectPdf(ndh, c.ndv, c.rain.filmAlpha, ggxG1(c.ndv, c.rain.filmAlpha));
    }
    return pdf;
}

// ---- M21 energy-layered rain film.

public float rainFilmFresnel(float cosine) {
    return fresnelSchlick(clamp(cosine, 0.0, 1.0),
            float3(WATER_FILM_F0, WATER_FILM_F0, WATER_FILM_F0)).x;
}

/** Two interface crossings attenuate the base layer: eye->base and light->base. */
public float rainBaseTransmission(BsdfContext c, float3 wi) {
    if (c.rain.film <= 0.0) {
        return 1.0;
    }
    float fv = rainFilmFresnel(c.ndv);
    float fl = rainFilmFresnel(max(0.0, dot(c.n, wi)));
    return (1.0 - c.rain.film * fv) * (1.0 - c.rain.film * fl);
}

/** Radiance emitted beneath the film crosses only the view-side interface. */
public float rainBaseExitTransmission(BsdfContext c) {
    return 1.0 - c.rain.film * rainFilmFresnel(c.ndv);
}

/** Cook-Torrance response of the top water layer; its IOR/F0 is fixed, not an art slider. */
public float3 rainFilmBrdf(BsdfContext c, float3 wi, bool floorAlpha) {
    if (c.rain.film <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float ndl = max(0.0, dot(c.n, wi));
    if (ndl <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 h = normalize(wi + c.v);
    float ndh = max(0.0, dot(c.n, h));
    float vdh = max(0.0, dot(c.v, h));
    float alpha = floorAlpha ? max(c.rain.filmAlpha, UNWEIGHTED_SPEC_ALPHA_FLOOR)
                             : c.rain.filmAlpha;
    float D = ggxD(ndh, alpha);
    float G = ggxG1(c.ndv, alpha) * ggxG1(ndl, alpha);
    float3 F = fresnelSchlick(vdh, float3(WATER_FILM_F0, WATER_FILM_F0, WATER_FILM_F0));
    return c.rain.film * (D * G) * F / (4.0 * c.ndv * ndl);
}

// ---- Optional Disney parameters.

/**
 * The Disney parameters a material authored, or the defaults it inherits by saying nothing.
 *
 * The payload carries a one-based index into the extension table, and zero means the material never
 * mentioned any of this — which is every material in an unmodified LabPBR pack, since no source format
 * carries these values. Those materials take the early return and never touch the extension buffer, so
 * supporting the feature costs them one comparison and no load.
 *
 * The defaults are Disney's own, chosen so that a material which authors one parameter does not
 * accidentally inherit something surprising for the rest. Every weight is zero, so the lobes they gate
 * are off and the surface behaves exactly as it did before this existed.
 */
public MaterialExtension materialExtension(uint payloadFlags) {
    MaterialExtension e;
    e.sheen = float4(0.0, 0.5, 0.0, 1.0);      // no sheen; halfway tint and a polished coat if enabled
    e.specular = float4(0.0, 0.0, 0.0, 0.0);   // no tint, isotropic, no subsurface
    e.subsurface = float4(0.0, 0.0, 0.0, 0.0);
    e.weather = float4(-1.0, -1.0, -1.0, -1.0); // inherit all global rain-response defaults
    uint index = (payloadFlags >> PAYLOAD_EXTENSION_SHIFT) & PAYLOAD_EXTENSION_MASK;
    if (index == 0u) {
        return e;
    }
    return ConstPtr<MaterialExtension>(pc.materialExtensionAddr)[index - 1u];
}

// ---- Lobes.

/**
 * Disney's sheen: the retroreflective grazing lobe that makes cloth, moss and foliage edges glow softly
 * against the light instead of falling off to black like a Lambertian would.
 *
 * f = sheen * lerp(1, hueOfAlbedo, sheenTint) * (1 - cos(theta_d))^5
 *
 * theta_d is the angle between the light and the half vector, so the term peaks when light and view are
 * on opposite sides at grazing — which is exactly the silhouette of a backlit leaf. The albedo hue is
 * normalised by its own luminance so tinting shifts the colour without also changing how bright the
 * sheen is; Disney's paper does the same.
 *
 * Additive on top of the diffuse lobe rather than energy-split against it. That is Disney's own
 * formulation and it is not energy conserving in the strict sense, but sheen amounts are small and the
 * alternative costs a normalisation nobody can author against.
 */
public float3 sheenLobe(BsdfContext c, MaterialExtension ext, float3 wi) {
    float sheen = ext.sheen.x;
    if (sheen <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 h = normalize(wi + c.v);
    float ldh = clamp(dot(wi, h), 0.0, 1.0);
    float f = pow(1.0 - ldh, 5.0);
    float lum = max(luminance(c.diffAlb), 1.0e-4);
    float3 hue = c.diffAlb / lum;
    float3 tint = lerp(float3(1.0, 1.0, 1.0), hue, clamp(ext.sheen.y, 0.0, 1.0));
    return sheen * tint * f;
}

/**
 * Disney's clearcoat: a second, thin specular layer over the base, with its own distribution and a fixed
 * dielectric F0.
 *
 * Three details are Disney's and none of them are arbitrary:
 *
 *  - the distribution is GTR1, not GGX. The wider tail is what reads as a coat rather than as a second
 *    ordinary highlight.
 *  - the coat's alpha is lerp(0.1, 0.001, gloss) directly. Those numbers ARE alphas, so unlike most
 *    formulas transcribed from that paper this one needs no un-squaring — see the header of this file for
 *    why that distinction matters so much here.
 *  - the masking term uses a fixed alpha of 0.25 rather than the coat's own. Disney found the coat's true
 *    roughness far too smooth to shadow anything, and a constant reads better than the truth.
 *
 * F0 is 0.04: a clear coat is a dielectric whatever the base is, which is the point of layering it over a
 * metal. The 0.25 weight is Disney's, absorbing the layer's thickness into one number an author can turn.
 */
public float3 clearcoatLobe(BsdfContext c, MaterialExtension ext, float3 wi) {
    float clearcoat = ext.sheen.z;
    float ndl = dot(c.n, wi);
    if (clearcoat <= 0.0 || ndl <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float3 h = normalize(wi + c.v);
    float ndh = max(0.0, dot(c.n, h));
    float ldh = clamp(dot(wi, h), 0.0, 1.0);
    float coatAlpha = lerp(0.1, 0.001, clamp(ext.sheen.w, 0.0, 1.0));
    float d = gtr1(ndh, coatAlpha);
    float fr = lerp(0.04, 1.0, pow(1.0 - ldh, 5.0));
    float g = ggxG1(ndl, 0.25) * ggxG1(c.ndv, 0.25);
    // Same Cook-Torrance arrangement the base specular uses, so the two are directly comparable. Disney's
    // smithG_GGX folds the 1/(4 ndl ndv) into its G; ggxG1 does not, hence the explicit divide.
    float value = 0.25 * clearcoat * d * fr * g / (4.0 * ndl * c.ndv);
    return float3(value, value, value);
}

/**
 * Every optional lobe a material may have authored, summed.
 *
 * One entry point so that adding the next lobe does not mean editing the direct-lighting sites again —
 * they were already edited once for sheen, and a lobe that someone forgets to wire into one of the two
 * would make a surface respond differently to sunlight than to a torch.
 *
 * Returns immediately for the overwhelming majority of materials, which authored none of this.
 */
public float3 disneyLobes(BsdfContext c, MaterialExtension ext, float3 wi) {
    // twoSided callers are particle billboards: flat, unlit-ish quads that were never meant to have a
    // fabric sheen or a lacquer coat.
    if (c.twoSided || (ext.sheen.x <= 0.0 && ext.sheen.z <= 0.0)) {
        return float3(0.0, 0.0, 0.0);
    }
    return sheenLobe(c, ext, wi) + clearcoatLobe(c, ext, wi);
}

// ---- What DLSS-RR is told.

/** The specular guide a surface should report: one roughness, one integrated reflectance. */
public struct SpecularGuide {
    public float alpha;       // what RR filters at
    public float3 reflectance;
};

/**
 * Collapses the specular lobes into the single roughness and reflectance DLSS-RR accepts.
 *
 * RR takes one roughness per pixel and picks its filter width from it, so a surface with two specular
 * lobes has to nominate one. Reporting the base unconditionally is wrong in the direction that shows:
 * a sharp coat over a rough base tells the denoiser to blur a highlight that should stay tight, and the
 * result reads as DLSS being broken rather than as the material being described wrongly.
 *
 * Nominates by PEAK radiance, not by total energy. That distinction is the whole function: a clear coat
 * carries far less energy than a rough base — 0.04 reflectance at a quarter weight against a rough
 * dielectric's integrated 0.05 to 0.10 — yet concentrates it into a solid angle four orders of magnitude
 * smaller, so it is the coat that puts a hard edge in the image. Comparing integrated reflectance picks
 * the base every time and changes nothing, which is exactly what an A/B of that version showed.
 *
 * Peak goes as reflectance over the lobe's solid angle, and a GGX lobe's solid angle goes as alpha^2.
 *
 * Reflectance is still the sum, since both lobes genuinely contribute and RR demodulates by it.
 *
 * Uses the existing rrSpecularAlbedo fit rather than a second one: RR was tuned against that curve, and
 * two approximations of the same quantity would drift.
 */
public SpecularGuide specularGuide(float3 F0, float alpha, float ndv, MaterialExtension ext) {
    SpecularGuide g;
    // RR takes one roughness, and an anisotropic lobe has two. The geometric mean is the isotropic lobe
    // of the same solid angle, so the denoiser filters over the right total width even though it cannot
    // be told the shape. A stretched highlight therefore denoises as a round one of equal area — visible
    // if you look for it at high anisotropy, and the alternative is telling RR the wrong width in one
    // axis or the other.
    float anisotropy = ext.specular.y;
    if (abs(anisotropy) > 1.0e-3 && anisotropyEnabled()) {
        float ax, ay;
        anisoAlphas(alpha, anisotropy, ax, ay);
        alpha = sqrt(ax * ay);
    }
    g.alpha = alpha;
    g.reflectance = rrSpecularAlbedo(F0, alpha, ndv);

    float clearcoat = ext.sheen.z;
    if (clearcoat <= 0.0) {
        return g;
    }
    float coatAlpha = lerp(0.1, 0.001, clamp(ext.sheen.w, 0.0, 1.0));
    // The 0.25 is the lobe's own weight, so the comparison is between what each layer actually sends to
    // the eye rather than between their raw reflectances.
    float3 coat = rrSpecularAlbedo(float3(0.04, 0.04, 0.04), coatAlpha, ndv) * (0.25 * clearcoat);
    float basePeak = luminance(g.reflectance) / max(alpha * alpha, 1.0e-8);
    float coatPeak = luminance(coat) / max(coatAlpha * coatAlpha, 1.0e-8);
    if (coatPeak > basePeak) {
        g.alpha = coatAlpha;
    }
    g.reflectance += coat;
    return g;
}
