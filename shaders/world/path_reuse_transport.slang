// Address transport by logical edge, independently of vertex decisions, NEE and reservoir draws.
// Medium and cloud work have separate domains: omitting source lighting cannot move a later jitter.
static const uint REUSE_MEDIUM_EDGE_SALT = 0xD1B54A35u;
static const uint REUSE_CLOUD_EDGE_SALT = 0x94D049BBu;

uint reuseTransportSeed(uint pathSeed, uint edge, bool cloud) {
    uint seed = vertexStream(pathSeed, edge) ^ (cloud ? REUSE_CLOUD_EDGE_SALT : REUSE_MEDIUM_EDGE_SALT);
    return pcg(seed);
}

// Pure transmittance specializations of the same physical kernels used by the source tracer.
// No full lighting evaluation, visibility trace, interface traversal or RGB division here.
void reuseAccumulateTransport(inout float3 prefix, float3 extinction, uint mediumFlags,
        float3 origin, float3 direction, float distance, uint pathSeed, uint edge, bool secondary) {
    prefix *= segmentPathTransmittance(extinction, mediumFlags, origin, direction, distance,
            reuseTransportSeed(pathSeed, edge, false));
    prefix *= cloudPathTransmittance(origin, direction, distance,
            reuseTransportSeed(pathSeed, edge, true), secondary);
}
