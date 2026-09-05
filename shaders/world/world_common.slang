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

// Dimension-provider ABI. These are capabilities, not dimension ids: shaders never branch on
// minecraft:the_nether or any other registry name. A resource-authored preset chooses one provider and
// the renderer publishes only the capabilities needed by that provider.
public static const uint SKY_PROVIDER_ATMOSPHERE = 0u;
public static const uint SKY_PROVIDER_LOCAL_AMBIENT = 1u;
public static const uint SKY_PROVIDER_ENVIRONMENT = 2u;
public static const uint ENVIRONMENT_AMBIENT_UNOCCLUDED = 1u << 0u;
public static const uint ENVIRONMENT_FROXEL_LOCAL_LIGHTS = 1u << 1u;
// M26 slice two: let SURFACE shading read the presampled pool as well as the volume.
//
// Its own bit rather than a share of the volume's switch, because they are two claims. Slice one was
// validated by toggling the pool and watching gpu.traceIndirect; if that same switch also moved the
// surface estimator, the measurement it produced would no longer mean what it was taken to mean.
//
// Bit 2 is the one M25 vacated when its hash grid was withdrawn. The word is named for the environment
// and has become the frame's general capability word -- the fog's in-scatter cap already sits in it --
// which is a smaller wrong than growing WorldPush by sixteen bytes to carry one bit.
public static const uint ENVIRONMENT_LIGHT_POOL_SURFACE = 1u << 2u;
// How many path segments may ADD fog light, counted from the camera. Zero means all of them, which is
// the shipped behaviour. Bits 4-7, so 0-15.
//
// TRANSMITTANCE IS NEVER GATED BY THIS, only in-scatter. Skipping a segment's fog entirely would leave
// distant geometry unfogged inside reflections while the direct view of the same place is fogged, and a
// seam that appears only in a mirror is the failure R18 exists to prevent. Dropping in-scatter alone is
// merely darker, and darker in the segments whose throughput has already decayed most.
public static const uint ENVIRONMENT_INSCATTER_SEGMENTS_SHIFT = 4u;
public static const uint ENVIRONMENT_INSCATTER_SEGMENTS_MASK = 0xFu;
public static const uint ENVIRONMENT_LAYER_SHIFT = 8u;
public static const uint ENVIRONMENT_LAYER_MASK = 0xFFu;

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
    public uint64_t materialExtensionAddr; // optional Disney/weather records; MaterialHeader.extensionOffset is 1-based
    public uint64_t lightBufAddr;        // RIS emitter-NEE global light buffer (0 = none published)
    public uint64_t lightAliasAddr;      // power-weighted O(1) alias table (0 = uniform fallback)
    public uint64_t lightLocalAliasAddr; // power aliases relative to each section's range
    public uint64_t lightGridCellAddr;   // dense light grid cell headers (0 = global proposals only)
    public uint64_t lightGridSpanAddr;   // packed weighted section spans referenced by cell headers
    public uint64_t pathQueueAddr;       // packed primary -> indirect continuation records
    public uint64_t waterProbeAddr;      // optional host-visible WaterMediumProbe (0 = diagnostic off)
    // One uint per 4x4-block column: rain, snow, none or not-yet-streamed. The shading needs it because
    // snow and rain share the exposure map -- a roof shelters a doorway from both -- while only rain
    // wets anything, so "has an exposure depth" stopped being the same question as "is getting wet".
    // 120 bytes with this; the guaranteed push-constant floor is 128, so there is room for one more.
    public uint64_t rainPrecipitationAddr;
    // Persistent ReSTIR reservoirs, both frame halves (0 = reuse off, and the shading falls back to
    // single-frame RIS exactly as before). See restir.slang for the layout and the slot arithmetic.
    //
    // THIS WAS THE LAST FREE ADDRESS. 14*8 + 12 = 124, rounded to 128, which is exactly Vulkan's
    // guaranteed push-constant floor. The next device buffer to need reaching from a raygen stage
    // cannot come here -- it goes in WorldPush, which is itself a device-address buffer and has no
    // such ceiling, at the cost of one indirection.
    public uint64_t reservoirAddr;
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

