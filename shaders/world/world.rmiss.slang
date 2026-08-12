// Ray miss = sky. Per-frame sky data comes from the WorldPush BDA buffer addressed by the 8-byte push
// constant. The shader writes full sky radiance (Rayleigh/Mie/ozone atmosphere + transmittance-tinted
// sun/moon discs + soft horizon + starfield) into payload.albedo; raygen consumes that value for both
// accumulated radiance and bounce-0 denoiser guides.
//
// Celestial discs (sun/moon) are gated by a packed payload flag, which raygen sets per-ray from the path
// state: true on the primary ray and after every specular/dielectric bounce (so the sun/moon show up in
// water/glass/metal reflections + refractions), false after a diffuse vertex did sun/moon NEE (so the
// disc isn't double-counted against the direct light → fireflies). The atmosphere gradient + stars are
// added on every miss (any bounce): stars aren't a NEE light, so they never double-count.
import world_common;
import atmosphere;
import atmosphere_lut;
import environment;

[[vk::push_constant]] WorldPushConstants pc;

// Vanilla celestials atlas (sun + moon-phase sprites), bound by RtComposite. Sampled with an explicit
// LOD (no derivatives in a miss shader). The sun/moon discs are drawn from its real texels.
[[vk::binding(9, 0)]] Sampler2D celestialsAtlas;

static const float PI = 3.14159265359;
float pow2(float x) { return x * x; }

// Near-white top-of-atmosphere disc radiances: the visible warm/red tint comes from multiplying by
// sampleTransmittanceToSpace(dir) at draw time — at noon the sun reads warm-white, at the horizon it reddens
// on exactly the sky's own sunset curve.
static const float3 SUN_DISC_RADIANCE  = float3(24.0, 23.0, 21.5);
static const float3 MOON_DISC_RADIANCE = float3(1.7, 1.85, 2.2) / 4.0; // lit-side moon (HDR, bright at night exposure)
// Vibrance applied to the atmosphere in-scatter only (not discs/stars/night ambient): luminance-
// preserving, so GI energy is unchanged.
static const float SKY_SATURATION = 1.2;
// VISIBLE disc half-angles, matched to vanilla: the sun/moon quads are drawn at half-width 30/20 at
// distance 100 → half-angle = atan(0.30) / atan(0.20). Decoupled from the NEE light radius (≈0.6°).
static const float SUN_DISC_HALF_ANGLE  = 0.29146; // atan(30/100), vanilla sun size
static const float MOON_DISC_HALF_ANGLE = 0.19740; // atan(20/100), vanilla moon size
// Faint night-sky ambient so the night isn't pure black where the atmosphere in-scatter falls to zero.
static const float3 NIGHT_ZENITH  = float3(0.004, 0.008, 0.022) / 5.0;
static const float3 NIGHT_HORIZON = float3(0.015, 0.022, 0.045) / 5.0;

// ---- The atmosphere, read from the sky-view table (M10.3).
//
// This was a 16-step march through the shell, each step running an 8-step march toward the sun: 128
// extinction evaluations for every ray that escaped, describing a picture that varies smoothly across the
// whole sky. It is now three texture fetches. The maths did not move to a different file, it moved to a
// different TIME — sky_view.comp bakes it once per frame for 24576 directions instead of once per pixel.

// Build the celestial body's square tangent frame: `right` along the arc-travel direction, `up`
// perpendicular within the sky. Shared by the visible disc and the NEE square sampling in raygen.
void celestialFrame(float3 dir, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(dir, celestialAxis));
    up = cross(right, dir);
}

// Gnomonic projection of `dir` into the body's tangent square, in units of the half-angle. local ∈
// [-1,1]² is inside the square. Returns 0 behind the body. The square (not a cone) matches MC's quad.
float squareBody(float3 dir, float3 bodyDir, float halfAngle, float3 celestialAxis, out float2 local) {
    float c = dot(dir, bodyDir);
    if (c <= 1.0e-3) { local = float2(2.0, 2.0); return 0.0; }
    float3 d = dir / c;                  // d·bodyDir == 1 (point on the tangent plane)
    float3 r, u;
    celestialFrame(bodyDir, celestialAxis, r, u);
    float t = tan(halfAngle);
    local = float2(dot(d, r), dot(d, u)) / t;
    float m = max(abs(local.x), abs(local.y));
    return 1.0 - smoothstep(0.90, 1.0, m); // soft square edge
}

