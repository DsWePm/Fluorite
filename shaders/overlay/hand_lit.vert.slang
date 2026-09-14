// M29 first-person hand, vertex stage. The vertex stream is captured from the vanilla submit stream
// (RtHandCapture): view-space positions, per-triangle normals + tangents, bindless texSlot + materialId
// per prim, the vanilla tint and the hurt/flash overlay lanes -- everything the rchit entity branch
// consumes, baked per-vertex for one triangle-list draw. Projection is the vanilla hand projection
// (captured from the same setProjectionMatrix call vanilla rasterizes the hand with), so pose alignment
// is by construction rather than by tuning.

import hand_lit_common;

[[vk::binding(HAND_BINDING_FRAME_DATA, 0)]] ByteAddressBuffer frameDataBuf;

struct VSIn {
    [[vk::location(0)]] float3 pos : POSITION0;
    [[vk::location(1)]] float2 uv : TEXCOORD0;
    [[vk::location(2)]] float3 normal : NORMAL0;
    [[vk::location(3)]] float4 tangent : TEXCOORD1;       // xyz tangent, w handedness (CPU uvTangent)
    [[vk::location(4)]] float4 tintEmission : TEXCOORD2;  // rgb tint, a fallback emission
    [[vk::location(5)]] uint4 data : TEXCOORD3;           // x texSlot, y materialId, z primFlags, w aux0
    [[vk::location(6)]] float4 extra : TEXCOORD4;         // x overlayStrength, y alphaBucket
};

struct VertexOut {
    float4 position : SV_Position;
    float2 uv : TEXCOORD0;
    float3 worldNormal : TEXCOORD1;
    float3 worldPos : TEXCOORD2;
    float3 worldTangent : TEXCOORD3;
    float tangentHandedness : TEXCOORD4;
    float4 tintEmission : TEXCOORD5;
    nointerpolation uint4 data : TEXCOORD6;
    nointerpolation float4 extra : TEXCOORD7;
};

[shader("vertex")]
VertexOut main(VSIn input) {
    VertexOut o;
    HandFrameData frame = frameDataBuf.Load<HandFrameData>(0);
    float4 viewPos = float4(input.pos, 1.0);
    o.position = mul(frame.handProjection, viewPos);
    // Rigid transform: the rotation maps view directions into world, the translation places the camera
    // at its rebased position. Normals take the rotation only -- the bob/hurt stack is pure rotation,
    // so no inverse-transpose is owed.
    o.worldPos = mul(frame.worldFromView, viewPos).xyz;
    o.worldNormal = normalize(mul(frame.worldFromView, float4(input.normal, 0.0)).xyz);
    o.worldTangent = normalize(mul(frame.worldFromView, float4(input.tangent.xyz, 0.0)).xyz);
    o.tangentHandedness = input.tangent.w;
    o.uv = input.uv;
    o.tintEmission = input.tintEmission;
    o.data = input.data;
    o.extra = input.extra;
    return o;
}