/**
 * M18's dynamic sphere emitters are being proposed this frame (dynamicRisCandidates > 0).
 *
 * <p>The hit shader needs it to decide whether an emissive ENTITY quad should claim
 * PAYLOAD_EMITTER_IN_LIST: the proxy covering it only exists as a candidate while the stratum is on, and
 * suppressing the surface's own emission when nothing samples the proxy would lose that light outright.
 * Terrain has no such condition — its emitters live in the buffer RIS always draws from.
 *
 * <p>Here rather than read from WorldPush because the hit shader deliberately never dereferences that
 * struct, which is the same judgement SHADE_SOLID_LAYER_SSS is here for.
 */
public static const uint SHADE_DYNAMIC_EMITTER_RIS = 2u;

/**
 * The two live volumetric switches, carried in WorldPush.volumetricSwitches -- NOT in WorldPush.flags,
 * and the reason is a landed bug worth remembering (D211): both switches first shipped as bits 27/28 of
 * the flags word after a bit audit that grepped only the SLANG readers and declared the bits free. The
 * Java assembly had owned them all along -- bit 27 is M17's SCATTER_VERTEX, bit 28 its nested
 * VOLUME_EMITTER_NEE -- so from the day S1 landed until this field existed, the bake ran directional
 * and the clamp applied whenever those (default-on) features set their bits, the switches did nothing,
 * and the off state was not the shipped picture. THE RULE: a bit is free only after enumerating every
 * writer of the word on every side of the language boundary; when in doubt, a word this cheap to append
 * is cheaper than a second silent collision.
 */
public static const uint FLAG_SKY_FIELD_DIRECTIONAL = 1u << 0u;

/**
 * Fog stretches the visibility grid's span does not cover read the CLAMPED field instead of assuming
 * open. The march cuts each segment into the stretch before the grid, the stretch inside it, and the
 * stretch beyond; the two outer ones were hardwired to openness 1 -- D178's deliberate leftover, kept
 * only so its clamp could be attributed alone, and that attribution closed the day it shipped. The
 * symptom of the leftover is exactly #41's original report: cave fog roughly a grid radius from the
 * camera (about 32 blocks at the default cell) glowing as if under open sky while nearby fog is dark.
 *
 * <p>The clamp is D178's, not a new miss policy: a sample outside the box asks the nearest measured
 * cell. Outdoors that boundary reads open, so the open-world far field keeps its brightness; in a cave
 * the boundary is roofed and the far fog goes dark with the near fog.
 */
public static const uint FLAG_FOG_BEYOND_GRID_CLAMP = 1u << 1u;

/**
 * Which of the four world-azimuth quarter sectors a direction belongs to, and the ONE definition of
 * "sector" for everything S1 touches: sky_medium_reduce splits the sky integral by it, and the
 * visibility bake samples one cosine-weighted ray per sector with azimuth (k + u) * pi/2 in the same
 * frame -- +X toward +Z, matching cosineHemisphereUp. If either side's azimuth convention moves, both
 * move or a sector's visibility is weighted by a different sector's radiance, which presents as cave
 * mouths lit from the wrong side.
 */
public uint skySectorIndexOf(float3 dir) {
    float phi = atan2(dir.z, dir.x);          // (-pi, pi]
    float turned = phi + 6.28318530718 * (phi <= 0.0 ? 1.0 : 0.0);
    return min(uint(turned * 0.63661977236), 3u); // turned / (pi/2), clamped at the 2*pi edge
}

