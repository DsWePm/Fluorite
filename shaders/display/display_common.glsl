#ifndef FLUORITE_DISPLAY_COMMON_GLSL
#define FLUORITE_DISPLAY_COMMON_GLSL

#if FLUORITE_ACES_EXACT
#include "aces2_sdr100.glsl"
#include "aces2_hdr500.glsl"
#include "aces2_hdr1000.glsl"
#include "aces2_hdr2000.glsl"
#include "aces2_hdr4000.glsl"
#endif

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(binding = 0, set = 0, rgba8) uniform writeonly image2D outputImage; // LDR world target image
layout(binding = 1, set = 0, rgba16f) uniform readonly image2D rtImage; // HDR RT radiance
layout(binding = 2, set = 0, r32f) uniform readonly image2D exposureImage; // 1x1 linear exposure scale
layout(binding = 3, set = 0, rgba16f) uniform writeonly image2D hdrImage; // PQ-encoded ([0,1], ST.2084) HDR display
layout(binding = 4, set = 0) uniform sampler3D acesSdrLut;
layout(binding = 5, set = 0) uniform sampler3D acesHdr500Lut;
layout(binding = 6, set = 0) uniform sampler3D acesHdr1000Lut;
layout(binding = 7, set = 0) uniform sampler3D acesHdr2000Lut;
layout(binding = 8, set = 0) uniform sampler3D acesHdr4000Lut;
layout(binding = 9, set = 0) uniform sampler2D rtLinear;
layout(binding = 10, set = 0) uniform sampler3D creativeGradingLut;
layout(binding = 11, set = 0) uniform sampler2D filmGrainNoise;

// Display-map parameters. The SDR world target always receives the selected output transform because
// vanilla presentation consumes it even while the parallel HDR/PQ image is active.
layout(push_constant) uniform Push {
    int hdrEnabled;          // 0 = SDR only, 1 = also write the PQ HDR image
    int outputTransform;     // 0 = AgX, 1 = ACES 2 LUT, 2 = exact analytic ACES 2
    int acesHdrPreset;       // 0/1/2/3 = official 500/1000/2000/4000-nit preset
    int gradingEnabled;      // one exact bypass for the complete creative grade
    float paperWhiteNits;    // compatibility AgX HDR only
    float headroom;          // compatibility AgX HDR only
    float temperatureK;      // 2000..12000 K, 6500 neutral after D65 calibration
    float tint;              // -100..100, green to magenta
    float contrast;          // scene-referred log contrast around 18% grey
    float saturation;        // opponent-space chroma scale
    float hueDegrees;        // opponent-space hue rotation
    float _pad0;
    int chromaticAberrationEnabled;
    int vignetteEnabled;
    float chromaticAberrationStrength; // maximum red/blue separation at the screen edge, in pixels
    float vignetteIntensity;
    float vignetteStart;
    float vignetteSoftness;
    int lensDistortionEnabled;
    float lensDistortionStrength; // -1 barrel, 0 bypass, +1 pincushion
    int filmGrainEnabled;
    int frameIndex;
    float filmGrainIntensity;
    float filmGrainSize;
    float filmGrainShadows;
    float filmGrainMidtones;
    float filmGrainHighlights;
    float shadowBoundaryEv;
    float highlightBoundaryEv;
    float filmGrainChromatic;
    float _pad2;
    float _pad3;
} pc;

// Tonemap seam: map HDR RT radiance to displayable LDR before copying back to the main target.
// The path tracer emits true HDR radiance, so exposure is applied from the compositor-owned 1x1 image
// before the AgX view transform. DLSS-RR still consumes HDR pre-tonemap.

const mat3 AGX_INSET = mat3(
    0.842479062253094, 0.042328242261012, 0.042375654905705,
    0.078433599999999, 0.878468636469772, 0.078433600000000,
    0.079223745147764, 0.079166127460543, 0.879142973793104
);

