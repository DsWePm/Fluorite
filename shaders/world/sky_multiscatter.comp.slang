// Bakes the light that has scattered TWICE OR MORE into a 32x32 LUT, indexed by (sun zenith, altitude).
//
// Single scattering answers "how much sunlight bounced once into my eye", and everywhere the air is
// thick that answer is too dark: the photons that did not survive the first bounce did not vanish, they
// kept bouncing. That deficit is not uniform — it grows precisely where the optical depth grows — so it
// cannot be dialled out with a constant ambient. It is why a single-scattering sky goes flat and dim
// away from the sun, why twilight switches off instead of glowing, and why dense haze turns to soot.
//
// Baked FROM the transmittance table, which is why the order of the two bakes matters and why RtSky owns
// both: this shader samples binding 0, which is the table sky_transmittance.comp wrote.
//
// The technique is Hillaire's; the implementation is derived here from the physics rather than ported.

import atmosphere;

// Directions sampled over the sphere per texel, and steps along each. 64x24 is 1536 iterations per
// texel and 1.6M for the whole table — once, at startup, for a table nothing afterwards recomputes.
static const int MS_DIRECTIONS = 64;
static const int MS_STEPS = 24;

[[vk::binding(0, 0)]] Sampler2D transmittanceLut;
[[vk::binding(1, 0)]] RWTexture2D<float4> multiScatterLut;

// Transmittance to space, read from the table rather than marched. Duplicated from atmosphere_lut's
// sampleTransmittance rather than imported, because that module declares set-0 binding 10 and this
// shader's descriptor set has two bindings of its own — importing it would hand the bake a binding its
// layout does not contain. The maths it shares (transmittanceLutUv, horizonMu) does live in atmosphere.
float3 transmittanceToSpace(float r, float mu) {
    if (mu < horizonMu(r)) {
        return float3(0.0, 0.0, 0.0);
    }
    return transmittanceLut.SampleLevel(transmittanceLutUv(r, clamp(mu, -1.0, 1.0)), 0.0).rgb;
}

// Fibonacci sphere: MS_DIRECTIONS directions spread evenly over the sphere with no random numbers.
// Deterministic on purpose — a stochastic set would put its noise in a table that is baked once and then
// read for the rest of the session, so there is nothing downstream to average it away.
float3 sphereDirection(int i) {
    const float GOLDEN_ANGLE = 2.39996323; // pi * (3 - sqrt(5))
    float z = 1.0 - (2.0 * float(i) + 1.0) / float(MS_DIRECTIONS);
    float rho = sqrt(max(0.0, 1.0 - z * z));
    float phi = GOLDEN_ANGLE * float(i);
    return float3(rho * cos(phi), z, rho * sin(phi));
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id: SV_DispatchThreadID) {
    if (id.x >= uint(SKY_MULTISCATTER_N) || id.y >= uint(SKY_MULTISCATTER_N)) {
        return;
    }
    float2 uv = (float2(id.xy) + 0.5) / float2(SKY_MULTISCATTER_N, SKY_MULTISCATTER_N);
    float r;
    float muS;
    multiScatterLutParams(uv, r, muS);

    float3 origin = float3(0.0, r, 0.0);
    float3 sunDir = float3(sqrt(max(0.0, 1.0 - muS * muS)), muS, 0.0);

    // Two integrals over the same marches, differing only in what illuminates the medium:
    //   sunOrder2 - the response to the SUN, i.e. the second-order scattered radiance
    //   feedback   - the response to a UNIFORM unit radiance field, i.e. the fraction of light that a
    //               single pass through the atmosphere scatters back into it
    // The second is what turns two orders into all of them: if one bounce returns `feedback` of what it
    // was given, the full series is 1 + f + f^2 + ... = 1/(1-f). That geometric sum IS the approximation,
    // and it is exact under this table's stated assumption that everything past the first bounce is
    // isotropic.
    float3 sunOrder2 = float3(0.0, 0.0, 0.0);
    float3 feedback = float3(0.0, 0.0, 0.0);

    for (int d = 0; d < MS_DIRECTIONS; ++d) {
        float3 dir = sphereDirection(d);
        float2 tAtmos = raySphere(origin, dir, ATMOS_R);
        float tEnd = tAtmos.y;
        if (tEnd <= 0.0) {
            continue;
        }
        // Stop at the ground. Light reflected back up off the planet is deliberately NOT added here,
        // though the technique allows for it: it needs a planetary albedo, and this planet's surface is
        // the player's actual voxel world. A uniform albedo would put a constant glow beneath the horizon
        // that the terrain right in front of them contradicts.
        float2 tGround = raySphere(origin, dir, PLANET_R);
        if (tGround.x > 0.0) {
            tEnd = min(tEnd, tGround.x);
        }
        float dt = tEnd / float(MS_STEPS);

        float3 rayTransmittance = float3(1.0, 1.0, 1.0);
        for (int s = 0; s < MS_STEPS; ++s) {
            float3 p = origin + dir * (dt * (float(s) + 0.5));
            float pr = length(p);
            float3 sigmaS;
            float3 sigmaT;
            atmosphereMedium(pr - PLANET_R, sigmaS, sigmaT);

            // Closed form over the step rather than a midpoint product. The integrand is
            // sigmaS * exp(-sigmaT * s) and its integral over the step is sigmaS * (1 - e^-sigmaT*dt) /
            // sigmaT. At 24 steps the two differ where it matters most — near the ground, where a step
            // can span a full optical depth and the midpoint rule overshoots.
            float3 stepT = exp(-sigmaT * dt);
            float3 scattered = rayTransmittance * sigmaS * (float3(1.0, 1.0, 1.0) - stepT)
                             / max(sigmaT, float3(1.0e-9, 1.0e-9, 1.0e-9));

            feedback += scattered;
            sunOrder2 += scattered * transmittanceToSpace(pr, dot(p / pr, sunDir));
            rayTransmittance *= stepT;
        }
    }

    // Divide by the direction count and nothing else. The sphere integral contributes 4*pi/N and the
    // isotropic phase function contributes 1/(4*pi); they cancel exactly, which is why neither constant
    // appears above. Worth stating, because an unexplained missing 4*pi is indistinguishable from a bug.
    sunOrder2 /= float(MS_DIRECTIONS);
    feedback /= float(MS_DIRECTIONS);

    // feedback is a reflectance and stays below 1 for any real atmosphere, but it is an estimate from 64
    // directions, and a value that crept to 1 would divide by zero and write infinity into a table that
    // then multiplies the whole sky. Clamped where the consequence is local rather than global.
    float3 psi = sunOrder2 / max(float3(1.0, 1.0, 1.0) - feedback, float3(1.0e-3, 1.0e-3, 1.0e-3));
    multiScatterLut[int2(id.xy)] = float4(psi, 1.0);
}