// A sector's representative direction for anything that must point at the sector rather than into it:
// its centre azimuth, at the cosine-weighted hemisphere's mean elevation (sin = 2/3). Currently only
// documentation-grade -- the marched sky term needs no direction because the phase is folded into the
// sector's radiance -- but a future consumer must take the direction from here, not re-derive it.
float3 skySectorDirection(uint sector) {
    float phi = (float(sector) + 0.5) * 1.57079632679;
    float cosE = 0.7453559925; // sqrt(1 - 4/9)
    return float3(cosE * cos(phi), 0.6666666667, cosE * sin(phi));
}

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
    public float4   sunDir;           // atmosphere: xyz sun, w dayFactor; environment: xyz heading, w disk phase
    public float4   lightDir;         // xyz active NEE light dir, w square half-angle (rad)
    public float4   lightRadiance;    // atmosphere: xyz celestial E, w stars; environment: xyz disk scale, w outer R/M
    public float4   moonDir;          // atmosphere: xyz moon, w phase; environment: xyz opposite heading, w thickness
    public float4   celestial;        // xyz celestial/projected-spin axis; w atmosphere star / environment HDRI angle
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
    public uint     skyProvider;      // SKY_PROVIDER_*; selected by the active dimension preset
    public uint     environmentFlags; // ENVIRONMENT_* capabilities, not dimension-specific branches
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
    public float4   mediumSkyRadiance; // xyz phase-integrated source; w environment-radiance scale
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
    // D163-D168 HIGH clouds: two analytic optical-depth sheets. y is both their vertical separation and
    // the reference thickness that turns extinction/block into vertical tau. w <= 0 disables both.
    public float4   cloudHigh;        // x bottom altitude, y separation/reference thickness, z coverage, w extinction/block
    // Shape scale and world origin are independent from the low volume; source RGB never reaches runtime.
    public float4   cloudHighShape;   // x patch diameter, y upper-field span, z density, w patch tau strength
    public float4   cloudHighOrigin;  // xyz high-cloud origin (rebase - drift), w patch-grid spacing
    // Low-volume differential advection plus the upper high-cloud sheet's independent tau strength.
    public float4   cloudEvolution;   // xy low detail offset (blocks), z upper high-cloud tau strength, w reserved
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

    // M21 directional rain exposure and globally retained surface water. The depth bake and every path
    // vertex consume the same origin/direction; duplicating either in a compute-only push constant would
    // allow the shadow map to be geometrically correct while every consumer sampled the neighbouring ray.
    // rainExposureOrigin.w is the active square resolution (256 or 512), zero for the exact dry/off path.
    public float4   rainExposureOrigin; // xyz top-plane minimum corner (rebased), w active resolution
    public float4   rainDirection;      // xyz unit direction toward ground, w ray length top-to-bottom
    public float4   rainState;          // x wetFilm storage, y puddle storage, z rain intensity, w enabled
    // Negative MaterialExtension.weather lanes inherit these values at shading time.
    public float4   rainMaterialDefaults; // absorption, darkening, film retention, puddle affinity
    public float4   rainSurface;        // x film strength, y film GGX alpha, z puddle coverage, w puddle scale
    public float4   rainPuddle;         // x ripple strength, y puddles enabled, z ripple radius, w wrapped time
    // Temporary M21 art-calibration controls. Keep physical water F0 fixed; these scale coverage,
    // shallow-puddle extra darkening and roughness and will be baked to constants after visual sign-off.
    public float4   rainCalibration0;    // wet darkening gain, wet coat gain, puddle layer gain, puddle alpha
    public float4   rainCalibration1;    // puddle extra darkening, normal flattening, ripple width, wet-film normal flatten
    /**
     * D173 low-cloud domain warp: displace WHERE the density field is sampled, rather than subtract from
     * what it returns.
     *
     * <p>Subtracting an erosion band thins and dims a boundary but leaves its SHAPE the base field's, which
     * is why the deck reads as rounded blobs however hard the erosion is driven. Displacing the sample
     * position moves the iso-surface itself, so it grows folds, filaments and hollows the base field never
     * had. The renderer already relies on this for the water wave field.
     *
     * <p>zw is the warp's OWN advection, and it is what makes clouds grow rather than merely drift. Sharing
     * the shape field's drift would carry the warp along rigidly: the cloud would be permanently deformed
     * in one fixed way and then translate. A different speed slides the warp through the shape, so the
     * displaced boundary is continuously re-formed -- lobes appear and dissolve. Same mechanism as the
     * differential detail offset above it, which is where the idea is already proven.
     *
     * <p>x = 0 skips the fetch and is bit-for-bit the field without any of this.
     */
    public float4   cloudWarp;          // x amplitude (blocks), y feature scale (blocks), zw own drift
    /**
     * The shape of the ReSTIR reservoir store at pc.reservoirAddr, published by whatever ALLOCATED it.
     *
     * <p>Not the config values. `restirReuseDepth` is the reuse-depth knob already clamped to the bounce
     * budget, and `restirPathCount` is spp * MAX_PATH_SEGMENTS as it stood when the buffer was sized --
     * both read back out of the renderer's own fields rather than out of FluoriteConfig, because a knob
     * that moves mid-frame would otherwise have the shading index a buffer sized for the old value. Zero
     * depth means no store, and the shading falls back to single-frame RIS.
     *
     * <p>`restirStatsAddr` is the per-depth reuse counter buffer, 0 when the diagnostic is off. It is a
     * DEVICE ADDRESS in WorldPush rather than in WorldPushConstants for the same reason: that block is
     * full at Vulkan's guaranteed 128-byte floor, and this is the first buffer to arrive after it filled.
     * The dereference it costs is paid once per pixel, in a diagnostic, off by default.
     *
     * <p>All three are here rather than in WorldPushConstants because nothing in a hit shader has any use
     * for them -- which is the judgement WorldPushConstants' own banner asks for.
     */
    public uint64_t restirStatsAddr;
    public uint     restirReuseDepth;
    public uint     restirPathCount;
    // How many screen-space neighbours each reused vertex asks. 0 leaves temporal reuse exactly as it was
    // measured, which is this knob's off state; the store is unaffected either way, since a neighbour is
    // read from slots that already exist.
    public uint     restirSpatialNeighbours;

    /**
     * M26's presampled emitter pool: base address and slots per cell.
     *
     * <p>0 and 0 whenever the switch sits at its published position, and every sampler then walks the
     * grid exactly as it always has. That is the off state iron law 8 asks for, spelled as an absent
     * buffer rather than as a branch, so there is nothing to forget.
     *
     * <p>HERE RATHER THAN IN WorldPushConstants, and not by preference: that block is 128 bytes, which is
     * the guaranteed floor, and counting its fields leaves four bytes -- half an address. M25 handed
     * five lanes back to this struct when its hash grid was withdrawn; these two are a live feature
     * taking two of them, which is what that space is for.
     */
    public uint64_t lightPoolAddr;
    public uint     lightPoolDepth;

    /**
     * M18's per-frame sphere emitters -- entities, held blocks, flame particles -- and how many.
     *
     * <p>THESE POSITIONS ARE ALREADY IN CURRENT REBASED SPACE and must NOT have lightRebase added, unlike
     * every position in the static light buffer. The static one is built once against a terrain hierarchy
     * and rebased on the way in; this one is rebuilt from live entities every frame, so it is authored in
     * the space the shader already works in. Adding the offset compiles, runs, and puts every dynamic
     * light a rebase distance away from the thing carrying it.
     *
     * <p>A separate buffer rather than an extension of the light buffer, because the alias tables and the
     * dense grid are built incrementally with the terrain and would have to be rebuilt whole every frame
     * to carry entities. That is why the dynamic candidates are their OWN stratum in the proposal mixture
     * (see risInitial) rather than competing in the existing ones.
     *
     * <p>The two buffers hold disjoint shapes -- rectangles there, spheres here -- which is what makes the
     * three-way mixture density trivial: no candidate can be proposed by more than one stratum, so each
     * one's pdf is its own stratum's term and nothing has to be summed across them.
     */
    public uint64_t dynamicLightAddr;
    public uint     dynamicLightCount;
    /** How many of the M RIS candidates are drawn from that buffer. 0 is off and is the shipped default. */
    public uint     dynamicRisCandidates;

    /**
     * One multiplier for every finite emitter's radiance: brightness times a colour-temperature tint whose
     * luminance is exactly one, so the two controls stay orthogonal and turning the temperature never
     * changes how bright anything is. (1,1,1,0) is off and is bit-for-bit the shipped picture.
     *
     * <p><b>Applied where radiance becomes OUTGOING LIGHT, never where a Light record is decoded.</b> That
     * is not a style preference, it is the difference between a working knob and one that does nothing.
     * The proposal density reconstructs a light's power as area times luminance, and normalises it by an
     * inverse total power the CPU published untinted -- so tinting the decode would scale every pdf by the
     * brightness, W = wSum/(M*phat) would divide by it again, and the two cancel EXACTLY. The picture would
     * not move by one bit however far the slider went.
     *
     * <p>So the decoders and the proposal stay untinted, and so does what ReSTIR stores: a reservoir keeps
     * the raw radiance, which is what its light-identity check compares against a freshly re-read record.
     * Tinting one side of that comparison and not the other would reject every stored sample.
     */
    public float4   emitterTint;
    /**
     * M28 S1: the sky's phase-integrated radiance split into the visibility grid's four world-azimuth
     * quarter sectors, so the marched ambient sky term can weight each sector's VISIBILITY (the grid's
     * bins) by that sector's own radiance instead of by the whole dome's mean. Each entry is the same
     * integral mediumSkyRadiance is, restricted to one sector; their sum therefore reproduces
     * mediumSkyRadiance up to summation order, which is what keeps a sector-lit cave mouth and an open
     * field consistent at the grid's boundary.
     *
     * <p>Written by sky_medium_reduce.comp every frame for atmosphere providers -- the same pass that
     * writes mediumSkyRadiance, from the same LUT, so the split cannot drift from the whole. The CPU
     * fallback below (no reduce runs outside an atmosphere) is the isotropic split: four quarters of
     * mediumSkyRadiance, whose sum is then EXACTLY the scalar. Consumers must not read these lanes
     * unless FLAG_SKY_FIELD_DIRECTIONAL is set.
     */
    public float4   skySectorRadiance[4]; // xyz sector phase-integrated radiance, w zero
    /**
     * Live volumetric switches, in their own word -- see the FLAG_SKY_FIELD_DIRECTIONAL banner for why
     * these are not bits of WorldPush.flags. Bit 0 = directional sky field, bit 1 = beyond-grid fog
     * clamp. Written by RtComposite from the same config values it counts into the frame CSV, so the
     * word and the capture columns cannot disagree about what a frame was told to do.
     */
    public uint     volumetricSwitches;
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
    public uint   section;  // rect: 10:10:10 grid coord + bit30 flip; bit31 selects a sphere record
};

