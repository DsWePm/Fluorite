// Shared definitions for all ray-tracing stages. The generateShaderRecords task generates typed Java
// records and serializers from Slang reflection.
module world_common;

// Read-only device pointer with explicit std430 layout. The layout parameter is LOAD-BEARING: a bare
// Ptr<T> gets Slang's "natural" layout (no vec4 16-byte alignment → e.g. WorldPush.sunDir lands at 200
// instead of the Java writer's 208, silently reading garbage) and additionally requires the
// scalarBlockLayout device feature. Keep std430 unless RtDeviceBringup explicitly enables that feature;
// this alias is the single layout switch and generated Java serialization stays in lock-step.
public typealias ConstPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;
public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

// Push constant block. The hot inline lanes avoid dereferencing WorldPush for values that are read in
// hit shaders or used for raygen control flow — every 64-bit device address lives here for exactly that
// reason, rather than behind the worldPushAddr indirection. tableAddr/entityTableAddr/materialTableAddr
// are read in world.rahit/world.rchit, which never load WorldPush at all; the light-buffer addresses are
// only read in world.rgen, which loads WorldPush anyway, but keeping every Addr in one place means there
// is exactly one struct to check when adding a new device buffer, instead of two.
public struct WorldPushConstants {
    public uint64_t worldPushAddr;
    public uint64_t tableAddr;
    public uint64_t entityTableAddr;
    public uint64_t materialTableAddr;
    public uint64_t materialExtensionAddr; // optional Disney records; MaterialHeader.extensionOffset indexes it 1-based
    public uint64_t lightBufAddr;        // RIS emitter-NEE global light buffer (0 = none published)
    public uint64_t lightAliasAddr;      // power-weighted O(1) alias table (0 = uniform fallback)
    public uint64_t lightLocalAliasAddr; // power aliases relative to each section's range
    public uint64_t lightGridCellAddr;   // dense light grid cell headers (0 = global proposals only)
    public uint64_t lightGridSpanAddr;   // packed weighted section spans referenced by cell headers
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    public uint64_t waterProbeAddr;      // optional host-visible WaterMediumProbe (0 = diagnostic off)
    public uint frameIndex;
    public uint debugView;
    // Shading switches the CLOSEST-HIT needs. They live here rather than in WorldPush because the hit
    // shader deliberately never dereferences that struct — see the note at the top of world.rchit — and
    // a per-hit BDA load to read one bit would cost more than the feature it gates.
    public uint shadeFlags;
};

/**
 * Let the SOLID terrain layer carry subsurface scattering.
 *
 * Extraction packs a non-SOLID marker into the prim's light lane, and the hit shader has been using it
 * to zero the LabPBR subsurface channel for SOLID blocks — so quartz, smooth stone and every other
 * ordinary block was excluded outright. That is also where a BSSRDF is most worth having: the materials
 * people expect to glow through are mostly SOLID.
 *
 * The gate exists because it is a genuine risk rather than a free win. LabPBR's subsurface channel is
 * the alpha of the _s texture, and packs that never intended to use it can leave anything in there; the
 * moment this opens, whatever they left becomes visible as translucency on ordinary blocks. One switch,
 * off, restores today's behaviour.
 */
public static const uint SHADE_SOLID_LAYER_SSS = 1u;

// Block-breaking overlay entry: xyz = breaking block position (rebased, same space as gl_WorldRay*),
// w = bindless entityAlbedoTex slot of the destroy-stage texture.
public struct BreakEntry {
    public int4 posSlot;
};