const mat3 AGX_OUTSET = mat3(
    1.196879005120170, -0.052896851757456, -0.052971635514443,
    -0.098020881140137, 1.151903129904170, -0.098043450117124,
    -0.099029744079720, -0.098961176844843, 1.151073672641160
);

const float AGX_MIN_EV = -12.47393;
const float AGX_MAX_EV = 4.026069;

vec3 agxDefaultContrast(vec3 x) {
    vec3 x2 = x * x;
    vec3 x4 = x2 * x2;
    return 15.5 * x4 * x2
        - 40.14 * x4 * x
        + 31.96 * x4
        - 6.868 * x2 * x
        + 0.4298 * x2
        + 0.1191 * x
        - 0.00232;
}

vec3 applyLook(vec3 color) {
    const float contrast = 1.04;   // try 1.15, 1.25, 1.4
    const float saturation = 1.05; // try 1.05-1.15 if AgX feels grey

    color = clamp((color - 0.5) * contrast + 0.5, 0.0, 1.0);

    float luma = dot(color, vec3(0.2126, 0.7152, 0.0722));
    color = mix(vec3(luma), color, saturation);

    return color;
}

vec3 agx(vec3 color) {
    color = AGX_INSET * max(color, vec3(0.0));
    color = clamp(log2(max(color, vec3(1.0e-10))), AGX_MIN_EV, AGX_MAX_EV);
    color = (color - AGX_MIN_EV) / (AGX_MAX_EV - AGX_MIN_EV);
    color = agxDefaultContrast(color);
    // color = applyLook(color);
    color = AGX_OUTSET * color;
    return clamp(color, 0.0, 1.0);
}

const float PQ_M1 = 0.1593017578125;
const float PQ_M2 = 78.84375;
const float PQ_C1 = 0.8359375;
const float PQ_C2 = 18.8515625;
const float PQ_C3 = 18.6875;

float pqEncode(float nits) {
    float y = pow(max(nits, 0.0) / 10000.0, PQ_M1);
    return pow((PQ_C1 + PQ_C2 * y) / (1.0 + PQ_C3 * y), PQ_M2);
}

// VK_COLOR_SPACE_HDR10_ST2084_EXT mandates BT.2020 primaries as its container gamut (not just the ST.2084
// transfer function) — but every color we compute (textures, tonemap) is authored/blended in BT.709/sRGB
// primaries. Feeding BT.709 numbers straight into a BT.2020-tagged buffer makes the display read them as
// (more saturated) BT.2020 coordinates, oversaturating everything. This matrix converts linear-light
// BT.709 -> BT.2020 right before the PQ encode, which is the container's actual gamut. (ITU-R BT.2087.)
const mat3 BT709_TO_BT2020 = mat3(
    0.6274039, 0.0690973, 0.0163916,
    0.3292830, 0.9195406, 0.0880132,
    0.0433131, 0.0113612, 0.8955953
);

// HDR display mapping: map exposed scene-linear radiance to absolute nits, then PQ-encode (ST.2084) for
// direct presentation to a PQ/HDR10 swapchain. SDR-range values (<= 1.0 after exposure) stay identity so
// paper white lands exactly at paperWhiteNits. Highlights above 1.0 roll off smoothly and asymptote to
// `headroom`, i.e. the brightest pixels approach peakNits. No SDR clamp here — that is the point.
vec3 tonemapHdr(vec3 hdr, float exposure) {
    vec3 c = max(hdr * exposure, vec3(0.0));
    vec3 lo = min(c, vec3(1.0));
    vec3 hi = max(c - vec3(1.0), vec3(0.0));
    float k = max(pc.headroom - 1.0, 0.0);
    vec3 rolled = (k > 0.0) ? (k * hi) / (k + hi) : vec3(0.0); // -> k as hi -> inf
    vec3 paperReferred = lo + rolled;                          // 1.0 == paper white, max -> headroom
    vec3 nits709 = paperReferred * pc.paperWhiteNits;
    vec3 nits2020 = BT709_TO_BT2020 * nits709;
    return vec3(pqEncode(nits2020.r), pqEncode(nits2020.g), pqEncode(nits2020.b));
}