// M18 reserves bit 31 as the shared ABI's shape tag. Dynamic sphere records store radius in the low
// half of halfUxy; section bits 0..29 carry a deterministic dynamic-source key, while bit 30 remains
// reserved. These records live in a separate per-frame buffer, and M24 S3 gave them their own stratum in
// the RIS proposal rather than folding them into the alias tables or the dense grid -- both are built
// incrementally with the terrain and would have to be rebuilt whole every frame to carry entities. So the
// alias/grid sampling still sees rectangle records only, and the sphere records are proposed beside it.
// See WorldPush.dynamicLightAddr, and note that THOSE POSITIONS ARE ALREADY REBASED.
public static const uint LIGHT_TYPE_SPHERE = 0x80000000u;
public static const uint LIGHT_NORMAL_FLIP = 0x40000000u;

public bool lightIsSphere(Light light) {
    return (light.section & LIGHT_TYPE_SPHERE) != 0u;
}

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
    /**
     * M26: this cell's index among POPULATED cells, which addresses its slice of the presampled pool.
     *
     * <p>Ranked rather than linear because the dense grid reaches four million cells while the pool
     * carries unpacked lights: at eight slots each that would be a gigabyte for a field that is mostly
     * empty. Undefined when spanCount is zero, and unreachable there -- emitterFindGridCell rejects an
     * empty cell before anything reads this.
     */
    public uint poolRank;
};

