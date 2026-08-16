// Persistent two-layer world-column wetness in RG32_UINT.
// Each uint: high 16 = half depth along rain ray, next 8 = wet UNORM, low 8 = puddle UNORM.
// Two ping-pong images therefore hold two associated surfaces in 4 MiB at 512^2.

import world_common;
import precipitation;

struct RainHistoryPush {
    uint64_t worldPushAddr;
    // What is falling on each column. Snow now gets a real exposure depth so its flakes can be sheltered
    // by a roof, which means this pass can no longer read "has a depth" as "is getting wet".
    uint64_t precipitationAddr;
    uint resolution;
    uint clearPrevious;
    float elapsedSeconds;
    float wetFillSeconds;
    float wetDrySeconds;
    float puddleFillSeconds;
    float puddleDrySeconds;
    float dryingScale;
    int2 originShift;
    uint reserved;
};
[[vk::push_constant]] RainHistoryPush rainHistoryPush;

[[vk::binding(0, 0)]] Sampler2D<uint2> previousHistory;
[[vk::binding(1, 0)]] RWTexture2D<uint2> nextHistory;
[[vk::binding(2, 0)]] Sampler2D<float> exposureDepth;

struct WetLayer {
    float depth;
    float wet;
    float puddle;
};

WetLayer emptyLayer() {
    WetLayer l;
    l.depth = -1.0;
    l.wet = 0.0;
    l.puddle = 0.0;
    return l;
}

WetLayer unpackLayer(uint packed) {
    WetLayer l;
    l.depth = f16tof32(packed >> 16u);
    l.wet = float((packed >> 8u) & 0xFFu) * (1.0 / 255.0);
    l.puddle = float(packed & 0xFFu) * (1.0 / 255.0);
    return l;
}

uint packLayer(WetLayer l) {
    uint depth = f32tof16(clamp(l.depth, -1.0, 65504.0)) & 0xFFFFu;
    uint wet = uint(round(clamp(l.wet, 0.0, 1.0) * 255.0));
    uint puddle = uint(round(clamp(l.puddle, 0.0, 1.0) * 255.0));
    return (depth << 16u) | (wet << 8u) | puddle;
}

float advanceStorage(float current, float rain, float dt, float fillSeconds,
                     float drySeconds, float dryingScale) {
    float wetRate = rain / max(fillSeconds, 1.0e-3);
    float dryRate = (1.0 - rain) * dryingScale / max(drySeconds, 1.0e-3);
    return clamp(current + dt * (wetRate - dryRate), 0.0, 1.0);
}

void dryLayer(inout WetLayer l, float dt) {
    if (l.depth < 0.0) return;
    l.wet = max(l.wet - dt * rainHistoryPush.dryingScale
            / max(rainHistoryPush.wetDrySeconds, 1.0e-3), 0.0);
    l.puddle = max(l.puddle - dt * rainHistoryPush.dryingScale
            / max(rainHistoryPush.puddleDrySeconds, 1.0e-3), 0.0);
    if (max(l.wet, l.puddle) <= 1.0 / 255.0) l = emptyLayer();
}

[shader("compute")]
[numthreads(8, 8, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    if (id.x >= rainHistoryPush.resolution || id.y >= rainHistoryPush.resolution) return;

    WorldPush wp = ConstPtr<WorldPush>(rainHistoryPush.worldPushAddr)[0];
    int2 src = int2(id.xy) + rainHistoryPush.originShift;
    bool srcValid = all(src >= int2(0, 0))
            && all(src < int2(int(rainHistoryPush.resolution), int(rainHistoryPush.resolution)));
    uint2 packedPrior = (!srcValid || rainHistoryPush.clearPrevious != 0u)
            ? uint2(packLayer(emptyLayer()), packLayer(emptyLayer()))
            : previousHistory.Load(int3(src, 0));
    WetLayer old0 = unpackLayer(packedPrior.x);
    WetLayer old1 = unpackLayer(packedPrior.y);
    WetLayer current = emptyLayer();
    WetLayer previous = emptyLayer();

    float newDepth = exposureDepth.Load(int3(int2(id.xy), 0));
    // SNOW IS DRY. It reaches the ground exactly as rain does -- same map, same depth, same roofs -- and
    // lies there as a solid, which Minecraft renders with its own snow-layer blocks. Reducing it to the
    // "nothing falls here" depth is what keeps this whole reservoir untouched by a blizzard, and it is
    // done by rewriting the depth rather than by branching below so that the drying, the layer matching
    // and the roof-change handling all continue to see exactly one case.
    uint precipitation = precipitationAtTexel(rainHistoryPush.precipitationAddr,
            float2(id.xy) + 0.5, float(rainHistoryPush.resolution));
    if (!precipitationWets(precipitation)) {
        newDepth = precipitation == PRECIP_UNKNOWN ? -2.0 : -1.0;
    }
    if (newDepth < -1.5) {
        // Exposure is progressively rebuilding this tile after a map/direction change. Preserve the
        // shifted history until a real receiver depth or the resolved no-precipitation marker arrives.
        nextHistory[int2(id.xy)] = packedPrior;
        return;
    }
    bool rainReceiver = newDepth >= 0.0;
    bool match0 = rainReceiver && old0.depth >= 0.0 && abs(newDepth - old0.depth) <= 0.75;
    bool match1 = rainReceiver && old1.depth >= 0.0 && abs(newDepth - old1.depth) <= 0.75;
    if (match0) {
        current = old0;
        previous = old1;
    } else if (match1) {
        current = old1;
        previous = old0;
    } else if (rainReceiver) {
        current.depth = newDepth;
        // A new map/world starts from the global reservoir. A newly placed roof in an existing column
        // starts dry and fills normally; the displaced wet ground becomes layer one and dries gradually.
        if (rainHistoryPush.clearPrevious != 0u || !srcValid
                || (old0.depth < 0.0 && old1.depth < 0.0)) {
            current.wet = wp.rainState.x;
            current.puddle = wp.rainState.y;
        }
        previous = old0;
    } else {
        previous = old0.depth >= 0.0 ? old0 : old1;
    }

    float dt = min(max(rainHistoryPush.elapsedSeconds, 0.0), 0.25);
    if (current.depth >= 0.0) {
        current.depth = newDepth;
        // rainState.z also drives visible rain. If wet surfaces are disabled, x/y are deliberately zero
        // and the particle-only exposure bake must not keep filling an invisible history behind the UI.
        float surfaceRain = max(wp.rainState.x, wp.rainState.y) > 1.0e-5
                ? wp.rainState.z : 0.0;
        current.wet = advanceStorage(current.wet, surfaceRain, dt,
                                     rainHistoryPush.wetFillSeconds,
                                     rainHistoryPush.wetDrySeconds,
                                     rainHistoryPush.dryingScale);
        current.puddle = advanceStorage(current.puddle, surfaceRain, dt,
                                        rainHistoryPush.puddleFillSeconds,
                                        rainHistoryPush.puddleDrySeconds,
                                        rainHistoryPush.dryingScale);
    }
    dryLayer(previous, dt);
    nextHistory[int2(id.xy)] = uint2(packLayer(current), packLayer(previous));
}
