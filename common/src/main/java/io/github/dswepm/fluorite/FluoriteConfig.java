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
            Rt.Bsdf.SUBSURFACE_MODE, Rt.Water.TURBIDITY,
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
                        + " to the water and glass a path enters through geometry. density-scale and\n"
                        + " intensity-scale are multipliers over the active dimension's preset rather\n"
                        + " than absolute values; cull-distance bounds how far a segment keeps\n"
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
                " Enclosed participating media. Absorption is what the biome tint has always driven;\n"
                        + " turbidity adds the scattered part, which is what separates water from a pane of\n"
                        + " coloured glass. 0 reproduces the absorption-only water exactly and is the A/B\n"
                        + " for the rest. phase-g is forward-scattering anisotropy: positive puts a halo\n"
                        + " around the sun seen from underwater.");
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

            /** Scales how brightly the fog scatters, without changing how much it occludes. */
            public static final FloatSetting INTENSITY_SCALE =
                    clampedFloat("fluorite.rt.fog.intensityScale", "volumetrics.intensity-scale", 1.0f, 0.0f, 10.0f);

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
             * How much water scatters, as a multiple of a clear-ocean reference.
             *
             * <p>Absorption alone makes water a coloured filter — what survives is tinted, what does not
             * is gone. Every property that reads as water rather than as glass is the scattered part: the
             * turbidity, the shafts, the way depth closes in milkily instead of merely darkening.
             *
             * <p>0 reproduces the absorption-only water this renderer shipped with, exactly, and is the
             * A/B for everything below. 1 is clear ocean; a pond or a swamp is higher.
             *
             * <p>One value for every water body, deliberately. Per-biome character lives in the
             * absorption, which the tint still drives, and keeping scattering global is what lets the
             * single-scattering albedo be recovered as sigma_s/sigma_t from data the wavefront record
             * already carries — so this milestone costs no memory at all.
             */
            public static final FloatSetting TURBIDITY =
                    clampedFloat("fluorite.rt.water.turbidity", "water.turbidity", 1.0f, 0.0f, 10.0f);

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
             * Scattering coefficient per block, before TURBIDITY scales it.
             *
             * <p>Roughly neutral with a slight blue lift: scattering in clean water is only weakly
             * wavelength-dependent, and the strong colour comes from absorption instead. Calibrated by
             * eye against the reference view rather than from a measurement, so it is a starting point.
             */
            public static float[] scatteringRgb() {
                float t = TURBIDITY.value();
                // k = sigma_s / sigma_a. The albedo is k/(1+k) and the extinction is sigma_a (1+k), so
                // one number moves both: 1 gives a modest 0.3 albedo and 1.4x extinction, 10 gives 0.8
                // and 5x — murky enough to lose the bottom, which is the thing turbidity is for.
                // Slightly blue-weighted, since scattering in water tilts that way while the strong
                // colour comes from absorption. Calibrated by eye against the reference view.
                return new float[] {0.35f * t, 0.40f * t, 0.48f * t};
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

        /** Startup Vulkan inventory + {@code VK_EXT_device_fault} reporting on device loss. See {@code VulkanDiagnostics}. */
        public static final class Diagnostics {
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
