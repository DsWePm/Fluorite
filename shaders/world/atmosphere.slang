// The planetary atmosphere's parameters and the two quantities every consumer of it needs: optical
// depth to space, and the transmittance that follows from it. Depends on nothing.
//
// This module exists because those parameters had begun to multiply. They were declared in
// world.rmiss.slang, and the extinction integral over them was ALSO ported by hand into
// RtComposite.atmosphereTransmittance on the Java side, deliberately, so the sun's disc, the sky
// gradient and the NEE sunlight could not disagree about the sunset. That duplication is load-bearing
// and was correct to write — but keeping two implementations in step by hand is a maintenance debt with
// a silent failure mode, and M10's LUT would have made it three. One definition, imported by everyone,
// is the fix the milestone actually buys.
//
// World up = +Y; the camera sits a couple of km above a spherical planet.

public static const float  PLANET_R   = 6371000.0;
public static const float  ATMOS_R    = 6471000.0;        // 100 km shell
public static const float3 RAY_BETA   = float3(5.5e-6, 13.0e-6, 22.4e-6); // Rayleigh scattering at sea level
public static const float  MIE_BETA   = 21.0e-6;          // Mie scattering at sea level
public static const float  RAY_SCALE_H = 8000.0;          // Rayleigh density scale height
public static const float  MIE_SCALE_H = 1200.0;          // Mie density scale height
public static const float  MIE_G      = 0.758;            // Mie anisotropy (forward-scattering)
// Ozone: absorption only (no scattering), tent-shaped density peaking at 25 km. Its green-heavy
// absorption keeps the zenith deep blue at noon and turns twilight purple-orange.
public static const float3 OZONE_BETA = float3(0.650e-6, 1.881e-6, 0.085e-6);
public static const float  OZONE_CENTER_H = 25000.0;
public static const float  OZONE_WIDTH_H  = 15000.0;
public static const float  SUN_INTENSITY = 22.0;

// The Mie extinction is 1.1x its scattering coefficient — Mie particles absorb a little as well as
// scatter. Named because it appeared as a bare 1.1 at three separate call sites.
public static const float  MIE_EXTINCTION_FACTOR = 1.1;

// Altitude the transmittance LUT and the CPU port are anchored at. The camera is not at sea level in
// this renderer's convention; every existing consumer used this same 2 km offset, so it is named here
// rather than repeated.
public static const float  ATMOS_VIEW_ALTITUDE = 2000.0;

public float ozoneDensity(float h) {
    return max(0.0, 1.0 - abs(h - OZONE_CENTER_H) / OZONE_WIDTH_H);
}

// Smaller root of ray vs sphere of radius r centred at origin (no-hit handled by x > y).
public float2 raySphere(float3 o, float3 d, float r) {
    float b = dot(o, d);
    float c = dot(o, o) - r * r;
    float disc = b * b - c;
    if (disc < 0.0) return float2(1.0e9, -1.0e9);
    disc = sqrt(disc);
    return float2(-b - disc, -b + disc);
}

/**
 * Transmittance from a point at radius `r` looking along a direction whose cosine against the local
 * vertical is `mu`, out to the top of the atmosphere.
 *
 * The sun-below-horizon case needs no explicit planet test: a grazing or downward path picks up an
 * enormous optical depth, so the transmittance rolls to zero on its own rather than at a hard edge.
 * That property is why the existing sunset curve has no seam at the horizon, and it must survive into
 * the LUT — a version that clamped instead would put one there.
 *
 * `steps` is a parameter rather than a constant because the two callers want different things: the LUT
 * bake can afford a high count once, while any remaining on-the-fly evaluation cannot.
 */
public float3 atmosphereOpticalDepth(float r, float mu, int steps) {
    float3 origin = float3(0.0, r, 0.0);
    float3 dir = float3(sqrt(max(0.0, 1.0 - mu * mu)), mu, 0.0);
    float2 t = raySphere(origin, dir, ATMOS_R);
    if (t.y <= 0.0) {
        return float3(0.0, 0.0, 0.0);
    }
    float segLen = t.y / float(steps);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    for (int i = 0; i < steps; i++) {
        float3 p = origin + dir * (segLen * (float(i) + 0.5));
        float h = length(p) - PLANET_R;
        odR += exp(-h / RAY_SCALE_H) * segLen;
        odM += exp(-h / MIE_SCALE_H) * segLen;
        odO += ozoneDensity(h) * segLen;
    }
    return RAY_BETA * odR + MIE_BETA * MIE_EXTINCTION_FACTOR * odM + OZONE_BETA * odO;
}

