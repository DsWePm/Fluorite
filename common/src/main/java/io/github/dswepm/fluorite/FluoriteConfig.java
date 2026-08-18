package io.github.dswepm.fluorite;

import io.github.dswepm.fluorite.rt.light.RtEmitterTint;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.core.file.FileNotFoundAction;
import com.electronwill.nightconfig.toml.TomlFormat;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
    /**
     * Values selected by a preset that are safe to persist now but cannot replace the objects currently
     * owned by the renderer. They deliberately live beside the central registry: every ordinary save
     * must preserve them until the next process start reads them from disk.
     */
    private static final Map<String, Object> PENDING_RESTART_EXTERNAL = new ConcurrentHashMap<>();

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
            Rt.Hdr.ENABLED, Rt.PostProcessing.OUTPUT_TRANSFORM, Rt.PostProcessing.COLOR_GRADING_ENABLED,
            Ngx.PATH, Rt.Volumetrics.ENABLED,
            Rt.Dimensions.NETHER_FOG_ENABLED, Rt.Dimensions.NETHER_AMBIENT_SCALE,
            Rt.Dimensions.END_ENVIRONMENT_SCALE, Rt.Dimensions.END_DISK_SCALE,
            Rt.Dimensions.END_DISK_OUTER_RADIUS, Rt.Dimensions.END_DISK_THICKNESS,
            Rt.Dimensions.END_ENVIRONMENT_ROTATION_SPEED,
            Rt.Bsdf.MIS_ENABLED, Rt.Bsdf.EMITTER_MIS_ENABLED,
            Rt.Bsdf.ANISOTROPY_ENABLED, Rt.Bsdf.SUBSURFACE_SOLID_LAYER,
            Rt.Bsdf.SUBSURFACE_MODE, Rt.Water.ABSORB_OVERRIDE,
            Rt.Water.SCATTER_R, Rt.Weather.RAIN_SURFACES_ENABLED,
            Rt.Weather.RAIN_EXPOSURE_QUALITY, Rt.Weather.RAIN_PARTICLES_ENABLED,
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
        writeSettingsSnapshot(FILE);
        FILE.save();
    }

    static void writeSettingsSnapshot(CommentedConfig config) {
        writeComments(config);
        for (RuntimeSetting<?> setting : SETTINGS) {
            setting.writeToFile(config);
        }
        PENDING_RESTART_EXTERNAL.forEach(config::set);
    }

    static void writeComments(CommentedConfig config) {
        config.setComment("enabled",
                " Fluorite RT renderer configuration.\n"
                        + " A matching -Dfluorite.* system property overrides the value below.");
        config.setComment("terrain",
                " Render-thread terrain work is bounded by dispatch/result counts per streaming pass.\n"
                        + " Buffer fill and BLAS/OMM preparation run on workers. max-inflight-sections bounds\n"
                        + " the complete snapshot -> worker -> GPU build -> publication lifecycle.");
        config.setComment("frame-generation",
                " DLSS Frame Generation. Default off; gated additionally by hardware/driver availability.\n"
                        + " multi-frame-count: frames generated per rendered frame (1 = 2x, 2 = 3x, ...), clamped\n"
                        + " at runtime to the driver's reported DLSSG.MultiFrameCountMax.");
        config.setComment("reflex",
                " NVIDIA Reflex (VK_NV_low_latency2). Default off; gated additionally by device support.\n"
                        + " minimum-interval-us: 0 = no framerate cap (Reflex just paces submission).");
        config.setComment("lights",
                " RIS direct lighting from block emitters (torches, glowstone, lava, ...): per diffuse\n"
                        + " vertex, resample ris-candidates power-weighted proposals and spend one shadow ray on\n"
                        + " the survivor. ris-candidates = 0 disables it entirely (emitters just gather on direct\n"
                        + " hit, same as with no NEE). Power-weighted sampling and the local per-section light\n"
                        + " grid are always active whenever RIS is on. min-fill-ratio drops emissive footprints\n"
                        + " below that fraction of their bounding rectangle (speckle/sparse crossed planes), so\n"
                        + " only reasonably compact glows become lights. stats/dump/dump-radius are debug logging.");
        config.setComment("volumetrics",
                " The world's ambient participating medium — the fog every path is inside, as opposed\n"
                        + " to the water and glass a path enters through geometry. density-scale multiplies\n"
                        + " the active dimension's density preset; the legacy-named intensity-scale is now\n"
                        + " a 0..1 multiplier over its physical scattering albedo. cull-distance bounds how far a segment keeps\n"
                        + " accumulating fog. fog-noise-enabled gates the heterogeneous path; fog-noise-contrast\n"
                        + " redistributes density through a world-anchored mean-one 3D field. scatter-tint is one of:\n"
                        + " neutral, warm, cool, green, violet.");
        config.setComment("weather",
                " Continuous rain/thunder/time responses over the clear-weather Fog, Sky and Water values.\n"
                        + " Positive scalar gains multiply non-negative coefficients; cloud coverage/type use\n"
                        + " signed biases. The CPU resolves these once per frame before the shader sees them.");
        config.setComment("dimensions",
                " Per-dimension player overrides applied after the resource-authored preset and global controls.\n"
                        + " The global volumetrics switch remains the master switch; nether.fog-enabled can turn\n"
                        + " only Nether fog off, and nether.fog-density-scale multiplies only its preset density.\n"
                        + " nether.ambient-scale multiplies the Nether preset's unified neutral environment\n"
                        + " radiance; it does not change lava, glowstone or other local emitter power.\n"
                        + " end.environment-scale and end.disk-scale independently multiply the star HDRI\n"
                        + " and Kerr accretion-disk emission. Disk outer radius/thickness alter the runtime\n"
                        + " volume itself; environment-rotation-deg-per-second rotates only the escaped HDRI.");
        config.setComment("bsdf",
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
        config.setComment("water",
                " Enclosed participating media. Scattering and optional absorption overrides each have\n"
                        + " a strength (the arithmetic-mean coefficient per block) and an RGB colour shape;\n"
                        + " changing colour does not change strength. phase-g is forward-scattering\n"
                        + " anisotropy: positive puts a halo around the sun seen from underwater.");
        config.setComment("hdr",
                " Display signal: false is Rec.709/sRGB SDR; true requests Rec.2020/ST.2084-PQ HDR10.\n"
                        + " HDR requires restart and falls back to SDR if the surface doesn't advertise it.\n"
                        + " paper-white-nits / peak-nits\n"
                        + " drive only the compatibility AgX HDR mapping; ACES 2 uses its fixed preset.");
        config.setComment("exposure",
                " Automatic mode meters the 50th-95th percentile of a 256-bin log2 histogram.\n"
                        + " auto-ev-bias is compensation applied to that target; manual-ev is instead the absolute\n"
                        + " EV used only in manual mode. Bright/dark adaptation values are exponential EV-domain\n"
                        + " time constants in seconds: one constant completes about 63% of a transition.");
        config.setComment("post-processing",
                " Display output and scene-referred creative grading. output-transform accepts agx (default),\n"
                        + " aces2-lut (fast 65-cube approximation), or aces2-exact (analytic reference).\n"
                        + " aces-hdr-preset accepts only 500, 1000, 2000, or 4000 nit. The colour grade is a\n"
                        + " complete bypass while enabled=false and uses a dirty-rebaked 65-cube scene-linear LUT.\n"
                        + " Film grain can mix correlated RGB noise into its shared luminance grain. Lens Flare has\n"
                        + " an independent threshold and a quarter-resolution pentagonal bokeh source. These effects\n"
                        + " default off; depth-of-field and motion\n"
                        + " blur share one display-resolution RGBA16F scratch, while cinematic DoF additionally\n"
                        + " allocates signed-CoC, tile and half-resolution near/far images only while enabled.");
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
        clearPendingRestart(setting.tomlPath());
        setting.applyExternalValue(setting.externalDefaultValue());
    }

    static void clearPendingRestart(String tomlPath) {
        PENDING_RESTART_EXTERNAL.remove(tomlPath);
    }

    static synchronized void installPendingRestart(Map<String, Object> values) {
        PENDING_RESTART_EXTERNAL.clear();
        PENDING_RESTART_EXTERNAL.putAll(values);
    }

    /** Values that are already persisted but intentionally do not describe this process's live renderer. */
    public static Map<String, Object> pendingRestartValues() {
        return Map.copyOf(PENDING_RESTART_EXTERNAL);
    }

    /** Synchronizes NightConfig's long-lived file object after a preset atomically replaces its path. */
    static synchronized void synchronizeBackingFile(CommentedConfig replacement) {
        FILE.clear();
        FILE.clearComments();
        FILE.putAll(replacement);
        FILE.putAllComments(replacement);
    }

    static synchronized boolean backingFileContains(String tomlPath) {
        return FILE.contains(tomlPath);
    }

    static synchronized Object backingFileValue(String tomlPath) {
        return FILE.get(tomlPath);
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

        /** Canonical TOML-domain value, before transforms such as degrees to radians. */
        Object externalValue();

        /** Canonical TOML-domain default, before transforms such as degrees to radians. */
        Object externalDefaultValue();

        /**
         * Validates without mutation and returns a canonical TOML-domain value. Implementations reject
         * wrong types, non-finite numbers, values that would be clamped, and unknown enum spellings.
         */
        Object validateExternalValue(Object raw);

        /** Applies a value already accepted by {@link #validateExternalValue(Object)} without clearing pending state. */
        void applyExternalValue(Object canonical);

        /** Writes one canonical external value without consulting the current runtime holder. */
        default void writeExternalValue(CommentedConfig config, Object canonical) {
            config.set(tomlPath(), canonical);
        }

        /** Same operation under an exchange-format prefix such as {@code settings.}. */
        default void writeExternalValue(CommentedConfig config, String path, Object canonical) {
            config.set(path, canonical);
        }

        default boolean hasSystemPropertyOverride() {
            return System.getProperty(key()) != null;
        }
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
            clearPendingRestart(tomlPath);
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = Boolean.parseBoolean(System.getProperty(key, Boolean.toString(defaultValue)));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        @Override
        public Object externalValue() {
            return value;
        }

        @Override
        public Object externalDefaultValue() {
            return defaultValue;
        }

        @Override
        public Object validateExternalValue(Object raw) {
            if (!(raw instanceof Boolean)) {
                throw new IllegalArgumentException(tomlPath + " must be a boolean");
            }
            return raw;
        }

        @Override
        public void applyExternalValue(Object canonical) {
            this.value = (Boolean) canonical;
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
            clearPendingRestart(tomlPath);
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

        @Override
        public Object externalValue() {
            return value;
        }

        @Override
        public Object externalDefaultValue() {
            return defaultValue;
        }

        @Override
        public Object validateExternalValue(Object raw) {
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException(tomlPath + " must be an integer");
            }
            double numberValue = number.doubleValue();
            if (!Double.isFinite(numberValue) || Math.rint(numberValue) != numberValue
                    || numberValue < Integer.MIN_VALUE || numberValue > Integer.MAX_VALUE) {
                throw new IllegalArgumentException(tomlPath + " must be a finite 32-bit integer");
            }
            int candidate = (int) numberValue;
            if (sanitize.applyAsInt(candidate) != candidate) {
                throw new IllegalArgumentException(tomlPath + " is outside its accepted range");
            }
            return candidate;
        }

        @Override
        public void applyExternalValue(Object canonical) {
            this.value = (Integer) canonical;
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
            clearPendingRestart(tomlPath);
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

        @Override
        public Object externalValue() {
            return canonicalExternal(value);
        }

        @Override
        public Object externalDefaultValue() {
            return canonicalExternal(defaultValue);
        }

        @Override
        public Object validateExternalValue(Object raw) {
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException(tomlPath + " must be a number");
            }
            double external = number.doubleValue();
            if (!Double.isFinite(external)) {
                throw new IllegalArgumentException(tomlPath + " must be finite");
            }
            double transformed = inputTransform.applyAsDouble(external);
            // The holder stores a float, so range closure must be tested in that same domain. In
            // particular, an authored decimal lower bound such as 0.002 is emitted by Float.toString()
            // but 0.002f promotes back to 0.002000000094... as a double. Comparing those doubles made an
            // exported minimum fail its own importer. A genuinely out-of-range decimal still rounds to
            // an out-of-range float and is changed by valueClamp, so strict rejection remains intact.
            float stored = (float) transformed;
            double accepted = valueClamp.applyAsDouble(stored);
            if (!Double.isFinite(transformed) || !Float.isFinite(stored) || !Double.isFinite(accepted)
                    || Float.compare(stored, (float) accepted) != 0) {
                throw new IllegalArgumentException(tomlPath + " is outside its accepted range");
            }
            return canonicalExternal((float) accepted);
        }

        @Override
        public void applyExternalValue(Object canonical) {
            double transformed = inputTransform.applyAsDouble(((Number) canonical).doubleValue());
            this.value = (float) valueClamp.applyAsDouble(transformed);
        }

        private double canonicalExternal(float stored) {
            float raw = (float) outputTransform.applyAsDouble(stored);
            return Double.parseDouble(Float.toString(raw));
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
            clearPendingRestart(tomlPath);
        }

        @Override
        public void reloadFromSystemProperties() {
            this.value = sanitize.apply(System.getProperty(key, defaultValue));
        }

        @Override
        public void writeToFile(CommentedConfig config) {
            config.set(tomlPath, value);
        }

        @Override
        public Object externalValue() {
            return value;
        }

        @Override
        public Object externalDefaultValue() {
            return defaultValue;
        }

        @Override
        public Object validateExternalValue(Object raw) {
            if (!(raw instanceof String text)) {
                throw new IllegalArgumentException(tomlPath + " must be a string");
            }
            String canonical = sanitize.apply(text);
            String normalizedInput = text.trim().toLowerCase(java.util.Locale.ROOT);
            if (!canonical.equals(normalizedInput)) {
                throw new IllegalArgumentException(tomlPath + " has an unknown value");
            }
            return canonical;
        }

        @Override
        public void applyExternalValue(Object canonical) {
            this.value = (String) canonical;
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
            clearPendingRestart(tomlPath);
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

        @Override
        public Object externalValue() {
            return value;
        }

        @Override
        public Object externalDefaultValue() {
            return null;
        }

        @Override
        public Object validateExternalValue(Object raw) {
            if (!(raw instanceof String)) {
                throw new IllegalArgumentException(tomlPath + " must be a string");
            }
            return raw;
        }

        @Override
        public void applyExternalValue(Object canonical) {
            this.value = (String) canonical;
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
            /**
             * ONE wind for the whole world, in degrees clockwise from +X — the direction weather travels.
             *
             * <p>Everything that is blown by wind takes its heading from here plus its own offset: the
             * cumulus deck, the cirrus sheet, and the sea. They genuinely do differ in reality — a high
             * ice sheet runs tens of degrees off the surface wind — so the offsets stay, but they are
             * offsets now rather than three unrelated numbers that happened to need hand-matching.
             *
             * <p>ONLY THE HEADING IS SHARED, AND ONLY THE HEADING CAN BE. The clouds' speed is authored in
             * blocks per second; the waves' is not authored at all — it falls out of deep-water dispersion
             * w = sqrt(g*k), one speed per wavelength. Forcing a blocks-per-second onto the waves would
             * destroy the relationship that makes long swell stride past while short chop flutters in
             * place, which water_wave.slang calls the biggest tell of fake water.
             */
            public static final FloatSetting WIND_ANGLE =
                    clampedFloat("fluorite.rt.windAngle", "composite.wind-angle", 35f, 0f, 360f);

            public static final IntSetting DEBUG_VIEW = intValue("fluorite.rt.debugView", "composite.debug-view", 0);
            public static final IntSetting SPP = intAtLeast("fluorite.rt.spp", "composite.spp", 1, 1);
            public static final IntSetting MAX_BOUNCES =
                    clampedInt("fluorite.rt.maxBounces", "composite.max-bounces", 4, 2, 8);

            /**
             * How many bounce vertices keep a persistent reservoir. 0 disables ReSTIR reuse entirely.
             *
             * <p><b>This knob is the memory dial, and it is the reason the store is measured before it is
             * compressed.</b> Every depth costs {@code 2 x paths x 64 B x renderWidth x renderHeight} —
             * two frame halves for temporal reuse, and one plane per path, where paths is {@code spp x 2}
             * because pass B runs the bounce loop once per sample and once more for the transmission
             * split. At 1920x1080 and the default spp of 1 that is 530 MB per depth, so 2.1 GB at depth 4;
             * the whole point of shipping it uncompressed first is to find out whether the depths past the
             * first two ever pay for themselves.
             *
             * <p><b>They do not, and it has now been measured.</b> Acceptance at the primary hit is 95–99%
             * with the view still; at every depth past it, it is under 1%. The reason is structural rather
             * than a matter of tuning: the continuation direction is reseeded every frame, so last frame's
             * bounce vertex is an independent random point metres away, and the sub-1% is the rate at which
             * two such points coincide. Loosening the rejection thresholds cannot help — it would only mean
             * applying last frame's lighting to different geometry.
             *
             * <p><b>Spatial reuse changed that, and the reason is the predicate rather than the
             * neighbour.</b> With {@code RESTIR_SPATIAL_NEIGHBOURS} on, depth 1 accepts about 15% and depth
             * 2 about 9% — fifty times the temporal rate at the same vertices, measured over 62 frames at
             * two resolutions. The temporal test asks whether a point moved more than 0.05 blocks, which
             * two independent hemisphere draws never pass; the spatial one asks whether they are on the
             * same surface, which two points on one wall do. So the earlier reading that these depths were
             * structurally dead was measuring the test, not the opportunity.
             *
             * <p>What is still unmeasured is whether 15% is worth 530 MB. Acceptance says the predicate
             * passed, not that the sample helped, and no image or frame-time comparison has been run — see
             * §8.9, where that is recorded as a deliberate omission rather than an oversight. Until then: 1
             * is the setting the evidence supports, and above it is an experiment.
             * {@code diagnostics.restir-stats} reports both rates per depth.
             *
             * <p>Clamped to MAX_BOUNCES' own ceiling because a reservoir past the last bounce is storage
             * for a vertex that never exists, and again to whatever keeps the store under 4 GiB — the slot
             * index reaching it is 32 bits wide. See {@code RtComposite.reservoirDepthThatFits}.
             */
            public static final IntSetting RESTIR_REUSE_DEPTH =
                    clampedInt("fluorite.rt.restirReuseDepth", "composite.restir-reuse-depth", 0, 0, 8);

            /**
             * How many screen-space neighbours each reused vertex borrows a reservoir from.
             *
             * <p>Costs no memory at all: a neighbour is read from a slot that already exists, so this dial
             * buys samples with arithmetic rather than with video memory — two target-function evaluations
             * and one 64-byte read apiece, and none of the light-buffer pointer chasing that makes an
             * ordinary RIS candidate expensive.
             *
             * <p>Neighbours come from the PREVIOUS frame's half, the same one temporal reuse reads. There is
             * no point in this dispatch where every reservoir is written and nothing is shaded yet — shading
             * is inline in the bounce loop — so this frame's neighbours would be whatever they had got to.
             * The consequence is that a spatial neighbour is also a frame old.
             *
             * <p>0 leaves temporal reuse exactly as it was measured, which is this knob's off state. It does
             * nothing at all unless {@code RESTIR_REUSE_DEPTH} is non-zero.
             */
            /**
             * How many of the RIS candidates are spent on M18's dynamic sphere emitters — entities, held
             * blocks, flame particles — rather than on the world's own light grid.
             *
             * <p>0 is off and is the shipped default, and off is exactly today's picture: the dynamic
             * buffer has been collected and uploaded since M18 and read by nothing, so a mob holding a
             * torch lights itself and nothing around it. Above 0 those emitters start being proposed.
             *
             * <p>They need their own stratum because they live in their own per-frame buffer: the power
             * alias tables and the dense light grid are built incrementally with the terrain, and carrying
             * entities in them would mean rebuilding both every frame. The price of that shortcut is that
             * this count is FIXED — a room full of torches and a single mob get the same share of the
             * candidate budget, where a merged alias table would divide it by emitted power.
             *
             * <p>Capped one candidate below {@code RIS_CANDIDATES} so a vertex can never spend its whole
             * budget on entities and stop seeing the world it stands in.
             */
            /**
             * One multiplier over every finite emitter's radiance — block lights, entity and held-item
             * spheres, and the glow of the emissive surface itself, so a torch in a hand and the same
             * torch in a wall stay the same brightness.
             *
             * <p>Does not touch the sun, the moon or the sky. Those are not emitters in this sense; they
             * have their own physical parameters, and folding them in here would turn one control into a
             * global exposure slider.
             *
             * <p>1 is off and is exactly the shipped picture — the multiply is by one, which IEEE leaves
             * alone. Above 1 is a deliberate departure from the emission the material declares.
             */
            public static final FloatSetting EMITTER_BRIGHTNESS =
                    clampedFloat("fluorite.rt.emitterBrightness", "composite.emitter-brightness",
                            1.0f, 0.0f, 8.0f);

            /**
             * Colour temperature for those same emitters, in kelvin. 0 keeps each emitter's own colour.
             *
             * <p>A real blackbody temperature rather than a warm/cool feeling: the Planckian locus gives a
             * chromaticity which is then divided by its own luminance, so this changes what colour the
             * lights are and never how bright they are. A torch is near 1800 K, a lantern near 2000 K,
             * daylight near 6500 K.
             *
             * <p>It applies to every emitter, including the ones that are not incandescent at all —
             * glowstone and a sea lantern have no temperature, and giving them one is an art choice.
             * That is why it ships off rather than at some plausible flame value.
             */
            public static final IntSetting EMITTER_TEMPERATURE_K =
                    clampedInt("fluorite.rt.emitterTemperatureK", "composite.emitter-temperature-k",
                            0, 0, RtEmitterTint.MAX_TEMPERATURE_K);

            public static final IntSetting DYNAMIC_RIS_CANDIDATES =
                    clampedInt("fluorite.rt.dynamicRisCandidates",
                            "composite.dynamic-ris-candidates", 0, 0, 8);

            public static final IntSetting RESTIR_SPATIAL_NEIGHBOURS =
                    clampedInt("fluorite.rt.restirSpatialNeighbours",
                            "composite.restir-spatial-neighbours", 0, 0, 8);
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

            /** Exact hot-path gate. Off exits before every fog-noise texture fetch and numerical march. */
            public static final BooleanSetting FOG_NOISE_ENABLED =
                    bool("fluorite.rt.fog.noiseEnabled", "volumetrics.fog-noise-enabled", false);

            /**
             * Contrast of the world-anchored heterogeneous density field.
             *
             * <p>One is D67's calibrated field. Values above one use an odd bounded remap in the shared
             * shader module rather than clipping negative density: paired samples still sum to two,
             * every height keeps mean density one, and the local multiplier remains in 0..2.
             */
            public static final FloatSetting FOG_NOISE_CONTRAST =
                    clampedFloat("fluorite.rt.fog.noiseContrast", "volumetrics.fog-noise-contrast",
                            1f, 0f, 4f);

            /** One repeat of the packed fog field in blocks: base features are /4, detail is /16. */
            public static final FloatSetting FOG_NOISE_FIELD_SCALE =
                    clampedFloat("fluorite.rt.fog.noiseFieldScale", "volumetrics.fog-noise-field-scale",
                            384f, 64f, 2048f);

            /** Advection speed of the fog density field in blocks per second. */
            public static final FloatSetting FOG_NOISE_WIND_SPEED =
                    clampedFloat("fluorite.rt.fog.noiseWindSpeed", "volumetrics.fog-noise-wind-speed",
                            0.15f, 0f, 8f);

            /** Degrees the near-ground fog runs off the global weather wind. */
            public static final FloatSetting FOG_NOISE_WIND_OFFSET =
                    clampedFloat("fluorite.rt.fog.noiseWindOffset", "volumetrics.fog-noise-wind-offset",
                            -35f, -180f, 180f);

            /** Absolute fog heading after D69's global direction plus layer offset are resolved. */
            public static float fogNoiseWindAngle() {
                return Rt.Composite.WIND_ANGLE.value() + FOG_NOISE_WIND_OFFSET.value();
            }

            /** Maximum numerical density samples in one contiguous ambient interval. */
            public static final IntSetting FOG_NOISE_MARCH_STEPS =
                    intValue("fluorite.rt.fog.noiseMarchSteps", "volumetrics.fog-noise-march-steps", 12);

            /**
             * How much thicker the fog gets at dawn, as a multiple of the base density.
             *
             * <p>THE SHAPE IS RADIATION FOG, which is the kind you actually see over a landscape. The
             * ground radiates its heat away overnight, the air above it cools to the dew point, and the
             * water in it condenses -- so the mist is thickest when the ground is coldest, which is just
             * before and at sunrise, NOT at midnight. Then the sun comes up, warms the ground, and burns
             * it off over the first few hours; the clearest air of the day is mid-afternoon, after the
             * ground has had all morning to heat.
             *
             * <p>That is why this is a curve over the day rather than a function of the sun's height: at
             * equal elevations the morning is misty and the evening is not, because what matters is how
             * long the ground has been cooling or heating, not where the sun happens to be.
             *
             * <p>0 leaves the fog the same at every hour, which is the shipped behaviour.
             */
            public static final FloatSetting FOG_TIME_GAIN =
                    clampedFloat("fluorite.rt.fog.timeGain", "volumetrics.fog-time-gain", 1f, 0f, 4f);

            /**
             * How much thicker the fog gets in rain, as a multiple of the base density.
             *
             * <p>Scales with how hard it is raining rather than switching on: drizzle hazes the distance
             * a little, a downpour closes it right down. Thunder counts on top, because it arrives with
             * the rain already at full and still makes things worse.
             *
             * <p>0 leaves the fog indifferent to the weather, which is the shipped behaviour.
             */
            public static final FloatSetting FOG_WEATHER_GAIN =
                    clampedFloat("fluorite.rt.fog.weatherGain", "volumetrics.fog-weather-gain",
                            1f, 0f, 4f);

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
             * stratified estimator, 7.56 ms under this one. These measurements closed M9's old water
             * benchmark debt and are the current underwater baseline.
             */
            /**
             * Volumetric clouds (M11).
             *
             * <p>A world-anchored, two-layer spherical cloud field marched on sky-escaping segments, so
             * it appears in reflections as well as overhead. Sun lighting, self-shadowing and the
             * multiple-scattering approximation are present. DEFAULT OFF because R19's full-path cost
             * remains unmeasured; use the cloud, sun-step and secondary-step controls for same-session A/B.
             */
            public static final BooleanSetting CLOUDS =
                    bool("fluorite.rt.fog.clouds", "volumetrics.clouds", false);

            /**
             * Let vanilla's rain and thunder drive the sky.
             *
             * <p>Rain adds coverage, thunder adds cloud TYPE — see {@code RtComposite.cloudParams}. Both
             * are added to the fields rather than replacing them, so a storm arrives over a sky that is
             * still made of individual cells instead of turning the whole dome into one uniform value.
             *
             * <p>Off leaves the sky at whatever the sliders below say, which is what makes the sliders
             * usable as authoring controls: with this on, moving the coverage slider during a storm
             * measures the slider plus the weather.
             */
            public static final BooleanSetting CLOUD_WEATHER =
                    bool("fluorite.rt.fog.cloudWeather", "volumetrics.cloud-weather", true);

            /**
             * Added to the coverage field everywhere, in [-1, 1]. 0 is the broken sky the noise bakes.
             *
             * <p>A BIAS rather than a multiplier because coverage is already a remapped signed quantity:
             * scaling it would move the clear sky and the overcast sky by different amounts and could
             * never reach either limit, while shifting it walks the whole field through both.
             */
            public static final FloatSetting CLOUD_COVERAGE =
                    clampedFloat("fluorite.rt.fog.cloudCoverage", "volumetrics.cloud-coverage", 0f, -1f, 1f);

            /**
             * Multiplies cloud density, and with it opacity. 1 is the authored sky.
             *
             * <p>Clamped to the same 0..10 as the fog's density scale so it can share {@code scaleSlider}
             * — a slider whose travel reaches further than the clamp behind it silently stops responding
             * partway along, which reads as a bug in the renderer rather than as a limit.
             */
            public static final FloatSetting CLOUD_DENSITY =
                    clampedFloat("fluorite.rt.fog.cloudDensity", "volumetrics.cloud-density", 1f, 0f, 10f);

            /**
             * Added to the cloud-type field, in [-1, 1]: negative flattens the sky toward stratus,
             * positive builds it toward cumulonimbus. See {@code cloudHeightProfile}.
             */
            public static final FloatSetting CLOUD_TYPE =
                    clampedFloat("fluorite.rt.fog.cloudType", "volumetrics.cloud-type", 0f, -1f, 1f);

            /**
             * Cloud extinction per block at full density.
             *
             * <p>The one number here with a physical meaning rather than an artistic one, and it is small
             * because the deck is hundreds of blocks deep: what matters is the product. At the default a
             * cumulus of a hundred blocks is most of the way to opaque and a stratus sheet is not.
             */
            public static final FloatSetting CLOUD_EXTINCTION =
                    clampedFloat("fluorite.rt.fog.cloudExtinction", "volumetrics.cloud-extinction",
                            0.045f, 0f, 0.5f);

            /**
             * The deck's floor, in blocks. Vanilla's own clouds sit at 192.
             *
             * <p>This is where every cloud's flat base lands, whatever its type, because that is what a
             * condensation altitude is.
             */
            public static final FloatSetting CLOUD_ALTITUDE =
                    clampedFloat("fluorite.rt.fog.cloudAltitude", "volumetrics.cloud-altitude",
                            180f, 64f, 1024f);

            /**
             * The deck's depth, in blocks. Deep by default because a cumulonimbus has to have somewhere
             * to stand — the tall types fill it while a stratus sheet occupies only its lowest tenth.
             *
             * <p>It costs march steps only where a tall cloud actually exists: the empty-space skip walks
             * through the rest of the depth in long strides, and above a sheet almost all of it is empty.
             */
            public static final FloatSetting CLOUD_THICKNESS =
                    clampedFloat("fluorite.rt.fog.cloudThickness", "volumetrics.cloud-thickness",
                            380f, 32f, 1024f);

            /** How wide one cloud is, in blocks — the period of the baked volume's shape channel. */
            public static final FloatSetting CLOUD_BASE_SCALE =
                    clampedFloat("fluorite.rt.fog.cloudBaseScale", "volumetrics.cloud-base-scale",
                            2400f, 200f, 8000f);

            /**
             * How fast the cloud field drifts, in blocks per second. 0 freezes it.
             *
             * <p>A sky that does not move is the one thing no amount of shape detail can pass for real,
             * and it costs nothing: the drift is subtracted from the field's origin on the CPU, so the
             * shader samples a moving field through the same addition that already un-rebases it.
             */
            public static final FloatSetting CLOUD_WIND_SPEED =
                    clampedFloat("fluorite.rt.fog.cloudWindSpeed", "volumetrics.cloud-wind-speed",
                            2.0f, 0f, 60f);

            /** Which way the wind blows, in degrees clockwise from +X. */
            /** Degrees off the global wind (Composite.WIND_ANGLE). 0 = the deck runs with the wind. */
            public static final FloatSetting CLOUD_WIND_OFFSET =
                    clampedFloat("fluorite.rt.fog.cloudWindOffset", "volumetrics.cloud-wind-offset",
                            0f, -180f, 180f);

            /** Absolute heading of the deck, in degrees clockwise from +X. */
            public static float cloudWindAngle() {
                return Rt.Composite.WIND_ANGLE.value() + CLOUD_WIND_OFFSET.value();
            }

            /**
             * How wide the cloud FIELD's cells are, in blocks — the 2D distribution, not the 3D puffs.
             *
             * <p>Separate from {@link #CLOUD_BASE_SCALE} because they are different questions and were
             * previously answerable only as one: this is how far apart the clumps and clearings are
             * across the sky, while the base scale is how big a single cloud in a clump is.
             */
            public static final FloatSetting CLOUD_FIELD_SCALE =
                    clampedFloat("fluorite.rt.fog.cloudFieldScale", "volumetrics.cloud-field-scale",
                            9000f, 500f, 40000f);

            /** How big the bites taken out of a cloud's edges are, in blocks. */
            public static final FloatSetting CLOUD_DETAIL_SCALE =
                    clampedFloat("fluorite.rt.fog.cloudDetailScale", "volumetrics.cloud-detail-scale",
                            340f, 40f, 2000f);

            /**
             * Henyey-Greenstein asymmetry of the cloud's forward lobe.
             *
             * <p>0.8 is close to the measured asymmetry parameter of a water cloud — droplets around ten
             * microns are large against visible wavelengths, so Mie scattering off them is strongly
             * forward-peaked. That is a property of the droplets rather than a look, which is why the
             * default sits where the physics does.
             *
             * <p>What it changes on screen is how hard the rim of a backlit cloud glows: at high values
             * almost all the light continues forward, so a cloud between the viewer and the sun is dark
             * with a fierce edge, and at low values it is uniformly bright and flat.
             */
            public static final FloatSetting CLOUD_PHASE_G =
                    clampedFloat("fluorite.rt.fog.cloudPhaseG", "volumetrics.cloud-phase-g",
                            0.8f, -0.95f, 0.95f);

            /**
             * Single-scattering albedo of the cloud: the fraction of an interaction that scatters rather
             * than absorbs.
             *
             * <p><b>The physically load-bearing number here.</b> The multiple-scattering term decays at
             * {@code sqrt(3*(1-albedo))} times the ordinary optical depth (see cloud.slang
             * cloudDiffusionRate), so this one value sets how deep light reaches into a cloud. At the
             * default 0.999 that is 0.055 — light reaches about eighteen times deeper than the direct
             * beam, which is why a thick cloud's interior is bright rather than black.
             *
             * <p>Water droplets barely absorb visible light at all, so the default is near one and moving
             * it far down is a deliberately unphysical dial: it makes clouds sooty. It is exposed because
             * it is the honest place to make clouds darker, unlike raising extinction, which makes them
             * more opaque instead.
             */
            public static final FloatSetting CLOUD_ALBEDO =
                    clampedFloat("fluorite.rt.fog.cloudAlbedo", "volumetrics.cloud-albedo",
                            0.999f, 0.5f, 0.9999f);

            /**
             * Steps in the march that asks how much cloud stands between a sample and the light. 0 takes
             * the light as unoccluded, which flattens every cloud.
             *
             * <p><b>The milestone's cost dial.</b> These run per in-cloud step of the primary march, so
             * they multiply the expensive path rather than adding to it — R19 names cloud cost as this
             * milestone's principal risk and this is the lever it is measured with. The steps double in
             * length, so the reach stays one deck thickness whatever this is set to and only the
             * resolution of the near field moves.
             */
            public static final IntSetting CLOUD_SUN_STEPS =
                    clampedInt("fluorite.rt.fog.cloudSunSteps", "volumetrics.cloud-sun-steps", 5, 0, 8);

            /**
             * The diffusion term that keeps the inside of a thick cloud bright.
             *
             * <p>Same physics as {@link #MULTI_SCATTER} does for fog and water, and derived rather than
             * fitted — but a separate switch, because a cloud's optical depth is an order of magnitude
             * past anything the fog reaches and this is the regime the approximation exists for (D5).
             *
             * <p>Off is single scattering exactly: the diffuse lobe is removed and the sky's occlusion
             * goes back on the beam's own extinction. That makes it an A/B rather than a tuning dial —
             * and the A/B is stark, because at an optical depth of 20 the direct beam is zero and this is
             * a third.
             */
            public static final BooleanSetting CLOUD_MULTI_SCATTER =
                    bool("fluorite.rt.fog.cloudMultiScatter", "volumetrics.cloud-multi-scatter", true);

            /**
             * D176: clouds cast their shadow onto the ground, water and everything the sun lights.
             *
             * <p>Read from a light-space transmittance map baked once a frame out of the same density
             * field the clouds themselves are drawn from, so a shadow cannot disagree with the cloud
             * casting it. One texture read per sun sample; the bake is about 1% of the primary trace by
             * arithmetic, which {@code gpu.cloudShadow} exists to check.
             *
             * <p>Off restores exactly what shipped, which takes two things rather than one: the map is
             * not read, and D73's global weather proxy for caustic contrast comes back — that scalar
             * exists to stand in for this map and the two must never both be live.
             */
            public static final BooleanSetting CLOUD_SHADOWS =
                    bool("fluorite.rt.fog.cloudShadows", "volumetrics.cloud-shadows", true);

            /** Two analytic optical-depth sheets far above the convective deck; no high-cloud march. */
            public static final BooleanSetting CLOUD_CIRRUS =
                    bool("fluorite.rt.fog.cloudCirrus", "volumetrics.cloud-cirrus", true);

            /**
             * Where the cirrus sits, in blocks. Raised to clear the deck below it if it would overlap.
             *
             * <p>That clamp is structural rather than cosmetic: the two shells being disjoint is what
             * makes ordering them by which one a ray reaches first correct.
             */
            public static final FloatSetting CLOUD_CIRRUS_ALTITUDE =
                    clampedFloat("fluorite.rt.fog.cloudCirrusAltitude", "volumetrics.cloud-cirrus-altitude",
                            760f, 128f, 2048f);

            /** Vertical separation and optical reference thickness of the two analytic high-cloud sheets. */
            public static final FloatSetting CLOUD_CIRRUS_THICKNESS =
                    clampedFloat("fluorite.rt.fog.cloudCirrusThickness", "volumetrics.cloud-cirrus-thickness",
                            60f, 8f, 400f);

            /** Added to both high-cloud optical-depth shape fields, in [-1, 1]. */
            public static final FloatSetting CLOUD_CIRRUS_COVERAGE =
                    clampedFloat("fluorite.rt.fog.cloudCirrusCoverage", "volumetrics.cloud-cirrus-coverage",
                            0f, -1f, 1f);

            /**
             * Cirrus extinction per block at full density. 0 turns the layer off, same as the switch.
             *
             * <p>An order of magnitude under the deck's, because that is the difference between the two:
             * you can see the sun through cirrus and read its shape, and you cannot see it through a
             * cumulus at all.
             */
            public static final FloatSetting CLOUD_CIRRUS_EXTINCTION =
                    clampedFloat("fluorite.rt.fog.cloudCirrusExtinction",
                            "volumetrics.cloud-cirrus-extinction", 0.004f, 0f, 0.1f);

            /** Mean diameter of one deterministic CC0 cloud patch, in world blocks. */
            public static final FloatSetting CLOUD_CIRRUS_PATCH_DIAMETER =
                    migratingClampedFloat("fluorite.rt.fog.cloudCirrusPatchDiameter",
                            "volumetrics.cloud-cirrus-patch-diameter",
                            "volumetrics.cloud-cirrus-base-scale", 9600f, 500f, 40000f);

            /** World-grid spacing whose hash selects patch, offset, rotation and scale. */
            public static final FloatSetting CLOUD_CIRRUS_PATCH_SPACING =
                    clampedFloat("fluorite.rt.fog.cloudCirrusPatchSpacing",
                            "volumetrics.cloud-cirrus-patch-spacing", 16000f, 1000f, 80000f);

            /**
             * How far the low deck's density field is displaced before it is sampled, in blocks.
             *
             * <p>DISPLACEMENT, NOT SUBTRACTION, and that is the whole difference. Eroding a cloud removes
             * material from its edge: it gets thinner and dimmer but keeps the base field's shape, which is
             * why a deck driven by erosion alone reads as rounded blobs however hard the erosion is pushed.
             * Displacing where the field is sampled moves the surface itself, so it grows folds, filaments
             * and hollows that were never in the base field.
             *
             * <p>The right order of magnitude is one feature of the field being displaced -- displace by far
             * less and nothing visible happens, by far more and the cloud stops being the shape the weather
             * map placed there. Zero skips the fetch and is bit-for-bit the field without any of this.
             */
            public static final FloatSetting CLOUD_WARP_AMOUNT =
                    clampedFloat("fluorite.rt.fog.cloudWarpAmount",
                            "volumetrics.cloud-warp-amount", 28f, 0f, 200f);

            /**
             * How fast a cloud's shape changes, in blocks per second, INDEPENDENT OF THE WIND.
             *
             * <p>Tying this to wind speed was wrong twice over. Physically, clouds are reshaped by
             * convection and turbulence, not by advection -- a still afternoon cumulus rises, billows and
             * dissolves without moving anywhere, and a wind-driven rate freezes the sky solid whenever the
             * wind is set to zero. And numerically it was invisible: as a fraction of the shipped wind
             * speed it took over eight minutes for one warp feature to pass through a cloud, which is not
             * a rate anyone can perceive.
             *
             * <p>This is the speed the warp field slides through the shape field, so it is the whole of
             * how fast lobes appear and dissolve. About one warp feature per minute and a half at the
             * default scale; too high and the cloud boils rather than grows.
             */
            public static final FloatSetting CLOUD_EVOLUTION_SPEED =
                    clampedFloat("fluorite.rt.fog.cloudEvolutionSpeed",
                            "volumetrics.cloud-evolution-speed", 6f, 0f, 60f);

            /** How large the warp's own swirls are, in blocks. Larger reshapes whole lobes; smaller frays edges. */
            public static final FloatSetting CLOUD_WARP_SCALE =
                    clampedFloat("fluorite.rt.fog.cloudWarpScale",
                            "volumetrics.cloud-warp-scale", 900f, 50f, 8000f);

            /** Optical-depth multiplier for the high-cloud sheet. */
            public static final FloatSetting CLOUD_CIRRUS_DENSITY =
                    clampedFloat("fluorite.rt.fog.cloudCirrusDensity",
                            "volumetrics.cloud-cirrus-density", 1f, 0f, 10f);

            /** Optical-depth gain for the randomly scattered patch sheet. */
            public static final FloatSetting CLOUD_CIRRUS_PATCH_STRENGTH =
                    clampedFloat("fluorite.rt.fog.cloudCirrusPatchStrength",
                            "volumetrics.cloud-cirrus-patch-strength", 1f, 0f, 10f);

            static {
                // The upper sheet sampled a single periodic HDRI and filled every gap the patches left,
                // which is the one thing a cirrus sky must not do. Its span and gain went with it; do not
                // leave dead keys behind for a later reader to wonder about.
                FILE.remove("volumetrics.cloud-cirrus-furry-span");
                FILE.remove("volumetrics.cloud-cirrus-furry-strength");
            }

            /**
             * How fast the high-cloud sheet drifts, in blocks per second.
             *
             * <p>Its own, not the deck's. Cirrus sits kilometres higher, where the wind is faster and
             * frequently from a different quarter — and two layers sliding past each other at different
             * speeds is most of what makes a sky read as deep rather than as one painted dome.
             */
            public static final FloatSetting CLOUD_CIRRUS_WIND_SPEED =
                    clampedFloat("fluorite.rt.fog.cloudCirrusWindSpeed",
                            "volumetrics.cloud-cirrus-wind-speed", 6f, 0f, 120f);

            /**
             * Degrees off the global wind. Defaults to +30, which reproduces the shipped 65 against a
             * global 35 — and is physically the right shape: high cirrus really does run at an angle to
             * the surface wind, which is why the offset is kept rather than collapsed.
             */
            public static final FloatSetting CLOUD_CIRRUS_WIND_OFFSET =
                    clampedFloat("fluorite.rt.fog.cloudCirrusWindOffset",
                            "volumetrics.cloud-cirrus-wind-offset", 30f, -180f, 180f);

            /** Absolute heading of both high-cloud sheets, in degrees clockwise from +X. */
            public static float cloudCirrusWindAngle() {
                return Rt.Composite.WIND_ANGLE.value() + CLOUD_CIRRUS_WIND_OFFSET.value();
            }

            /**
             * What clouds a ray that is not the first of its path gets: {@code off}, {@code reduced} or
             * {@code full}.
             *
             * <p>Purely a cost dial. Every ray finds its clouds by intersecting the same world-anchored
             * shells from its own origin, so this cannot move a cloud in a reflection relative to the one
             * overhead — the two can only disagree about how finely the same cloud was integrated. That
             * separation is what R18 exists to protect, and it is why this can be a budget rather than a
             * second sky.
             *
             * <p>Reduced halves the shadow march, drops the erosion fetch, cuts the step cap from 96 to
             * 40 and gives up on a nearly opaque path at 0.05 rather than 0.01. Off leaves reflections
             * showing the bare sky, which is visibly wrong on a lake but is the floor this milestone can
             * fall back to if the measurement goes badly.
             */
            public static final StringSetting CLOUD_SECONDARY =
                    string("fluorite.rt.fog.cloudSecondary", "volumetrics.cloud-secondary", "reduced",
                            Volumetrics::sanitizeCloudSecondary);

            private static String sanitizeCloudSecondary(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return switch (v) {
                    case "off", "full" -> v;
                    default -> "reduced";
                };
            }

            /** 0 off, 1 reduced, 2 full — packed into flags bits 2-3. */
            public static int cloudSecondaryId() {
                return switch (CLOUD_SECONDARY.get()) {
                    case "off" -> 0;
                    case "full" -> 2;
                    default -> 1;
                };
            }

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
             * <p><b>D31 ran that experiment and the ray is exonerated.</b> Silencing it while keeping the
             * lookup made the frame 5.2 ms SLOWER, not faster (62.85 against 57.67 at the same build,
             * same batch) — a negative cost, which can only be F15's observer effect: removing the trace
             * changed register live ranges. What that rules out is the useful part. <b>Optimising the ray
             * cannot help.</b> Distance culling, an irradiance early-out, a shorter tmax: all answers to
             * the wrong question. The cost is the walk and the pressure this function creates, and both
             * point one way — invoke it less often rather than make the invocation cheaper.
             *
             * <p>So the candidates are structural: once per PATH instead of once per segment (biased —
             * later segments would get no emitter light), first segment only (same bias, smaller), or
             * ReSTIR's presampled pool, which is where D3 already decided the sampling side belongs.
             * None of them is worth building before ReSTIR's shape is known.
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

            /** Runtime bound mirrored by VOLUME_FOG_MARCH_LIMIT in volume_source.slang. */
            public static int fogNoiseMarchSteps() {
                return Math.clamp(FOG_NOISE_MARCH_STEPS.value(), 1, 31);
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

        /**
         * Player controls that intentionally affect one dimension rather than every authored preset.
         *
         * <p>These are the final, local adjustment layer. The global Volumetrics page remains useful as
         * a master accessibility/performance control, while this group lets a player remove or retune a
         * dimension's character without changing the Overworld. End environment and disk emission remain
         * separate so visibility and direct-light energy cannot silently compensate for one another.
         */
        public static final class Dimensions {
            /** Local gate; the global fog switches still have final authority over every dimension. */
            public static final BooleanSetting NETHER_FOG_ENABLED =
                    bool("fluorite.rt.dimensions.netherFogEnabled",
                            "dimensions.nether.fog-enabled", true);

            /** Multiplier over the Nether preset after the global density multiplier. */
            public static final FloatSetting NETHER_FOG_DENSITY_SCALE =
                    clampedFloat("fluorite.rt.dimensions.netherFogDensityScale",
                            "dimensions.nether.fog-density-scale", 1f, 0f, 2f);

            /**
             * Multiplier over the Nether preset's neutral environment Radiance. The one scaled value is
             * shared by the diffuse readability floor, escaped rays and participating media; local emitter
             * power remains physically separate.
             */
            public static final FloatSetting NETHER_AMBIENT_SCALE =
                    clampedFloat("fluorite.rt.dimensions.netherAmbientScale",
                            "dimensions.nether.ambient-scale", 1f, 0f, 8f);

            /** Multiplier over the End's full-sphere star environment and its solid-angle mean. */
            public static final FloatSetting END_ENVIRONMENT_SCALE =
                    clampedFloat("fluorite.rt.dimensions.endEnvironmentScale",
                            "dimensions.end.environment-scale", 1f, 0f, 8f);

            /** Multiplier over visible Kerr disk emission and the matching Le/pdf light proposal. */
            public static final FloatSetting END_DISK_SCALE =
                    clampedFloat("fluorite.rt.dimensions.endDiskScale",
                            "dimensions.end.disk-scale", 1f, 0f, 8f);

            /** Runtime outer edge in gravitational radii; the path LUT retains the complete 12M domain. */
            public static final FloatSetting END_DISK_OUTER_RADIUS =
                    clampedFloat("fluorite.rt.dimensions.endDiskOuterRadius",
                            "dimensions.end.disk-outer-radius", 8f, 4f, 12f);

            /** Multiplier over the independently authored 0.55M disk half-height. */
            public static final FloatSetting END_DISK_THICKNESS =
                    clampedFloat("fluorite.rt.dimensions.endDiskThickness",
                            "dimensions.end.disk-thickness", 1f, 0.25f, 2f);

            /** Rotation of escaped HDRI only, around the Kerr spin axis, in degrees per game second. */
            public static final FloatSetting END_ENVIRONMENT_ROTATION_SPEED =
                    clampedFloat("fluorite.rt.dimensions.endEnvironmentRotationSpeed",
                            "dimensions.end.environment-rotation-deg-per-second", 0.02f, 0f, 1f);

            private Dimensions() {
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
             * The same weighing for the world's own emitters: a torch, glowstone, lava.
             *
             * <p>Its sibling above covers ONE light of zero angular size. This covers every light with an
             * extent, and the double count it removes was not the sun's. RIS estimates an emitter through
             * the diffuse and specular lobes both, while the gate that preceded M24 S4b only suppressed
             * the diffuse continuation — so an emitter reached by a specular bounce was counted twice for
             * as long as both existed, and the alpha floor is most likely why it never read as a doubling:
             * it made the RIS half a blurred copy of the sharp highlight rather than the same highlight.
             *
             * <p><b>Off restores both halves</b>, the 1/0 gate and the alpha floor, because they are one
             * decision — the floor bounded a peak nothing else bounded, and the weight bounds it by
             * weighting instead of by blurring. Off with only the weight removed would be an unfloored
             * highlight counted twice: a picture that never shipped, and a worse baseline than no switch.
             *
             * <p>Here to make the comparison possible inside one session rather than across two commits,
             * which is what iron law 7 asks of a change that moved a published picture.
             */
            public static final BooleanSetting EMITTER_MIS_ENABLED =
                    bool("fluorite.rt.bsdf.emitterMis", "bsdf.emitter-mis", true);

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

        /**
         * Continuous environment forcing authored on top of each medium's clear-weather baseline.
         *
         * <p>These are gains or signed biases, not a second copy of the fog, cloud and water settings.
         * Rain and thunder remain independent vanilla axes and are resolved once per frame by
         * {@code RtEnvironmentForcing}; the shader receives only the final physical parameters.
         */
        public static final class Weather {
            /** Master switch for D105-D108 rain exposure, wet film and puddles. */
            public static final BooleanSetting RAIN_SURFACES_ENABLED =
                    bool("fluorite.rt.weather.rainSurfacesEnabled",
                            "weather.rain-surfaces-enabled", true);
            /** D105A exposure-map size: low=256², high=512², both at one block per texel. */
            public static final StringSetting RAIN_EXPOSURE_QUALITY =
                    string("fluorite.rt.weather.rainExposureQuality",
                            "weather.rain-exposure-quality", "high", Weather::sanitizeExposureQuality);
            /** Downwind tilt from vertical. Heading is the existing global weather wind. */
            public static final FloatSetting RAIN_SLANT_DEGREES =
                    clampedFloat("fluorite.rt.weather.rainSlantDegrees",
                            "weather.rain-slant-degrees", 8f, 0f, 30f);

            public static final FloatSetting WET_FILL_SECONDS =
                    clampedFloat("fluorite.rt.weather.wetFillSeconds",
                            "weather.wet-fill-seconds", 8f, 1f, 60f);
            public static final FloatSetting WET_DRY_SECONDS =
                    clampedFloat("fluorite.rt.weather.wetDrySeconds",
                            "weather.wet-dry-seconds", 120f, 10f, 600f);
            public static final FloatSetting PUDDLE_FILL_SECONDS =
                    clampedFloat("fluorite.rt.weather.puddleFillSeconds",
                            "weather.puddle-fill-seconds", 45f, 5f, 300f);
            public static final FloatSetting PUDDLE_DRY_SECONDS =
                    clampedFloat("fluorite.rt.weather.puddleDrySeconds",
                            "weather.puddle-dry-seconds", 300f, 30f, 1800f);
            public static final BooleanSetting DAYLIGHT_DRYING =
                    bool("fluorite.rt.weather.daylightDrying",
                            "weather.daylight-drying", true);

            public static final FloatSetting WET_FILM_STRENGTH =
                    clampedFloat("fluorite.rt.weather.wetFilmStrength",
                            "weather.wet-film-strength", 1f, 0f, 1f);
            public static final FloatSetting WET_FILM_ROUGHNESS =
                    clampedFloat("fluorite.rt.weather.wetFilmRoughness",
                            "weather.wet-film-roughness", 0.08f, 0.01f, 0.30f);
            public static final BooleanSetting PUDDLES_ENABLED =
                    bool("fluorite.rt.weather.puddlesEnabled",
                            "weather.puddles-enabled", true);
            public static final FloatSetting PUDDLE_COVERAGE =
                    clampedFloat("fluorite.rt.weather.puddleCoverage",
                            "weather.puddle-coverage", 0.35f, 0f, 1f);
            public static final FloatSetting PUDDLE_SCALE =
                    clampedFloat("fluorite.rt.weather.puddleScale",
                            "weather.puddle-scale", 8f, 2f, 32f);
            public static final FloatSetting PUDDLE_RIPPLE_STRENGTH =
                    clampedFloat("fluorite.rt.weather.puddleRippleStrength",
                            "weather.puddle-ripple-strength", 0.35f, 0f, 3f);
            public static final FloatSetting RAIN_RIPPLE_SIZE =
                    clampedFloat("fluorite.rt.weather.rainRippleSize",
                            "weather.rain-ripple-size", 0.12f, 0.03f, 0.30f);

            /** Temporary M21 art-calibration controls; remove after accepted values become constants. */
            public static final FloatSetting WET_DARKENING_GAIN =
                    clampedFloat("fluorite.rt.weather.wetDarkeningGain",
                            "weather.wet-darkening-gain", 1f, 0f, 8f);
            public static final FloatSetting WET_COAT_GAIN =
                    clampedFloat("fluorite.rt.weather.wetCoatGain",
                            "weather.wet-coat-gain", 1f, 0f, 2f);
            public static final FloatSetting PUDDLE_LAYER_GAIN =
                    clampedFloat("fluorite.rt.weather.puddleLayerGain",
                            "weather.puddle-layer-gain", 1.8f, 0f, 3f);
            public static final FloatSetting PUDDLE_ROUGHNESS =
                    clampedFloat("fluorite.rt.weather.puddleRoughness",
                            "weather.puddle-roughness", 0.02f, 0.002f, 0.15f);
            public static final FloatSetting PUDDLE_EXTRA_DARKENING =
                    clampedFloat("fluorite.rt.weather.puddleExtraDarkening",
                            "weather.puddle-extra-darkening", 0.08f, 0f, 0.25f);
            public static final FloatSetting PUDDLE_NORMAL_FLATTENING =
                    clampedFloat("fluorite.rt.weather.puddleNormalFlattening",
                            "weather.puddle-normal-flattening", 1.6f, 0f, 3f);
            public static final FloatSetting WET_FILM_NORMAL_FLATTENING =
                    clampedFloat("fluorite.rt.weather.wetFilmNormalFlattening",
                            "weather.wet-film-normal-flattening", 0.15f, 0f, 1f);
            public static final FloatSetting RAIN_RIPPLE_WIDTH =
                    clampedFloat("fluorite.rt.weather.rainRippleWidth",
                            "weather.rain-ripple-width", 0.04f, 0.01f, 0.08f);

            /** Global fallback for material JSON v3 weather properties. */
            public static final FloatSetting DEFAULT_WET_ABSORPTION =
                    clampedFloat("fluorite.rt.weather.defaultWetAbsorption",
                            "weather.default-wet-absorption", 0.50f, 0f, 1f);
            public static final FloatSetting DEFAULT_WET_DARKENING =
                    clampedFloat("fluorite.rt.weather.defaultWetDarkening",
                            "weather.default-wet-darkening", 0.20f, 0f, 1f);
            public static final FloatSetting DEFAULT_WET_FILM =
                    clampedFloat("fluorite.rt.weather.defaultWetFilm",
                            "weather.default-wet-film", 0.65f, 0f, 1f);
            public static final FloatSetting DEFAULT_PUDDLE_AFFINITY =
                    clampedFloat("fluorite.rt.weather.defaultPuddleAffinity",
                            "weather.default-puddle-affinity", 0.35f, 0f, 1f);

            /** D109A visible rain streaks plus D127A's selected shared-event RT impact pool. */
            public static final BooleanSetting RAIN_PARTICLES_ENABLED =
                    bool("fluorite.rt.weather.rainParticlesEnabled",
                            "weather.rain-particles-enabled", true);
            public static final StringSetting RAIN_STREAK_QUALITY =
                    string("fluorite.rt.weather.rainStreakQuality",
                            "weather.rain-streak-quality", "medium", Weather::sanitizeStreakQuality);
            public static final FloatSetting RAIN_STREAK_DENSITY =
                    clampedFloat("fluorite.rt.weather.rainStreakDensity",
                            "weather.rain-streak-density", 1f, 0f, 2f);
            public static final FloatSetting RAIN_STREAK_SPEED =
                    clampedFloat("fluorite.rt.weather.rainStreakSpeed",
                            "weather.rain-streak-speed", 24f, 1f, 64f);
            public static final FloatSetting RAIN_STREAK_LENGTH =
                    clampedFloat("fluorite.rt.weather.rainStreakLength",
                            "weather.rain-streak-length", 0.7f, 0.1f, 2f);

            /**
             * How fast snow falls, in blocks per second.
             *
             * <p>Its own setting because it cannot share rain's: the default there is 24 blocks a second
             * against a snowflake's terminal velocity of roughly 1 to 1.5, and one dial moving both would
             * make any value wrong for one of them. The default is deliberately a little above the
             * physical figure — rain is stylised fast by the same kind of margin, and matching that keeps
             * the two reading as the same weather system rather than as two unrelated effects.
             *
             * <p>The consequence worth knowing: at this speed a flake takes about twenty seconds to cross
             * the 48-block fall span, so it is on screen long enough to be watched, which is why its
             * drift is a curl field rather than a per-flake wobble.
             */
            public static final FloatSetting SNOW_FALL_SPEED =
                    clampedFloat("fluorite.rt.weather.snowFallSpeed",
                            "weather.snow-fall-speed", 2.4f, 0.2f, 12f);

            /**
             * How wide a snowflake is drawn, in blocks.
             *
             * <p>Pure style. A real aggregate flake is two to ten millimetres, which at any sane render
             * distance is far below a pixel; every game draws them enormously oversized, and the only
             * question is how much. Floored at the streak's screen-space minimum so distant snow thins
             * out smoothly instead of flickering with sub-pixel coverage.
             */
            public static final FloatSetting SNOW_FLAKE_SIZE =
                    clampedFloat("fluorite.rt.weather.snowFlakeSize",
                            "weather.snow-flake-size", 0.09f, 0.02f, 0.4f);
            public static final IntSetting RAIN_SPLASH_TARGET =
                    clampedInt("fluorite.rt.weather.rainSplashTarget",
                            "weather.rain-splash-target", 96, 0, 256);
            public static final FloatSetting RAIN_SPLASH_SIZE =
                    clampedFloat("fluorite.rt.weather.rainSplashSize",
                            "weather.rain-splash-size", 0.18f, 0.05f, 0.50f);
            public static final FloatSetting RAIN_SPLASH_OPACITY =
                    clampedFloat("fluorite.rt.weather.rainSplashOpacity",
                            "weather.rain-splash-opacity", 0.55f, 0f, 1f);
            public static final FloatSetting RAIN_SPLASH_BRIGHTNESS =
                    clampedFloat("fluorite.rt.weather.rainSplashBrightness",
                            "weather.rain-splash-brightness", 1f, 0.05f, 1.25f);

            /** Extra fog-density gain at full thunder, on top of the existing rain gain. */
            public static final FloatSetting FOG_THUNDER_DENSITY_GAIN =
                    clampedFloat("fluorite.rt.weather.fogThunderDensityGain",
                            "weather.fog-thunder-density-gain", 0.5f, -1f, 4f);

            /** Signed response of fog-structure contrast at peak radiation-fog time. */
            public static final FloatSetting FOG_TIME_STRUCTURE_GAIN =
                    clampedFloat("fluorite.rt.weather.fogTimeStructureGain",
                            "weather.fog-time-structure-gain", 0f, -1f, 4f);
            /** Signed response of fog-structure contrast at full rain. */
            public static final FloatSetting FOG_RAIN_STRUCTURE_GAIN =
                    clampedFloat("fluorite.rt.weather.fogRainStructureGain",
                            "weather.fog-rain-structure-gain", 0f, -1f, 4f);
            /** Signed response of fog-structure contrast at full thunder. */
            public static final FloatSetting FOG_THUNDER_STRUCTURE_GAIN =
                    clampedFloat("fluorite.rt.weather.fogThunderStructureGain",
                            "weather.fog-thunder-structure-gain", 0f, -1f, 4f);

            /** Coverage-field bias at full rain; preserves M11's former authored constant by default. */
            public static final FloatSetting CLOUD_RAIN_COVERAGE_BIAS =
                    clampedFloat("fluorite.rt.weather.cloudRainCoverageBias",
                            "weather.cloud-rain-coverage-bias", 0.55f, -1f, 1f);
            /** Multiplicative cloud-density gain at full rain. */
            public static final FloatSetting CLOUD_RAIN_DENSITY_GAIN =
                    clampedFloat("fluorite.rt.weather.cloudRainDensityGain",
                            "weather.cloud-rain-density-gain", 0.8f, -1f, 4f);
            /** Cloud-type field bias at full thunder. */
            public static final FloatSetting CLOUD_THUNDER_TYPE_BIAS =
                    clampedFloat("fluorite.rt.weather.cloudThunderTypeBias",
                            "weather.cloud-thunder-type-bias", 0.75f, -1f, 1f);

            /** Suspended-particle scattering gain at full rain; extinction follows because sigma_t=a+s. */
            public static final FloatSetting WATER_RAIN_SCATTER_GAIN =
                    clampedFloat("fluorite.rt.weather.waterRainScatterGain",
                            "weather.water-rain-scatter-gain", 0f, -1f, 4f);
            /** Additional water-scattering gain at full thunder. */
            public static final FloatSetting WATER_THUNDER_SCATTER_GAIN =
                    clampedFloat("fluorite.rt.weather.waterThunderScatterGain",
                            "weather.water-thunder-scatter-gain", 0f, -1f, 4f);

            /**
             * How strongly storms transfer energy from short chop toward the existing long bands.
             * Wavelengths and phases stay fixed; changing either against absolute time makes every crest
             * jump. The resolved bias is ramped over twenty seconds before reaching the shader.
             */
            public static final FloatSetting WATER_STORM_SWELL_BIAS =
                    clampedFloat("fluorite.rt.weather.waterStormSwellBias",
                            "weather.water-storm-swell-bias", 0.5f, 0f, 1f);

            public static int rainExposureResolution() {
                return "low".equals(RAIN_EXPOSURE_QUALITY.get()) ? 256 : 512;
            }

            public static int rainStreakBudget() {
                return switch (RAIN_STREAK_QUALITY.get()) {
                    case "low" -> 2048;
                    case "high" -> 8192;
                    default -> 4096;
                };
            }

            private static String sanitizeExposureQuality(String value) {
                return "low".equals(value) ? "low" : "high";
            }

            private static String sanitizeStreakQuality(String value) {
                return switch (value) {
                    case "low", "high" -> value;
                    default -> "medium";
                };
            }

            private Weather() {
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
             * The interactive water simulation (M12). Off leaves the procedural wave spectrum alone,
             * bit for bit — the sampler returns before it reads anything.
             */
            public static final BooleanSetting WATER_SIM =
                    bool("fluorite.rt.water.sim", "water.sim", true);

            /**
             * How far across the simulated domain reaches, in blocks.
             *
             * <p>The grid is a fixed 256 cells, so this IS the cell size in disguise: 64 blocks gives
             * quarter-block cells and ripples down to about half a block, 128 blocks gives half-block
             * cells and twice the reach for half the detail. Reach and detail are the two ends of one
             * stick and this is where you hold it (D39).
             */
            public static final FloatSetting WATER_SIM_RANGE =
                    clampedFloat("fluorite.rt.water.simRange", "water.sim-range", 64f, 32f, 256f);

            /**
             * How far above or below you the simulation will look for a water surface, in blocks.
             *
             * <p>THE OTHER HALF OF WHICH WATER RIPPLES. The range setting says how wide the domain is;
             * this says how tall a slice of the world it may sit in. Together they are the answer to
             * "which surface am I simulating" — a lake thirty blocks below is still your lake, a lake two
             * hundred below is somewhere else entirely.
             *
             * <p>Symmetric about you rather than downward only, so it holds while you are under the
             * surface looking up as well as above it looking down.
             *
             * <p>This used to be a hidden constant of 24, scanning downward. Flying up from water you had
             * just disturbed took you out of its reach in about twenty-six blocks, at which point the
             * probe found nothing, the domain switched off, and the ripples did not fade — they went out
             * at once, along with every other ripple in the world.
             */
            public static final FloatSetting WATER_SIM_HEIGHT =
                    clampedFloat("fluorite.rt.water.simHeight", "water.sim-height", 32f, 8f, 128f);

            /**
             * How far the player may walk before the domain is re-anchored, in blocks.
             *
             * <p>Re-anchoring is the only thing in this system that costs anything beyond one dispatch:
             * it recasts the obstacle mask. Between re-anchors the domain is completely still. So this is
             * a straight trade of how often that cost is paid against how close to the domain's edge the
             * player may get -- and the edge is well inside the fade, because ripples are damped hard
             * enough that they do not reach it anyway.
             */
            public static final FloatSetting WATER_SIM_REANCHOR =
                    clampedFloat("fluorite.rt.water.simReanchor", "water.sim-reanchor", 16f, 4f, 64f);

            /**
             * How fast a ripple travels, in blocks per second.
             *
             * <p>CLAMPED AGAINST THE CFL LIMIT, not against taste. Explicit leapfrog is stable only
             * while {@code c*dt/dx <= 1/sqrt(2)}; past it the scheme amplifies every step and the field
             * explodes within a second. The clamp lives in RtWaterSim.courant where the timestep and the
             * cell size are both known, so this can be authored freely and is bounded there.
             */
            public static final FloatSetting WATER_SIM_SPEED =
                    clampedFloat("fluorite.rt.water.simSpeed", "water.sim-speed", 3.5f, 0.1f, 20f);

            /**
             * Per-step energy retention. 1 is lossless; below it ripples fade.
             *
             * <p>An approximation and named as one. Real water loses ripple energy to viscosity and
             * surface tension, fastest at the shortest wavelengths; a uniform multiply is not that. It
             * also keeps the scheme from ringing forever on the grid's own Nyquist frequency, which is
             * the less honourable half of why it is here.
             */
            public static final FloatSetting WATER_SIM_DAMPING =
                    clampedFloat("fluorite.rt.water.simDamping", "water.sim-damping", 0.996f, 0.9f, 1f);

            /** How much of the simulated slope reaches the shading, against the procedural spectrum's. */
            public static final FloatSetting WATER_SIM_STRENGTH =
                    clampedFloat("fluorite.rt.water.simStrength", "water.sim-strength", 1.0f, 0f, 4f);

            /**
             * How irregular the sea is, 0 to 1 — one knob for three things that all fight the same
             * problem, which is that ten plane waves read as a comb.
             *
             * <p>It ramps in a SECOND wave system crossing the first, widens and jitters the fan of
             * headings, and extends the crest meander from the four longest components to seven. They are
             * one control because they are one intent, and because three sliders that each do a fifth of
             * the job is worse than one that does it.
             *
             * <p>The crossing swell is the load-bearing one. Two wave systems interfere, and the shifting
             * diamond lattice that produces reads as far more complex than any single fan can — which is
             * also why real water almost always has one: a swell outlives the wind that made it and
             * arrives from wherever that was.
             *
             * <p>WHAT IT DOES NOT DO is add spectral content. There are still ten components. It changes
             * their character and their arrangement; if the sea still looks repetitive at 1, the answer is
             * more components (about 0.09 ms each, measured) and not this.
             *
             * <p>0 is the shipped field exactly.
             */
            public static final FloatSetting WAVE_COMPLEXITY =
                    clampedFloat("fluorite.rt.water.waveComplexity", "water.wave-complexity",
                            0.5f, 0f, 1f);

            /**
             * How strongly gusts ruffle the surface, 0 to 1.
             *
             * <p>WHAT THIS FIXES is that the sea is otherwise equally rough everywhere, which is the
             * single thing that most makes it read as a pattern rather than as weather. Real water is
             * patchy: a dark ruffled stretch here, a glassy calm one there, and the boundary crawling
             * downwind. That patchwork is most of what "alive" means on open water.
             *
             * <p>It is weighted toward the SHORT waves, because a gust ruffles a surface, it does not
             * raise a swell. A gust front passing over water darkens it with chop while the long waves
             * underneath carry on unchanged; modulating everything equally would give a sea that breathes
             * in and out, which is a different and much worse effect.
             *
             * <p>Costs three noise fetches per shaded point, not per wave, so it does not scale with the
             * component count. 0 is the shipped field exactly.
             */
            public static final FloatSetting WAVE_GUST =
                    clampedFloat("fluorite.rt.water.waveGust", "water.wave-gust", 0.5f, 0f, 1f);

            /**
             * How much the weather moves the sea, 0 to 2.
             *
             * <p>The rain and thunder levels have been available all along and the clouds lean on them
             * heavily; the water read neither, so a storm rolled in overhead and the sea below it stayed
             * exactly as glassy as it was at noon.
             *
             * <p>It drives THREE things, not one. A storm sea is taller, and steeper, and gustier -- and
             * of those, steepness is what actually reads as violent. Scaling only the amplitude gives a
             * bigger calm sea, which looks like the camera moved closer rather than like weather.
             * The Weather Effects swell-bias control additionally transfers emphasis toward the existing
             * long bands. The resolved state takes twenty seconds per unit to change.
             *
             * <p>0 leaves the sea indifferent to the sky, which is the shipped behaviour.
             */
            public static final FloatSetting WAVE_WEATHER =
                    clampedFloat("fluorite.rt.water.waveWeather", "water.wave-weather", 1f, 0f, 2f);

            /**
             * How far the world the waves travel through is bent, in blocks.
             *
             * <p>WHAT THIS FIXES is the lattice. A sum of ten plane waves is quasi-periodic whatever you
             * do to it -- measured, wavelength jitter, irrational ratios, a wider fan, a flatter
             * amplitude law and even thirty-two components all left the long-range autocorrelation above
             * about 0.42, against 0.69 for the original. Bending the domain took it to 0.056 for one
             * noise fetch, because it attacks the cause rather than the symptom: the phase relationships
             * between components stop being the same everywhere in the world.
             *
             * <p>It is not a cheat either. Real water refracts over currents and bathymetry and bends its
             * crests in exactly this way. It stops being physical as the bend approaches a wavelength,
             * which is why this is authored and why its useful range sits well under the swell's length.
             *
             * <p>0 is the shipped field exactly, and skips the noise entirely.
             */
            public static final FloatSetting WAVE_WARP =
                    clampedFloat("fluorite.rt.water.waveWarp", "water.wave-warp", 10f, 0f, 40f);

            /**
             * Which wave components run, 1 to 10, inclusive. DIAGNOSTIC, not art.
             *
             * <p>A sum of ten waves cannot tell you which of them you are looking at, and reading the
             * code has now failed at that four times over one visible artefact. Set both to the same
             * number to see one component alone; walk the upper one up from 1 to see the field
             * accumulate and catch the exact step where something appears.
             */
            public static final FloatSetting WAVE_FIRST =
                    clampedFloat("fluorite.rt.water.waveFirst", "water.wave-first", 1f, 1f, 10f);

            public static final FloatSetting WAVE_LAST =
                    clampedFloat("fluorite.rt.water.waveLast", "water.wave-last", 10f, 1f, 10f);

            /**
             * The distance-based band limit that fades short waves out as the ray footprint grows.
             *
             * <p>Off is WRONG -- it aliases, badly, at any distance -- and that is the point: it exists
             * so a ring or a line on the water can be attributed. If a boundary vanishes with this off,
             * it is a component fading out at a fixed distance rather than anything in the field itself.
             */
            public static final BooleanSetting WAVE_BAND_LIMIT =
                    bool("fluorite.rt.water.waveBandLimit", "water.wave-band-limit", true);

            /** How far apart the bends are, in blocks. Large is a slow lazy meander, small is churn. */
            public static final FloatSetting WAVE_WARP_SCALE =
                    clampedFloat("fluorite.rt.water.waveWarpScale", "water.wave-warp-scale",
                            100f, 20f, 400f);

            /**
             * How fast the whole sea moves, as a multiple of its shipped speed.
             *
             * <p>UNIFORM ON PURPOSE, and that is what makes it safe. It scales every wave equally, so the
             * relation between them survives: long swell still strides past while short chop flutters
             * nearly in place. A per-wavelength speed control would destroy that relation, which
             * water_wave.slang calls the biggest tell of fake water, so there deliberately is not one.
             *
             * <p>The weather does NOT act here, and that is deliberate too. In deep water a wave's speed
             * depends only on its wavelength -- c = sqrt(g/k) -- so wind does not make a given wave
             * travel faster. D72 also keeps every authored wavelength fixed: changing k against an
             * accumulated absolute phase clock teleports crests. Storms instead make the already-long,
             * already-faster bands more prominent over twenty seconds. Multiplying time would look
             * similar while meaning something false.
             */
            public static final FloatSetting WAVE_SPEED =
                    clampedFloat("fluorite.rt.water.waveSpeed", "water.wave-speed", 1f, 0f, 3f);

            /** How big a gust patch is, in blocks. */
            public static final FloatSetting WAVE_GUST_SCALE =
                    clampedFloat("fluorite.rt.water.waveGustScale", "water.wave-gust-scale",
                            40f, 8f, 200f);

            /**
             * How fast the patches travel downwind, in blocks per second.
             *
             * <p>Not optional in spirit: a patch field pinned to the world is a stain on the sea, with the
             * waves moving through it while it sits still. That reads worse than no patches at all.
             */
            public static final FloatSetting WAVE_GUST_SPEED =
                    clampedFloat("fluorite.rt.water.waveGustSpeed", "water.wave-gust-speed",
                            4f, 0f, 30f);

            /** Degrees the second wave system runs off the first. Only bites while complexity > 0. */
            public static final FloatSetting WAVE_CROSS_ANGLE =
                    clampedFloat("fluorite.rt.water.waveCrossAngle", "water.wave-cross-angle",
                            50f, -180f, 180f);

            /**
             * Degrees the swell runs off the global wind (Composite.WIND_ANGLE).
             *
             * <p>The default is -15.71 rather than 0, and that is not a taste choice: the wave direction
             * used to be a compile-time constant normalize(1.0, 0.35), which is 19.29 degrees, and
             * 35 - 15.71 reproduces it exactly. Iron rule 8 is about switches, but the principle is the
             * same -- adding a control must not silently move what the control now controls.
             */
            public static final FloatSetting WAVE_WIND_OFFSET =
                    clampedFloat("fluorite.rt.water.waveWindOffset", "water.wave-wind-offset",
                            -15.71f, -180f, 180f);

            /** Absolute heading of the swell, in degrees clockwise from +X. */
            public static float waveWindAngle() {
                return Rt.Composite.WIND_ANGLE.value() + WAVE_WIND_OFFSET.value();
            }

            /**
             * How big a patch an entity disturbs, as a multiple of its own width.
             *
             * <p>THIS IS THE RIPPLE'S WAVELENGTH, which is not obvious and is worth writing down. The
             * solver is the linear wave equation, which is NON-DISPERSIVE: every wavelength travels at
             * the same c (that one is water.sim-speed). So a ripple's wavelength is not a property of the
             * water at all -- it is set entirely by the size of whatever disturbed it. A bump of radius R
             * radiates waves of about 2R. Scaling the source is the only honest way to author it.
             *
             * <p>Turning it up has a second effect worth knowing: past a radius of two or three blocks
             * the ripples are long enough to survive the water mesh's band limit, so they stop being a
             * normal-map effect and start actually moving the geometry -- without waiting for the
             * subdivided mesh (D48).
             */
            public static final FloatSetting WATER_SIM_IMPULSE_SIZE =
                    clampedFloat("fluorite.rt.water.simImpulseSize", "water.sim-impulse-size",
                            1f, 0.25f, 6f);

            /**
             * How deep below the surface something can be and still disturb it, in blocks.
             *
             * <p>PHYSICAL, not a preference. A surface wave's motion decays as e^(-k·d) with depth, so a
             * swimmer well under the surface is not coupled to it at all — the water above simply slides
             * past. Before this, diving to the bottom of a lake went on stamping ripples into a surface
             * several blocks overhead, which is both wrong and conspicuous.
             *
             * <p>Faded across the range rather than switched off at the end of it, because the real
             * decay is smooth and a hard cut would pop as you swam down. Generous by default: the honest
             * decay for ripple-scale wavelengths is nearly spent within half a block, and a range that
             * short would make swimming at the surface feel dead.
             */
            public static final FloatSetting WATER_SIM_IMPULSE_DEPTH =
                    clampedFloat("fluorite.rt.water.simImpulseDepth", "water.sim-impulse-depth",
                            3f, 0.5f, 16f);

            /**
             * Metres of surface displacement an entity's impulse may inject, before falloff.
             *
             * <p>A STABILITY GUARD, not an art parameter. The solver is explicit, so a displacement large
             * enough relative to the cell size makes the local slope steep enough that the next step
             * overshoots, and the overshoot compounds. The reference implementation clamps for the same
             * reason and its value is its own; this one is scaled to this world's cell size.
             */
            public static final FloatSetting WATER_SIM_IMPULSE =
                    clampedFloat("fluorite.rt.water.simImpulse", "water.sim-impulse", 0.06f, 0f, 0.25f);

            /**
             * The wave field's overall amplitude. 1 is the shipped look, exactly.
             *
             * <p>SCALES HEIGHT AND SLOPE TOGETHER, and must — they are the same function, so scaling them
             * apart would shade a surface the geometry does not have. Which is also why this exists at
             * all: the field's base scale was tuned against reflections, when a normal was the only
             * consumer and absolute height meant nothing. Measured, that leaves the surface ±2.5 cm tall
             * with a maximum tilt of 3.7°, against 10–15° for real water under a light breeze — calm as
             * slope, and very nearly invisible as displacement.
             *
             * <p>So raising this is how the deformation becomes something you can see, and the honest
             * cost is that the REFLECTIONS CHANGE WITH IT: choppier glints, more broken mirror. That is a
             * visible change to shipped behaviour, which is why it is authored rather than simply raised.
             * Around 3–8 reaches the steepness real water has.
             */
            /**
             * The longest wave in the spectrum, in blocks. Every component follows from it: ten of them,
             * each 1.5x shorter, so 14 reaches down to about 0.36.
             *
             * <p>SPEED IS NOT A SEPARATE SETTING AND MUST NOT BECOME ONE. Deep-water dispersion ties it
             * to wavelength as w = sqrt(g*k), so shortening the swell makes it travel slower on its own,
             * and the relative speeds across the spectrum stay physical. That relationship — long swell
             * striding past while short chop flutters nearly in place — is the single biggest reason the
             * water reads as water, and a speed knob would be a knob for destroying it.
             *
             * <p>Amplitude follows too, because the per-component steepness a*k is what is authored: a
             * longer wave of the same steepness is a taller wave. So this changes the sea's character —
             * a pond of short choppy waves against an ocean swell — rather than merely its scale.
             */
            public static final FloatSetting WAVE_LENGTH =
                    clampedFloat("fluorite.rt.water.waveLength", "water.wave-length", 14f, 2f, 40f);

            public static final FloatSetting WAVE_AMPLITUDE =
                    clampedFloat("fluorite.rt.water.waveAmplitude", "water.wave-amplitude", 1f, 0f, 8f);

            /**
             * Move the water's actual geometry with the waves (M12.5), instead of only tilting its
             * normal.
             *
             * <p>OFF IS THE SHIPPED BEHAVIOUR, bit for bit — the water stays the flat quads the terrain
             * mesher emits and the whole displacement path is never dispatched. That is iron rule 8, and
             * here it is also the only way the cost of this is measurable at all.
             *
             * <p>What it buys is the three things a normal cannot fake, because a normal describes a
             * surface the geometry does not have: a wavy silhouette against the sky, shadows the crests
             * actually cast on the troughs, and a shoreline that the waves ride up and down. What it
             * costs is a per-frame BLAS refit over the near field, which is the risk this milestone is
             * really about (see D44).
             */
            public static final BooleanSetting WATER_DEFORM =
                    bool("fluorite.rt.water.deform", "water.deform", false);

            /**
             * Which sections are BUILT ready to deform: {@code all} water-bearing ones, or only those
             * {@code near} the camera. Takes effect on the next terrain load.
             *
             * <p>WHAT THIS DECIDES IS THE FLICKER. Crossing the deformation boundary changes how a
             * section is built -- updatable and uncompacted, with its build inputs retained -- and the
             * only way to change that is to build it again. In {@code near} mode every re-anchor
             * therefore re-extracts a ring of sections, and that rebuild is visible.
             *
             * <p>{@code all} removes the boundary entirely: nothing ever crosses it, so nothing is ever
             * rebuilt, and the flicker cannot happen. It does NOT cost more per frame -- the dispatch
             * still only touches sections near the camera, which is the whole point of separating the
             * two questions. What it costs is fixed: vertices, indices and a rest copy retained for
             * every water-bearing section, and BLAS compaction given up on them.
             *
             * <p>{@code near} is the low-memory option and keeps the flicker. Restart-scoped rather than
             * live, because switching would require rebuilding every water section -- precisely the cost
             * being avoided.
             */
            public static final StringSetting WATER_DEFORM_MODE =
                    string("fluorite.rt.water.deformMode", "water.deform-mode", "all",
                            Water::sanitizeDeformMode);

            private static String sanitizeDeformMode(String value) {
                String v = value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT);
                return v.equals("near") ? "near" : "all";
            }

            /**
             * How far from the camera the water is real geometry, in blocks. Beyond it the surface is
             * flat and the waves live entirely in the normal, as they always have.
             *
             * <p>THE COST IS QUADRATIC IN THIS. Triangles = 2·(range/cell)², and every one of them is
             * refit every frame. 64 blocks at quarter-block cells is about 131k triangles; doubling the
             * range quadruples that. It is deliberately its own setting rather than being tied to the
             * ripple range (D45), because the two are paid for in completely different currencies —
             * ripple range costs a fixed dispatch, this costs triangles.
             *
             * <p>Deliberately short by default. The displacement is centimetres, so it reads as shape
             * only up close; far water gets nothing from the geometry that the normal was not already
             * giving it.
             */
            public static final FloatSetting WATER_DEFORM_RANGE =
                    clampedFloat("fluorite.rt.water.deformRange", "water.deform-range", 48f, 8f, 128f);

            /**
             * Blocks per mesh cell in the deformed region — the tessellation, and the other half of the
             * triangle count.
             *
             * <p>It is also the band limit: the mesh cannot carry a wave shorter than about twice a cell,
             * so this is what the displacement passes to the spectrum as its footprint and everything
             * finer stays in the normal (D46). Measured, that costs almost nothing in shape — the
             * components longer than 2.8 m hold 96% of the field's rms height, so even half-block cells
             * are carrying essentially all of it.
             */
            public static final FloatSetting WATER_DEFORM_CELL =
                    clampedFloat("fluorite.rt.water.deformCell", "water.deform-cell", 0.25f, 0.125f, 1f);

            /**
             * How far the player may walk before the deformed region is re-anchored, in blocks.
             *
             * <p>Its own control rather than the ripple domain's (D47), because the two re-anchor for
             * different reasons and at different costs: that one recasts an obstacle mask, this one
             * rebuilds a vertex grid. Snapped to whole cells for the same reason as R22 — a grid that
             * slid continuously would resample the field at a new phase every frame, and here that would
             * show as the surface crawling rather than as a smeared ripple.
             */
            public static final FloatSetting WATER_DEFORM_REANCHOR =
                    clampedFloat("fluorite.rt.water.deformReanchor", "water.deform-reanchor", 8f, 0f, 32f);

            /**
             * The ripple domain's reach once the deformation range has had its say: ripples are not
             * simulated further out than the geometry can move (D45).
             *
             * <p>The grid is a fixed 256 cells, so shortening the reach buys detail (D39) — and it buys
             * it exactly where the deformation can show it. Only applies while the deformation is on;
             * with it off the ripple range stands alone, which is what keeps the off state equal to the
             * shipped behaviour.
             */
            public static float simRangeBlocks() {
                // NOT CLAMPED YET, deliberately. D45 pulls the ripple domain in to match the deformation
                // range, on the reasoning that a ripple the geometry cannot move is not worth simulating.
                // That reasoning holds only once the deformation actually runs, and its per-frame path is
                // not wired (M12.5 slice 3b). Until then the clamp would make WATER_DEFORM a switch whose
                // entire observable effect is halving the ripple radius -- which is what it did: with the
                // 32-block domain that produced, the ripples simply ended a few blocks out and looked
                // like they vanished when the camera lifted.
                //
                // Restore the min() in the same commit that makes the deformation dispatch.
                return WATER_SIM_RANGE.value();
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
            /**
             * Authored upper bound for underwater caustic contrast. This is a property of the water's
             * focusing model, beside dispersion; weather only supplies the automatic attenuation.
             */
            public static final FloatSetting CAUSTIC_STRENGTH =
                    clampedFloat("fluorite.rt.water.causticStrength",
                            "water.caustic-strength", 1f, 0f, 1f);

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
            /**
             * Let particles cast shadows.
             *
             * <p>Particles are primary-ray-only otherwise, so a column of campfire smoke darkens nothing
             * beneath it. This puts them in SHADOW rays only, on their own TLAS mask bit — reflections
             * and GI stay off, because a camera-facing billboard seen from a reflected ray is edge-on and
             * that is a different feature with a different cost. Staged deliberately (M20.3).
             *
             * <p>Costs any-hit work on every shadow ray that crosses a particle, and particles are alpha
             * -tested, so the cost lands in the hottest shader in the frame. Measured before the default
             * moves.
             */
            public static final BooleanSetting PARTICLE_SHADOWS =
                    bool("fluorite.rt.entities.particleShadows", "entities.particle-shadows", false);

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
                    clampedFloat("fluorite.rt.exposure.manualEv", "exposure.manual-ev", 0.0f, -5.0f, 5.0f);
            public static final FloatSetting AUTO_EV_BIAS =
                    clampedFloat("fluorite.rt.exposure.autoEvBias", "exposure.auto-ev-bias", 0.0f, -5.0f, 5.0f);
            public static final FloatSetting KEY = exposureScale("fluorite.rt.exposure.key", "exposure.key", 0.18f);
            public static final FloatSetting MIN_EV =
                    finiteFloat("fluorite.rt.exposure.minEv", "exposure.min-ev", -1.5f);
            public static final FloatSetting MAX_EV =
                    finiteFloat("fluorite.rt.exposure.maxEv", "exposure.max-ev", 4.0f);
            public static final FloatSetting BRIGHT_ADAPT_SECONDS = clampedFloat(
                    "fluorite.rt.exposure.brightAdaptSeconds",
                    "exposure.bright-adaptation-seconds", 0.25f, 0.05f, 5.0f);
            public static final FloatSetting DARK_ADAPT_SECONDS = clampedFloat(
                    "fluorite.rt.exposure.darkAdaptSeconds",
                    "exposure.dark-adaptation-seconds", 1.5f, 0.1f, 10.0f);

            static {
                // D153A splits the old shared dial without changing an existing automatic-exposure look:
                // on the first load only, copy its value into the new auto bias. Reset-to-default remains
                // the authored zero because AUTO_EV_BIAS itself was constructed with zero above.
                if (System.getProperty("fluorite.rt.exposure.autoEvBias") == null
                        && !FILE.contains("exposure.auto-ev-bias")) {
                    AUTO_EV_BIAS.set(MANUAL_EV.value());
                }
                // These hidden linear-exposure time constants had reversed human semantics. D152A replaces
                // them with explicit bright/dark EV-domain time constants; do not leave dead keys behind.
                FILE.remove("exposure.adapt-up");
                FILE.remove("exposure.adapt-down");
            }

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

            /** Per-depth acceptance rate for ReSTIR temporal reuse — the measurement
             * {@code composite.restir-reuse-depth} exists to be judged by. Costs one nibble of register
             * arithmetic per shading vertex plus a handful of atomics on one pixel in sixteen, and does
             * nothing at all while the reuse depth is 0. See {@code RtRestirStats}. */
            public static final BooleanSetting RESTIR_STATS =
                    bool("fluorite.rt.restirStats", "diagnostics.restir-stats", false);

            /** Heavy driver-side crash diagnostics: vendor diagnostics-config extensions (shader debug
             * info, resource tracking, automatic checkpoints, shader error reporting) and the
             * {@code deviceFaultVendorBinary} feature (vendor-format crash dump on device loss). Off by
             * default: measured ~10x BLAS build time / -20% fps when enabled. Plain {@code deviceFault}
             * reporting (fault addresses + vendor records) is always on and unaffected. Turn on only
             * while chasing a live device-loss crash. */
            public static final BooleanSetting HEAVY_CRASH_DIAGNOSTICS =
                    bool("fluorite.rt.heavyCrashDiagnostics", "diagnostics.heavy-crash-diagnostics", false);

            private Diagnostics() {
            }
        }

        /**
         * HDR display output. When enabled the swapchain is created in PQ (ST.2084/HDR10 — the display-ready
         * encoding both HDR10 swapchains and DLSS Frame Generation require; whatever pixel format the surface
         * pairs with that color space, commonly a 10-bit UNORM), falling back to SDR if the surface doesn't
         * advertise it. The nit values drive the scene-HDR → display mapping: SDR paper white maps to
         * In compatibility AgX mode {@code paperWhiteNits} is SDR paper white and highlights roll off
         * toward {@code peakNits}. ACES 2 instead uses the fixed peak preset in {@link PostProcessing}.
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

        /**
         * Display-referred output and scene-referred creative grading. Exposure remains in
         * {@link Exposure} because it owns the metering state, but its player-facing controls are grouped
         * with this class on the Post Processing screen.
         *
         * <p>AgX is deliberately the compatibility default. The optional ACES path is the fixed ACES 2.0
         * Output Transform and accepts only the official 500/1000/2000/4000-nit HDR presets; arbitrary
         * peak values would no longer be the published transform. Creative controls are identity by
         * default and are bypassed as one branch when disabled.
         */
        public static final class PostProcessing {
            public static final StringSetting OUTPUT_TRANSFORM = string(
                    "fluorite.rt.postProcessing.outputTransform", "post-processing.output-transform",
                    "agx", PostProcessing::sanitizeOutputTransform);
            public static final IntSetting ACES_HDR_PRESET = new IntSetting(
                    "fluorite.rt.postProcessing.acesHdrPreset", "post-processing.aces-hdr-preset",
                    1000, PostProcessing::sanitizeAcesHdrPreset);
            public static final BooleanSetting COLOR_GRADING_ENABLED = bool(
                    "fluorite.rt.postProcessing.colorGrading", "post-processing.color-grading.enabled", false);
            public static final IntSetting TEMPERATURE_K = clampedInt(
                    "fluorite.rt.postProcessing.temperature", "post-processing.color-grading.temperature-k",
                    6500, 2000, 12000);
            public static final FloatSetting TINT = clampedFloat(
                    "fluorite.rt.postProcessing.tint", "post-processing.color-grading.tint",
                    0.0f, -100.0f, 100.0f);
            public static final FloatSetting CONTRAST = clampedFloat(
                    "fluorite.rt.postProcessing.contrast", "post-processing.color-grading.contrast",
                    1.0f, 0.0f, 2.0f);
            public static final FloatSetting SATURATION = clampedFloat(
                    "fluorite.rt.postProcessing.saturation", "post-processing.color-grading.saturation",
                    1.0f, 0.0f, 2.0f);
            public static final FloatSetting HUE_DEGREES = clampedFloat(
                    "fluorite.rt.postProcessing.hue", "post-processing.color-grading.hue-degrees",
                    0.0f, -180.0f, 180.0f);
            public static final FloatSetting SHADOW_EXPOSURE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.shadowExposure",
                    "post-processing.color-grading.shadows.exposure-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting SHADOW_RED_EV = clampedFloat(
                    "fluorite.rt.postProcessing.shadowRed",
                    "post-processing.color-grading.shadows.red-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting SHADOW_GREEN_EV = clampedFloat(
                    "fluorite.rt.postProcessing.shadowGreen",
                    "post-processing.color-grading.shadows.green-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting SHADOW_BLUE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.shadowBlue",
                    "post-processing.color-grading.shadows.blue-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting SHADOW_SATURATION = clampedFloat(
                    "fluorite.rt.postProcessing.shadowSaturation",
                    "post-processing.color-grading.shadows.saturation", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting SHADOW_CONTRAST = clampedFloat(
                    "fluorite.rt.postProcessing.shadowContrast",
                    "post-processing.color-grading.shadows.contrast", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting MID_EXPOSURE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.midExposure",
                    "post-processing.color-grading.midtones.exposure-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting MID_RED_EV = clampedFloat(
                    "fluorite.rt.postProcessing.midRed",
                    "post-processing.color-grading.midtones.red-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting MID_GREEN_EV = clampedFloat(
                    "fluorite.rt.postProcessing.midGreen",
                    "post-processing.color-grading.midtones.green-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting MID_BLUE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.midBlue",
                    "post-processing.color-grading.midtones.blue-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting MID_SATURATION = clampedFloat(
                    "fluorite.rt.postProcessing.midSaturation",
                    "post-processing.color-grading.midtones.saturation", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting MID_CONTRAST = clampedFloat(
                    "fluorite.rt.postProcessing.midContrast",
                    "post-processing.color-grading.midtones.contrast", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_EXPOSURE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.highlightExposure",
                    "post-processing.color-grading.highlights.exposure-ev", 0.0f, -4.0f, 4.0f);
            public static final FloatSetting HIGHLIGHT_RED_EV = clampedFloat(
                    "fluorite.rt.postProcessing.highlightRed",
                    "post-processing.color-grading.highlights.red-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_GREEN_EV = clampedFloat(
                    "fluorite.rt.postProcessing.highlightGreen",
                    "post-processing.color-grading.highlights.green-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_BLUE_EV = clampedFloat(
                    "fluorite.rt.postProcessing.highlightBlue",
                    "post-processing.color-grading.highlights.blue-ev", 0.0f, -2.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_SATURATION = clampedFloat(
                    "fluorite.rt.postProcessing.highlightSaturation",
                    "post-processing.color-grading.highlights.saturation", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_CONTRAST = clampedFloat(
                    "fluorite.rt.postProcessing.highlightContrast",
                    "post-processing.color-grading.highlights.contrast", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting SHADOW_BOUNDARY_EV = clampedFloat(
                    "fluorite.rt.postProcessing.shadowBoundary",
                    "post-processing.color-grading.ranges.shadow-boundary-ev", -2.0f, -8.0f, 0.0f);
            public static final FloatSetting HIGHLIGHT_BOUNDARY_EV = clampedFloat(
                    "fluorite.rt.postProcessing.highlightBoundary",
                    "post-processing.color-grading.ranges.highlight-boundary-ev", 2.0f, 0.0f, 8.0f);
            public static final BooleanSetting FILM_GRAIN_ENABLED = bool(
                    "fluorite.rt.postProcessing.filmGrain",
                    "post-processing.film-grain.enabled", false);
            public static final FloatSetting FILM_GRAIN_INTENSITY = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainIntensity",
                    "post-processing.film-grain.intensity", 0.2f, 0.0f, 1.0f);
            public static final FloatSetting FILM_GRAIN_SIZE = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainSize",
                    "post-processing.film-grain.size-px", 1.0f, 0.5f, 4.0f);
            public static final FloatSetting FILM_GRAIN_CHROMATIC = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainChromatic",
                    "post-processing.film-grain.chromatic-separation", 0.35f, 0.0f, 1.0f);
            public static final FloatSetting FILM_GRAIN_SHADOWS = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainShadows",
                    "post-processing.film-grain.shadows", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting FILM_GRAIN_MIDTONES = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainMidtones",
                    "post-processing.film-grain.midtones", 1.0f, 0.0f, 2.0f);
            public static final FloatSetting FILM_GRAIN_HIGHLIGHTS = clampedFloat(
                    "fluorite.rt.postProcessing.filmGrainHighlights",
                    "post-processing.film-grain.highlights", 0.5f, 0.0f, 2.0f);
            public static final FloatSetting HIGHLIGHT_FILTER_THRESHOLD = clampedFloat(
                    "fluorite.rt.postProcessing.highlightThreshold",
                    "post-processing.highlights.threshold", 1.0f, 0.0f, 16.0f);
            public static final FloatSetting HIGHLIGHT_FILTER_SOFT_KNEE = clampedFloat(
                    "fluorite.rt.postProcessing.highlightSoftKnee",
                    "post-processing.highlights.soft-knee", 0.5f, 0.0f, 1.0f);
            public static final BooleanSetting BLOOM_ENABLED = bool(
                    "fluorite.rt.postProcessing.bloom", "post-processing.bloom.enabled", false);
            public static final FloatSetting BLOOM_INTENSITY = clampedFloat(
                    "fluorite.rt.postProcessing.bloomIntensity",
                    "post-processing.bloom.intensity", 0.2f, 0.0f, 2.0f);
            public static final FloatSetting BLOOM_RADIUS = clampedFloat(
                    "fluorite.rt.postProcessing.bloomRadius",
                    "post-processing.bloom.radius", 0.65f, 0.0f, 1.0f);
            public static final BooleanSetting LENS_FLARE_ENABLED = bool(
                    "fluorite.rt.postProcessing.lensFlare", "post-processing.lens-flare.enabled", false);
            public static final FloatSetting LENS_FLARE_INTENSITY = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareIntensity",
                    "post-processing.lens-flare.intensity", 0.2f, 0.0f, 2.0f);
            public static final FloatSetting LENS_FLARE_GHOSTS = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareGhosts",
                    "post-processing.lens-flare.ghosts", 0.7f, 0.0f, 2.0f);
            public static final FloatSetting LENS_FLARE_HALO = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareHalo",
                    "post-processing.lens-flare.halo", 0.35f, 0.0f, 2.0f);
            public static final FloatSetting LENS_FLARE_STREAKS = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareStreaks",
                    "post-processing.lens-flare.streaks", 0.2f, 0.0f, 2.0f);
            public static final FloatSetting LENS_FLARE_THRESHOLD = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareThreshold",
                    "post-processing.lens-flare.threshold", 4.0f, 0.0f, 32.0f);
            public static final FloatSetting LENS_FLARE_BOKEH_SIZE = clampedFloat(
                    "fluorite.rt.postProcessing.lensFlareBokehSize",
                    "post-processing.lens-flare.bokeh-size-px", 12.0f, 2.0f, 32.0f);
            public static final BooleanSetting DEPTH_OF_FIELD_ENABLED = bool(
                    "fluorite.rt.postProcessing.depthOfField", "post-processing.lens.depth-of-field.enabled", false);
            public static final StringSetting DEPTH_OF_FIELD_FOCUS_MODE = string(
                    "fluorite.rt.postProcessing.depthOfFieldFocusMode",
                    "post-processing.lens.depth-of-field.focus-mode", "auto",
                    PostProcessing::sanitizeFocusMode);
            public static final FloatSetting DEPTH_OF_FIELD_FOCUS_DISTANCE = clampedFloat(
                    "fluorite.rt.postProcessing.depthOfFieldFocusDistance",
                    "post-processing.lens.depth-of-field.focus-distance", 10.0f, 0.5f, 256.0f);
            public static final FloatSetting DEPTH_OF_FIELD_F_STOP = clampedFloat(
                    "fluorite.rt.postProcessing.depthOfFieldFStop",
                    "post-processing.lens.depth-of-field.f-stop", 4.0f, 0.7f, 32.0f);
            public static final FloatSetting DEPTH_OF_FIELD_MAX_RADIUS = clampedFloat(
                    "fluorite.rt.postProcessing.depthOfFieldMaxRadius",
                    "post-processing.lens.depth-of-field.max-radius-px", 32.0f, 0.0f, 64.0f);
            public static final IntSetting DEPTH_OF_FIELD_APERTURE_BLADES = new IntSetting(
                    "fluorite.rt.postProcessing.depthOfFieldApertureBlades",
                    "post-processing.lens.depth-of-field.aperture-blades", 0,
                    value -> value == 0 ? 0 : Math.clamp(value, 5, 9));
            public static final BooleanSetting MOTION_BLUR_ENABLED = bool(
                    "fluorite.rt.postProcessing.motionBlur", "post-processing.lens.motion-blur.enabled", false);
            public static final FloatSetting MOTION_BLUR_SHUTTER_ANGLE = clampedFloat(
                    "fluorite.rt.postProcessing.motionBlurShutterAngle",
                    "post-processing.lens.motion-blur.shutter-angle", 180.0f, 0.0f, 360.0f);
            public static final IntSetting MOTION_BLUR_SAMPLES = new IntSetting(
                    "fluorite.rt.postProcessing.motionBlurSamples",
                    "post-processing.lens.motion-blur.samples", 16,
                    value -> value <= 8 ? 8 : 16);
            public static final FloatSetting MOTION_BLUR_MAX_RADIUS = clampedFloat(
                    "fluorite.rt.postProcessing.motionBlurMaxRadius",
                    "post-processing.lens.motion-blur.max-radius-px", 32.0f, 0.0f, 64.0f);
            public static final BooleanSetting LENS_DISTORTION_ENABLED = bool(
                    "fluorite.rt.postProcessing.lensDistortion",
                    "post-processing.lens.distortion.enabled", false);
            public static final FloatSetting LENS_DISTORTION_STRENGTH = clampedFloat(
                    "fluorite.rt.postProcessing.lensDistortionStrength",
                    "post-processing.lens.distortion.strength", 0.0f, -1.0f, 1.0f);
            public static final BooleanSetting CHROMATIC_ABERRATION_ENABLED = bool(
                    "fluorite.rt.postProcessing.chromaticAberration",
                    "post-processing.lens.chromatic-aberration.enabled", false);
            public static final FloatSetting CHROMATIC_ABERRATION_STRENGTH = clampedFloat(
                    "fluorite.rt.postProcessing.chromaticAberrationStrength",
                    "post-processing.lens.chromatic-aberration.strength-px", 0.0f, 0.0f, 8.0f);
            public static final BooleanSetting VIGNETTE_ENABLED = bool(
                    "fluorite.rt.postProcessing.vignette", "post-processing.lens.vignette.enabled", false);
            public static final FloatSetting VIGNETTE_INTENSITY = clampedFloat(
                    "fluorite.rt.postProcessing.vignetteIntensity",
                    "post-processing.lens.vignette.intensity", 0.0f, 0.0f, 1.0f);
            public static final FloatSetting VIGNETTE_START = clampedFloat(
                    "fluorite.rt.postProcessing.vignetteStart",
                    "post-processing.lens.vignette.start", 0.5f, 0.0f, 1.0f);
            public static final FloatSetting VIGNETTE_SOFTNESS = clampedFloat(
                    "fluorite.rt.postProcessing.vignetteSoftness",
                    "post-processing.lens.vignette.softness", 0.5f, 0.05f, 1.0f);

            private PostProcessing() {
            }

            /** Maps an approved peak to the compact shader selector: 500, 1000, 2000, or 4000 nit. */
            public static int acesHdrPresetNits() {
                return ACES_HDR_PRESET.value();
            }

            /** 0 = AgX, 1 = sampled 65^3 ACES 2 LUT, 2 = exact analytic ACES 2. */
            public static int outputTransformMode() {
                return switch (OUTPUT_TRANSFORM.get()) {
                    case "aces2-lut" -> 1;
                    case "aces2-exact" -> 2;
                    default -> 0;
                };
            }

            private static String sanitizeOutputTransform(String value) {
                if ("aces2-lut".equalsIgnoreCase(value)) return "aces2-lut";
                // Migrate the short-lived D134 value without changing what an existing selection meant.
                if ("aces2".equalsIgnoreCase(value) || "aces2-exact".equalsIgnoreCase(value)) {
                    return "aces2-exact";
                }
                return "agx";
            }

            private static int sanitizeAcesHdrPreset(int value) {
                return value == 500 || value == 1000 || value == 2000 || value == 4000 ? value : 1000;
            }

            public static boolean automaticFocus() {
                return "auto".equals(DEPTH_OF_FIELD_FOCUS_MODE.get());
            }

            public static boolean spatialLensEffectsEnabled() {
                return DEPTH_OF_FIELD_ENABLED.value() || MOTION_BLUR_ENABLED.value();
            }

            private static String sanitizeFocusMode(String value) {
                return "manual".equalsIgnoreCase(value) ? "manual" : "auto";
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

    /** Moves one compatible legacy numeric value to its renamed path before the setting resolves it. */
    private static FloatSetting migratingClampedFloat(String key, String tomlPath, String legacyTomlPath,
                                                       float fallback, float min, float max) {
        synchronized (FluoriteConfig.class) {
            if (!FILE.contains(tomlPath) && FILE.contains(legacyTomlPath)) {
                Object legacy = FILE.get(legacyTomlPath);
                if (legacy instanceof Number) FILE.set(tomlPath, legacy);
                FILE.remove(legacyTomlPath);
            }
        }
        return clampedFloat(key, tomlPath, fallback, min, max);
    }

    private static FloatSetting radians(String key, String tomlPath, float fallbackDegrees) {
        return new FloatSetting(key, tomlPath, fallbackDegrees, Math::toRadians, Math::toDegrees, v -> Double.isFinite(v) ? v : 0.0);
    }

    private static int defaultWorkerThreads() {
        return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
    }
}