const mat3 BT709_TO_XYZ_D65 = mat3(
    0.4123907993, 0.2126390059, 0.0193308187,
    0.3575843394, 0.7151686788, 0.1191947798,
    0.1804807884, 0.0721923154, 0.9505321522
);

const mat3 XYZ_D65_TO_BT709 = mat3(
     3.2409699419, -0.9692436363,  0.0556300797,
    -1.5373831776,  1.8759675015, -0.2039769589,
    -0.4986107603,  0.0415550574,  1.0569715142
);

const mat3 XYZ_D65_TO_BT2020 = mat3(
     1.7166511880, -0.6666843518,  0.0176398574,
    -0.3556707838,  1.6164812366, -0.0427706133,
    -0.2533662814,  0.0157685458,  0.9421031212
);

const mat3 BRADFORD = mat3(
     0.8951000, -0.7502000,  0.0389000,
     0.2664000,  1.7135000, -0.0685000,
    -0.1614000,  0.0367000,  1.0296000
);

const mat3 BRADFORD_INV = mat3(
     0.9869929,  0.4323053, -0.0085287,
    -0.1470543,  0.5183603,  0.0400428,
     0.1599627,  0.0492912,  0.9684867
);

vec2 planckianXy(float kelvin) {
    float t = clamp(kelvin, 2000.0, 12000.0);
    float x;
    if (t < 4000.0) {
        x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t)
            + 0.8776956e3 / t + 0.179910;
    } else {
        x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t)
            + 0.2226347e3 / t + 0.240390;
    }
    float y;
    if (t < 2222.0) {
        y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
    } else if (t < 4000.0) {
        y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
    } else {
        y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
    }
    return vec2(x, y);
}

vec3 xyToXyz(vec2 xy) {
    float y = max(xy.y, 1.0e-4);
    return vec3(xy.x / y, 1.0, (1.0 - xy.x - xy.y) / y);
}

// Bradford chromatic adaptation in XYZ. The polynomial Planckian locus is offset so exactly 6500 K,
// tint 0 maps to D65 and therefore remains identity apart from floating-point roundoff. Tint is an
// intentionally artistic Duv-like displacement, not a claim that Minecraft assets are spectral data.
vec3 whiteBalance(vec3 color) {
    const vec2 D65 = vec2(0.3127, 0.3290);
    vec2 neutralFit = planckianXy(6500.0);
    vec2 target = planckianXy(pc.temperatureK) + (D65 - neutralFit);
    target.y -= clamp(pc.tint, -100.0, 100.0) * 0.00025;
    target.y = clamp(target.y, 0.20, 0.45);

    vec3 sourceLms = BRADFORD * xyToXyz(D65);
    vec3 targetLms = BRADFORD * xyToXyz(target);
    vec3 xyz = BT709_TO_XYZ_D65 * color;
    xyz = BRADFORD_INV * ((targetLms / sourceLms) * (BRADFORD * xyz));
    return XYZ_D65_TO_BT709 * xyz;
}

vec3 creativeGrade(vec3 color) {
    color = max(whiteBalance(max(color, vec3(0.0))), vec3(0.0));

    // Contrast in scene-referred log luminance around 18% grey. Scaling RGB by the resulting luminance
    // ratio retains hue and avoids applying three unrelated log curves to saturated colours.
    const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
    float oldY = max(dot(color, LUMA), 1.0e-6);
    float logPivot = log2(0.18);
    float newY = exp2((log2(oldY) - logPivot) * clamp(pc.contrast, 0.0, 2.0) + logPivot);
    color *= newY / oldY;

    // YIQ gives a cheap luminance-preserving opponent plane for the approved saturation and hue knobs.
    float y = dot(color, vec3(0.299, 0.587, 0.114));
    float i = dot(color, vec3(0.595716, -0.274453, -0.321263));
    float q = dot(color, vec3(0.211456, -0.522591, 0.311135));
    float angle = radians(pc.hueDegrees);
    float cs = cos(angle);
    float sn = sin(angle);
    vec2 iq = mat2(cs, sn, -sn, cs) * vec2(i, q) * clamp(pc.saturation, 0.0, 2.0);
    return max(vec3(
        y + 0.95629572 * iq.x + 0.62102442 * iq.y,
        y - 0.27212210 * iq.x - 0.64738060 * iq.y,
        y - 1.10698902 * iq.x + 1.70461500 * iq.y), vec3(0.0));
}

