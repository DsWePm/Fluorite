// Build-time reflection probe only. This file is not packaged as a runtime shader.
// Exposing WorldPush as a structured-buffer element makes Slang's JSON reflection include the
// complete Std430DataLayout: field types, byte offsets, total size, matrix mode, and array counts.
import world_common;

struct WorldPushLayoutProbe {
    WorldPush values[2]; // array stride is the complete, tail-padded WorldPush byte size
};

struct MaterialHeaderLayoutProbe {
    MaterialHeader values[2]; // array stride is the complete, tail-padded MaterialHeader byte size
};

struct MaterialExtensionLayoutProbe {
    MaterialExtension values[2]; // array stride is the complete, tail-padded MaterialExtension byte size
};

struct PackedPathSegmentLayoutProbe {
    PackedPathSegment values[2]; // array stride is the complete, tail-padded PackedPathSegment byte size
};

struct WaterMediumProbeLayoutProbe {
    WaterMediumProbe values[2]; // array stride is the complete, tail-padded telemetry record size
};

[[vk::binding(0, 0)]] StructuredBuffer<WorldPushLayoutProbe, Std430DataLayout> worldPushLayoutProbe;
[[vk::binding(1, 0)]] StructuredBuffer<MaterialHeaderLayoutProbe, Std430DataLayout> materialHeaderLayoutProbe;
[[vk::binding(2, 0)]] StructuredBuffer<PackedPathSegmentLayoutProbe, Std430DataLayout> packedPathSegmentLayoutProbe;
[[vk::binding(3, 0)]] StructuredBuffer<MaterialExtensionLayoutProbe, Std430DataLayout> materialExtensionLayoutProbe;
[[vk::binding(4, 0)]] StructuredBuffer<WaterMediumProbeLayoutProbe, Std430DataLayout> waterMediumProbeLayoutProbe;
[[vk::push_constant]] WorldPushConstants pushConstantsLayoutProbe;

[shader("compute")]
[numthreads(1, 1, 1)]
void main(uint3 id : SV_DispatchThreadID) {
    // Keep the parameter reachable without requiring a writable output resource.
    if (id.x == 0xFFFFFFFFu) {
        float sink = worldPushLayoutProbe[0].values[0].invViewProj[0][0]
                + materialHeaderLayoutProbe[0].values[0].params.x
                + float(pushConstantsLayoutProbe.frameIndex)
                + float(packedPathSegmentLayoutProbe[0].values[0].seed)
                + materialExtensionLayoutProbe[0].values[0].sheen.x
                + waterMediumProbeLayoutProbe[0].values[0].prefixScatterLength.x;
    }
}