// Per-frame world data, read through a 64-bit device address. WorldPushData.java is generated from this
// reflected type, including its serializer, byte size, vector/matrix types, and fixed-array capacities.
public struct WorldPush {
    public float4x4 invViewProj;
    public float3   camOffset;
    public uint     frameIndex;
    public float4x4 prevViewProj;
    public float3   camDelta;
    public uint     spp;
    public float2   jitter;
    public uint     flags;            // bit0 submerged, bit4 waves, bit5 sun MIS (bit1 was PBR-toggle, now unconditional)
    public uint     maxBounces;
    public float4   sunDir;           // xyz true sun direction, w dayFactor 0..1
    public float4   lightDir;         // xyz active NEE light dir, w square half-angle (rad)
    public float4   lightRadiance;    // xyz celestial irradiance (legacy field name), w star brightness
    public float4   moonDir;          // xyz moon direction, w moonPhase (0 full .. 4 new)
    public float4   celestial;        // xyz celestial rotation axis, w star angle (rad)
    public float4   sunUv;            // vanilla sun sprite atlas rect (u0,v0,u1,v1)
    public float4   moonUv;           // vanilla current-moon-phase sprite atlas rect
    public float4   waterParams;      // xyz camera-biome water tint, w wave time (s)
    // w is the camera's own water surface height (rebased), or -1e9 when the camera is not in
    // water. Enclosed single scattering needs to know how deep a point is to know how much light
    // ever reached it — see integrateSegment.
    public float4   waterAnchor;      // xy wave-domain anchor, z previous wave time, w water surface Y
    public float4x4 curViewProj;      // forward camera-relative view-projection
    public uint     breakCount;
    public BreakEntry breaking[8];    // Java capacity and breakCount serialization are generated from this
    public float4   lightRebase;      // xyz hierarchy-to-current rebase, w inverse total light power
    public float4   lightGridOrigin;     // xyz minimum cell origin in rebased blocks, w cell size
    public int4     lightGridDims;       // xyz dense grid dimensions, w reserved
    public uint     lightCount;       // packed Light records in pc.lightBufAddr
    public uint     risCandidates;    // RIS candidate count M per diffuse vertex (0 = emitter NEE off)
    // Ambient participating medium — the world's air. Evaluated per segment from world position rather
    // than carried on the medium stack; see volume.slang for why. Zero density disables it entirely at
    // the cost of one comparison.
    public float4   fogParams;        // x density at reference height, y height scale, z reference height, w cull distance
    public float4   fogExtinction;    // xyz per-channel extinction tint
    public float4   fogScatter;       // xyz single-scattering albedo, w Henyey-Greenstein g for the sun lobe
    // M13 residual spends the retired xyz lanes without growing the buffer for three scalars: x is the
    // heterogeneous-field strength, y the fixed detail share, z the numerical-march cap. Strength zero
    // is the exact analytic-height path. w remains owned by thin-shell subsurface.
    public float4   fogAmbient;       // x noise strength, y detail share, z march cap, w thin-shell thickness
    // Rebased world origin minus the fog's own accumulated wind drift. Adding this to a shader-space
    // point recovers stable absolute field coordinates; w is the 384-block default repeat scale.
    public float4   fogNoiseOrigin;   // xyz field origin (rebase - fog drift), w repeat scale in blocks
    // One GPU-derived source shared by every participating medium. The sky-view reduction writes xyz
    // after the CPU upload and before either froxel or ray tracing reads this buffer. It is already the
    // 1/(4*pi) upper-hemisphere integral, including Rayleigh, Mie and atmospheric multiple scattering.
    public float4   mediumSkyRadiance; // xyz phase-integrated sky source; w reserved
    // Enclosed media. Absorption and scattering are TWO INDEPENDENTLY AUTHORED spectral coefficients,
    // not one derived from the other. A single "turbidity" scalar that only raised sigma_s was tried and
    // is the reason this comment is here: the deep-water limit of the single-scattering integral is
    // albedo * source with sigma_t cancelling out, and albedo = sigma_s/(sigma_a+sigma_s), so a knob that
    // only raises sigma_s drives the albedo to 1 and can therefore only ever make deep water WHITER and
    // never darker. Measured at turbidity 10: albedo (0.76, 0.82, 0.95) and a deep limit of (1.79, 1.68,
    // 1.80) — grey, and as bright as a sunlit mid-grey surface, in every biome. Authoring both lets
    // "murky AND dark" exist, which it could not before.
    //
    // sigma_s stays GLOBAL (one value for the world) on purpose: mediumScatterAlbedo recovers the albedo
    // as sigma_s/extinction from the extinction the wavefront record already carries, which is the only
    // reason M9 never had to spend the record's last spare uint. Per-biome character lives in sigma_a.
    public float4   waterScatter;     // xyz scattering coefficient per block, w Henyey-Greenstein g
    public float4   waterAbsorbOverride; // xyz fixed absorption, w != 0 to use it instead of the biome
    // xyz retired in M16: water and fog now read mediumSkyRadiance. w remains the caustic-dispersion
    // strength; the lane was preserved to avoid unrelated ABI churn while the old xyz slots stayed zero.
    //
    // x is now the wave field's authored amplitude (M12.5 / D49), spending one of those dead slots rather
    // than growing the pinned layout for a single float. It scales HEIGHT AND SLOPE TOGETHER, because
    // they are the same function -- scaling them apart would shade a surface the geometry is not. 1 is
    // the shipped look exactly. yz remain zero.
    public float4   waterAmbient;     // x wave amplitude, yz retired, w caustic dispersion
    // The volumetric visibility grid (M13.2), baked on the GPU with two rays per cell: R sun visibility,
    // G sky openness. Read by the BAKE that fills it and by every segment that samples it, which is why
    // the placement lives here rather than in either one's push constants — a grid origin that two shaders
    // disagree about by one cell is a bug that presents as light leaking through walls.
    //
    // Snapped to whole cells in ABSOLUTE world coordinates before rebasing, so the sample lattice stays
    // put as the camera moves and as the terrain rebases. Unsnapped, every step the player takes would
    // reshuffle where the rays are cast and the fog would boil. w is the cell size; 0 means no grid, and
    // sampleVolumeVisibility then reports everything lit, which is what the renderer did before this
    // existed.
    public float4   visGridOrigin;    // xyz snapped minimum corner (rebased), w cell size in blocks
    // Volumetric clouds (M11). Authored per frame rather than compiled in, because vanilla's weather has
    // to be able to move them: rain drives coverage, thunder drives type. See cloud.slang for what each
    // lane means to the density field, and RtComposite.cloudParams for where the weather enters.
    //
    // These are the last two lanes of a buffer that is read but never written by the ray tracer, so
    // adding them costs 32 B of a per-frame upload and nothing per ray. A cloud that could not respond to
    // the weather would have been the alternative, and the storm is most of the point.
    public float4   cloudParams;      // x coverage bias, y density scale, z type bias, w extinction/block
    public float4   cloudShape;       // x deck bottom (blocks), y deck thickness, z base size, w detail size
    // The terrain rebase origin, in ABSOLUTE blocks. Everything the ray tracer sees is expressed
    // relative to this, and it FOLLOWS THE CAMERA -- it is reset whenever the camera drifts more than
    // terrain.rebase-distance-blocks from it. So a cloud deck placed at a rebased altitude is a cloud
    // deck that climbs with the player: authored at 192, it sits 192 blocks above wherever they are,
    // and can never be entered or flown above. Adding this back is what makes the density field
    // world-anchored in fact rather than only in the comments.
    // xyz is the terrain rebase origin MINUS the wind's accumulated drift, which is the same operation
    // -- both say where the density field's origin sits this frame, in absolute blocks. Folding the wind
    // in here rather than into a lane of its own is not a saving, it is the honest description: a cloud
    // field that drifts is a cloud field whose origin moves. Only xz drift; y stays the pure rebase
    // because the shell's altitude is measured from it.
    public float4   cloudRebase;      // xyz field origin (rebase - wind drift), w field scale (blocks)
    // Cloud LIGHTING (M11 slice two). y is the physically load-bearing one: the diffusion attenuation the
    // multiple-scattering term decays at is sqrt(3*(1-albedo)) times the ordinary optical depth, so this
    // single number is the difference between a cloud whose interior stays bright and one that goes black.
    public float4   cloudLighting;    // x phase g, y albedo, z sun-march steps, w diffuse source A/(4*pi)
    // The HIGH layer (M11 slice three): cirrus, a thin ice sheet far above the convective deck. Its own
    // lane rather than a second copy of cloudShape because almost nothing it needs is the same — it has
    // no cloud-type dimension, no self-shadowing worth marching, and a feature size an order of magnitude
    // larger. w <= 0 turns it off, which is the same disable-by-zero pattern the fog's density uses.
    public float4   cloudHigh;        // x altitude, y thickness, z coverage bias, w extinction/block
    // The cirrus layer's OWN shape and OWN origin. It derived both from the deck below until it became
    // clear that sharing them is the same mistake as sharing a height would be: a high ice sheet and a
    // convective deck are different weather at different altitudes in different wind. Separate lanes are
    // what let one be authored without moving the other.
    public float4   cloudHighShape;   // x base size, y detail size, z density scale, w reserved
    public float4   cloudHighOrigin;  // xyz its own field origin (rebase - its own drift), w field scale
    // The interactive water simulation's domain (M12). xy is the rebased world position of cell (0,0)'s
    // centre, z the blocks per cell, w the slope strength the sampled field contributes.
    //
    // SNAPPED TO WHOLE CELLS before rebasing, for the same reason visGridOrigin is: a domain that slid
    // continuously with the player would resample the height field at a different phase every frame and
    // smear the ripples into streaks (R22, which the reference implementation does not solve). Zero in z
    // is the disable path -- the sampler returns no contribution and the procedural spectrum stands
    // alone, which is exactly the shipped behaviour before this existed.
    public float4   waterSimDomain;   // xy origin (rebased), z cell size, w slope strength

