package io.github.dswepm.fluorite;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntUnaryOperator;
import java.util.function.UnaryOperator;
import io.github.dswepm.fluorite.platform.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Central mutable runtime configuration. Each setting resolves its value, in order of precedence, from a
 * {@code -Dfluorite.*} system property, then the {@code config/fluorite.toml} file, then a hardcoded
 * default. The settings UI and any other code call the same {@code set(...)} methods, and {@link #save()}
 * writes the current values back to the TOML file.
 *
 * <p>The system property namespace ({@code fluorite.rt.foo}) and the TOML layout are independent: the file
 * uses real nested tables (e.g. {@code [omm]} with a {@code subdivision} key) grouped for readability, while
 * the property namespace stays flat and dotted for convenient one-off {@code -D} overrides.
 */
public final class FluoriteConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("Fluorite");
    private static final List<RuntimeSetting<?>> SETTINGS = new CopyOnWriteArrayList<>();

    private static final Path CONFIG_PATH = resolveConfigPath();
    private static final CommentedFileConfig FILE = loadFile(CONFIG_PATH);

    private FluoriteConfig() {
    }

    public static List<RuntimeSetting<?>> settings() {
        return List.copyOf(SETTINGS);
    }

    public static Path configPath() {
        return CONFIG_PATH;
    }

    public static void reloadFromSystemProperties() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.reloadFromSystemProperties();
        }
    }

    /**
     * Forces every settings holder to class-initialize so all settings are registered (and have applied
     * their file values). Call before {@link #save()} to write a complete file, and once at startup so the
     * file round-trips the full surface even for settings the renderer has not touched yet.
     */
    public static void ensureRegistered() {
        @SuppressWarnings("unused")
        Object[] touch = {
            Rt.ENABLED, Rt.Composite.SPP, Rt.Composite.MAX_BOUNCES, Rt.Terrain.ASYNC_DISPATCH_PER_PASS, Rt.Omm.ENABLED,
            Rt.Entities.ENABLED, Rt.Entities.GLOW_ENABLED, Rt.EntityTextures.MAX_TEXTURES, Rt.DlssRr.ENABLED, Rt.Fg.ENABLED,
            Rt.Reflex.ENABLED, Rt.Exposure.MODE, Rt.FrameStats.ENABLED,
            Rt.Hdr.ENABLED, Ngx.PATH, Rt.Diagnostics.TERRAIN_DIGEST, Rt.Volumetrics.ENABLED,
            Rt.Bsdf.MIS_ENABLED, Rt.Bsdf.ANISOTROPY_ENABLED, Rt.Bsdf.SUBSURFACE_SOLID_LAYER,
            Rt.Bsdf.SUBSURFACE_MODE, Rt.Water.ABSORB_OVERRIDE,
            Rt.Water.SCATTER_R,
        };
    }

    /** Writes the default config file if it does not exist yet. */
    public static void saveIfMissing() {
        ensureRegistered();
        if (FILE.valueMap().isEmpty()) {
            save();
        }
    }

    /** Serializes all registered settings to the TOML config file. */
    public static synchronized void save() {
        ensureRegistered();
        writeComments();
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(FILE);
        }
        FILE.save();
    }

    private static void writeComments() {
        FILE.setComment("enabled",
                " Fluorite RT renderer configuration.\n"
                        + " A matching -Dfluorite.* system property overrides the value below.");
        FILE.setComment("terrain",
                " Render-thread terrain work is bounded by dispatch/result counts per streaming pass.\n"
                        + " Buffer fill and BLAS/OMM preparation run on workers. max-inflight-sections bounds\n"
                        + " the complete snapshot -> worker -> GPU build -> publication lifecycle.");
        FILE.setComment("frame-generation",
                " DLSS Frame Generation. Default off; gated additionally by hardware/driver availability.\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...), clamped\n"
                        + " at runtime to the driver's reported DLSSG.MultiFrameCountMax.");
        FILE.setComment("reflex",
                " NVIDIA Reflex (VK_NV_low_latency2). Default off; gated additionally by device support.\n"
                        + " minimum-interval-us: 0 = no framerate cap (Reflex just paces submission).");
        FILE.setComment("lights",
                " RIS direct lighting from block emitters (torches, glowstone, lava, ...): per diffuse\n"
                        + " vertex, resample ris-candidates power-weighted proposals and spend one shadow ray on\n"
                        + " the survivor. ris-candidates = 0 disables it entirely (emitters just gather on direct\n"
                        + " hit, same as with no NEE). Power-weighted sampling and the local per-section light\n"
                        + " grid are always active whenever RIS is on. min-fill-ratio drops emissive footprints\n"
                        + " below that fraction of their bounding rectangle (speckle/sparse crossed planes), so\n"
                        + " only reasonably compact glows become lights. stats/dump/dump-radius are debug logging.");
        FILE.setComment("volumetrics",
                " The world's ambient participating medium — the fog every path is inside, as opposed\n"
                        + " to the water and glass a path enters through geometry. density-scale multiplies\n"
                        + " the active dimension's density preset; the legacy-named intensity-scale is now\n"
                        + " a 0..1 multiplier over its physical scattering albedo. cull-distance bounds how far a segment keeps\n"
                        + " accumulating fog. scatter-tint is one of: neutral, warm, cool, green, violet.");
        FILE.setComment("bsdf",
                " Surface response. sun-mis weights the two ways the sun and moon are estimated —\n"
                        + " next-event estimation toward the light, and a continuation ray landing on it —\n"
                        + " against each other instead of summing them. Only materials smoother than\n"
                        + " roughness ~0.006 are affected; mirrors are untouched by construction.\n"
                        + " anisotropy stretches the specular highlight along the surface tangent for\n"
                        + " materials that author anisotropy.amount; everything else is unaffected.\n"
                        + " subsurface-solid-layer lets ordinary (SOLID-layer) blocks carry LabPBR\n"
                        + " subsurface; turn it off if a pack's _s alpha makes plain blocks look waxy.\n"
                        + " subsurface-mode is off | thin | random-walk. thin is the cheap surface\n"
                        + " approximation; random-walk actually walks the photon through the medium and\n"
                        + " costs one traversal per scattering event. subsurface-max-events bounds that\n"
                        + " walk — running out falls back to a diffuse bounce rather than losing energy.");
        FILE.setComment("water",
                " Enclosed participating media. Scattering and optional absorption overrides each have\n"
                        + " a strength (the arithmetic-mean coefficient per block) and an RGB colour shape;\n"
                        + " changing colour does not change strength. phase-g is forward-scattering\n"
                        + " anisotropy: positive puts a halo around the sun seen from underwater.");
        FILE.setComment("hdr",
                " HDR display output (ST.2084/PQ). When enabled the swapchain is created in PQ automatically\n"
                        + " (falls back to SDR if the surface doesn't advertise it). paper-white-nits / peak-nits\n"
                        + " drive the scene-HDR -> display mapping.");
    }

    private static Path resolveConfigPath() {
        try {
            return Platform.paths().configDir().resolve("fluorite.toml");
        } catch (Throwable t) {
            // Reached when no loader is present — the JUnit suite constructs settings that way. Platform
            // throws rather than returning null precisely so this catch keeps working.
            return Path.of("config", "fluorite.toml");
        }
    }

    private static CommentedFileConfig loadFile(Path path) {
        CommentedFileConfig config = CommentedFileConfig.builder(path, TomlFormat.instance())
                .onFileNotFound(FileNotFoundAction.CREATE_EMPTY)
                .preserveInsertionOrder()
                .sync()
                .build();
        try {
            config.load();
        } catch (Exception e) {
            LOGGER.warn("Failed to read Fluorite config {}: {}", path, e.toString());
        }
        return config;
    }

    private static Boolean fileBoolean(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Boolean>get(tomlPath) : null;
    }

    private static Number fileNumber(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<Number>get(tomlPath) : null;
    }

    private static String fileString(String tomlPath) {
        return FILE.contains(tomlPath) ? FILE.<String>get(tomlPath) : null;
    }

    /**
     * Put every setting back to the value the code ships with.
     *
     * <p>Generic over the whole registry rather than a method on each setting class, because the
     * interface already exposes both halves of the operation. A {@code -Dfluorite.*} override is
     * deliberately NOT consulted: this is the button a player presses after an evening of tuning, and it
     * should land somewhere predictable rather than somewhere that depends on how the game was launched.
     */
    public static void resetAllToDefaults() {
        for (RuntimeSetting<?> setting : SETTINGS) {
            resetOne(setting);
        }
    }

    private static <T> void resetOne(RuntimeSetting<T> setting) {
        setting.set(setting.defaultValue());
    }

    public interface RuntimeSetting<T> {
        /** The {@code -Dfluorite.*} system property name that overrides this setting. */
        String key();

        /** The dotted path of this setting inside the nested {@code config/fluorite.toml} tables. */
        String tomlPath();

        T defaultValue();

        T get();

        void set(T value);

        void reloadFromSystemProperties();

        /** Writes this setting's current value into the given config at {@link #tomlPath()}. */
        void writeToFile(CommentedConfig config);
    }

    public static final class BooleanSetting implements RuntimeSetting<Boolean> {
        private final String key;
        private final String tomlPath;
        private final boolean defaultValue;
        private volatile boolean value;

        private BooleanSetting(String key, String tomlPath, boolean defaultValue) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = defaultValue;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Boolean defaultValue() {
            return defaultValue;
        }

        @Override
        public Boolean get() {
            return value;
        }

        public boolean value() {
            return value;
        }

        @Override
        public void set(Boolean value) {
            this.value = value != null ? value : defaultValue;
        }

        @Override
        public void reloadFromSystemProperties() {
            set(Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue))));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private boolean resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return Boolean.parseBoolean(prop.trim());
            }
            Boolean fromFile = fileBoolean(tomlPath);
            return fromFile != null ? fromFile : defaultValue;
        }
    }

    public static final class IntSetting implements RuntimeSetting<Integer> {
        private final String key;
        private final String tomlPath;
        private final int defaultValue;
        private final IntUnaryOperator sanitize;
        private volatile int value;

        private IntSetting(String key, String tomlPath, int defaultValue, IntUnaryOperator sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.applyAsInt(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Integer defaultValue() {
            return defaultValue;
        }

        @Override
        public Integer get() {
            return value;
        }

        public int value() {
            return value;
        }

        @Override
        public void set(Integer value) {
            this.value = sanitize.applyAsInt(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = sanitize.applyAsInt(Integer.parseInt(prop.trim()));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private int resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return sanitize.applyAsInt(Integer.parseInt(prop.trim()));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            return fromFile != null ? sanitize.applyAsInt(fromFile.intValue()) : defaultValue;
        }
    }

    public static final class FloatSetting implements RuntimeSetting<Float> {
        private final String key;
        private final String tomlPath;
        private final float defaultValue;
        // Maps a raw external number (system property, file, or the constructor's raw default) into the
        // stored value domain, e.g. degrees -> radians.
        private final DoubleUnaryOperator inputTransform;
        // Inverse of inputTransform: maps the stored value domain back to the raw external domain (e.g.
        // radians -> degrees) for writeToFile, so a value round-trips through the file unchanged instead
        // of having inputTransform re-applied to an already-transformed number on the next load.
        private final DoubleUnaryOperator outputTransform;
        // Idempotent guard on a value-domain number (clamp / finite check); safe to apply to any source.
        private final DoubleUnaryOperator valueClamp;
        private volatile float value;

        private FloatSetting(String key, String tomlPath, float rawDefault, DoubleUnaryOperator inputTransform,
                             DoubleUnaryOperator outputTransform, DoubleUnaryOperator valueClamp) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.inputTransform = inputTransform;
            this.outputTransform = outputTransform;
            this.valueClamp = valueClamp;
            this.defaultValue = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(rawDefault));
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public Float defaultValue() {
            return defaultValue;
        }

        @Override
        public Float get() {
            return value;
        }

        public float value() {
            return value;
        }

        @Override
        public void set(Float value) {
            if (value == null) {
                this.value = defaultValue;
            } else {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(value));
            }
        }

        @Override
        public void reloadFromSystemProperties() {
            String prop = System.getProperty(key);
            if (prop == null) {
                this.value = defaultValue;
                return;
            }
            try {
                this.value = (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
            } catch (NumberFormatException e) {
                this.value = defaultValue;
            }
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            // Round-trip through Float.toString() so the file gets the shortest decimal that reproduces
            // this float (e.g. "0.6"), not outputTransform's raw double with float's binary noise spelled
            // out to 17 digits (e.g. 0.6000000487130328).
            float raw = (float) outputTransform.applyAsDouble(value);
            config.set(tomlPath, Double.parseDouble(Float.toString(raw)));
        }

        private float resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                try {
                    return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(Double.parseDouble(prop.trim())));
                } catch (NumberFormatException e) {
                    return defaultValue;
                }
            }
            Number fromFile = fileNumber(tomlPath);
            if (fromFile == null) {
                return defaultValue;
            }
            return (float) valueClamp.applyAsDouble(inputTransform.applyAsDouble(fromFile.doubleValue()));
        }
    }

    public static final class StringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private final String defaultValue;
        private final UnaryOperator<String> sanitize;
        private volatile String value;

        private StringSetting(String key, String tomlPath, String defaultValue, UnaryOperator<String> sanitize) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.defaultValue = sanitize.apply(defaultValue);
            this.sanitize = sanitize;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return defaultValue;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = sanitize.apply(value != null ? value : defaultValue);
        }

        @Override
        public void reloadFromSystemProperties() {
            set(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            if (prop != null) {
                return sanitize.apply(prop);
            }
            String fromFile = fileString(tomlPath);
            return sanitize.apply(fromFile != null ? fromFile : defaultValue);
        }
    }

    public static final class OptionalStringSetting implements RuntimeSetting<String> {
        private final String key;
        private final String tomlPath;
        private volatile String value;

        private OptionalStringSetting(String key, String tomlPath) {
            this.key = key;
            this.tomlPath = tomlPath;
            this.value = resolveInitial();
            SETTINGS.add(this);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public String tomlPath() {
            return tomlPath;
        }

        @Override
        public String defaultValue() {
            return null;
        }

        @Override
        public String get() {
            return value;
        }

        @Override
        public void set(String value) {
            this.value = value;
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = System.getProperty(key);
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            if (value != null) {
                config.set(tomlPath, value);
            } else {
                config.remove(tomlPath);
            }
        }

        private String resolveInitial() {
            String prop = System.getProperty(key);
            return prop != null ? prop : fileString(tomlPath);
        }
    }

    public static final class Rt {
        public static final BooleanSetting ENABLED = bool("fluorite.rt", "enabled", true);
        public static final IntSetting WORKER_THREADS =
                intAtLeast("fluorite.rt.workerThreads", "worker-threads", defaultWorkerThreads(), 1);

        private Rt() {
        }

        public static final class Composite {
            public static final IntSetting DEBUG_VIEW = intValue("fluorite.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("fluorite.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("fluorite.rt.maxBounces", "composite.max-bounces", 4, 2, 8);
            public static final BooleanSetting WATER_WAVES =
                    bool("fluorite.rt.waterWaves", "composite.water-waves", true);
            public static final FloatSetting SUN_ANGULAR_RADIUS =
                    radians("fluorite.rt.sunAngularRadius", "composite.sun-angular-radius-deg", 0.6f);
            public static final FloatSetting MOON_ANGULAR_RADIUS =
                    radians("fluorite.rt.moonAngularRadius", "composite.moon-angular-radius-deg", 1.5f);
            public static final FloatSetting SUN_NOON_SOUTH_TILT =
                    radians("fluorite.rt.sunNoonSouthDeg", "composite.sun-noon-south-tilt-deg", 30.0f);
            public static final FloatSetting JITTER_SIGN_X =
                    finiteFloat("fluorite.rt.jitterSignX", "composite.jitter-sign-x", 1.0f);
            public static final FloatSetting JITTER_SIGN_Y =
                    finiteFloat("fluorite.rt.jitterSignY", "composite.jitter-sign-y", -1.0f);

            private Composite() {
            }
        }

        public static final class Terrain {
            // External keys retain their historical "per-tick" names for config compatibility; terrain
            // streaming is render-pass driven and these Java names reflect the actual scheduling unit.
            public static final IntSetting ASYNC_DISPATCH_PER_PASS =
                    intAtLeast("fluorite.rt.asyncDispatchPerTick", "terrain.async-dispatch-per-tick", 32, 0);
            public static final IntSetting COMPLETION_RESULTS_PER_PASS =
                    intAtLeast("fluorite.rt.sectionResultsPerTick", "terrain.section-results-per-tick", 32, 0);
            public static final IntSetting MAX_INFLIGHT_SECTIONS =
                    intAtLeast("fluorite.rt.maxInflightSections", "terrain.max-inflight-sections", 32, 0);
            public static final IntSetting SECTION_TABLE_INITIAL_CAPACITY =
                    intAtLeast("fluorite.rt.sectionTableInitialCapacity", "terrain.section-table-initial-capacity", 512, 1);
            public static final IntSetting REBASE_DISTANCE_BLOCKS =
                    intAtLeast("fluorite.rt.rebaseDistanceBlocks", "terrain.rebase-distance-blocks", 128, 0);
            public static final BooleanSetting BLAS_COMPACTION =
                    bool("fluorite.rt.blasCompaction", "terrain.blas-compaction", true);

            private Terrain() {
            }
        }

        /** RIS block-emitter lights. {@code ris-candidates = 0} disables everything. */
        /**
         * The world's ambient participating medium: the fog every path is inside at all times, as opposed
         * to the water and glass a path enters through geometry.
         *
         * <p>These are global multipliers over whatever the active dimension asks for, not the values
         * themselves. Per-dimension character belongs in a preset; what belongs here is the player's
         * ability to want more or less of it than the preset chose.
         */
        public static final class Volumetrics {
            public static final BooleanSetting ENABLED =
                    bool("fluorite.rt.fog.enabled", "volumetrics.enabled", true);

            /** Exponential height fog: the analytic term, evaluated per segment with no marching. */
            public static final BooleanSetting HEIGHT_FOG =
                    bool("fluorite.rt.fog.heightFog", "volumetrics.height-fog", true);

            /** Scales the preset's density. 1 is the preset as authored. */
            public static final FloatSetting DENSITY_SCALE =
                    clampedFloat("fluorite.rt.fog.densityScale", "volumetrics.density-scale", 1.0f, 0.0f, 10.0f);

            /**
             * Multiplies the preset's single-scattering albedo. The external keys retain their legacy
             * intensity name so existing configs migrate by clamping instead of silently resetting.
             */
            public static final FloatSetting ALBEDO_SCALE =
                    clampedFloat("fluorite.rt.fog.intensityScale", "volumetrics.intensity-scale", 1.0f, 0.0f, 1.0f);

            /**
             * Blocks in front of the eye that stay clear. Fog starts accumulating past this, which keeps
             * the near field readable instead of veiling the whole screen uniformly.
             */
            public static final FloatSetting START_DISTANCE =
                    clampedFloat("fluorite.rt.fog.startDistance", "volumetrics.start-distance", 16.0f, 0.0f, 512.0f);

            /** Blocks beyond which a segment stops accumulating fog. Distant terrain stays readable. */
            public static final FloatSetting CULL_DISTANCE =
                    clampedFloat("fluorite.rt.fog.cullDistance", "volumetrics.cull-distance", 512.0f, 16.0f, 4096.0f);

            /** Height in blocks over which the density falls by a factor of e. */
            public static final FloatSetting HEIGHT_SCALE =
                    clampedFloat("fluorite.rt.fog.heightScale", "volumetrics.height-scale", 48.0f, 1.0f, 512.0f);

            /**
              * World height the layer sits at. The density holds steady at and below this, and falls off
              * above it with HEIGHT_SCALE, so this is what moves the fog rather than reshaping it.
              */
            public static final FloatSetting HEIGHT_BASE =
                    clampedFloat("fluorite.rt.fog.heightBase", "volumetrics.height-base", 62.0f, -64.0f, 320.0f);

            /**
             * Scattering tint, as a named hue. Not a colour picker: the settings UI has no colour control,
             * and an exact RGB belongs in the dimension preset rather than in a per-player override.
             */
            public static final StringSetting SCATTER_TINT =
                    string("fluorite.rt.fog.scatterTint", "volumetrics.scatter-tint", "neutral",
                            Volumetrics::sanitizeTint);

            /**
             * Forward-scattering anisotropy of the sun lobe. Positive values put a glow around the sun,
             * which is most of what makes fog read as lit rather than as a grey wash.
             */
            public static final FloatSetting PHASE_G =
                    clampedFloat("fluorite.rt.fog.phaseG", "volumetrics.phase-g", 0.55f, -0.9f, 0.9f);

            /**
             * Which segments of a path are allowed to add fog in-scatter: {@code both}, {@code froxel},
             * {@code marched}, {@code none}.
             *
             * <p>A measurement switch, like {@link Water#SCATTER_SOURCE}, and it exists because the fog's
             * in-scatter now comes from two machines with two different occlusion models. The camera's
             * prefix segment reads the froxel, which resolves sun and sky visibility per world-space cell.
             * Every other segment — every bounce — still runs the closed form in {@code integrateSegment},
             * which has no occlusion at all. The two are added together on screen, so "the fog indoors is
             * too bright" cannot be attributed to either by looking at it.
             *
             * <p>{@code froxel} silences the marched half and {@code marched} silences the froxel half.
             * Neither touches extinction, so what a segment hides stays exactly as it was and only what it
             * emits moves — the same separation of "too bright" from "too opaque" the water switch makes.
             */
            public static final StringSetting SEGMENT_SOURCE =
                    string("fluorite.rt.fog.segmentSource", "volumetrics.segment-source", "both",
                            Volumetrics::sanitizeSegmentSource);

            private static String sanitizeSegmentSource(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return switch (v) {
                    case "froxel", "marched", "none" -> v;
                    default -> "both";
                };
            }

            /**
             * Cell size of the volumetric visibility grid, in blocks. 0 turns the grid off entirely and
             * restores the unshadowed fog exactly.
             *
             * <p>The grid's CELL COUNT is fixed (it is a texture's dimensions, 64x32x64), so this is the
             * one dial and it trades reach against leakage: at 1 block the grid reaches 32 blocks
             * horizontally and 16 vertically from the camera, at 2 blocks twice that in each axis. Beyond
             * it the fog falls back to unshadowed, faded in rather than cut, so the choice is where the
             * shadowing stops rather than whether it looks wrong somewhere.
             *
             * <p><b>One is the default, and the step from 2 to 1 is a change in kind rather than in
             * degree.</b> Trilinear filtering blends the eight nearest cells, so light leaks inward from
             * outside a wall unless a cell of its own sits in the way. At 2 blocks a one-block wall is
             * thinner than a cell and cannot hold one, so the interpolation runs straight from "inside the
             * room" to "outside the house" — measured exactly that way: a shell of single dirt blocks lit
             * the fog inside it, and doubling the wall to two blocks (one cell) stopped it dead.
             *
             * <p>At 1 block the lattice lands on block CENTRES rather than block corners, which is the
             * property that does the work: every solid block then holds a cell of its own, that cell's
             * rays leave from inside the block and hit its own faces, and it reads occluded. A sample
             * point in the room blends room cells and wall cells and can never reach an outside one. Even
             * cell sizes cannot have this property at all — their centres fall on block boundaries by
             * construction, which is the worst place to sample a one-block wall from.
             *
             * <p>Eight was measured and is useless, which is where this line of work started: that was the
             * coarse CPU sky-light grid, whose cells are taller than a house, so the cell holding a sealed
             * room also held the open sky above its roof and averaged to open.
             *
             * <p>Turning it off is not the same as turning fog scattering off: extinction is untouched, so
             * what the fog HIDES is identical either way and only the shadowing moves.
             */
            public static final FloatSetting VISIBILITY_CELL_SIZE =
                    clampedFloat("fluorite.rt.fog.visibilityCellSize", "volumetrics.visibility-cell-size",
                            1.0f, 0.0f, 16.0f);

            /**
             * Most sub-steps a marched segment may split its ambient in-scatter into, one visibility
             * sample each.
             *
             * <p>A CAP, not a count. The step length is the grid's cell size, because sampling a grid
             * finer than its cells tells you nothing it can represent and sampling it coarser throws away
             * resolution the rays already paid for. A short segment therefore takes as many steps as it
             * has cells to cross and stops; only a long one reaches this limit, and past it the steps
             * grow rather than the count.
             *
             * <p>This exists as a dial because it is the one candidate for soft light shafts that can be
             * separated from the other two by moving it. Grid resolution and the denoiser also blur a
             * shaft, and all three arguments are equally plausible written down — raising this and seeing
             * nothing change rules it out in one look, which no amount of reasoning about it does.
             */
            public static final IntSetting VISIBILITY_MAX_STEPS =
                    intValue("fluorite.rt.fog.visibilityMaxSteps", "volumetrics.visibility-max-steps", 6);

            /**
             * Jittered shadow rays the fog's SUN term gets per marched segment. 0 keeps it on the
             * visibility grid.
             *
             * <p>The grid cannot carry a light shaft and this is measured, not assumed: debug view 19
             * plots sun visibility along a scanline from the grid and from a ray at the same points, and
             * indoors the grid reaches 0 in shadow but only 0.5 where the truth is 1, with a ramp where
             * the truth is a step. Trilinear blends the eight nearest cells and every indoor sample is
             * within one cell of a wall, so the lit peak is averaged down whatever the cell size.
             *
             * <p>The SKY term stays on the grid regardless. It is genuinely low frequency — a room is
             * dark, a hillside is not — so it needs no edge, and it is 0.072 ms for the whole field.
             * Splitting the two by their frequency content is the point.
             *
             * <p><b>Default 1, and both halves of that are measured.</b> Cost, from flipping this knob at
             * a fixed camera position inside one session: 2 rays cost 2.0 +/- 0.2 ms of
             * {@code gpu.traceIndirect} (17.5 against 15.5, +13%), and the reading is trustworthy because
             * the third plateau returned to the first within 0.2 ms. Benefit: 1 and 2 are hard to tell
             * apart, and both are visibly better than the grid. So 1 buys the whole visible difference for
             * about half the cost.
             *
             * <p>One sample per pixel per frame is not a compromise here, it is the regime this kind of
             * renderer is built for: the estimator is unbiased and DLSS-RR already accumulates temporally,
             * which is exactly how production path tracers shade volumetrics. The falsification test for
             * that claim is in sunInScatterStochastic and it has been run — with the denoiser off the
             * result is noise, not the blocky bias M9 measured.
             */
            public static final IntSetting SUN_SHADOW_RAYS =
                    intValue("fluorite.rt.fog.sunShadowRays", "volumetrics.sun-shadow-rays", 1);

            /**
             * Let light reaching a scattering point decay at the DIFFUSION rate instead of the beam's.
             *
             * <p>Fixes two symptoms that look unrelated and are one bug. Dense fog goes black instead of
             * turning into a bright formless glow, and deep water goes black instead of settling to a
             * water colour. Both come from single scattering treating every scattering event as a loss
             * to the source -- but scattering removes light from the BEAM, not from the MEDIUM.
             *
             * <p>The coefficient is the diffusion approximation's K, derived rather than fitted (see
             * volume.slang diffuseAttenuation). At the water's default turbidity that is about 0.39 times
             * the extinction, so the source reaches two and a half times deeper than the beam.
             *
             * <p>Off reproduces the previous behaviour exactly, which is what makes it an A/B rather than
             * a tuning dial.
             */
            public static final BooleanSetting MULTI_SCATTER =
                    bool("fluorite.rt.fog.multiScatter", "volumetrics.multi-scatter", true);

            /**
             * Sample ONE scattering event per segment instead of assuming the source is constant along it.
             *
             * <p><b>DEFAULT ON since the measurement (2026-08-05).</b> It was expected to cost something
             * and to be judged on whether the fix was worth it; it is instead <b>2.93 ms cheaper</b> at
             * bench-water-bottom (38.812 against 41.744, 0.930x, same batch, terrain-settled window).
             * The reason is that the estimator it replaces casts up to WATER_SUN_VIS_STRATA shadow rays
             * per water segment and this casts one — so the claim that the ray budget was unchanged,
             * which this comment used to make, was wrong for water and right only for fog at one ray.
             *
             * <p>Off remains the previous estimator exactly: it lives beside the closed forms and returns
             * before any of them, so flipping this back is the shipped picture rather than an
             * approximation of it. That is also the A/B lever for the noise trade below.
             *
             * <p>What a point buys that a segment cannot have is the ability to be ASKED things. How much
             * sky reaches THIS depth, and which emitters are nearby and where, are functions of position,
             * and a closed form over a whole segment has no position to offer them. That is why the water's
             * sky openness stops being one probe fired from the segment's start and gating everything after
             * it -- the artifact where a single block over a submerged camera zeroed the scattering across
             * the whole screen (D15).
             *
             * <p>The trade is variance. The stratified sun estimator this replaces keeps closed-form
             * weights per stratum and samples only the source inside each, so it is quieter per frame; a
             * single event with the exact f/pdf weight is a strict Monte Carlo estimator and noisier. Both
             * are correct in expectation, and with a constant unoccluded source they agree exactly -- which
             * is the identity to check first if the two ever disagree by more than noise.
             *
             * <p>Water's in-scatter, measured the same way: 10.49 ms of a 41.74 ms frame under the
             * stratified estimator, 7.56 ms under this one. That figure is M9's oldest outstanding debt
             * and had never been measured before this switch gave it a denominator.
             */
            public static final BooleanSetting SCATTER_VERTEX =
                    bool("fluorite.rt.fog.scatterVertex", "volumetrics.scatter-vertex", true);

            /**
             * Let block emitters light the fog and the water, not just the surfaces around them.
             *
             * <p>Lava, a torch and a campfire have never reached a participating medium in this renderer:
             * the closed forms carry the sun and the sky and nothing else, because a segment cannot ask
             * where an emitter is. So this requires {@link #SCATTER_VERTEX} — it samples ONE emitter at
             * that segment's sampled event, reusing the same power-weighted grid, alias tables and mixture
             * pdf the surface estimator uses, with the phase function standing in for the BRDF.
             *
             * <p>Deliberately clear of everything ReSTIR will rewrite: M is one, there is no reservoir, no
             * reuse, and no analytic MIS weight against emitter sampling. It does not touch the measured
             * hot path either. When ReSTIR lands it should change how this sample is CHOSEN and leave what
             * it is worth alone.
             *
             * <p><b>PARKED, and the number is why: +20.9 ms</b> at bench-water-bottom (59.707 against
             * 38.812 with the vertex alone, 1.538x, same batch). One shadow ray does not cost twenty
             * milliseconds, so the suspicion is the light grid's dependent-load chain rather than the ray
             * — lighting.slang records that walk at 5.9 ms of an 18 ms frame, and this runs it PER SEGMENT
             * where surface shading runs it per shading vertex, and a path has far more segments than
             * shading vertices.
             *
             * <p><b>That is a hypothesis, not a conclusion</b>, and the experiment that settles it is
             * cheap: silence the shadow ray and keep the grid lookup and pdf reconstruction. Still
             * expensive means the chain, and the fix is structural — once per path rather than once per
             * segment, or waiting for ReSTIR's presampled pool. Cheap means the ray, and the fix is
             * ordinary optimisation. Do not rebuild this on a guess about which.
             */
            public static final BooleanSetting VOLUME_EMITTER_NEE =
                    bool("fluorite.rt.fog.volumeEmitterNee", "volumetrics.emitter-nee", false);

            /** Bits 23-25 of worldPush.flags. */
            public static int sunShadowRays() {
                return Math.clamp(SUN_SHADOW_RAYS.value(), 0, 7);
            }

            /** Bits 18-22 of worldPush.flags. Clamped so a bad config cannot unroll a raygen loop. */
            public static int visibilityMaxSteps() {
                return Math.clamp(VISIBILITY_MAX_STEPS.value(), 1, 31);
            }

            /** Bits 16-17 of worldPush.flags: 0 both, 1 froxel only, 2 marched only, 3 neither. */
            public static int segmentSourceId() {
                return switch (SEGMENT_SOURCE.get()) {
                    case "froxel" -> 1;
                    case "marched" -> 2;
                    case "none" -> 3;
                    default -> 0;
                };
            }

            private static String sanitizeTint(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return switch (v) {
                    case "neutral", "warm", "cool", "green", "violet" -> v;
                    default -> "neutral";
                };
            }

            /** Linear RGB for the named tint. Kept beside the names so the two cannot drift. */
            public static float[] scatterTintRgb() {
                return switch (SCATTER_TINT.get()) {
                    case "warm" -> new float[] {1.00f, 0.86f, 0.68f};
                    case "cool" -> new float[] {0.72f, 0.82f, 1.00f};
                    case "green" -> new float[] {0.72f, 0.92f, 0.74f};
                    case "violet" -> new float[] {0.84f, 0.74f, 1.00f};
                    default -> new float[] {0.92f, 0.94f, 0.96f};
                };
            }

            private Volumetrics() {
            }
        }

        /** Surface response: which parts of the Disney model are active, and how they are estimated. */
        public static final class Bsdf {
            /**
             * Multiple importance sampling for the sun and moon.
             *
             * <p>The celestial light is estimated twice at every glossy vertex — once by next-event
             * estimation toward it, once by whatever the continuation lobe happens to hit — and without
             * a weight the two are summed, which counts it twice. It was held in check by an epsilon in
             * the GGX denominator that clamped every specular peak in the renderer; with this on, the
             * clamp is gone and each estimate is weighted by the density of the strategy that produced
             * it.
             *
             * <p>Only materials smoother than roughness ~0.006 change, since that is where a lobe gets
             * tight enough to compete with the light's own angular size. Mirrors are untouched by
             * construction: a delta lobe has no finite density, contributes no specular next-event term,
             * and keeps reflecting the drawn sprite exactly as before. Off restores the summation, for
             * comparing the two.
             */
            public static final BooleanSetting MIS_ENABLED =
                    bool("fluorite.rt.bsdf.sunMis", "bsdf.sun-mis", true);

            /**
             * The anisotropic specular lobe.
             *
             * <p>Stretches the highlight along the surface tangent, which is what brushed metal, satin
             * and hair look like. Costs nothing on a material that authored no {@code anisotropy}: the
             * lobe collapses to the isotropic one exactly, and the shading context takes an early
             * return rather than decoding a tangent.
             *
             * <p>Default on, unlike the original plan. That plan reasoned there was no authoring channel
             * for it, so the feature could only ever cost. There is one now — {@code anisotropy.amount}
             * in a material JSON — and a material that does not use it is unaffected either way, so the
             * switch exists to A/B the lobe rather than to keep it off.
             */
            public static final BooleanSetting ANISOTROPY_ENABLED =
                    bool("fluorite.rt.bsdf.anisotropy", "bsdf.anisotropy", true);

            /**
             * Let the SOLID terrain layer carry subsurface scattering.
             *
             * <p>Terrain extraction marks non-SOLID (cutout/translucent) quads, and the hit shader has
             * been using that marker to zero the LabPBR subsurface channel for everything else — so
             * quartz, smooth stone and every ordinary block were excluded. That is precisely where a
             * BSSRDF earns its cost: the materials people expect light to seep through are mostly SOLID.
             *
             * <p>On by default, but the switch is not ceremonial. LabPBR keeps subsurface in the alpha of
             * the {@code _s} texture, and a pack that never meant to use it can leave anything there;
             * opening this makes whatever they left visible as translucency on ordinary blocks. If a pack
             * suddenly looks waxy, this is the first thing to turn off.
             */
            /**
             * Modelled thickness of the thin-shell subsurface slab. 1 is exactly today's behaviour.
             *
             * <p><b>Added as a calibration instrument with an explicit end state to decide.</b> The thin
             * shell is the default subsurface path after M8 (the random walk is correct but lands at
             * 1.612x the trace budget against a 1.5x gate, so it stays opt-in), which makes its look
             * worth tuning by eye against the real renderer instead of by editing two constants and
             * rebuilding. When a value is settled, ONE of two things must happen deliberately:
             *
             * <ul>
             *   <li>fold it into SSS_STRENGTH and SSS_G in math.slang and delete this setting, its UI
             *       row and its lang keys — the default if it turns out one number suits everything; or
             *   <li>keep it as an art-direction control, in which case its home is the TOML / dimension
             *       preset surface rather than the video settings screen, alongside the other authored
             *       appearance values (see M14).
             * </ul>
             *
             * <p>What must not happen is neither: a knob left in the UI after its purpose is served is
             * how a settings screen fills with rows nobody can explain, and this one is doubly
             * misleading because it looks like a material property when it is really two hardcoded
             * constants wearing a disguise.
             *
             * <p>One number for two effects because thickness drives both, and separate sliders would
             * let someone build a slab that cannot exist — very thick yet still sharply forward
             * scattering, which reads as glowing through without diffusing. Thicker transmits less and
             * scatters more isotropically; thinner is brighter and more directional, like a petal.
             */
            public static final FloatSetting SUBSURFACE_THICKNESS =
                    clampedFloat("fluorite.rt.bsdf.subsurfaceThickness", "bsdf.subsurface-thickness",
                            1.0f, 0.05f, 5.0f);

            public static final BooleanSetting SUBSURFACE_SOLID_LAYER =
                    bool("fluorite.rt.bsdf.subsurfaceSolidLayer", "bsdf.subsurface-solid-layer", true);

            /**
             * How subsurface scattering is estimated: {@code off}, {@code thin}, or {@code random-walk}.
             *
             * <p>{@code thin} is the shipping approximation — one forward-biased phase term across the
             * surface, no interior at all. It is cheap, it looks right on a backlit leaf, and it has
             * nothing to say about a block with volume.
             *
             * <p>{@code random-walk} adds a real walk through the medium as a continuation lobe: the
             * photon enters, scatters until it finds a way out, and leaves somewhere else. That is what
             * gives quartz depth rather than a painted-on glow. It costs one traversal per scattering
             * event, which is why the budget below exists.
             *
             * <p>Default {@code thin}. The walk is opt-in until it has been measured on more than one
             * machine — see the note on Turing in the plan: the extension is exposed there but the
             * hardware behind it is not, so short incoherent rays cost what they say they cost.
             */
            public static final StringSetting SUBSURFACE_MODE =
                    string("fluorite.rt.bsdf.subsurfaceMode", "bsdf.subsurface-mode", "thin",
                            Bsdf::sanitizeSubsurfaceMode);

            /**
             * Scattering events one walk may take before it gives up.
             *
             * <p>This is the performance lever, and it is meant to be turned. Cost is very close to
             * linear in it, because each event is one traversal. Running out does NOT truncate the
             * energy — the path falls back to an ordinary diffuse bounce — so lowering it trades
             * accuracy inside thick material for time, rather than trading brightness for time.
             */
            public static final IntSetting SUBSURFACE_MAX_EVENTS =
                    clampedInt("fluorite.rt.bsdf.subsurfaceMaxEvents", "bsdf.subsurface-max-events", 4, 0, 7);

            private static String sanitizeSubsurfaceMode(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return switch (v) {
                    case "off", "thin", "random-walk" -> v;
                    default -> "thin";
                };
            }

            /** 0 off, 1 thin, 2 random walk — the encoding world.rgen unpacks from the flags word. */
            public static int subsurfaceModeId() {
                return switch (SUBSURFACE_MODE.get()) {
                    case "off" -> 0;
                    case "random-walk" -> 2;
                    default -> 1;
                };
            }

            private Bsdf() {
            }
        }

        /** Enclosed participating media: what water does besides absorb. */
        public static final class Water {
            /**
             * Which of the two sources feeds the water's single scattering: {@code both}, {@code sun},
             * {@code sky}, {@code none}.
             *
             * <p>A measurement switch, not a look. The scattering has two independent sources with
             * independent occlusion models — the sun through shadow rays, the sky through the sky-light
             * grid — and when the result misbehaves the first question is always which of them did it.
             * Before this switch existed there was no matching way to silence the sun, so every
             * investigation had to reason about the sum. Isolating a term is the difference between
             * measuring and guessing.
             *
             * <p>{@code none} is not redundant with turning scattering off through the coefficients:
             * this leaves sigma_s (and therefore the extinction, and therefore visibility) exactly as
             * it is, and removes only the in-scattered light. That separates "the water is too bright"
             * from "the water is too opaque".
             */
            public static final StringSetting SCATTER_SOURCE =
                    string("fluorite.rt.water.scatterSource", "water.scatter-source", "both",
                            Water::sanitizeScatterSource);

            private static String sanitizeScatterSource(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return switch (v) {
                    case "sun", "sky", "none" -> v;
                    default -> "both";
                };
            }

            /**
             * Apply the sun-visibility shadow rays' transmittance to the water's sun term, or take the
             * sun as unoccluded.
             *
             * <p>DEFAULT OFF, which is older than the current estimator and due a re-measure. The off
             * default was chosen when the strata were deterministic: one binary answer evaluated near
             * the camera and applied to the whole segment, so crossing any occlusion boundary toggled
             * the murk across the entire screen at once — measured by isolation, and the reason this
             * switch exists. The strata have since been jittered per frame from the path's own RNG (the
             * M13.3 shape; see enclosedSingleScatter), which turns that bias into zero-mean noise the
             * denoiser can converge — and the fog's sun term made the same move and ships with its
             * stochastic ray ON (Volumetrics.SUN_SHADOW_RAYS, default 1). The two media now differ only
             * by this default; M15.1's unified volume shadow sampler is where their estimators merge,
             * and re-deciding this default belongs to that measurement.
             *
             * <p>The rays are traced whether or not this is on — the sun term's depth attenuation reads
             * their measured water column (sh.waterHitT) either way — so ON changes which answers are
             * USED, not which rays are cast.
             *
             * <p>Kept as a switch because it is the isolation tool that found the original artifact, and
             * the A/B lever for the re-measure.
             */
            public static final BooleanSetting SUN_SHADOW =
                    bool("fluorite.rt.water.sunShadow", "water.sun-shadow", false);

            /**
             * Chromatic spread of the caustic pattern, as a multiple of water's real dispersion.
             *
             * <p>1 is physical: water's index runs about 1.331 at 700 nm against 1.340 at 450 nm.
             *
             * <p>It was expected to be visible at 1 anyway, on the reasoning that it only has to matter
             * where {@code det J} approaches zero, so the fringes would appear along the bright
             * filaments where the eye already is. MEASURED, and that reasoning is wrong: at TWENTY times
             * physical, a debug view amplifying the inter-channel deviation twentyfold still showed
             * neutral grey — under one percent of channel separation. Colour only appears around 100x.
             *
             * <p>The likely reason is {@link io.github.dswepm.fluorite.rt.RtComposite} nothing and this
             * file nothing: it is CAUSTIC_MAX in water.slang. The filaments clamp, all three channels
             * clamp to the same 6, and the fringe can only show where the fold has moved far enough that
             * one channel clamps and another does not. That is a lot of spread.
             *
             * <p>So the default is an exaggeration, deliberately and by a large factor. 0 is monochrome.
             * Physically correct and invisible is a legitimate thing to overrule, but it should be
             * overruled knowingly rather than by a number that quietly means something else.
             *
             * <p>Nearly free: the expensive part of a caustic is evaluating the wave field, the wave
             * field does not know about colour, and all three wavelengths share the same three surface
             * normals. Only the refraction and the landing arithmetic run three times.
             */
            public static final FloatSetting CAUSTIC_DISPERSION =
                    clampedFloat("fluorite.rt.water.causticDispersion", "water.caustic-dispersion",
                            50.0f, 0.0f, 100.0f);

            /** Bits 13-14 of worldPush.flags: 0 both, 1 sun only, 2 sky only, 3 neither. */
            public static int scatterSourceId() {
                return switch (SCATTER_SOURCE.get()) {
                    case "sun" -> 1;
                    case "sky" -> 2;
                    case "none" -> 3;
                    default -> 0;
                };
            }

            /**
             * Forward-scattering anisotropy of the water's phase function.
             *
             * <p>Positive keeps light going the way it was already going, which is what puts a bright
             * halo around the sun seen from underwater. Water is strongly forward-scattering in reality;
             * 0 would be a fog of perfectly isotropic particles and looks flat.
             */
            public static final FloatSetting PHASE_G =
                    clampedFloat("fluorite.rt.water.phaseG", "water.phase-g", 0.75f, -0.9f, 0.9f);

            /**
             * Absorption dialled directly, in 0-255 per channel, instead of taken from the biome.
             *
             * <p>Absorption is the channel every visible property of water descends from — the colour,
             * how far you can see, and now the scattering albedo, which is sigma_s over sigma_t and so
             * inherits the tint from here. Being able to set it by hand is the base for art-directing
             * any of them, and the base for telling a wrong colour from a wrong formula.
             *
             * <p>Note it is INVERTED from what a water colour looks like: high red absorption means red
             * is removed fastest, so the water reads cyan. A swamp is high red and high blue; an ocean
             * is high red and high green.
             */
            public static final BooleanSetting ABSORB_OVERRIDE =
                    bool("fluorite.rt.water.absorbOverride", "water.absorb-override", false);
            public static final IntSetting ABSORB_R =
                    clampedInt("fluorite.rt.water.absorbR", "water.absorb-r", 60, 0, 255);
            public static final IntSetting ABSORB_G =
                    clampedInt("fluorite.rt.water.absorbG", "water.absorb-g", 42, 0, 255);
            public static final IntSetting ABSORB_B =
                    clampedInt("fluorite.rt.water.absorbB", "water.absorb-b", 12, 0, 255);

            /** Mean absorption coefficient range, per block. Individual colour-shaped channels may exceed it. */
            public static final float ABSORB_FULL_SCALE = 0.4f;
            public static final FloatSetting ABSORB_STRENGTH =
                    clampedFloat("fluorite.rt.water.absorbStrength", "water.absorb-strength",
                            legacyCoefficientStrength(60, 42, 12, ABSORB_FULL_SCALE),
                            0.0f, ABSORB_FULL_SCALE);

            public static float[] absorptionRgb() {
                return coefficientRgb(ABSORB_R.value(), ABSORB_G.value(), ABSORB_B.value(),
                        ABSORB_STRENGTH.value());
            }

            /**
             * Scattering, dialled directly in 0-255 per channel — the other half of the medium, and
             * authored independently of absorption rather than derived from it.
             *
             * <p>Absorption alone makes water a coloured filter: what survives is tinted, what does not
             * is gone. Everything that reads as water rather than as glass is the scattered part — the
             * murk, the shafts, the way depth closes in instead of merely darkening.
             *
             * <p>Very nearly neutral by default, which is both the physics and the point: absorption is
             * what varies strongly with wavelength, so the albedo {@code sigma_s/sigma_t} comes out
             * blue-weighted on its own without this having to be blue.
             *
             * <p>This used to be a single "turbidity" multiplier over a fixed neutral colour, and that
             * shape is the whole reason the water went pale. Raising a knob that only feeds sigma_s
             * raises the albedo toward 1, and the deep-water limit is albedo * source, so more turbid
             * could only ever mean whiter and brighter — never darker, which is the opposite of what
             * suspended matter does. Real particles scatter AND absorb; two coefficients let that be
             * said. Murky water is high in BOTH.
             *
             * <p>Zero reproduces the absorption-only water this renderer shipped with, exactly, and is
             * the A/B for everything here. Reference points at the default full scale: ~24 is clear
             * ocean; a pond or a swamp wants both this and the absorption several times higher.
             */
            public static final IntSetting SCATTER_R =
                    clampedInt("fluorite.rt.water.scatterR", "water.scatter-r", 24, 0, 255);
            public static final IntSetting SCATTER_G =
                    clampedInt("fluorite.rt.water.scatterG", "water.scatter-g", 26, 0, 255);
            public static final IntSetting SCATTER_B =
                    clampedInt("fluorite.rt.water.scatterB", "water.scatter-b", 28, 0, 255);

            /**
             * Mean scattering coefficient range, per block. Individual colour-shaped channels may exceed it.
             *
             * <p>Half of {@link #ABSORB_FULL_SCALE} deliberately. Water whose scattering rivals its
             * absorption has a single-scattering albedo near 1, and that is the pale-grey failure this
             * replaced; the ceiling being lower makes the well-behaved range the easy one to land in.
             */
            public static final float SCATTER_FULL_SCALE = 0.2f;
            public static final FloatSetting SCATTER_STRENGTH =
                    clampedFloat("fluorite.rt.water.scatterStrength", "water.scatter-strength",
                            legacyCoefficientStrength(24, 26, 28, SCATTER_FULL_SCALE),
                            0.0f, SCATTER_FULL_SCALE);

            static {
                // D17/9A: the retired value was a fraction of peak sun radiance. M16's source is an
                // actual phase-integrated sky-view radiance, so reusing the same key would give one
                // number incompatible units. The next normal options save persists this removal.
                FILE.remove("water.ambient-scale");
                // One-time compatibility path. Before D16 the RGB sliders were coefficients, so their
                // arithmetic mean was also their hidden strength. If the new scalar is absent, recover
                // that mean from the already-resolved legacy values. The next save writes the scalar;
                // reset-to-default still uses the authored defaults above rather than this migrated value.
                if (System.getProperty("fluorite.rt.water.absorbStrength") == null
                        && !FILE.contains("water.absorb-strength")) {
                    ABSORB_STRENGTH.set(legacyCoefficientStrength(
                            ABSORB_R.value(), ABSORB_G.value(), ABSORB_B.value(), ABSORB_FULL_SCALE));
                }
                if (System.getProperty("fluorite.rt.water.scatterStrength") == null
                        && !FILE.contains("water.scatter-strength")) {
                    SCATTER_STRENGTH.set(legacyCoefficientStrength(
                            SCATTER_R.value(), SCATTER_G.value(), SCATTER_B.value(), SCATTER_FULL_SCALE));
                }
            }

            public static float[] scatteringRgb() {
                return coefficientRgb(SCATTER_R.value(), SCATTER_G.value(), SCATTER_B.value(),
                        SCATTER_STRENGTH.value());
            }

            private static float legacyCoefficientStrength(int r, int g, int b, float fullScale) {
                return ((r + g + b) / 3.0f) / 255.0f * fullScale;
            }

            /**
             * Resolve a coefficient from an RGB shape and its independent arithmetic-mean strength.
             * An all-zero shape has no chromaticity, so it resolves to neutral rather than secretly
             * disabling a non-zero strength; strength zero is the one unambiguous off control.
             */
            static float[] coefficientRgb(int r, int g, int b, float strength) {
                float cr = Math.clamp(r, 0, 255);
                float cg = Math.clamp(g, 0, 255);
                float cb = Math.clamp(b, 0, 255);
                float mean = (cr + cg + cb) / 3.0f;
                float safeStrength = Math.max(strength, 0.0f);
                if (mean <= 1.0e-6f) {
                    return new float[] {safeStrength, safeStrength, safeStrength};
                }
                float scale = safeStrength / mean;
                return new float[] {cr * scale, cg * scale, cb * scale};
            }

            private Water() {
            }
        }

        public static final class Lights {
            public static final IntSetting RIS_CANDIDATES =
                    intAtLeast("fluorite.rt.risCandidates", "lights.ris-candidates", 8, 0);
            public static final FloatSetting MIN_FILL_RATIO =
                    finiteFloat("fluorite.rt.lightMinFillRatio", "lights.min-fill-ratio", 0.25f);
            public static final BooleanSetting STATS = bool("fluorite.rt.lightStats", "lights.stats", false);
            public static final BooleanSetting DUMP = bool("fluorite.rt.lightDump", "lights.dump", false);
            public static final IntSetting DUMP_RADIUS =
                    intAtLeast("fluorite.rt.lightDumpRadius", "lights.dump-radius", 12, 1);

            private Lights() {
            }
        }

        public static final class Omm {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.omm", "omm.enabled", true);

            /** Enable opacity micromaps even when the device looks like it would emulate them in software.
             * The extension is advertised well below the NVIDIA generation that has the engine for it, and
             * on those parts the driver's software path stalls acceleration-structure builds for seconds —
             * long enough to trip Minecraft's 5s semaphore wait and kill the client. Bring-up therefore
             * skips OMM unless the device reports a real SER reordering hint; set this to override that
             * for a device known to be fine. */
            public static final BooleanSetting FORCE = bool("fluorite.rt.omm.force", "omm.force", false);
            public static final IntSetting SUBDIVISION =
                    clampedInt("fluorite.rt.ommSubdivision", "omm.subdivision", 4, 0, 6);
            public static final BooleanSetting STATS = bool("fluorite.rt.ommStats", "omm.stats", false);

            private Omm() {
            }
        }

        public static final class Entities {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.entities", "entities.enabled", true);
            public static final BooleanSetting PARTICLES_ENABLED =
                    bool("fluorite.rt.particles", "particles.enabled", true);
            public static final BooleanSetting GLOW_ENABLED =
                    bool("fluorite.rt.glow", "entities.glow.enabled", true);
            public static final BooleanSetting NAME_TAGS_ENABLED =
                    bool("fluorite.rt.nameTags", "entities.name-tags.enabled", true);
            /** Debug-only: render each model submission twice and require bitwise-identical CPU captures. */
            public static final BooleanSetting CAPTURE_PARITY =
                    bool("fluorite.rt.entityCaptureParity", "entities.debug.capture-parity", false);
            public static final IntSetting MAX_ORDINARY_ENTITIES =
                    intAtLeast("fluorite.rt.maxOrdinaryEntities", "entities.max-ordinary-entities", 1024, 0);
            public static final IntSetting MAX_BLOCK_ENTITIES =
                    intAtLeast("fluorite.rt.maxBlockEntities", "entities.block-entities.max-entities", 1024, 0);
            public static final IntSetting MAX_PARTICLES =
                    intAtLeast("fluorite.rt.maxParticles", "particles.max-particles", 1024, 0);
            public static final IntSetting BE_VIEW_CHUNKS =
                    intAtLeast("fluorite.rt.beViewChunks", "entities.block-entities.view-chunks", 8, 0);
            public static final IntSetting BE_BUILDS_PER_FRAME =
                    intAtLeast("fluorite.rt.beBuildsPerFrame", "entities.block-entities.builds-per-frame", 64, 0);
            public static final BooleanSetting REFIT_ENABLED =
                    bool("fluorite.rt.entityRefit", "entities.refit.enabled", true);

            private Entities() {
            }

            public static int maxEntities() {
                return Math.addExact(Math.addExact(
                        MAX_ORDINARY_ENTITIES.value(), MAX_BLOCK_ENTITIES.value()), MAX_PARTICLES.value());
            }

            public static int entityListCapacity() {
                return Math.max(16, maxEntities());
            }

            public static int entityMapCapacity() {
                // Fastutil expected-size constructors apply their own load-factor headroom.
                return Math.max(16, MAX_ORDINARY_ENTITIES.value());
            }
        }

        public static final class EntityTextures {
            public static final IntSetting MAX_TEXTURES =
                    intAtLeast("fluorite.rt.maxEntityTextures", "entities.textures.max-textures", 256, 1);
            public static final BooleanSetting PBR = bool("fluorite.rt.entityPbr", "entities.textures.pbr", true);

            private EntityTextures() {
            }
        }

        public static final class Overlay {
            public static final BooleanSetting BLOCK_OUTLINE_ENABLED =
                    bool("fluorite.rt.blockOutline", "overlay.block-outline.enabled", true);

            private Overlay() {
            }
        }

        public static final class DlssRr {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.dlssRr", "dlss-rr.enabled", true);
            public static final IntSetting PRESET = intValue("fluorite.rt.dlssRr.preset", "dlss-rr.preset", 0);
            public static final IntSetting QUALITY = intValue("fluorite.rt.dlssRr.quality", "dlss-rr.quality", 0);

            private DlssRr() {
            }
        }

        /** DLSS Frame Generation. Default off; gated additionally by hardware/driver availability. */
        public static final class Fg {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.fg", "frame-generation.enabled", false);
            public static final IntSetting MULTI_FRAME_COUNT =
                    intAtLeast("fluorite.rt.fg.multiFrameCount", "frame-generation.multi-frame-count", 1, 1);

            private Fg() {
            }
        }

        /**
         * NVIDIA Reflex ({@code VK_NV_low_latency2}). Default off; gated additionally by device support.
         * Phase 0 (extension + capability probe only, see {@code RtDeviceBringup}/{@code RtReflex}) — the
         * per-frame sleep call + latency markers + the swapchain {@code VkSwapchainLatencyCreateInfoNV} the
         * spec requires for {@code vkSetLatencySleepModeNV} to take effect land in a later phase.
         */
        public static final class Reflex {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.reflex", "reflex.enabled", false);
            public static final BooleanSetting LOW_LATENCY_BOOST =
                    bool("fluorite.rt.reflex.boost", "reflex.low-latency-boost", false);
            public static final IntSetting MINIMUM_INTERVAL_US =
                    intAtLeast("fluorite.rt.reflex.minIntervalUs", "reflex.minimum-interval-us", 0, 0);

            private Reflex() {
            }
        }

        public static final class Exposure {
            public static final StringSetting MODE =
                    string("fluorite.rt.exposure.mode", "exposure.mode", "auto", Exposure::sanitizeMode);
            public static final FloatSetting MANUAL_EV =
                    finiteFloat("fluorite.rt.exposure.manualEv", "exposure.manual-ev", 0.0f);
            public static final FloatSetting KEY = exposureScale("fluorite.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("fluorite.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("fluorite.rt.exposure.maxEv", "exposure.max-ev", 4.0f);
            public static final FloatSetting ADAPT_UP =
                    exposureScale("fluorite.rt.exposure.adaptUp", "exposure.adapt-up", 0.12f);
            public static final FloatSetting ADAPT_DOWN =
                    exposureScale("fluorite.rt.exposure.adaptDown", "exposure.adapt-down", 0.35f);

            private Exposure() {
            }

            public static float minEv() {
                return Math.min(MIN_EV.value(), MAX_EV.value());
            }

            public static float maxEv() {
                return Math.max(MIN_EV.value(), MAX_EV.value());
            }

            public static float clampScale(float value) {
                return Math.clamp(value, 1.0e-4f, 1.0e4f);
            }

            private static String sanitizeMode(String value) {
                if ("auto".equalsIgnoreCase(value)) {
                    return "auto";
                }
                if ("manual".equalsIgnoreCase(value)) {
                    return "manual";
                }
                return "auto";
            }
        }

        /** Render-frame timing + hitch logging. See {@code RtFrameStats}. */
        public static final class FrameStats {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.frameStats", "frame-stats.enabled", false);

            private FrameStats() {
            }
        }

        /**
         * Art direction over the celestial light and the sky, on top of the physics rather than instead of
         * it. <b>Every default here is the identity</b> — a fresh install renders exactly what the
         * atmosphere model says, and any deviation is something a person chose.
         *
         * <p>Intensity and temperature are separate knobs on purpose: the temperature tint is normalised
         * to unit luminance, so turning the sun warmer does not also turn it dimmer, and the two can be
         * dialled without fighting each other.
         *
         * <p>Sun and sky are also separate. Physically one temperature governs both, but the two are
         * being tuned against different things — the sun against how lit surfaces read, the sky against
         * how the backdrop reads — and a single control would make every adjustment to one a regression
         * in the other.
         */
        public static final class Sky {
            /** Multiplies the celestial light: sun/moon NEE, and the water and fog ambient derived from it. */
            public static final FloatSetting SUN_INTENSITY =
                    clampedFloat("fluorite.rt.sunIntensity", "sky.sun-intensity", 1.0f, 0.0f, 8.0f);
            /** Colour temperature of that light in kelvin. 0 leaves the atmosphere's own colour alone. */
            public static final IntSetting SUN_TEMPERATURE =
                    clampedInt("fluorite.rt.sunTemperature", "sky.sun-temperature", 0, 0, 20000);
            /** Multiplies the sky's in-scatter. Applied in the sky-view bake, so it costs nothing per pixel. */
            public static final FloatSetting SKY_INTENSITY =
                    clampedFloat("fluorite.rt.skyIntensity", "sky.sky-intensity", 1.0f, 0.0f, 8.0f);
            /** Colour temperature of the sky in kelvin. 0 leaves it alone. */
            public static final IntSetting SKY_TEMPERATURE =
                    clampedInt("fluorite.rt.skyTemperature", "sky.sky-temperature", 0, 0, 20000);

            private Sky() {
            }

            /** The sun's tint: unit-luminance blackbody colour, or white when the knob is off. */
            public static float[] sunTint() {
                return blackbodyTint(SUN_TEMPERATURE.value());
            }

            /** The sky's tint, same convention. */
            public static float[] skyTint() {
                return blackbodyTint(SKY_TEMPERATURE.value());
            }

            /**
             * Linear sRGB for a blackbody at {@code kelvin}, normalised so its luminance is 1.
             *
             * <p>Via the Planckian locus in CIE xy and the standard D65 matrix rather than a hand-made
             * warm-to-cool ramp: a ramp would put "6500 K" wherever it was drawn, and the whole point of
             * naming the control in kelvin is that the number means something outside this file.
             *
             * <p>The normalisation is what keeps this orthogonal to {@link #SUN_INTENSITY}. Without it a
             * warm setting would also be a dim one, since a blackbody's blue channel falls off fastest,
             * and every temperature change would need an intensity change to undo the brightness it
             * brought with it.
             */
            public static float[] blackbodyTint(int kelvin) {
                if (kelvin <= 0) {
                    return new float[]{1.0f, 1.0f, 1.0f};
                }
                double t = Math.clamp(kelvin, 1667, 25000);
                double x;
                if (t < 4000.0) {
                    x = -0.2661239e9 / (t * t * t) - 0.2343589e6 / (t * t) + 0.8776956e3 / t + 0.179910;
                } else {
                    x = -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390;
                }
                double y;
                if (t < 2222.0) {
                    y = -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683;
                } else if (t < 4000.0) {
                    y = -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867;
                } else {
                    y = 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483;
                }
                if (y <= 1.0e-6) {
                    return new float[]{1.0f, 1.0f, 1.0f};
                }
                double bigX = x / y;
                double bigZ = (1.0 - x - y) / y;
                double r = 3.2406 * bigX - 1.5372 - 0.4986 * bigZ;
                double g = -0.9689 * bigX + 1.8758 + 0.0415 * bigZ;
                double b = 0.0557 * bigX - 0.2040 + 1.0570 * bigZ;
                r = Math.max(r, 0.0);
                g = Math.max(g, 0.0);
                b = Math.max(b, 0.0);
                double lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                if (lum <= 1.0e-6) {
                    return new float[]{1.0f, 1.0f, 1.0f};
                }
                return new float[]{(float) (r / lum), (float) (g / lum), (float) (b / lum)};
            }
        }

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {

            /** Low-rate centre-pixel GPU/CPU telemetry for water-medium streaming bugs. The probe adds one
             * upward ray per frame while enabled and logs only after an already-completed ring slot is
             * reused, so it does not introduce a readback stall. */
            public static final BooleanSetting WATER_MEDIUM_TRACE =
                    bool("fluorite.rt.waterMediumTrace", "diagnostics.water-medium-trace", false);

            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("fluorite.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            /** Content-hash every tessellated section to {@code <gameDir>/rt-terrain-digest/<loader>.txt}.
             * For comparing geometry extraction across builds and across mod loaders: the semantics that
             * do not survive a change of quad source — per-quad chunk layer above all — fail quietly and
             * put the wrong triangles in the wrong bucket rather than crashing. Off by default; costs one
             * hash per section build, and sections are built once and cached. */
            public static final BooleanSetting TERRAIN_DIGEST =
                    bool("fluorite.rt.terrainDigest", "diagnostics.terrain-digest", false);

            /** Sections to additionally dump triangle by triangle, as {@code "sx,sy,sz;sx,sy,sz"} in section
             * coordinates (block coordinates rounded down to 16). The digest says a section differs; it
             * cannot say which faces, and "NeoForge emitted two quads fewer here" is not something you can
             * read off a hash. Each named section gets its own file of sorted per-triangle centroids and
             * normals, so diffing two runs names the missing faces and where they are. Needs
             * {@code terrain-digest} on. Empty by default — one file per section per run, so name only the
             * sections a comparison already flagged. */
            public static final StringSetting TERRAIN_DIGEST_SECTIONS =
                    string("fluorite.rt.terrainDigestSections", "diagnostics.terrain-digest-sections", "",
                            String::trim);

            /** Block positions to log face-culling decisions for, as {@code "x,y,z;x,y,z"}. Culling is
             * shared code — {@code Block.shouldRenderFace} behind {@code RtTerrainMesher}'s cull predicate
             * — so the two loaders cannot decide a face differently. When they nonetheless disagree about
             * whether a face exists, this says so: matching verdicts here mean the quad sources bucket that
             * quad under different cullfaces, and the culling logic is not where the bug is. Empty by
             * default; the check is folded away entirely when nothing is named. */
            public static final StringSetting CULL_TRACE =
                    string("fluorite.rt.cullTrace", "diagnostics.cull-trace", "", String::trim);

            private Diagnostics() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * {@code paperWhiteNits}, and highlights roll off toward {@code peakNits}.
         */
        public static final class Hdr {
            public static final BooleanSetting ENABLED = bool("fluorite.rt.hdr", "hdr.enabled", false);
            public static final FloatSetting PAPER_WHITE_NITS =
                    clampedFloat("fluorite.rt.hdr.paperWhiteNits", "hdr.paper-white-nits", 200.0f, 80.0f, 500.0f);
            public static final FloatSetting PEAK_NITS =
                    clampedFloat("fluorite.rt.hdr.peakNits", "hdr.peak-nits", 1000.0f, 80.0f, 5000.0f);

            // Snapshot of ENABLED as resolved at startup (system property / config file), before any
            // in-session edit from the options screen. The swapchain's pixel format (PQ vs SDR) is fixed
            // at surface-creation time, so flipping ENABLED later cannot change what's actually presented
            // until a restart — every runtime/rendering check reads this frozen value via enabled(),
            // never ENABLED directly, so the live toggle is a no-op for the current session.
            private static final boolean ENABLED_AT_STARTUP = ENABLED.value();

            private Hdr() {
            }

            /** Whether the HDR display path (world HDR + PQ swapchain + UI overlay) is active this session. */
            public static boolean enabled() {
                return ENABLED_AT_STARTUP;
            }

            /** Whether {@link #ENABLED} has been changed since startup and needs a restart to take effect. */
            public static boolean pendingRestart() {
                return ENABLED.value() != ENABLED_AT_STARTUP;
            }

            /** Absolute nits SDR paper white maps to in the PQ encode (ST.2084 is referenced to 10000 nits). */
            public static float paperWhiteNits() {
                return PAPER_WHITE_NITS.value();
            }

            /** Highlight headroom above paper white, in paper-white-referred units ({@code >= 1}). */
            public static float headroom() {
                return Math.max(1.0f, PEAK_NITS.value() / Math.max(1.0f, PAPER_WHITE_NITS.value()));
            }
        }
    }

    public static final class Ngx {
        public static final OptionalStringSetting PATH = optionalString("fluorite.ngx.path", "ngx.path");

        private Ngx() {
        }
    }

    private static BooleanSetting bool(String key, String tomlPath, boolean fallback) {
        return new BooleanSetting(key, tomlPath, fallback);
    }

    private static StringSetting string(String key, String tomlPath, String fallback, UnaryOperator<String> sanitize) {
        return new StringSetting(key, tomlPath, fallback, sanitize);
    }

    private static OptionalStringSetting optionalString(String key, String tomlPath) {
        return new OptionalStringSetting(key, tomlPath);
    }

    private static IntSetting intValue(String key, String tomlPath, int fallback) {
        return new IntSetting(key, tomlPath, fallback, v -> v);
    }

    private static IntSetting intAtLeast(String key, String tomlPath, int fallback, int min) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.max(min, v));
    }

    private static IntSetting clampedInt(String key, String tomlPath, int fallback, int min, int max) {
        return new IntSetting(key, tomlPath, fallback, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting finiteFloat(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Double.isFinite(v) ? v : fallback);
    }

    private static FloatSetting exposureScale(String key, String tomlPath, float fallback) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, 1.0e-4, 1.0e4));
    }

    private static FloatSetting clampedFloat(String key, String tomlPath, float fallback, float min, float max) {
        return new FloatSetting(key, tomlPath, fallback, v -> v, v -> v, v -> Math.clamp(v, min, max));
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