// Dave Hoskins' hash13 (https://www.shadertoy.com/view/4djSRW): well-distributed and pattern-free.
float hash13(float3 p3) {
    p3 = frac(p3 * 0.1031);
    p3 += dot(p3, p3.zyx + 31.32);
    return frac((p3.x + p3.y) * p3.z);
}

// Rotate v about a unit axis by angle (Rodrigues). Used to wheel the starfield about the celestial pole.
float3 rotateAxis(float3 v, float3 axis, float ang) {
    float c = cos(ang), s = sin(ang);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

// Procedural starfield, a faithful port of vanilla's buildStars(): ~1500 discrete stars at random
// sphere directions, each a small SQUARE billboard of random size + random rotation. A cube-map grid is
// the spatial accelerator — at most one star per cell, placed at a hashed position INSET from the cell
// edges (so its square never crosses into a neighbour), drawn as a hash-rotated square of hashed size.
// The lookup direction is rotated about the celestial pole by -STAR_ANGLE so the field wheels with
// world time like the real sky.
static const float STAR_CELLS     = 21.0;  // cube-face grid resolution; with the threshold gives ~1500 stars
static const float STAR_THRESHOLD = 0.86;  // cell occupancy cutoff (higher = fewer stars)
static const float STAR_HALF      = 0.045; // star half-size as a fraction of a cell (≈ vanilla 0.11° at center)
float3 stars(float3 dir, float starBrightness, float VdotS, float4 celestial) {
    if (starBrightness <= 0.0 || dir.y <= 0.0) return float3(0.0, 0.0, 0.0);
    // Negative angle: rotating the LOOKUP direction is the inverse of rotating star geometry, so
    // -starAngle makes the field wheel the SAME way as the sun/moon (whose world dirs are
    // forward-rotated on the CPU).
    float3 sdir = rotateAxis(dir, normalize(celestial.xyz), -celestial.w);
    // Cube-map face + 2D gnomonic uv → uniform square cells, no pole/equator distortion.
    float3 a = abs(sdir);
    float2 uv;
    float face;
    if (a.x >= a.y && a.x >= a.z)      { uv = sdir.yz / a.x; face = sdir.x > 0.0 ? 0.0 : 1.0; }
    else if (a.y >= a.z)               { uv = sdir.xz / a.y; face = sdir.y > 0.0 ? 2.0 : 3.0; }
    else                               { uv = sdir.xy / a.z; face = sdir.z > 0.0 ? 4.0 : 5.0; }
    float2 g = uv * STAR_CELLS;
    float3 id = float3(floor(g), face);
    if (hash13(id) < STAR_THRESHOLD) return float3(0.0, 0.0, 0.0); // no star in this cell
    // Per-star randoms: size, centre (inset so the rotated square stays inside the cell), rotation.
    float sz = STAR_HALF * (0.6 + 0.8 * hash13(id + 53.0));
    float inset = sz * 1.5; // keeps the rotated square clear of the cell edge
    float2 center = inset + (1.0 - 2.0 * inset) * float2(hash13(id + 11.0), hash13(id + 23.0));
    float2 q = frac(g) - center;
    float ang = hash13(id + 91.0) * 6.2831853;        // random per-star rotation (vanilla zRot)
    float cs = cos(ang), sn = sin(ang);
    q = float2(cs * q.x - sn * q.y, sn * q.x + cs * q.y); // GLSL mat2(cs,-sn,sn,cs) * q, written out
    float box = max(abs(q.x), abs(q.y));               // Chebyshev distance → square
    float star = 1.0 - smoothstep(sz * 0.6, sz, box);  // soft-edged square (helps DLSS stability)
    if (star <= 0.0) return float3(0.0, 0.0, 0.0);
    float bright = 0.55 + 0.45 * hash13(id + 71.0);    // mild per-star brightness variation
    star *= bright * min(dir.y * 3.0, 1.0) * max(0.0, 1.0 - pow(abs(VdotS) * 1.002, 100.0));
    // Per-star colour temperature: hash picks a spot on a warm-orange ↔ blue-white ramp (real star
    // populations). Average luminance close to the old uniform constant so night exposure doesn't shift.
    float3 starCol = lerp(float3(0.78, 0.60, 0.42), float3(0.50, 0.62, 0.95), hash13(id + 137.0));
    return 0.2 * star * starCol * starBrightness;
}

[shader("miss")]
void main(inout Payload payload) {
    WorldPush worldPush = ConstPtr<WorldPush>(pc.worldPushAddr)[0];
    if (worldPush.skyProvider == SKY_PROVIDER_ENVIRONMENT) {
        float3 dir = normalize(WorldRayDirection());
        float coneSpread = max(unpackHalf2(payload.rayCone).y, 0.0);
        float lod = log2(max(coneSpread * 4096.0 / (2.0 * PI), 1.0));
        // D91A: RR quality 3 traces the accepted 1600x900 view at 533x300. Strict cone filtering then
        // reads this 4K HDRI around mip 1.4, reducing the directly visible authored sky to roughly 1.5K.
        // Reconstruct one extra mip only for the camera ray. Reflections, refractions and diffuse misses
        // keep the unmodified cone LOD so bright source texels do not crawl through broad secondary lobes.
        if ((payload.flags & PAYLOAD_DIRECT_VIEW) != 0u) {
            lod = max(lod - 1.0, 0.0);
        }
        bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;
        EnvironmentSample environment = sampleEnvironment(
                worldPush, dir, lod, worldPush.lightRadiance.x);
        float3 col = environment.radiance;
        float3 analytic = float3(0.0, 0.0, 0.0);
        if (environment.disk) {
            bool proposalSupportsDirection = environmentLightSupportsDirection(worldPush, dir);
            if (proposalSupportsDirection) {
                if (!showCelestial) {
                    // A diffuse continuation suppresses only disk Le; attenuated HDRI behind a
                    // finite-thickness disk remains an ordinary environment continuation.
                    col = max(col - environment.diskRadiance, float3(0.0, 0.0, 0.0));
                } else {
                    // Keep the exact disk Le out of the scenery lane when NEE can also sample it. Raygen
                    // reconstructs Le from this Le/pdf proposal and applies the continuation MIS weight.
                    float pdf = squareLightDirectionalPdf(
                            worldPush.lightDir.xyz, worldPush.lightDir.w, dir);
                    analytic = environment.diskRadiance / max(pdf, 1.0e-20);
                    col = max(col - environment.diskRadiance, float3(0.0, 0.0, 0.0));
                }
            }
            // Outside proposal support there is no competing NEE sample. Leave Le in the ordinary
            // continuation lane even after a diffuse bounce; hiding it would bias away high-order images.
        }
        payload.albedo = half3(clamp(col, float3(0.0, 0.0, 0.0),
                float3(HALF_MAX, HALF_MAX, HALF_MAX)));
        payload.hitT = -1.0;
        payload.normal = half3(0.0h, 0.0h, 0.0h);
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(clamp(analytic, float3(0.0, 0.0, 0.0),
                float3(HALF_MAX, HALF_MAX, HALF_MAX)));
        payload.flags = any(analytic > 0.0) ? PAYLOAD_ANALYTIC_LIGHT : 0u;
        payload.roughMetal = packHalf2(float2(1.0, 0.0));
        payload.emissionSss = packHalf2(float2(0.0, 0.0));
        payload.iorTransmission = packHalf2(float2(1.0, 0.0));
        payload.rayCone = 0u;
        return;
    }
    if (worldPush.skyProvider == SKY_PROVIDER_LOCAL_AMBIENT) {
        // Nether-style provider: no atmosphere, planet, sun, moon or procedural stars. The authored
        // neutral floor is intentionally visible in every direction and also feeds mediumSkyRadiance;
        // local emitters provide the structured light. ENVIRONMENT is kept separate for the later End
        // HDRI/Kerr implementation and is not faked by this branch.
        float3 col = max(worldPush.mediumSkyRadiance.xyz, float3(0.0, 0.0, 0.0));
        payload.albedo = half3(clamp(col, float3(0.0, 0.0, 0.0),
                float3(HALF_MAX, HALF_MAX, HALF_MAX)));
        payload.hitT = -1.0;
        payload.normal = half3(0.0h, 0.0h, 0.0h);
        payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
        payload.f0 = half3(0.0h, 0.0h, 0.0h);
        payload.flags = 0u;
        payload.roughMetal = packHalf2(float2(1.0, 0.0));
        payload.emissionSss = packHalf2(float2(0.0, 0.0));
        payload.iorTransmission = packHalf2(float2(1.0, 0.0));
        payload.rayCone = 0u;
        return;
    }
    // The analytic light below is the MIS partner of raygen's sun NEE — the same emitter reached the
    // other way — so it has to be dyed by the same atmosphere, from the same table, or the two estimators
    // disagree and the weighting stops cancelling. (This stage has its own local worldPush: it does not
    // import world_core, so it does not share raygen's global.)
    dyeCelestialLight(worldPush);
    float3 dir = normalize(WorldRayDirection());
    float day = worldPush.sunDir.w;
    float3 sd = worldPush.sunDir.xyz;
    float SdotU = sd.y;
    float VdotS = dot(dir, sd);
    float sunUp = clamp((SdotU + 0.0625) / 0.125, 0.0, 1.0); // 0 well below horizon .. 1 above
    float starBrightness = worldPush.lightRadiance.w;
    bool showCelestial = (payload.flags & PAYLOAD_SHOW_CELESTIAL) != 0u;

    // Physically-based in-scattering: blue zenith, bright/desaturated horizon, warm Mie halo + red
    // sunset all fall out of the Rayleigh/Mie/ozone march. At night the sun is below the horizon so the
    // light march is blocked → near-zero; a faint night ambient + the rotating starfield sit underneath.
    //
    // Below-horizon rays are looked up like any other. There used to be a hand-made substitute here: the
    // direction was flattened onto the horizon and the result multiplied by an exponential fade, because
    // the march it fed would otherwise run into the planet and draw a hard line across the sky. The
    // sky-view table has a real answer for those directions — its elevation axis covers them, and the
    // bake simply ends the march at the ground — so the substitute goes, and with it a fade constant that
    // was chosen to look right rather than to mean anything.
    float3 col = sampleSkyView(dir, sd);
    // Luminance-preserving vibrance on the in-scatter only (see SKY_SATURATION).
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = max(lerp(float3(lum, lum, lum), col, SKY_SATURATION), float3(0.0, 0.0, 0.0));
    float up = clamp(dir.y, 0.0, 1.0);
    float3 nightAmbient = lerp(NIGHT_HORIZON, NIGHT_ZENITH, up);
    col += nightAmbient * (1.0 - day);
    col += stars(dir, starBrightness, VdotS, worldPush.celestial);

    // Sun & moon discs — sampled from the vanilla celestials atlas, gated to rays that haven't already
    // accounted for the light via diffuse NEE. squareBody gives the body-local [-1,1] coord; the
    // sprite's own texel coverage (alpha) shapes the disc.
    float3 disc = float3(0.0, 0.0, 0.0);
    if (showCelestial) {
        float2 local;
        squareBody(dir, sd, SUN_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0))) {
            float2 uv = lerp(worldPush.sunUv.xy, worldPush.sunUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // The vanilla sun sprite bakes a soft glow gradient into its texels; scaling that raw to HDR
            // makes a huge ring. Raise the texture luminance to a high power — only the bright core
            // survives, the halo collapses to ~0.
            float core = pow(clamp(dot(t, t) * 0.45, 0.0, 1.0), 6.0);
            // transmittanceToSpace reddens+dims the disc through the sunset on the sky's own curve.
            disc += SUN_DISC_RADIANCE * sampleTransmittanceToSpace(dir) * core * sunUp;
        }
        // Same horizon gate as the sun: with the soft-horizon change, below-horizon rays no longer clip
        // at the planet, so without this the moon disc would stay visible after it set.
        float moonUp = clamp((worldPush.moonDir.y + 0.0625) / 0.125, 0.0, 1.0);
        squareBody(dir, worldPush.moonDir.xyz, MOON_DISC_HALF_ANGLE, worldPush.celestial.xyz, local);
        if (all(abs(local) < float2(1.0, 1.0)) && moonUp > 0.0) {
            float2 uv = lerp(worldPush.moonUv.xy, worldPush.moonUv.zw, local * 0.5 + 0.5);
            float3 t = celestialsAtlas.SampleLevel(uv, 0.0).rgb;
            // Moon: a smoothstep contrast ramp suppresses the faint halo while keeping the lit phase
            // shape from the texture. The transmittance tint gives a warm amber moon at moonrise/set.
            float m = smoothstep(0.0, 1.0, min(length(t), 1.0)) * 1.3;
            disc += MOON_DISC_RADIANCE * sampleTransmittanceToSpace(dir) * t * m * pow2(1.0 - day) * moonUp;
        }
    }

    // The drawn discs are scenery and join the sky. They are vanilla's stylised sun and moon — sixteen
    // and eleven degrees across — and nothing samples them directly, so they are only ever reached one
    // way and want no weighting. Keeping them here is also what keeps a glossy reflection of the sun
    // looking like the sun in the sky above it rather than like the point light below.
    col += disc;

    // The light itself, which is a different object: the small square NEE jitters over. It is estimated
    // both by NEE and by a BSDF ray landing on it, so it travels in its own lane for the raygen to
    // weight. See PAYLOAD_ANALYTIC_LIGHT — including why this is an irradiance and not a radiance.
    float3 analytic = float3(0.0, 0.0, 0.0);
    float lightHalfAngle = worldPush.lightDir.w;
    if (lightHalfAngle > 0.0) {
        float3 axis = worldPush.lightDir.xyz;
        float toward = dot(dir, axis);
        if (toward > 1.0e-3) {
            float3 right, up;
            squareLightFrame(axis, worldPush.celestial.xyz, right, up);
            float3 tangentPoint = dir / toward; // on the plane where tangentPoint . axis == 1
            float t = tan(lightHalfAngle);
            if (max(abs(dot(tangentPoint, right)), abs(dot(tangentPoint, up))) < t) {
                analytic = worldPush.lightRadiance.xyz;
            }
        }
    }

    // albedo is a half3 lane, so the sky's HDR radiance is clamped to half's finite range rather than
    // allowed to round up to +inf and propagate as NaN through the path throughput. This is a guard, not
    // a correction: the brightest term here is SUN_DISC_RADIANCE at 24.0, far under the 65504 ceiling.
    payload.albedo = half3(clamp(col, float3(0.0, 0.0, 0.0), float3(HALF_MAX, HALF_MAX, HALF_MAX)));
    payload.hitT = -1.0;
    payload.normal = half3(0.0h, 0.0h, 0.0h);
    payload.motionPrev = half3(0.0h, 0.0h, 0.0h);
    payload.f0 = half3(analytic); // f0 is dead space on a miss; irradiance, so it cannot overflow half
    payload.flags = dot(analytic, float3(1.0, 1.0, 1.0)) > 0.0 ? PAYLOAD_ANALYTIC_LIGHT : 0u;
    payload.roughMetal = packHalf2(float2(1.0, 0.0));
    payload.emissionSss = packHalf2(float2(0.0, 0.0));
    payload.iorTransmission = packHalf2(float2(1.0, 0.0));
    payload.rayCone = 0u;
}