    // The PLANE the simulation runs on, and how far off it a surface may be and still count as being on
    // it. x = rebased world Y of that plane, y = tolerance in blocks (0 disables the test).
    //
    // WHY THE HEIGHT FIELD NEEDS A HEIGHT TO LIVE AT. waterSimDomain says where the grid is in xz and
    // nothing about y, so a sampler that only knows xz hands the same ripples to every water surface in
    // that column -- the lake you disturbed, and the pool at the bottom of the waterfall thirty blocks
    // below it, rippling in perfect sympathy with something it has never touched.
    //
    // NOT waterAnchor.w, which looks similar and is not the same thing: that is the surface near the
    // CAMERA, published for the volumetric depth term, and it is a different probe with different rules.
    // The simulation picks its plane by its own search and has to publish it itself.
    // The wave field's shape beyond amplitude and length (D52). xy is the SECOND wave system's heading
    // as a unit vector -- crossing seas are what make real water read as complex, and a swell outlives
    // the wind that made it, so it arrives from somewhere else. z is the complexity ramp: 0 is the
    // shipped field exactly, which is what makes the knob measurable. w scales the authored steepness,
    // because a storm sea is steeper and not merely taller.
    public float4   waterWaveShape;
    // Gusts (D52): x patch size in blocks (<= 0 off), y depth of the modulation, z how fast patches
    // drift. Weighted toward the SHORT components inside the walk -- a gust ruffles the surface, it does
    // not raise the swell.
    public float4   waterWaveGust;   // x patch size, y strength, z drift speed, w wave time scale
    // Domain warp (D53): x amplitude in blocks, y scale in blocks, zw spare. Zero amplitude skips it
    // and its two noise fetches entirely, which is also the shipped field exactly.
    public float4   waterWaveWarp;   // x warp blocks, y warp scale, z first*16+last, w band-limit on
    public float4   waterSimPlane;  // x plane Y, y tolerance, z storm swell bias, w caustic contrast
};

