// The existing emitter-hit partition, shared by the source and fresh material evaluation.
// Reads the hit payload flags; callers provide the actual incoming ray and its selected density.
float reuseLocalEmitterShare(bool risOn, bool showCelestial, float bsdfPdf, float3 n,
                             float3 hitPos, float3 ro, float3 rd, float3 albedo, float emission) {
    float emitterShare = 1.0;
    if (risOn && payloadEmitterInList()) {
        // The 1/0 gate is also what OFF means: FLAG_EMITTER_MIS_OFF sends the second case down the
        // first one's path, which is bit for bit the gate that stood here before M24 S4b.
        if (payloadEmitterProxied() || !emitterMisOn()) {
            emitterShare = showCelestial ? 1.0 : 0.0;
        } else {
            // bsdfPdf is the density of the ray that arrived here; negative marks a DELTA lobe, which
            // has no finite density for the emitter strategy to compete with and therefore takes all
            // of it -- a mirror shows the emitter, and RIS's own specular term vanishes at alpha 0.
            float cosAtEmitter = max(0.0, dot(n, -rd));
            float travelled = max(dot(hitPos - ro, hitPos - ro), 1.0e-6);
            float3 emitted = albedo * emission * worldPush.emitterTint.xyz;
            emitterShare = bsdfPdf < 0.0 ? 1.0
                    : powerHeuristic(bsdfAreaDensity(bsdfPdf, cosAtEmitter, travelled),
                                     emitterAreaDensity(emitted));
        }
    }
    return emitterShare;
}