float srgbEncode(float linearValue) {
    float x = max(linearValue, 0.0);
    return x <= 0.0031308 ? 12.92 * x : 1.055 * pow(x, 1.0 / 2.4) - 0.055;
}

const float ACES2_SHAPER_MIN_EV = -16.0;
const float ACES2_SHAPER_MAX_EV = 16.0;
const float ACES2_LUT_SIZE = 65.0;

float aces2ShaperIndex(float value) {
    const float minimum = exp2(ACES2_SHAPER_MIN_EV);
    if (value <= minimum) return clamp(value / minimum, 0.0, 1.0);
    float t = (clamp(log2(value), ACES2_SHAPER_MIN_EV, ACES2_SHAPER_MAX_EV)
            - ACES2_SHAPER_MIN_EV) / (ACES2_SHAPER_MAX_EV - ACES2_SHAPER_MIN_EV);
    return 1.0 + t * (ACES2_LUT_SIZE - 2.0);
}

vec3 aces2LutCoord(vec3 sceneLinear709) {
    vec3 index = vec3(aces2ShaperIndex(sceneLinear709.r),
            aces2ShaperIndex(sceneLinear709.g), aces2ShaperIndex(sceneLinear709.b));
    return (index + 0.5) / ACES2_LUT_SIZE;
}

vec3 aces2SdrLutSample(vec3 sceneLinear709) {
    return texture(acesSdrLut, aces2LutCoord(sceneLinear709)).rgb;
}

vec3 aces2HdrLutSample(vec3 sceneLinear709) {
    vec3 uvw = aces2LutCoord(sceneLinear709);
    if (pc.acesHdrPreset == 0) return texture(acesHdr500Lut, uvw).rgb;
    if (pc.acesHdrPreset == 2) return texture(acesHdr2000Lut, uvw).rgb;
    if (pc.acesHdrPreset == 3) return texture(acesHdr4000Lut, uvw).rgb;
    return texture(acesHdr1000Lut, uvw).rgb;
}

vec2 lensDistortionUv(vec2 uv, ivec2 size) {
    if (pc.lensDistortionEnabled == 0 || abs(pc.lensDistortionStrength) < 1.0e-5) return uv;

    // Work in equal display-pixel units so the radial curve remains circular at non-square aspects.
    // The linked k1+k2 curve is deliberately bounded: its corner scale remains in [0.80, 1.20]
    // and the inverse lookup stays monotonic throughout the approved slider range.
    float aspect = float(size.x) / max(float(size.y), 1.0);
    vec2 sensor = (uv - 0.5) * vec2(aspect, 1.0);
    float cornerRadius = 0.5 * length(vec2(aspect, 1.0));
    vec2 p = sensor / cornerRadius;
    float r2 = dot(p, p);
    float strength = clamp(pc.lensDistortionStrength, -1.0, 1.0);
    float k1 = -0.15 * strength;
    float k2 = -0.05 * strength;
    float radialScale = 1.0 + k1 * r2 + k2 * r2 * r2;

    // Negative UI strength is barrel distortion. Its inverse lookup expands toward the source border,
    // so divide by the maximum corner expansion to crop automatically instead of exposing black edges.
    float cropScale = max(1.0, 1.0 + k1 + k2);
    vec2 sourceSensor = sensor * (radialScale / cropScale);
    return sourceSensor / vec2(aspect, 1.0) + 0.5;
}

