// R2b-8, user-approved: the same Schlick BSDF, expressed without 1-dot(v,h) cancellation.
// Reuse sampler and evaluator share the direction pair; the published off tracer stays unchanged.
// These local normalizations affect only Fresnel evaluation, never a traced ray or its PDF.
float3 reuseFresnelSchlickDirections(float3 view, float3 outgoing, float3 f0) {
    float3 v = normalize(view);
    float3 l = normalize(outgoing);
    float3 difference = v - l;
    float3 sum = v + l;
    // Unit vectors: m=1-cos(theta/2)=sin^2(theta/2)/(1+cos(theta/2)).
    // The vector sum also avoids cancellation near opposite directions.
    float numerator = 0.25 * dot(difference, difference);
    float denominator = 1.0 + 0.5 * sqrt(dot(sum, sum));
    float m = clamp(numerator / denominator, 0.0, 1.0);
    float m2 = m * m;
    return f0 + (1.0 - f0) * (m2 * m2 * m);
}