/**
 * One presampled emitter: the Light record itself, plus the probability it was drawn with.
 *
 * <p>THE POINT OF THE MILESTONE IS THE LOAD IT REMOVES. Sampling an emitter used to walk four dependent
 * loads -- cell, span, alias, then the Light record -- each address waiting on the one before it, which
 * is latency a ray-tracing shader's occupancy cannot hide. D31 measured that walk at +20.9 ms and proved
 * the shadow ray innocent: silencing the ray while keeping the walk made the frame SLOWER, which can
 * only be the walk and the register pressure around it.
 *
 * <p>CARRYING THE RECORD RATHER THAN AN INDEX is what takes the chain to two loads instead of three. An
 * index would leave the Light to be fetched at an address depending on this read, which is precisely the
 * link the pool exists to break. It costs 16 bytes a slot over an index, and nothing else: these are the
 * same packed lanes Light already stores, so emitterRadiance, emitterHalfU/V, emitterArea and
 * emitterNormal decode a pooled slot with no new code and no new arithmetic.
 *
 * <p>THE RECTANGLE POINT IS NOT BAKED IN, and that is the reason this holds half-extents rather than a
 * finished position. Drawing the point at build time would give every pixel that reads a slot the same
 * point on the emitter -- correlation across the screen rather than noise, which no temporal accumulator
 * removes because it is not noise. So the slot chooses the LIGHT and the sample still chooses the point.
 *
 * <p>`pdf` is the discrete selection probability the slot was drawn with, carried so the estimator stays
 * unbiased however the pool was filled. The caller still applies the rectangle's own 1/area density in
 * its geometry term, exactly as sampleVolumeEmitter's contract already says.
 */