vec3 sceneLinearWithLensEffects(ivec2 pixel, ivec2 size) {
    vec2 uv = (vec2(pixel) + 0.5) / vec2(size);
    vec2 sceneUv = lensDistortionUv(uv, size);
    bool distortionActive = pc.lensDistortionEnabled != 0
            && abs(pc.lensDistortionStrength) >= 1.0e-5;
    vec3 color;
    if (pc.chromaticAberrationEnabled != 0 && pc.chromaticAberrationStrength > 0.0) {
        vec2 fromCentrePixels = (uv - 0.5) * vec2(size);
        float radial = clamp(length((uv - 0.5) * 2.0), 0.0, 1.0);
        vec2 direction = length(fromCentrePixels) > 1.0e-4
                ? normalize(fromCentrePixels) : vec2(0.0);
        vec2 offset = direction * (pc.chromaticAberrationStrength * radial * radial) / vec2(size);
        vec3 centre = textureLod(rtLinear, sceneUv, 0.0).rgb;
        color = vec3(textureLod(rtLinear, sceneUv + offset, 0.0).r,
                centre.g, textureLod(rtLinear, sceneUv - offset, 0.0).b);
    } else if (distortionActive) {
        color = textureLod(rtLinear, sceneUv, 0.0).rgb;
    } else {
        color = imageLoad(rtImage, pixel).rgb;
    }

    if (pc.vignetteEnabled != 0 && pc.vignetteIntensity > 0.0) {
        // Screen-normalised elliptical radius: side midpoints are 0.707 and corners are 1.0. This is an
        // artistic composition control, not the cos^4 falloff of a measured optical system.
        float radius = length((uv - 0.5) * 2.0) * 0.70710678118;
        float start = clamp(pc.vignetteStart, 0.0, 1.0);
        float end = min(start + max(pc.vignetteSoftness, 0.05), 1.41421356237);
        float edge = smoothstep(start, end, radius);
        color *= 1.0 - clamp(pc.vignetteIntensity, 0.0, 1.0) * edge;
    }
    return color;
}

vec3 applyFilmGrain(vec3 color, ivec2 pixel) {
    if (pc.filmGrainEnabled == 0 || pc.filmGrainIntensity <= 0.0) return color;
    float grainSize = clamp(pc.filmGrainSize, 0.5, 4.0);
    ivec2 grainPixel = ivec2(floor(vec2(pixel) / grainSize));
    // Prime frame offsets prevent short axis-aligned cycles; the irrational phase changes the rank
    // threshold each frame. D150A keeps this shared component, then mixes in independently shifted
    // channel ranks so low separation remains film-like while high separation approaches analogue TV noise.
    grainPixel += ivec2(pc.frameIndex * 17, pc.frameIndex * 29);
    vec2 noiseUv = (vec2(grainPixel & 63) + 0.5) / 64.0;
    float rank = textureLod(filmGrainNoise, noiseUv, 0.0).r;
    float framePhase = float(pc.frameIndex & 255) * 0.61803398875;
    float sharedNoise = fract(rank + framePhase) - 0.5;
    vec3 grainNoise = vec3(sharedNoise);
    float chromatic = clamp(pc.filmGrainChromatic, 0.0, 1.0);
    if (chromatic > 0.0) {
        ivec2 redPixel = grainPixel + ivec2(1, 11);
        ivec2 bluePixel = grainPixel + ivec2(-7, 19);
        vec3 ranks = vec3(
                textureLod(filmGrainNoise, (vec2(redPixel & 63) + 0.5) / 64.0, 0.0).r,
                rank,
                textureLod(filmGrainNoise, (vec2(bluePixel & 63) + 0.5) / 64.0, 0.0).r);
        vec3 channelNoise = fract(ranks + framePhase * vec3(1.071, 1.137, 1.193)) - 0.5;
        grainNoise = mix(grainNoise, channelNoise, chromatic);
    }

    float ev = log2(max(dot(color, vec3(0.2126, 0.7152, 0.0722)), 1.0e-6) / 0.18);
    const float transitionEv = 1.0;
    float shadowWeight = 1.0 - smoothstep(pc.shadowBoundaryEv - transitionEv,
            pc.shadowBoundaryEv + transitionEv, ev);
    float highlightWeight = smoothstep(pc.highlightBoundaryEv - transitionEv,
            pc.highlightBoundaryEv + transitionEv, ev);
    float midWeight = max(1.0 - shadowWeight - highlightWeight, 0.0);
    float tonalStrength = shadowWeight * pc.filmGrainShadows
            + midWeight * pc.filmGrainMidtones + highlightWeight * pc.filmGrainHighlights;
    // Multiplicative log-light modulation is zero-centred, cannot make radiance negative, and behaves
    // consistently before either the SDR or HDR output transform.  This is synthetic grain, not an
    // emulation of a measured film stock.
    vec3 grainEv = grainNoise * clamp(pc.filmGrainIntensity, 0.0, 1.0)
            * clamp(tonalStrength, 0.0, 2.0) * 0.5;
    return color * exp2(grainEv);
}

