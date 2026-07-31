// Stateless math: luminance, GGX (D/G/VNDF), Fresnel, Henyey-Greenstein, the RR specular-albedo
// fit, the PCG stream, and direction sampling. No bindings, no globals, no rays.

import world_common;
import world_core;

public float luminance(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }

// GGX normal distribution.
//
// The denominator epsilon is a divide-by-zero guard and nothing else. It used to be 1e-7, which is large
// enough to clamp the lobe itself: at the peak d == alpha^2, so PI*d*d is PI*alpha^4 and the epsilon
// dominates it below alpha ~ 0.013, capping the peak at about 890 however smooth the material claims to
// be. That clamp was load-bearing. It was the only thing keeping the sun from being counted twice on a
// near-mirror surface, because NEE evaluated a full specular term toward the sun while the specular
// continuation could also land on the light, with nothing weighting the two against each other.
//
// Multiple importance sampling replaced it — see the sun MIS block in world.rgen, which weights both
// estimates by the density of the strategy that produced them and is what now bounds the near-delta
// case. Do not raise this back: with MIS in place the clamp only removes energy that is already
// weighted correctly, and it would do so silently.
public float ggxD(float ndh, float alpha) {
    float a2 = alpha * alpha;
    float d = ndh * ndh * (a2 - 1.0) + 1.0;
    return a2 / (PI * d * d + 1.0e-20);
}

/**
 * Solid-angle density sampleGGXVNDF induces on the direction it reflects.
 *
 * The visible-normal distribution is D_v(h) = G1(v) * max(0, v.h) * D(h) / (n.v), and the reflection
 * about h carries a Jacobian of 1/(4 v.h) — the v.h cancels, which is why this is cheaper than it looks
 * and why it needs no half-vector dot product beyond the one D already wanted.
 *
 * `g1v` is passed in rather than recomputed because every caller already holds it.
 */
public float ggxReflectPdf(float ndh, float ndv, float alpha, float g1v) {
    return ggxD(ndh, alpha) * g1v / max(4.0 * ndv, 1.0e-8);
}

// Smith GGX masking term for one direction (separable form; G2 = G1(v) * G1(l)).
/**
 * GTR1 (Berry) normal distribution — the clearcoat lobe's, not the base specular's.
 *
 * GGX is GTR2. GTR1 has a longer tail still, which is what gives a clear coat its wide dim halo around a
 * tight core rather than GGX's more compact falloff. Disney uses it for exactly this one layer.
 *
 * `alpha` is linear roughness, like everywhere else here. Degenerate at alpha == 1, where the closed form
 * divides by log(1) — uniform is the correct limit.
 */
public float gtr1(float ndh, float alpha) {
    if (alpha >= 1.0) {
        return 1.0 / PI;
    }
    float a2 = alpha * alpha;
    float t = 1.0 + (a2 - 1.0) * ndh * ndh;
    return (a2 - 1.0) / (PI * log(a2) * t + 1.0e-7);
}

public float ggxG1(float ndx, float alpha) {
    float a2 = alpha * alpha;
    return 2.0 * ndx / (ndx + sqrt(a2 + (1.0 - a2) * ndx * ndx) + 1.0e-7);
}

// ---- Anisotropic GGX. Set ax == ay and every function here reduces exactly to its isotropic twin
// above; that identity is the test, and it is worth re-deriving before touching any of them.

/**
 * Split one alpha into a tangent/bitangent pair.
 *
 * Disney's aspect ratio. `anisotropy` runs -1..1 and its SIGN picks which axis stretches, so a pack can
 * turn a brushed streak ninety degrees without also rotating the tangent.
 *
 * Disney writes ax = roughness^2 / aspect. Ours is ax = alpha / aspect, with no squaring, because
 * roughness here already IS alpha — the invariant at the top of bsdf.slang. This is one of the formulas
 * that file warns about.
 */
public void anisoAlphas(float alpha, float anisotropy, out float ax, out float ay) {
    float a = clamp(anisotropy, -1.0, 1.0);
    float aspect = sqrt(1.0 - 0.9 * abs(a));
    float wide = alpha / aspect;
    float tight = alpha * aspect;
    ax = a >= 0.0 ? wide : tight;
    ay = a >= 0.0 ? tight : wide;
}