public struct PooledLight {
    public float3 pos;      // raw, unrebased: the sampler adds lightRebase as it always has
    public float  pdf;      // 0 means this slot holds nothing
    public uint   le;
    public uint   halfUxy;
    public uint   halfUzVx;
    public uint   halfVyz;
    public uint   section;
    /**
     * The emitter's index in the terrain light buffer, and it is NOT spare bookkeeping.
     *
     * <p>A surface sample goes into a ReSTIR reservoir, and storedLightStillPresent re-loads Light by
     * this index a frame later to decide whether the stored sample may be reused. A pooled slot that
     * could not name its light would validate reuse against whatever now sits at some other index --
     * which is not a crash, it is last frame's light wearing this frame's geometry.
     *
     * <p>The volume path never reads it: M is one there and nothing is reused.
     */
    public uint   lightIndex;
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
    public uint   flags;       // bits 0..1 material, bit 2 celestial, bit 3 direct-view input / entering output.
    public uint   roughMetal;  // packHalf2x16(roughness, metalness)
    public uint   emissionSss; // packHalf2x16(emission, sss)
    public uint   iorTransmission; // packHalf2x16(IOR, transmission factor)
    public uint   rayCone;     // packHalf2x16(width, spread)
};

public static const uint PAYLOAD_SHOW_CELESTIAL = 4u;
// Trace-input meaning of bit 3. A miss reads it before replacing flags with its output classification;
// a hit replaces flags outright and may then reuse the same bit as PAYLOAD_DIELECTRIC_ENTERING. Keeping
// this in the existing word lets the directly visible environment choose its camera-only reconstruction
// filter without growing the already pressure-sensitive payload.
public static const uint PAYLOAD_DIRECT_VIEW = 8u;
// Set by world.rchit on a terrain hit whose prim carries TERRAIN_PRIM_IN_LIGHT_BUFFER: this emitter is
// RIS-sampled, so raygen gates its direct-hit emission on diffuse continuation rays (no double count).
public static const uint PAYLOAD_EMITTER_IN_LIST = 128u;

/**
 * This emitter is covered by a PROXY rather than by a second sampling of the same surface.
 *
 * <p>The distinction decides which correction applies, and the two are not interchangeable. A terrain
 * rectangle is sampled by the emitter strategy and hit by the BSDF strategy on THE SAME SURFACE, so the
 * two densities meet and MIS weights them. A dynamic entity emitter is sampled as a SPHERE that stands in
 * for a mesh the BSDF ray actually hits — two different domains, whose densities never meet, so there is
 * no MIS weight to compute and the estimators have to be partitioned outright instead.
 *
 * <p>Weighting the proxy case as though it were the surface case would produce weights that do not sum to
 * one, which is a quiet energy error rather than a visible one.
 */
public static const uint PAYLOAD_EMITTER_PROXIED = 1u << 21u;
// Set by world.rchit on any dielectric hit (water or glass/ice): true when the incoming ray travels
// against the prim's outward face normal (entering the volume), false when it exits. Derived from face
// orientation rather than toggled, so a stray or missing face cannot corrupt the medium for the rest of
// the path — each crossing re-derives which side it is on. Bit 3's direct-view trace input shares this
// value, but the hit shader overwrites flags before setting this output, so the two meanings cannot leak
// into one another.
public static const uint PAYLOAD_DIELECTRIC_ENTERING = 8u;
// Opaque-hit reuse of bit 3: the unflipped terrain geometric normal points upward. Hits and dielectric
// crossings are mutually exclusive material classes, so this costs no payload lane.
public static const uint PAYLOAD_RAIN_TOP_FACE = 8u;
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
// Opaque-hit reuse of bit 20. Misses use it for the analytic celestial emitter; terrain hits use it to
// identify small pass-through plants whose exposed cards receive rain film on every visible face.
public static const uint PAYLOAD_RAIN_PLANT = 1u << 20u;

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