// 32-byte hot area-light record. Radiance uses packed R11G11B10 and the grid-relative owner section
// uses three unsigned 10-bit coordinates; the geometric normal is reconstructed from the half axes.
//
// 32 divides the 64-byte cache line, so a record never straddles one. At the previous 48 bytes about
// half of them did, costing two transactions apiece — and RIS fetches these at random indices, M per
// shading vertex, so that is the layout's whole purpose. float3 forces 16-byte struct alignment and 32
// is a multiple of it, so this lands with no tail padding.
//
// The rectangle area is derived rather than stored: the rect spans 2U x 2V, so area = 4*|U x V|, which
// is exactly what the collector used to write (halfU = 0.5*(aHi-aLo)*e01 and rectArea =
// |e01 x e03|*(aHi-aLo)*(bHi-bLo)). lightGeometricNormal already needs that cross product, so the
// freed lane costs one length() the compiler shares with the normalize.
public struct Light {
    public float3 pos;      // rebased world centre, f32 (the RIS target divides by distance to it)
    public uint   le;       // bitcast R11G11B10 radiance
    public uint   halfUxy;  // half2(U.x, U.y)
    public uint   halfUzVx; // half2(U.z, V.x)
    public uint   halfVyz;  // half2(V.y, V.z)
    public uint   section;  // 10:10:10 grid-relative section coord + bit 30 normal-flip
};

// Eight-byte Vose alias-table column. PDFs are reconstructed from the selected Light.
public struct LightAlias {
    public uint  aliasIndex;
    public float accept;
};

// Dense light grid cell entry. Empty coordinates have spanCount=0; populated section cells point into
// an immutable list of weighted section light ranges built and published alongside this grid.
public struct LightGridCell {
    public uint spanOffset;
    public uint spanCount;
    public float invWeightSum;
};

// One 16-byte Vose alias column for a cell's section distribution. The two light counts are uint16s.
public struct LightGridSpan {
    public uint firstLight;
    public uint aliasFirstLight;
    public uint packedLightCounts; // low16 self, high16 alias
    public float accept;
};

// TerrainPrim.flags bit 0: this emissive quad is in the light buffer (RtLightCollector membership).
public static const uint TERRAIN_PRIM_IN_LIGHT_BUFFER = 1u;

// Largest finite half. The payload's half3 lanes carry HDR values (sky radiance most of all), so
// producers clamp to this instead of letting the conversion round up to +inf.
public static const float HALF_MAX = 65504.0;

