// Reuse-only local event responses. Keep the source NEE and its shifted evaluation on one formula.
// Incident radiance, visibility and the external roulette factor are owned by the event producer.
float3 reuseLocalSunBrdf(BsdfContext bc, MaterialExtension ext, float pf, float ps, float walkSssShare,
                        bool misActive, bool exactSpecular, float pdfLight, float3 lightDir, float ndl) {
    if (!(ndl > 0.0)) return float3(0.0, 0.0, 0.0);
    float3 n = bc.n;
    float3 v = bc.v;
    float3 diffAlb = bc.diffAlb;
    float3 F0 = bc.F0;
    float3 h = normalize(lightDir + v);
    float ndh = max(0.0, dot(n, h));
    float ndv = bc.ndv;
    float vdh = max(0.0, dot(v, h));
    // With MIS off, nothing weights this peak down and the floor that the old ggxD epsilon
    // amounted to comes back — otherwise the switch would be comparing MIS against a
    // firefly storm rather than against what the renderer actually shipped. A delta lobe is
    // exempt: alpha stays 0, so a mirror keeps having no specular NEE term at all.
    bool floorAlpha = !(misActive || exactSpecular);
    float ax = floorAlpha ? max(bc.alphaX, UNWEIGHTED_SPEC_ALPHA_FLOOR) : bc.alphaX;
    float ay = floorAlpha ? max(bc.alphaY, UNWEIGHTED_SPEC_ALPHA_FLOOR) : bc.alphaY;
    // The anisotropic form unconditionally: with ax == ay it IS the isotropic one, term for
    // term, so an isotropic material pays four dot products and there is no second code
    // path to fall out of step. This site runs once per vertex, unlike the RIS loop.
    float hx = dot(h, bc.t);
    float hy = dot(h, bc.b);
    float lx = dot(lightDir, bc.t);
    float ly = dot(lightDir, bc.b);
    float g1v = ggxG1Aniso(dot(v, bc.t), dot(v, bc.b), ndv, ax, ay);
    float D = ggxDAniso(hx, hy, ndh, ax, ay);
    float G = g1v * ggxG1Aniso(lx, ly, ndl, ax, ay); // separable Smith masking
    float3 F = fresnelSchlick(vdh, F0);
    // The three lobes the continuation actually samples: Lambertian diffuse, base
    // Cook–Torrance specular, and M21's top water-film Cook–Torrance lobe.
    float3 baseBrdf = diffAlb * INV_PI + (D * G) * F / (4.0 * ndv * ndl);
    float3 sampled = baseBrdf * rainBaseTransmission(bc, lightDir)
            + rainFilmBrdf(bc, lightDir, floorAlpha);

    // Multiple importance sampling, but only over `sampled`. MIS pairs strategies for one
    // integral, and the strategies here are NEE and the continuation mixture — so the
    // weight applies to exactly the lobes that mixture can draw, and nothing else.
    // Sheen and clearcoat are evaluated but never sampled as a continuation, so NEE is their only
    // estimator and they keep the full contribution; weighting them down would simply lose
    // that energy, because no BSDF ray would ever arrive to make it up. (It is also why the
    // clearcoat highlight stays noisy on a very smooth coat: adding the coat to the
    // continuation mixture is what would fix that, and it is not this change.)
    //
    // A delta specular lobe contributes no density at any direction but its own, so it
    // drops out of the mixture — and D is identically zero there anyway, so the whole
    // specular half of `sampled` vanishes with it. Mirrors are untouched by this.
    // The diffuse share shrinks when a subsurface lobe is in the mixture, and the walk
    // itself contributes no analytic density, so this is the whole of what the continuation
    // can put toward the sun. Getting it wrong loses energy quietly on smooth materials.
    // The same density M24 S4 weighs the emitter estimator against, and deliberately the
    // same function: two spellings of a density that must agree exactly is how a MIS weight
    // silently stops summing to one. Where it matters -- misActive -- floorAlpha is false, so
    // the unfloored form below IS the number the floored expression produced here.
    float pdfB = bsdfContinuationPdf(bc, lightDir, pf, ps, walkSssShare);
    float wLight = misActive ? powerHeuristic(pdfLight, pdfB) : 1.0;

    float3 brdf = sampled * wLight
            + disneyLobes(bc, ext, lightDir) * rainBaseTransmission(bc, lightDir);
    return brdf;
}

float3 reuseLocalAmbientWeight(BsdfContext bc) {
    return bc.diffAlb * rainBaseTransmission(bc, bc.n);
}

float3 reuseLocalThinWeight(BsdfContext bc, float3 lightDir, float strength, float phase) {
    float backNdl = max(0.0, dot(-bc.n, lightDir));
    if (!(backNdl > 0.0) || !(bc.sss > 0.0)) return float3(0.0, 0.0, 0.0);
    return bc.diffAlb * bc.sss * strength * phase * backNdl * rainBaseExitTransmission(bc);
}

// The endpoint light PDF stays separate from the cached, unweighted endpoint radiance.
float reuseLocalHitMis(float selectedPdf, float competingPdf) {
    return competingPdf > 0.0 && selectedPdf >= 0.0
            ? powerHeuristic(selectedPdf, competingPdf) : 1.0;
}