/**
 * hx/hy are the half vector along the tangent and bitangent. ax == ay gives ggxD exactly.
 *
 * Written WITHOUT dividing by ax or ay, and that is not a style choice. The textbook form is
 *
 *     1 / (PI ax ay ((hx/ax)^2 + (hy/ay)^2 + ndh^2)^2)
 *
 * which is NaN at alpha 0: hx/0 is infinite, and PI*0*0*inf*inf is not a number. Alpha zero is not a
 * corner case anyone has to go looking for — isDeltaAlpha SNAPS every near-mirror to exactly zero, and
 * ggxD returns a clean 0 there because its numerator is alpha^2. The delta path depends on that zero.
 *
 * Multiplying numerator and denominator through by (ax ay)^3 gives an algebraically identical
 * expression with the alphas in the numerator, so alpha 0 falls out as 0 rather than poisoning every
 * lit pixel of the surface. It really did: a NaN here reached L through the sun's NEE, which runs only
 * where ndl > 0 and the shadow ray is clear — so the lit faces of a mirror went black while the shaded
 * ones stayed correct, which is a strange enough symptom to be worth naming.
 *
 * The claim that this reduces exactly to the isotropic form was made once already and was wrong at
 * exactly one value. Substituting ax = ay = a gives a^6 / (PI (a^2(hx^2+hy^2) + ndh^2 a^4)^2), and
 * hx^2 + hy^2 = 1 - ndh^2, so it collapses to a^2 / (PI (ndh^2(a^2-1) + 1)^2) — ggxD, term for term,
 * and now at a = 0 as well.
 */
public float ggxDAniso(float hx, float hy, float ndh, float ax, float ay) {
    float ax2 = ax * ax;
    float ay2 = ay * ay;
    float t = hx * hx * ay2 + hy * hy * ax2 + ndh * ndh * ax2 * ay2;
    float num = ax2 * ax * ay2 * ay;
    return num / (PI * t * t + 1.0e-30);
}

/**
 * Smith masking for one direction under the anisotropic distribution.
 *
 * With ax == ay == a this is sqrt(a^2 (vx^2 + vy^2) + ndv^2), and vx^2 + vy^2 is 1 - ndv^2, which is
 * sqrt(a^2 + (1 - a^2) ndv^2) — the isotropic form above, term for term.
 */
public float ggxG1Aniso(float vx, float vy, float ndv, float ax, float ay) {
    float sx = ax * vx;
    float sy = ay * vy;
    return 2.0 * ndv / (ndv + sqrt(sx * sx + sy * sy + ndv * ndv) + 1.0e-7);
}

public float ggxReflectPdfAniso(float hx, float hy, float ndh, float ndv, float ax, float ay, float g1v) {
    return ggxDAniso(hx, hy, ndh, ax, ay) * g1v / max(4.0 * ndv, 1.0e-8);
}