/** Exact solid-angle density for the tangent-plane square at one sampled/landed direction. */
public float squareLightDirectionalPdf(float3 axis, float halfAngle, float3 direction) {
    float projectedPdf = squareLightPdf(halfAngle);
    float cosine = max(dot(normalize(axis), normalize(direction)), 1.0e-6);
    return projectedPdf / max(cosine * cosine * cosine, 1.0e-20);
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
// Ten bits, not the sixteen originally sketched — bits 8..19 went to the extension index, bit 20 to the
// celestial light, and bit 21 to M24 S4's emitter-proxy tag. Every bit of this word is now spoken for.
// 10 bits is 0.35 degrees of grain direction, which is still an order of magnitude finer than any
// visible error in a brushed streak; the bit bought the difference between an emitter counted once and
// an emitter counted twice, which is visible. Bit 20 could in principle be shared, since it only means
// anything on a miss and this only on a hit, but PAYLOAD_SHOW_CELESTIAL and PAYLOAD_DIELECTRIC_ENTERING
// already declined that trade to keep the two concerns unambiguous, and this follows them.
public static const uint PAYLOAD_TANGENT_SHIFT = 22u;
public static const uint PAYLOAD_TANGENT_MASK = 0x3FFu;
public static const float PAYLOAD_TANGENT_STEPS = 1023.0;

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
// This entity quad's emission is also carried by a dynamic sphere emitter, so RIS already samples it.
// The entity twin of TERRAIN_PRIM_IN_LIGHT_BUFFER, and it feeds the same payload bit -- see
// RtEntityCapture.PRIM_EMITTER_IN_LIGHT_BUFFER for why an entity-wide flag would be the wrong shape.
public static const uint ENTITY_PRIM_EMITTER_IN_LIGHT_BUFFER = 8u;
// Bit 1: dither this prim's alpha instead of cutting it off. Entities get the same behaviour from their
// MaterialHeader, but PARTICLES CARRY NO MATERIAL RECORD — they never index the table — so the flag lane
// is the only place the any-hit shader can learn it. Vanilla decides: SingleQuadParticle.Layer.translucent.
public static const uint PRIM_STOCHASTIC_ALPHA = 2u;
// Fluorite-owned neutral rain crown/droplet geometry. It carries procedural alpha in Prim.aux1 and never
// samples the vanilla blue rain sprite; valid only when InstanceID has PARTICLE_BIT.
public static const uint PRIM_RAIN_SPLASH = 4u;
// Terrain precipitation interception. These bits live on TerrainPrim rather than MaterialHeader because
// the same atlas sprite can be emitted by a low grass card and a dense leaf block. The mesher knows the
// block state and can classify that geometry without making the rain bake fetch Minecraft state.
public static const uint TERRAIN_PRIM_RAIN_PASS = 4u;      // grass/flowers/crops: wet themselves, do not shelter ground
public static const uint TERRAIN_PRIM_RAIN_FOLIAGE = 8u;  // leaves: a capped number of layers attenuate rain

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
 * actually author Disney or format-3 rain response parameters pay for one.
 *
 * Packed into float4s rather than declared field by field: std430 gives a float3 16-byte alignment, so
 * loose scalars would round up to more than these 64 bytes. Every roughness-like quantity in
 * here is linear and is the GGX alpha — see the header of bsdf.slang before transcribing anything from
 * Disney's paper.
 */
public struct MaterialExtension {
    public float4 sheen;      // x sheen, y sheenTint, z clearcoat, w clearcoatGloss
    public float4 specular;   // x specularTint, y anisotropy, z subsurfaceWeight, w subsurfacePhaseG
    public float4 subsurface; // xyz subsurface radius (blocks, per channel), w reserved
    // Format 3 rain response. A negative lane inherits the matching global WorldPush default, so changing
    // a video option never requires rebuilding the resource-epoch material table.
    public float4 weather;    // x absorption, y darkening, z film retention, w puddle affinity
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