public float3 atmosphereTransmittance(float r, float mu, int steps) {
    return exp(-atmosphereOpticalDepth(r, mu, steps));
}

// ---- Transmittance LUT parameterisation (Bruneton/Hillaire).
//
// Not a plain (mu, altitude) grid. A linear mu axis spends most of its resolution on directions whose
// transmittance barely changes and almost none near the horizon, which is the only place it changes
// fast — and the horizon is exactly where sunsets live. Mapping through the distance-to-atmosphere-top
// instead distributes texels by how much the answer actually varies.

public static const int SKY_TRANSMITTANCE_W = 256;
public static const int SKY_TRANSMITTANCE_H = 64;

/** (r, mu) -> uv in [0,1]^2. */
public float2 transmittanceLutUv(float r, float mu) {
    float H = sqrt(max(0.0, ATMOS_R * ATMOS_R - PLANET_R * PLANET_R));
    float rho = sqrt(max(0.0, r * r - PLANET_R * PLANET_R));
    float discriminant = r * r * (mu * mu - 1.0) + ATMOS_R * ATMOS_R;
    float d = max(0.0, -r * mu + sqrt(max(0.0, discriminant)));
    float dMin = ATMOS_R - r;
    float dMax = rho + H;
    float xMu = (d - dMin) / max(dMax - dMin, 1.0e-6);
    float xR = rho / max(H, 1.0e-6);
    return float2(xMu, xR);
}

/** uv in [0,1]^2 -> (r, mu). The inverse of transmittanceLutUv, used by the bake. */
public void transmittanceLutParams(float2 uv, out float r, out float mu) {
    float H = sqrt(max(0.0, ATMOS_R * ATMOS_R - PLANET_R * PLANET_R));
    float rho = H * uv.y;
    r = sqrt(max(0.0, rho * rho + PLANET_R * PLANET_R));
    float dMin = ATMOS_R - r;
    float dMax = rho + H;
    float d = dMin + uv.x * (dMax - dMin);
    mu = d == 0.0 ? 1.0 : clamp((H * H - rho * rho - d * d) / (2.0 * r * d), -1.0, 1.0);
}

/** Cosine of the horizon direction at radius `r`: below this, a ray meets the ground. */
public float horizonMu(float r) {
    return -sqrt(max(0.0, 1.0 - (PLANET_R * PLANET_R) / (r * r)));
}

// ---- The medium at an altitude, in one place.
//
// Scattering and extinction were being rebuilt from the three density profiles at every site that needed
// them, which is how MIE_EXTINCTION_FACTOR came to appear as a bare 1.1 in three places. Ozone is the
// reason the two differ by more than that factor: it absorbs without scattering at all, so it is in
// sigmaT and absent from sigmaS.
public void atmosphereMedium(float h, out float3 sigmaS, out float3 sigmaT) {
    float densityR = exp(-h / RAY_SCALE_H);
    float densityM = exp(-h / MIE_SCALE_H);
    sigmaS = RAY_BETA * densityR + MIE_BETA * densityM;
    sigmaT = RAY_BETA * densityR + MIE_BETA * MIE_EXTINCTION_FACTOR * densityM
           + OZONE_BETA * ozoneDensity(h);
}

// ---- Multi-scattering LUT parameterisation (Hillaire).
//
// 32x32 and a plain linear grid, unlike the transmittance table, and that is not an oversight: this
// quantity is the light that has bounced at least twice, which the table's whole premise treats as
// isotropic. Isotropic means smooth — there is no horizon feature to resolve, no sharp gradient anywhere
// in it — so texels spent on a clever mapping would buy nothing. It is small for the same reason.
public static const int SKY_MULTISCATTER_N = 32;

/** (r, muS) -> uv, with muS the cosine between the local vertical and the sun. */
public float2 multiScatterLutUv(float r, float muS) {
    float altitude = clamp((r - PLANET_R) / (ATMOS_R - PLANET_R), 0.0, 1.0);
    return float2(clamp(muS, -1.0, 1.0) * 0.5 + 0.5, altitude);
}

/** uv -> (r, muS). The inverse of multiScatterLutUv, used by the bake. */
public void multiScatterLutParams(float2 uv, out float r, out float muS) {
    muS = clamp(uv.x * 2.0 - 1.0, -1.0, 1.0);
    r = PLANET_R + uv.y * (ATMOS_R - PLANET_R);
}
