import rain_streak_common;

[[vk::push_constant]] RainStreakPush push;

[[vk::binding(0, 0)]] RWTexture2D<float> sceneDepth;

struct FragmentIn {
    float4 position : SV_Position;
    float2 local : TEXCOORD0;
    float3 radiance : TEXCOORD1;
    float exposure : TEXCOORD2;
    float flake : TEXCOORD3;
};

[shader("fragment")]
float4 main(FragmentIn i) : SV_Target0 {
    uint guideW, guideH;
    sceneDepth.GetDimensions(guideW, guideH);
    float2 uv = i.position.xy / max(push.displaySize, float2(1.0, 1.0));
    int2 guidePixel = int2(clamp(uv * float2(guideW, guideH),
            float2(0.0, 0.0), float2(guideW - 1u, guideH - 1u)));
    float opaqueDepth = sceneDepth.Load(guidePixel);
    // Reversed-Z: a smaller value is farther away. Let primary geometry occlude the streak.
    if (i.position.z < opaqueDepth - 1.0e-4) {
        discard;
    }
    float side = saturate(1.0 - abs(i.local.x));
    float endFade = smoothstep(0.0, 0.12, i.local.y)
            * smoothstep(0.0, 0.12, 1.0 - i.local.y);
    float shape = side * endFade;
    // A ROUND falloff for snow, not a line's taper. The streak's profile fades across its width and
    // then again toward its two ends, which is the right shape for something long; a flake has no ends,
    // and running that profile on a square draws a lozenge with visibly flat sides.
    float alpha = shape * i.exposure * 0.30;
    if (i.flake > 0.5) {
        float2 d = float2(i.local.x, i.local.y * 2.0 - 1.0);
        // Opacity above rain's, and authored rather than derived: a flake is a solid scatterer read at a
        // few pixels across, where a raindrop is a faint smear you are meant to see the world through.
        alpha = saturate(1.0 - dot(d, d)) * i.exposure * 0.55;
    }
    // Density controls how many streaks exist, not their opacity as well. Applying it twice made the
    // user-facing multiplier super-linear and changed the appearance of every surviving streak.
    return float4(i.radiance, alpha);
}