public float3 fresnelSchlick(float cosT, float3 f0) {
    float m = clamp(1.0 - cosT, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}

// Henyey–Greenstein phase function (with 4π normalisation so it integrates to 1 over the sphere).
// g=0 → isotropic; g>0 → forward-scatter peak at cosT=1 (light and view aligned through a thin slab).
public static const float SSS_G        = 0.6; // forward-scatter anisotropy; leaves/grass are quite directional
public static const float SSS_STRENGTH = 1.0; // 1.0 ≈ 2.5× the Lambertian at peak (sss=1, cosT=1, backNdl=1)

/**
 * The thin shell's modelled thickness, from worldPush.fogAmbient.w. 1 reproduces the two constants
 * above exactly, so the default changes nothing.
 *
 * One control rather than separate strength and anisotropy sliders, because thickness drives both and
 * driving them independently lets a user build a slab that is physically impossible — very thick and
 * still sharply forward-scattering, which reads as a material that glows through without diffusing.
 *
 *   strength ∝ 1/thickness   a thicker slab transmits less
 *   g_eff = g^thickness      each scattering event multiplies the mean cosine by g, and a thicker slab
 *                            takes proportionally more of them, so directionality decays geometrically
 *
 * The second is the same similarity argument the water's phase function uses (see volume.slang's
 * effectiveAnisotropy) — worth recognising as the same shape rather than two coincidences.
 */
public float sssThickness() {
    return max(worldPush.fogAmbient.w, 0.05);
}

public float sssStrength() {
    return SSS_STRENGTH / sssThickness();
}

public float sssPhase(float cosT) {
    return hg(cosT, pow(SSS_G, sssThickness()));
}
// Keep SSS on the first two Pass B hits. Pass A's primary/interface prefix is outside this local depth.
public static const int   MAX_SSS_INDIRECT_DEPTH = 1;
public float hg(float cosT, float g) {
    float g2 = g * g;
    return (1.0 - g2) / (4.0 * PI * pow(max(0.0, 1.0 + g2 - 2.0 * g * cosT), 1.5));
}

// NVIDIA RR guide-buffer helper: integrated/view-dependent specular reflectivity, not raw F0.
// alpha is GGX alpha (== the linear roughness materials store), NoV is the first-hit view cosine.
public float3 rrSpecularAlbedo(float3 specularColor, float alpha, float NoV) {
    NoV = abs(NoV);
    float NoV2 = NoV * NoV;
    float alpha2 = alpha * alpha;
    float4 X = float4(1.0, NoV, NoV2, NoV * NoV2);
    float4 Y = float4(1.0, alpha, alpha2, alpha * alpha2);

    float2 m1 = float2(
            dot(float2(0.99044, -1.28514), X.xy),
            dot(float2(1.29678, -0.755907), X.xy));
    float3 Xxyw = float3(X.x, X.y, X.w);
    float3 m2 = float3(
            dot(float3(1.0, 2.92338, 59.4188), Xxyw),
            dot(float3(20.3225, -27.0302, 222.592), Xxyw),
            dot(float3(121.563, 626.13, 316.627), Xxyw));
    float bias = dot(m1, Y.xy) / max(dot(m2, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    float2 m3 = float2(
            dot(float2(0.0365463, 3.32707), X.xy),
            dot(float2(9.0632, -9.04756), X.xy));
    float3 Xxzw = float3(X.x, X.z, X.w);
    float3 m4 = float3(
            dot(float3(1.0, 3.59685, -1.36772), Xxzw),
            dot(float3(9.04401, -16.3174, 9.22949), Xxzw),
            dot(float3(5.56589, 19.7886, -20.2123), Xxzw));
    float scale = dot(m3, Y.xy) / max(dot(m4, float3(Y.x, Y.y, Y.w)), 1.0e-7);

    bias *= clamp(specularColor.g * 50.0, 0.0, 1.0);
    return specularColor * max(0.0, scale) + float3(max(0.0, bias), max(0.0, bias), max(0.0, bias));
}

// Exact (unpolarized) Fresnel reflectance for a dielectric interface. cosI is the incidence cosine on
// the incoming side; etaI/etaT are the IORs the ray travels from / into. Returns 1.0 on total internal
// reflection (sin of the transmitted angle >= 1), which matches refract() returning the zero vector —
// so reflect/refract selection and TIR stay consistent.
public float fresnelDielectric(float cosI, float etaI, float etaT) {
    float sinT2 = (etaI * etaI) / (etaT * etaT) * max(0.0, 1.0 - cosI * cosI);
    if (sinT2 >= 1.0) {
        return 1.0;
    }
    float cosT = sqrt(1.0 - sinT2);
    float rs = (etaI * cosI - etaT * cosT) / (etaI * cosI + etaT * cosT);
    float rp = (etaI * cosT - etaT * cosI) / (etaI * cosT + etaT * cosI);
    return 0.5 * (rs * rs + rp * rp);
}

// PCG hash RNG.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}
public float rndf(inout uint s) {
    // Convert the high 24 bits, which are exactly representable as float. Converting all 32 bits first
    // lets the top 128 uint values round to 2^32, incorrectly returning 1.0 and breaking `< probability`
    // tests (most seriously the F == 1 total-internal-reflection branch).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

public float3 primaryRayDir(float2 ndc) {
    float4 nearH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 1.0, 1.0));
    float4 farH = mul(worldPush.invViewProj, float4(ndc.x, ndc.y, 0.0, 1.0));
    float3 nearP = nearH.xyz / nearH.w;
    float3 farP = farH.xyz / farH.w;
    return normalize(farP - nearP);
}

public float primaryRayConeSpread(float2 ndc, float2 size, float3 dir) {
    float2 onePixelNdc = 2.0 / size;
    float3 dx = primaryRayDir(ndc + float2(onePixelNdc.x, 0.0));
    float3 dy = primaryRayDir(ndc + float2(0.0, onePixelNdc.y));
    return max(max(length(cross(dir, dx)), length(cross(dir, dy))), RAY_CONE_MIN_SPREAD);
}

// Cosine-weighted hemisphere sample about n (Malley's method). For a Lambertian BRDF this is the
// importance-sampling match: BRDF*cos/pdf = (albedo/PI)*cos / (cos/PI) = albedo, so the continuation
// throughput is just *= albedo with no PI/cos bookkeeping left over.
public float3 cosineDir(float3 n, inout uint s) {
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float3 t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    float3 b = normalize(cross(n, t));
    t = cross(b, n);
    float3 local = float3(r * cos(phi), r * sin(phi), sqrt(max(0.0, 1.0 - u1)));
    return normalize(local.x * t + local.y * b + local.z * n);
}

// Soft shadows: sample a direction within the light's SQUARE angular extent about `axis`. MC's
// sun/moon are square quads, so the NEE shadow ray samples a square (not a cone) — the same square the
// visible disc in world.rmiss uses, built from the same celestial tangent frame (right = arc-travel
// direction, from worldPush.celestial.xyz). Averaged over frames by DLSS-RR this yields soft penumbrae that
// widen with occluder distance (contact-hardening) for free. halfAngle <= 0 ⇒ exact direction (hard).
public float3 sampleSquare(float3 axis, float halfAngle, inout uint s) {
    float3 right, up;
    squareLightFrame(axis, worldPush.celestial.xyz, right, up); // shared with world.rmiss — see there
    float t = tan(halfAngle);
    float u = (rndf(s) * 2.0 - 1.0) * t;
    float v = (rndf(s) * 2.0 - 1.0) * t;
    return normalize(axis + u * right + v * up);
}

// Sample a GGX visible-normal (VNDF, Heitz 2018) about geometric normal n for view ve, returning the
// sampled microfacet normal (half-vector) in world space. Paired with the separable Smith term, the
// importance-sampling weight reduces to F * G1(NdotL) (applied by the caller).
public float3 sampleGGXVNDF(float3 n, float3 ve, float alpha, inout uint s) {
    // The isotropic case is the anisotropic one with a square lobe, and routing it through the same
    // function is what keeps the two from drifting apart as either is edited.
    float3 t, b;
    canonicalTangentFrame(n, t, b);
    return sampleGGXVNDFAniso(n, t, b, ve, alpha, alpha, s);
}

/**
 * As above, but in a caller-supplied tangent frame with a separate alpha per axis.
 *
 * The frame matters here in a way it does not for the isotropic lobe: a square lobe is invariant to how
 * the basis is rolled about n, a stretched one is not. So this takes the SURFACE tangent, which the
 * closest-hit derived from the UV parameterisation and shipped as an angle — see PAYLOAD_TANGENT_SHIFT.
 */
public float3 sampleGGXVNDFAniso(float3 n, float3 t, float3 b, float3 ve,
                                 float ax, float ay, inout uint s) {
    float3 Ve = float3(dot(ve, t), dot(ve, b), dot(ve, n)); // view in tangent space (z = n)
    float3 Vh = normalize(float3(ax * Ve.x, ay * Ve.y, Ve.z));
    float lensq = Vh.x * Vh.x + Vh.y * Vh.y;
    float3 T1 = lensq > 0.0 ? float3(-Vh.y, Vh.x, 0.0) * rsqrt(lensq) : float3(1.0, 0.0, 0.0);
    float3 T2 = cross(Vh, T1);
    float u1 = rndf(s);
    float u2 = rndf(s);
    float r = sqrt(u1);
    float phi = 6.2831853 * u2;
    float p1 = r * cos(phi);
    float p2 = r * sin(phi);
    float ss = 0.5 * (1.0 + Vh.z);
    p2 = (1.0 - ss) * sqrt(1.0 - p1 * p1) + ss * p2;
    float3 Nh = p1 * T1 + p2 * T2 + sqrt(max(0.0, 1.0 - p1 * p1 - p2 * p2)) * Vh;
    float3 Ne = normalize(float3(ax * Nh.x, ay * Nh.y, max(0.0, Nh.z))); // microfacet normal, tangent space
    return normalize(Ne.x * t + Ne.y * b + Ne.z * n);                    // -> world space
}

/** Quantise a surface tangent to the payload's azimuth field. Returns 0 for a degenerate tangent. */
public uint packTangentAzimuth(float3 n, float3 tangent) {
    float3 refT, refB;
    canonicalTangentFrame(n, refT, refB);
    float3 t = tangent - n * dot(n, tangent); // the normal may have been perturbed since
    float len2 = dot(t, t);
    if (len2 < 1.0e-12) {
        return 0u;
    }
    t *= rsqrt(len2);
    float angle = atan2(dot(t, refB), dot(t, refT));       // -PI .. PI
    float unit = clamp(angle * (0.5 / PI) + 0.5, 0.0, 1.0);
    return (uint(unit * PAYLOAD_TANGENT_STEPS + 0.5) & PAYLOAD_TANGENT_MASK) << PAYLOAD_TANGENT_SHIFT;
}

/** Rebuild the surface tangent frame a hit packed. `n` must be the normal the payload carries. */
public void unpackTangentFrame(float3 n, uint flags, out float3 t, out float3 b) {
    float3 refT, refB;
    canonicalTangentFrame(n, refT, refB);
    float unit = float((flags >> PAYLOAD_TANGENT_SHIFT) & PAYLOAD_TANGENT_MASK) / PAYLOAD_TANGENT_STEPS;
    float angle = (unit - 0.5) * (2.0 * PI);
    float s = sin(angle);
    float c = cos(angle);
    t = refT * c + refB * s;
    b = cross(n, t);
}

public float3 srgbToLinear(float3 c) {
    float3 lo = c / 12.92;
    float3 hi = pow((c + 0.055) / 1.055, float3(2.4, 2.4, 2.4));
    float3 isHi = step(float3(0.04045, 0.04045, 0.04045), c);
    return lerp(lo, hi, isHi);
}