// Radiance-ray payload (location 0). Member order and types are a cross-stage ABI.
//
// The three-component lanes are half3: they cost 6 bytes instead of 12, and payload storage is reserved
// per trace call site, so every byte here is paid twice per radiance trace (once for TraceRay, once for
// Invoke) and is preserved across the SER reorder. hitT stays f32 — it reaches 10000 blocks and the hit
// position is reconstructed from it, where half's ~4-block spacing at that magnitude would be visible.
public struct Payload {
    // Sky radiance on a miss, so this carries HDR values well above 1; half tops out at 65504.
    public half3  albedo;      // hit: block albedo. miss: sky radiance.
    public half3  normal;      // hit: geometric normal, viewer-oriented.
    public float  hitT;        // >= 0 on hit, < 0 on miss.
    public half3  motionPrev;  // per-vertex world displacement since last frame.
    public half3  f0;          // specular F0.
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 water-entering, bits 4..6 emission source.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Set by world.rchit on a terrain hit whose prim carries TERRAIN_PRIM_IN_LIGHT_BUFFER: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;
// Set by world.rchit on any dielectric hit (water or glass/ice): true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 2 (PAYLOAD_SHOW_CELESTIAL) is only
// meaningful on a miss and this bit only on a hit, so they never need to be read together, but they get
// distinct bits anyway to keep the two concerns unambiguous.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;
public static const uint PAYLOAD_EMISSION_SOURCE_SHIFT = 4u;
public static const uint PAYLOAD_EMISSION_SOURCE_MASK = 7u;
public static const uint EMISSION_SOURCE_NONE = 0u;
public static const uint EMISSION_SOURCE_UNIFORM = 1u;
public static const uint EMISSION_SOURCE_HEURISTIC = 2u;
public static const uint EMISSION_SOURCE_LAB_PBR = 3u;

public static const uint PAYLOAD_MATERIAL_MASK = 3u;

// Which extension record the hit material owns, one-based, zero for none. Carried in the payload rather
// than each Disney parameter separately: one field unlocks all of them, and a material without a record
// never loads one. Twelve bits rather than sixteen so the tangent azimuth the anisotropic lobe needs can
// share the same word — 12 bits of azimuth is 0.09 degrees, far below any visible streak error, and 4095
// distinct materials authoring Disney parameters is already implausible. The compiler enforces the cap.
// A miss whose ray landed inside the celestial light's own square — the light NEE samples, not the
// drawn sun/moon sprite. The sprite is vanilla's stylised sun, sixteen degrees across, and it is
// scenery: it travels with the sky in payload.albedo and is never weighted. The light is roughly the
// real thing (0.6 degrees by default) and it is what actually lights the world, so it is estimated two
// ways at once — once by NEE, once by a BSDF ray landing on it — and multiple importance sampling has
// to weight those against each other. That is what needs its own lane.
//
// It rides in payload.f0, which a miss otherwise writes zero and nobody reads, so the split costs no
// payload growth and the SER path pays nothing for it. The lane carries the light's IRRADIANCE rather
// than its radiance: dividing by the square's solid angle is the raygen's job, because at the smallest
// configurable sun the radiance is over a million and half would clamp it.
public static const uint PAYLOAD_ANALYTIC_LIGHT = 1u << 20u;

// PCG lives in the binding-free common module because raygen, the froxel compute bake and the
// world-space visibility compute bake all need the SAME celestial-emitter sampler. Keeping the stream
// in math.slang made compute callers grow lookalike hashes because math imports the ray-tracing bindings.
public uint pcg(inout uint s) {
    s = s * 747796405u + 2891336453u;
    uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
    return (w >> 22u) ^ w;
}

public float rndf(inout uint s) {
    // Convert the high 24 bits, which are exactly representable as float. Converting all 32 bits first
    // lets the top 128 uint values round to 2^32, incorrectly returning 1.0 and breaking `< probability`
    // tests (most seriously the F == 1 total-internal-reflection branch).
    return float(pcg(s) >> 8u) * (1.0 / 16777216.0);
}

// The celestial light is a square of angular half-size `halfAngle` laid out on the plane tangent to the
// light direction, spun to the celestial axis so it does not roll as the sun tracks across the sky.
// sampleSquare draws from it and world.rmiss tests against it; the two have to agree on this frame
// exactly, or NEE and BSDF sampling would be weighting two differently-oriented lights against each
// other and the combination would be neither estimate.
public void squareLightFrame(float3 axis, float3 celestialAxis, out float3 right, out float3 up) {
    right = normalize(cross(axis, celestialAxis));
    up = cross(right, axis);
}

/**
 * One direction on the active square celestial emitter from a caller-owned unit-square sample.
 *
 * <p>Pure and binding-free: every estimator supplies its own random stream, while this function owns the
 * emitter geometry. The visible square, surface NEE, water, marched fog, froxel fog and visibility grid
 * therefore cannot silently disagree about the light's angular extent or tangent frame.
 */
public float3 sampleSquareLightDirection(float3 axis, float3 celestialAxis, float halfAngle,
                                         float2 unitSample) {
    if (halfAngle <= 0.0) {
        return normalize(axis);
    }
    float3 right;
    float3 up;
    squareLightFrame(axis, celestialAxis, right, up);
    float tangentExtent = tan(halfAngle);
    float2 square = (unitSample * 2.0 - float2(1.0, 1.0)) * tangentExtent;
    return normalize(axis + square.x * right + square.y * up);
}

public struct SquareLightSample {
    public float3 direction;
    public float2 uv;
};

// One-pixel runtime telemetry for the disappearing-underwater-scatter investigation. Pass B writes this
// only for the centre pixel and only when waterProbeAddr is non-zero. It deliberately carries quantities
// from both sides of the compositor seam so a log can distinguish a shortened camera segment from a dead
// source, a visibility-grid transition, or a later radiance term. The buffer is ringed with WorldPush and
// read only after that slot's graphics timeline has completed, so diagnosis never stalls the current frame.
public struct WaterMediumProbe {
    public uint frameIndex;
    public uint flags;       // bit0 valid, bit1 submerged, bit2 non-zero prefix, bit3 finite output
    public uint pathFlags;   // first queued leaf's path flags
    public uint debugView;
    public float4 prefixScatterLength; // xyz in-scatter, w camera-to-first-leaf length
    public float4 prefixTransmittance; // xyz Beer transmittance, w luminance
    public float4 leafRadiance;        // xyz post-prefix leaf radiance, w luminance
    public float4 compositeRadiance;   // xyz complete raw HDR, w prefix luminance fraction
    public float4 skySourceOpen;       // xyz shared sky Radiance source, w sampled sky openness
    public float4 upwardWater;         // x measured up-ray water length, y chosen sky depth, z fallback depth, w surface Y
    public float4 firstSegmentScatter; // xyz ACTUAL first trace's raw in-scatter, w travelled distance
    public float4 firstSegmentT;       // xyz ACTUAL first trace's transmittance, w luminance
    public float4 firstHit;            // x raw hitT, y material id, z escaped flag, w medium profile
    public float4 firstThroughput;     // xyz throughput entering the actual first segment, w luminance
    public float4 firstWeightedScatter;// xyz exact contribution added to L, w luminance
};

/** The single stochastic interface for the active finite celestial emitter. */
public SquareLightSample sampleSquareLight(float3 axis, float3 celestialAxis, float halfAngle,
                                            inout uint seed) {
    SquareLightSample sample;
    if (halfAngle <= 0.0) {
        sample.uv = float2(0.5, 0.5);
        sample.direction = normalize(axis);
        return sample;
    }
    sample.uv = float2(rndf(seed), rndf(seed));
    sample.direction = sampleSquareLightDirection(axis, celestialAxis, halfAngle, sample.uv);
    return sample;
}

/**
 * Solid-angle density of a direction drawn from that square.
 *
 * Sampling is uniform in the tangent-plane coordinates, so the exact density carries a 1/cos^3 from the
 * projection onto the sphere. It is dropped: 1.0002 at the 0.6 degree default and 1.011 at the largest
 * sun the settings offer. Keeping it would also make the light's brightness depend on its angular size,
 * which is an appearance knob — the pushed radiance is an irradiance, and this is exactly the factor
 * that turns one into the other, so the two have to use the same approximation or the sun changes
 * brightness when you change its size.
 */
public float squareLightPdf(float halfAngle) {
    float t = tan(halfAngle);
    return 1.0 / max(4.0 * t * t, 1.0e-20);
}

/** Power heuristic (Veach, beta = 2) for combining two sampling strategies for the same integral. */
public float powerHeuristic(float pdfA, float pdfB) {
    float a = pdfA * pdfA;
    float b = pdfB * pdfB;
    return a / (a + b + 1.0e-20);
}

public static const uint PAYLOAD_EXTENSION_SHIFT = 8u;
public static const uint PAYLOAD_EXTENSION_MASK = 0xFFFu;
public static const uint MAX_MATERIAL_EXTENSIONS = 4095u;

// The surface tangent's azimuth about the shading normal, for the anisotropic specular lobe.
//
// Carried as an ANGLE against a frame both ends rebuild from the normal, rather than as a vector. That
// is what makes it free: the payload does not grow, so the SER path spills nothing extra for it, and a
// tangent is otherwise three floats the closest-hit computes and throws away.
//
// Eleven bits, not the sixteen originally sketched — bits 8..19 went to the extension index and bit 20
// to the celestial light. 11 bits is 0.18 degrees, still far below any visible error in a brushed
// streak. Bit 20 could in principle be shared, since it only means anything on a miss and this only on
// a hit, but PAYLOAD_SHOW_CELESTIAL and PAYLOAD_DIELECTRIC_ENTERING already declined that trade to keep
// the two concerns unambiguous, and this follows them.
public static const uint PAYLOAD_TANGENT_SHIFT = 21u;
public static const uint PAYLOAD_TANGENT_MASK = 0x7FFu;
public static const float PAYLOAD_TANGENT_STEPS = 2047.0;

/**
 * An arbitrary but DETERMINISTIC basis about n.
 *
 * Not a surface tangent and not meant to be one. Its only job is to be reproducible: a frame built here
 * in the closest-hit and a frame rebuilt here in the raygen agree exactly, because both derive it from
 * the one normal the payload carries. The real tangent then travels as an angle against it.
 *
 * sampleGGXVNDF and cosineDir build their sampling basis from this same function, so a lobe sampled in
 * one place and evaluated in another cannot end up in differently-rolled frames.
 */
public void canonicalTangentFrame(float3 n, out float3 t, out float3 b) {
    t = normalize(abs(n.x) > 0.9 ? float3(0.0, 1.0, 0.0) : float3(1.0, 0.0, 0.0));
    b = normalize(cross(n, t));
    t = cross(b, n);
}

public static const uint MATERIAL_OPAQUE = 0u;
// Both dielectrics are volumes: they refract and push a participating medium. They stay distinct ids
// because water owns behaviour nothing else has — an animated wave surface, the caustic term, biome-tint
// absorption calibrated per block of depth, and a chit path fed by the fluid mesher rather than the
// (inset) translucent terrain layer. MATERIAL_DIELECTRIC is everything else transparent: glass, ice, and
// any solid transparent block a pack defines.
public static const uint MATERIAL_WATER = 1u;
public static const uint MATERIAL_PARTICLE = 2u;
public static const uint MATERIAL_DIELECTRIC = 3u;

// Prim.flags, shared by entity and particle records.
//
// Bit 0: this submission carried an enchantment foil, so the hit shader gives it a violet sheen instead
// of shading the glint pass as ordinary albedo.
public static const uint ENTITY_PRIM_GLINT = 1u;
// Bit 1: dither this prim's alpha instead of cutting it off. Entities get the same behaviour from their
// MaterialHeader, but PARTICLES CARRY NO MATERIAL RECORD — they never index the table — so the flag lane
// is the only place the any-hit shader can learn it. Vanilla decides: SingleQuadParticle.Layer.translucent.
public static const uint PRIM_STOCHASTIC_ALPHA = 2u;

// Entity per-triangle record (48 B). The final lane mirrors TerrainPrim's integer material metadata.
public struct Prim {
    public float4 normal; // xyz = geometric normal, w = per-primitive emission strength
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Terrain per-triangle record (48 B). The final 16-byte lane contains integer geometry-local metadata;
// shared surface parameters live once in MaterialHeader instead of being copied into every triangle.
public struct TerrainPrim {
    public float4 normal;
    public float4 tint;
    public uint materialId;
    public uint flags;
    public uint aux0;
    public uint aux1;
};

// Compiled material record (80 B). Source-format data has already been decoded into canonical pages;
// texturePage indexes the dedicated canonical page arrays. materialUv maps sprite-local UV into the packed
// page, while albedoUv maps the vanilla atlas rectangle back to sprite-local UV.
/**
 * Optional Disney parameters, addressed by MaterialHeader.extensionOffset.
 *
 * Separate from the header because almost no material needs any of it: a block with default parameters
 * keeps extensionOffset == 0 and costs nothing, neither a record nor a load. Only materials that
 * actually author sheen, clearcoat, anisotropy or subsurface pay for one.
 *
 * Packed into float4s rather than declared field by field: std430 gives a float3 16-byte alignment, so
 * ten loose scalars would round up to far more than these 48 bytes. Every roughness-like quantity in
 * here is linear and is the GGX alpha — see the header of bsdf.slang before transcribing anything from
 * Disney's paper.
 */
public struct MaterialExtension {
    public float4 sheen;      // x sheen, y sheenTint, z clearcoat, w clearcoatGloss
    public float4 specular;   // x specularTint, y anisotropy, z subsurfaceWeight, w subsurfacePhaseG
    public float4 subsurface; // xyz subsurface radius (blocks, per channel), w reserved
};

public struct MaterialHeader {
    public uint model;
    public uint features;        // low feature bits; bits 24..31 = maximum valid material-page LOD
    public uint texturePage;
    public uint extensionOffset; // zero when no optional extension record is present
    public float4 materialUv; // packed-page uvMin.xy + uvExtent.xy
    public float4 albedoUv;   // vanilla atlas uvMin.xy + inverse uvExtent.xy
    public float4 params;  // roughness, metalness, IOR, transmission
    public float4 average; // whole-sprite linear-input average RGBA for translucent shadow filtering
};

public static const uint MATERIAL_FEATURE_SPEC = 1u;
public static const uint MATERIAL_FEATURE_NORMAL = 2u;
public static const uint MATERIAL_FEATURE_HEURISTIC_EMISSION = 4u;
public static const uint MATERIAL_FEATURE_STOCHASTIC_ALPHA = 16u;
// A resource pack named this channel in its material JSON, so the JSON value must survive the LabPBR
// decode below instead of being overwritten by the _s texture.
//
// The two are not peers. LabPBR is a source format: it is decoded into canonical channels like every
// other source, and the decode happens per texel in the hit shader because that is where the texture
// is. The JSON is an explicit authoring decision one stage further along the importer, applied to the
// already-decoded material — so it wins, the same way it wins over the vanilla heuristics.
//
// The flag is what separates "the pack said 0.3" from "nothing said anything, so this is a default".
// Without it the shader cannot tell them apart, and defaulting must NOT beat a real _s texture.
public static const uint MATERIAL_FEATURE_ROUGHNESS_AUTHORED = 8u;
public static const uint MATERIAL_FEATURE_METALNESS_AUTHORED = 32u;
// Final HDR emission strength (EMISSIVE_STRENGTH baseline * any JSON override multiplier), baked in Java
// at material-compile time (RtMaterialRegistry) and packed here as a 16-bit fraction of the max — every
// emissive material carries a value (0 for non-emissive), not just resource-pack-overridden ones, so the
// full features word's spare 16 bits (8..23) go to it instead of the 8 bits the old override-only field used.
public static const uint MATERIAL_EMISSION_STRENGTH_SHIFT = 8u;
public static const uint MATERIAL_EMISSION_STRENGTH_MASK = 65535u;
public static const float MATERIAL_MAX_EMISSION_STRENGTH = 32.0;
public static const uint MATERIAL_MAX_LOD_SHIFT = 24u;
public static const uint MATERIAL_MAX_LOD_MASK = 255u;

// Per-section geometry record: packed buffer addresses + per-bucket triangle offsets
// (gl_PrimitiveID restarts at 0 per geometry, so hit shaders re-add triBase[geometryIndex]).
public struct Section {
    public uint64_t primAddr;
    public uint64_t uvAddr;
    public uint triBase[4]; // fixed bucket order: solid/cutout/translucent/water
};

// Entity geometry record: packed buffer addresses, motion, and per-geometry triangle bases. Entity BLAS
// geometry order is opaque/any-hit; PrimitiveIndex restarts at zero for each geometry.
public struct EntityGeom {
    public uint64_t primAddr;
    public uint64_t idxAddr;
    public uint64_t uvAddr;
    public uint64_t dispAddr;
    public float4 rigidDisp;
    public uint triBase[2];
    public uint2 reserved;
};

// TLAS instance-custom-index encoding + terrain geometry buckets (shared by rahit/rchit).
public static const uint ENTITY_BIT = 0x800000u;
public static const uint PARTICLE_BIT = 0x400000u; // particles share the entity cutout path
public static const uint IDX_MASK = 0x3FFFFFu;     // low 22 bits = geom-table index
public static const uint BUCKET_CUTOUT = 1u;
public static const uint BUCKET_TRANSLUCENT = 2u;
public static const uint BUCKET_WATER = 3u;

// packHalf2x16 equivalent (Slang core module has no GLSL pack builtin; f32tof16 returns the half
// bit-pattern in the low 16 bits).
public uint packHalf2(float2 v) {
    return (f32tof16(v.y) << 16) | (f32tof16(v.x) & 0xFFFFu);
}

public float2 unpackHalf2(uint p) {
    return float2(f16tof32(p & 0xFFFFu), f16tof32(p >> 16));
}

// The wavefront record: what the primary pass writes and the indirect pass reads, one per split
// continuation. Declared here rather than beside its pack/unpack in segment.slang so world_layout_probe
// can reflect its true std430 stride — segment.slang imports world_core, whose set-0 bindings would
// collide with the probe's.
//
// std430 gives the struct 16-byte alignment, so its stride is 48: eight uints after ro come to 44 bytes
// and the last four are tail padding. That padding is the only room there is — a tenth uint fills it, an
// eleventh rounds the stride to 64, and at 1440p with two records per pixel that step costs +118 MB
// rather than the +29 MB one uint suggests. See RtPathSegmentLayoutTest before spending it; pathFlags
// has spare bits above 13 and those are free (11 is PATH_HAS_NEXT; 9/10 and 12/13 carry the medium's
// water and ambient bits — see segment.slang).
public struct PackedPathSegment {
    public float3 ro;
    public uint rd;
    public uint throughput;
    public uint currentExtinction;
    public uint outerExtinction;
    public uint mediumIors;
    public uint rayCone;
    public uint seed;
    public uint pathFlags;
};