#if FLUORITE_ACES_EXACT
vec3 aces2SdrExact(vec3 sceneLinear709) {
    vec3 xyzD65 = fluoriteAces2Sdr100(vec4(sceneLinear709, 1.0)).rgb;
    vec3 linear709 = max(XYZ_D65_TO_BT709 * xyzD65, vec3(0.0));
    return clamp(vec3(srgbEncode(linear709.r), srgbEncode(linear709.g), srgbEncode(linear709.b)), 0.0, 1.0);
}

vec3 aces2HdrExact(vec3 sceneLinear709) {
    vec3 xyzD65;
    if (pc.acesHdrPreset == 0) {
        xyzD65 = fluoriteAces2Hdr500(vec4(sceneLinear709, 1.0)).rgb;
    } else if (pc.acesHdrPreset == 2) {
        xyzD65 = fluoriteAces2Hdr2000(vec4(sceneLinear709, 1.0)).rgb;
    } else if (pc.acesHdrPreset == 3) {
        xyzD65 = fluoriteAces2Hdr4000(vec4(sceneLinear709, 1.0)).rgb;
    } else {
        xyzD65 = fluoriteAces2Hdr1000(vec4(sceneLinear709, 1.0)).rgb;
    }
    // ACES 2 HDR Output Transform returns XYZ where 1.0 represents 100 cd/m^2. Convert the official
    // Rec.2020 limiting gamut result to absolute nits, then encode it for the HDR10/PQ swapchain.
    vec3 nits2020 = max(XYZ_D65_TO_BT2020 * xyzD65, vec3(0.0)) * 100.0;
    return clamp(vec3(pqEncode(nits2020.r), pqEncode(nits2020.g), pqEncode(nits2020.b)), 0.0, 1.0);
}
#endif

void main() {
    ivec2 pix = ivec2(gl_GlobalInvocationID.xy);
    ivec2 size = imageSize(outputImage);
    if (pix.x >= size.x || pix.y >= size.y) {
        return;
    }

    float exposure = max(imageLoad(exposureImage, ivec2(0)).r, 0.0);
    vec3 exposed = max(sceneLinearWithLensEffects(pix, size) * exposure, vec3(0.0));
    if (pc.gradingEnabled != 0) {
        exposed = texture(creativeGradingLut, aces2LutCoord(exposed)).rgb;
    }
    exposed = applyFilmGrain(exposed, pix);

#if FLUORITE_ACES_EXACT
    vec3 ldr = aces2SdrExact(exposed);
#else
    vec3 ldr = pc.outputTransform == 1 ? aces2SdrLutSample(exposed) : agx(exposed);
#endif
    imageStore(outputImage, pix, vec4(ldr, 1.0));

    if (pc.hdrEnabled != 0) {
#if FLUORITE_ACES_EXACT
        vec3 hdr = aces2HdrExact(exposed);
#else
        vec3 hdr = pc.outputTransform == 1 ? aces2HdrLutSample(exposed) : tonemapHdr(exposed, 1.0);
#endif
        imageStore(hdrImage, pix, vec4(hdr, 1.0));
    }
}

#endif // FLUORITE_DISPLAY_COMMON_GLSL
