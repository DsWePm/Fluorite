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

// ---- Sky-view LUT parameterisation.
//
// The whole sky at the camera's altitude, so a miss shader can look up what it currently marches with 16
// steps and an 8-step inner loop per step — 128 extinction evaluations per pixel, for a picture that
// varies smoothly over the entire hemisphere.
//
// Two axes with opposite needs. AZIMUTH only has to resolve the sun's halo and is otherwise almost flat,
// and the sky is symmetric about the sun's own meridian, so half a turn is stored and the other half
// mirrors onto it — the symmetry is exact for a horizontally uniform atmosphere and buys a factor of two
// in resolution rather than in memory. ELEVATION is the opposite: nearly all of the variation is packed
// against the horizon, so the axis is warped by a square root centred there, which spends texels where
// the gradient is instead of spreading them evenly over a sky that is mostly one colour.
// Not `public`, and not imported from world_core: that module declares the TLAS and the guide images as
// set-0 bindings, and the bake compute shaders import this one. A module-private constant keeps the name
// from colliding in the raygens, which see both.
static const float ATMOS_PI = 3.14159265359;

public static const int SKY_VIEW_W = 192;
public static const int SKY_VIEW_H = 128;

/** Horizontal component of a direction, normalised; a stable fallback when it points straight up. */
public float3 horizontalDir(float3 d) {
    float2 flat = float2(d.x, d.z);
    float len = length(flat);
    return len > 1.0e-5 ? float3(flat.x / len, 0.0, flat.y / len) : float3(1.0, 0.0, 0.0);
}

/** (view dir, sun dir) -> uv. */
public float2 skyViewLutUv(float3 dir, float3 sunDir) {
    float r = PLANET_R + ATMOS_VIEW_ALTITUDE;
    float muH = horizonMu(r);
    float mu = clamp(dir.y, -1.0, 1.0);

    // Elevation: sqrt on each side of the horizon, so v = 0.5 IS the horizon and texel density rises into
    // it from both directions. A linear axis puts almost every texel in sky that barely changes.
    float v;
    if (mu > muH) {
        v = 0.5 + 0.5 * sqrt(clamp((mu - muH) / max(1.0 - muH, 1.0e-5), 0.0, 1.0));
    } else {
        v = 0.5 - 0.5 * sqrt(clamp((muH - mu) / max(muH + 1.0, 1.0e-5), 0.0, 1.0));
    }

    // Azimuth: the angle to the sun's meridian, folded to [0, pi]. Sign is discarded deliberately — that
    // is the mirror symmetry, and it is why the halo gets twice the texels it otherwise would.
    float3 sunFlat = horizontalDir(sunDir);
    float3 dirFlat = horizontalDir(dir);
    float cosAz = clamp(dot(dirFlat, sunFlat), -1.0, 1.0);
    return float2(acos(cosAz) * (1.0 / ATMOS_PI), v);
}

/** uv -> view direction, given the sun. The inverse of skyViewLutUv, used by the bake. */
public float3 skyViewLutDir(float2 uv, float3 sunDir) {
    float r = PLANET_R + ATMOS_VIEW_ALTITUDE;
    float muH = horizonMu(r);
    float mu;
    if (uv.y > 0.5) {
        float t = (uv.y - 0.5) * 2.0;
        mu = muH + t * t * (1.0 - muH);
    } else {
        float t = (0.5 - uv.y) * 2.0;
        mu = muH - t * t * (muH + 1.0);
    }
    float az = uv.x * ATMOS_PI;
    float3 sunFlat = horizontalDir(sunDir);
    float3 side = float3(-sunFlat.z, 0.0, sunFlat.x); // sunFlat rotated a quarter turn in the ground plane
    float horiz = sqrt(max(0.0, 1.0 - mu * mu));
    return normalize(float3(0.0, mu, 0.0) + horiz * (cos(az) * sunFlat + sin(az) * side));
}

/**
 * The sky by brute force: the Nishita/O'Neil march this milestone replaced, kept as a REFERENCE.
 *
 * Self-contained on purpose — it samples no table and declares no binding, so it can be evaluated
 * anywhere the parameters are visible and compared against the sky-view lookup at the same direction.
 * That comparison is the only unambiguous statement available about whether the table is right: reading
 * the three baked channels by eye means guessing what they ought to look like, and the first two guesses
 * offered for them were both wrong — a Mie band was predicted to hold a halo it cannot hold, and a
 * near-zero multi-scatter band read as "structured" only because a hue normalisation was dividing noise
 * by noise.
 *
 * Slow. It exists to be run at a handful of pixels in a debug view, never in a frame that ships.
 */
public float3 atmosphereReferenceSky(float3 dir, float3 sunDir) {
    const int PRIMARY_STEPS = 16;
    const int LIGHT_STEPS = 8;
    float3 orig = float3(0.0, PLANET_R + ATMOS_VIEW_ALTITUDE, 0.0);
    float2 t = raySphere(orig, dir, ATMOS_R);
    if (t.x > t.y) return float3(0.0, 0.0, 0.0);
    float tStart = max(t.x, 0.0);
    float tEnd = t.y;
    float2 tGround = raySphere(orig, dir, PLANET_R);
    if (tGround.x > 0.0) tEnd = min(tEnd, tGround.x);
    float segLen = (tEnd - tStart) / float(PRIMARY_STEPS);

    float mu = dot(dir, sunDir);
    float phaseR = (3.0 / (16.0 * ATMOS_PI)) * (1.0 + mu * mu);
    float g = MIE_G;
    float g2 = g * g;
    float d = 1.0 + g2 - 2.0 * g * mu;
    float phaseM = (1.0 - g2) / (4.0 * ATMOS_PI * max(d * sqrt(max(d, 0.0)), 1.0e-6));

    float3 sumR = float3(0.0, 0.0, 0.0), sumM = float3(0.0, 0.0, 0.0);
    float odR = 0.0, odM = 0.0, odO = 0.0;
    float tCur = tStart;
    for (int i = 0; i < PRIMARY_STEPS; i++) {
        float3 p = orig + dir * (tCur + segLen * 0.5);
        float h = length(p) - PLANET_R;
        float hr = exp(-h / RAY_SCALE_H) * segLen;
        float hm = exp(-h / MIE_SCALE_H) * segLen;
        odR += hr; odM += hm; odO += ozoneDensity(h) * segLen;

        float2 tl = raySphere(p, sunDir, ATMOS_R);
        float segLenL = tl.y / float(LIGHT_STEPS);
        float tlCur = 0.0, odRL = 0.0, odML = 0.0, odOL = 0.0;
        bool blocked = false;
        for (int j = 0; j < LIGHT_STEPS; j++) {
            float3 pl = p + sunDir * (tlCur + segLenL * 0.5);
            float hl = length(pl) - PLANET_R;
            if (hl < 0.0) { blocked = true; break; }
            odRL += exp(-hl / RAY_SCALE_H) * segLenL;
            odML += exp(-hl / MIE_SCALE_H) * segLenL;
            odOL += ozoneDensity(hl) * segLenL;
            tlCur += segLenL;
        }
        if (!blocked) {
            float3 atten = exp(-(RAY_BETA * (odR + odRL)
                               + MIE_BETA * MIE_EXTINCTION_FACTOR * (odM + odML)
                               + OZONE_BETA * (odO + odOL)));
            sumR += hr * atten;
            sumM += hm * atten;
        }
        tCur += segLen;
    }
    return SUN_INTENSITY * (sumR * RAY_BETA * phaseR + sumM * MIE_BETA * phaseM);
}
