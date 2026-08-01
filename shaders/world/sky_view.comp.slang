// Bakes the whole sky at the camera's altitude into a 192x128 table, so world.rmiss can look up what it
// used to march: 16 primary steps each running an 8-step inner loop toward the sun, 128 extinction
// evaluations per pixel, for a picture that varies smoothly over the entire hemisphere.
//
// Unlike the two tables before it, THIS ONE DEPENDS ON THE TIME OF DAY and is rebaked every frame. That
// is worth stating loudly next to RtSky's insistence that the others are baked once: the difference is
// not effort or caution, it is that the sun's direction enters this integral, and there it enters where
// the table is BAKED rather than where it is sampled. Reading the once-only pattern off its neighbours
// and applying it here would freeze the sky at whatever time the world loaded.
//
// Three outputs rather than one, because the phase functions stay OUTSIDE the table. Rayleigh's phase is
// broad and Mie's is a narrow forward spike, and a 192-wide azimuth axis cannot resolve that spike — bake
// the phase in and the sun's halo turns to mush. Both phases depend only on the angle between the view
// and the sun, which is constant along any one ray, so factoring them out is exact rather than an
// approximation. Multiple scattering is isotropic and has no phase at all, which is precisely why it
// cannot ride along in either of the other two.

import atmosphere;

static const int SKY_VIEW_STEPS = 32;

struct SkyViewPush {
    float4 sunDir; // xyz world-space, w unused
    // Art direction on the sky: a unit-luminance blackbody tint already multiplied by an intensity, so
    // this is one multiply rather than two knobs the shader has to know about. Identity by default.
    float4 tint;
};
[[vk::push_constant]] SkyViewPush push;

[[vk::binding(0, 0)]] Sampler2D transmittanceLut;
[[vk::binding(1, 0)]] Sampler2D multiScatterLut;
[[vk::binding(2, 0)]] RWTexture2D<float4> skyViewRayleigh;
[[vk::binding(3, 0)]] RWTexture2D<float4> skyViewMie;
[[vk::binding(4, 0)]] RWTexture2D<float4> skyViewMulti;

// Duplicated from atmosphere_lut rather than imported, for the reason given there: that module declares
// set-0 bindings belonging to the ray-tracing pipeline, and this shader's set is its own.
float3 transmittanceToSpace(float r, float mu) {
    if (mu < horizonMu(r)) {
        return float3(0.0, 0.0, 0.0);
    }
    return transmittanceLut.SampleLevel(transmittanceLutUv(r, clamp(mu, -1.0, 1.0)), 0.0).rgb;
}

float3 multiScatterAt(float r, float muS) {
    return multiScatterLut.SampleLevel(multiScatterLutUv(r, muS), 0.0).rgb;
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(SKY_VIEW_W) || id.y >= uint(SKY_VIEW_H)) {
        return;
    }
    float2 uv = (float2(id.xy) + 0.5) / float2(SKY_VIEW_W, SKY_VIEW_H);
    float3 sunDir = normalize(push.sunDir.xyz);
    float3 dir = skyViewLutDir(uv, sunDir);

    float3 origin = float3(0.0, PLANET_R + ATMOS_VIEW_ALTITUDE, 0.0);
    float2 tAtmos = raySphere(origin, dir, ATMOS_R);
    if (tAtmos.y <= 0.0) {
        skyViewRayleigh[int2(id.xy)] = float4(0.0, 0.0, 0.0, 1.0);
        skyViewMie[int2(id.xy)] = float4(0.0, 0.0, 0.0, 1.0);
        skyViewMulti[int2(id.xy)] = float4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    float tEnd = tAtmos.y;
    // Stop at the planet. Below-horizon rays are baked honestly rather than clipped: the march simply
    // ends early and returns the little haze it gathered on the way down. The march this replaces had a
    // hand-made substitute — it flattened downward directions onto the horizon and multiplied by an
    // exponential fade — precisely because it had nothing better to say about them. A table indexed by
    // elevation does, and that hack goes away with it.
    float2 tGround = raySphere(origin, dir, PLANET_R);
    if (tGround.x > 0.0) {
        tEnd = min(tEnd, tGround.x);
    }
    float dt = tEnd / float(SKY_VIEW_STEPS);

    float3 sumR = float3(0.0, 0.0, 0.0);
    float3 sumM = float3(0.0, 0.0, 0.0);
    float3 sumMs = float3(0.0, 0.0, 0.0);
    float3 viewTransmittance = float3(1.0, 1.0, 1.0);

    for (int s = 0; s < SKY_VIEW_STEPS; ++s) {
        float3 p = origin + dir * (dt * (float(s) + 0.5));
        float pr = length(p);
        float h = pr - PLANET_R;
        float3 up = p / pr;

        float3 sigmaS;
        float3 sigmaT;
        atmosphereMedium(h, sigmaS, sigmaT);
        float densityR = exp(-h / RAY_SCALE_H);
        float densityM = exp(-h / MIE_SCALE_H);

        // Closed form over the step rather than a midpoint product: the integrand is exp(-sigmaT * s) and
        // near the ground a step can span a whole optical depth, where the midpoint rule overshoots.
        float3 stepT = exp(-sigmaT * dt);
        float3 weight = viewTransmittance * (float3(1.0, 1.0, 1.0) - stepT)
                      / max(sigmaT, float3(1.0e-9, 1.0e-9, 1.0e-9));

        float muS = dot(up, sunDir);
        float3 sunT = transmittanceToSpace(pr, muS);

        sumR += weight * (RAY_BETA * densityR) * sunT;
        sumM += weight * (MIE_BETA * densityM) * sunT;
        // No sun transmittance and no visibility test on this one: it is light that already bounced more
        // than once, so it reaches points the direct beam does not. Gating it on the sun being visible is
        // what makes a single-scattering twilight switch off instead of glow.
        sumMs += weight * sigmaS * multiScatterAt(pr, muS);

        viewTransmittance *= stepT;
    }

    float3 art = push.tint.xyz;
    skyViewRayleigh[int2(id.xy)] = float4(sumR * SUN_INTENSITY * art, 1.0);
    skyViewMie[int2(id.xy)] = float4(sumM * SUN_INTENSITY * art, 1.0);
    skyViewMulti[int2(id.xy)] = float4(sumMs * SUN_INTENSITY * art, 1.0);
}
