package io.github.dswepm.fluorite;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.electronwill.nightconfig.toml.TomlWriter;
import io.github.dswepm.fluorite.FluoriteConfig.RuntimeSetting;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Versioned, portable Fluorite configuration presets.
 *
 * <p>The service is intentionally stricter than the ordinary hand-edited {@code fluorite.toml}: a preset
 * is an exchange format, so an unknown key or a value that would be silently clamped rejects the whole
 * import. Validation and file construction finish before either the live registry or the active config is
 * touched. Restart-scoped values are persisted and protected from later ordinary saves without changing
 * this process's GPU/resource state.
 */
public final class FluoritePresetService {
    public static final int FORMAT_VERSION = 1;
    public static final String SCOPE = "portable-full";

    private static final DateTimeFormatter FILE_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final Set<String> ROOT_KEYS = Set.of("preset", "settings");
    private static final Set<String> METADATA_KEYS = Set.of("format-version", "scope", "created-at");

    private FluoritePresetService() {
    }

    public enum ApplyMode {
        LIVE,
        RESTART
    }

    public record ExportResult(Path path, int settingCount) {
    }

    public record ImportResult(
            Path path,
            Path backupPath,
            int liveCount,
            int restartCount,
            int systemOverrideCount) {
        public int pendingRestartCount() {
            return restartCount;
        }
    }

    record PreparedPreset(Path source, Map<String, Object> staged) {
        PreparedPreset {
            staged = Map.copyOf(staged);
        }
    }

    public static final class PresetException extends Exception {
        public PresetException(String message) {
            super(message);
        }

        public PresetException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** Default folder approved by D157A. It is created lazily when a dialog or export needs it. */
    public static Path presetDirectory() {
        Path configParent = FluoriteConfig.configPath().toAbsolutePath().normalize().getParent();
        return configParent.resolve("fluorite-presets");
    }

    public static Path defaultExportPath() {
        return presetDirectory().resolve("fluorite-preset-" + FILE_STAMP.format(Instant.now()) + ".toml");
    }

    public static ExportResult exportPreset(Path selectedPath) throws PresetException {
        Path target = withTomlExtension(selectedPath.toAbsolutePath().normalize());
        if (target.equals(FluoriteConfig.configPath().toAbsolutePath().normalize())) {
            throw new PresetException("A preset cannot overwrite the active fluorite.toml");
        }
        synchronized (FluoriteConfig.class) {
            FluoriteConfig.ensureRegistered();
            CommentedConfig preset = CommentedConfig.inMemory();
            preset.set("preset.format-version", FORMAT_VERSION);
            preset.set("preset.scope", SCOPE);
            preset.set("preset.created-at", Instant.now().toString());

            Map<String, Object> pending = FluoriteConfig.pendingRestartValues();
            int count = 0;
            for (RuntimeSetting<?> setting : FluoriteConfig.settings()) {
                if (!isPortable(setting)) {
                    continue;
                }
                // A launch-time -D override is the actual effective value and therefore wins in an
                // export. Otherwise a selected restart value is the user's current configuration even
                // though this process still renders with the previous resource allocation.
                Object value = setting.hasSystemPropertyOverride()
                        ? setting.externalValue()
                        : pending.getOrDefault(setting.tomlPath(), setting.externalValue());
                setting.writeExternalValue(preset, "settings." + setting.tomlPath(), value);
                count++;
            }
            try {
                writeTomlAtomically(preset, target);
            } catch (IOException e) {
                throw new PresetException("Could not export preset to " + target, e);
            }
            return new ExportResult(target, count);
        }
    }

    public static ImportResult importPreset(Path selectedPath) throws PresetException {
        Path source = selectedPath.toAbsolutePath().normalize();
        synchronized (FluoriteConfig.class) {
            FluoriteConfig.ensureRegistered();
            PreparedPreset prepared = preparePreset(source);
            Map<String, Object> staged = prepared.staged();
            Map<String, RuntimeSetting<?>> portable = portableSettingsByPath();

            Map<String, Object> pending = new LinkedHashMap<>();
            int liveCount = 0;
            int restartCount = 0;
            int systemOverrideCount = 0;
            for (RuntimeSetting<?> setting : portable.values()) {
                Object value = staged.get(setting.tomlPath());
                boolean changed = !Objects.equals(value, setting.externalValue());
                if (setting.hasSystemPropertyOverride()) {
                    // -D remains the highest-precedence runtime source. Preserve the imported file value
                    // underneath it, and do not pretend the live process accepted a contradictory value.
                    if (changed) {
                        pending.put(setting.tomlPath(), value);
                    }
                    systemOverrideCount++;
                } else if (applyMode(setting) == ApplyMode.RESTART) {
                    if (changed) {
                        pending.put(setting.tomlPath(), value);
                        restartCount++;
                    }
                } else if (changed) {
                    liveCount++;
                }
            }

            CommentedConfig replacement = buildActiveConfig(staged);
            Path active = FluoriteConfig.configPath().toAbsolutePath().normalize();
            Path backup;
            try {
                backup = replaceActiveConfig(replacement, active);
            } catch (IOException e) {
                throw new PresetException("Could not atomically replace " + active, e);
            }

            // No live value changes before the durable replacement and backup have succeeded. Mirror the
            // already-built tree into NightConfig's long-lived object without a second fallible disk read.
            FluoriteConfig.synchronizeBackingFile(replacement);
            FluoriteConfig.installPendingRestart(pending);
            for (RuntimeSetting<?> setting : portable.values()) {
                if (applyMode(setting) == ApplyMode.LIVE && !setting.hasSystemPropertyOverride()) {
                    setting.applyExternalValue(staged.get(setting.tomlPath()));
                }
            }
            return new ImportResult(source, backup, liveCount, restartCount, systemOverrideCount);
        }
    }

    static boolean isPortable(RuntimeSetting<?> setting) {
        String path = setting.tomlPath();
        return !path.equals("ngx.path")
                && !path.startsWith("diagnostics.")
                && !path.startsWith("frame-stats.")
                && !path.equals("lights.stats")
                && !path.equals("lights.dump")
                && !path.equals("lights.dump-radius")
                && !path.equals("omm.stats")
                && !path.equals("entities.debug.capture-parity");
    }

    /**
     * Conservative allow-list: adding a setting to the registry never makes it hot-applied by accident.
     * Whole namespaces here are explicitly the per-frame UI surfaces; all other settings remain restart
     * scoped until their owner proves otherwise.
     */
    static ApplyMode applyMode(RuntimeSetting<?> setting) {
        String path = setting.tomlPath();
        if (path.equals("hdr.enabled") || path.equals("water.deform-mode")) {
            return ApplyMode.RESTART;
        }
        if (startsWithAny(path,
                "volumetrics.", "dimensions.", "weather.", "sky.", "water.",
                "exposure.", "post-processing.", "hdr.")) {
            return ApplyMode.LIVE;
        }
        if (Set.of(
                "composite.debug-view",
                "composite.spp",
                "composite.max-bounces",
                "composite.water-waves",
                "composite.sun-angular-radius-deg",
                "composite.wind-angle",
                "bsdf.sun-mis",
                "bsdf.anisotropy",
                "bsdf.subsurface-solid-layer",
                "bsdf.subsurface-mode",
                "bsdf.subsurface-max-events",
                "bsdf.subsurface-thickness",
                "entities.enabled",
                "particles.enabled",
                "entities.particle-shadows",
                "dlss-rr.enabled",
                "dlss-rr.quality").contains(path)) {
            return ApplyMode.LIVE;
        }
        return ApplyMode.RESTART;
    }

    static PreparedPreset preparePreset(Path selectedPath) throws PresetException {
        Path source = selectedPath.toAbsolutePath().normalize();
        FluoriteConfig.ensureRegistered();
        CommentedConfig parsed = parse(source);
        validateEnvelope(parsed);

        Map<String, RuntimeSetting<?>> portable = portableSettingsByPath();
        Map<String, Object> supplied = flattenSettings(parsed);
        for (String path : supplied.keySet()) {
            if (!portable.containsKey(path)) {
                throw new PresetException("Unknown or non-portable setting: settings." + path);
            }
        }

        // A missing key means the shipped default, not the current value: import is true replacement.
        Map<String, Object> staged = new LinkedHashMap<>();
        for (RuntimeSetting<?> setting : portable.values()) {
            Object raw = supplied.getOrDefault(setting.tomlPath(), setting.externalDefaultValue());
            try {
                staged.put(setting.tomlPath(), setting.validateExternalValue(raw));
            } catch (IllegalArgumentException e) {
                throw new PresetException("Invalid settings." + setting.tomlPath() + ": " + e.getMessage(), e);
            }
        }
        return new PreparedPreset(source, staged);
    }

    private static boolean startsWithAny(String value, String... prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, RuntimeSetting<?>> portableSettingsByPath() throws PresetException {
        Map<String, RuntimeSetting<?>> byPath = new LinkedHashMap<>();
        for (RuntimeSetting<?> setting : FluoriteConfig.settings()) {
            if (isPortable(setting) && byPath.put(setting.tomlPath(), setting) != null) {
                throw new PresetException("Duplicate registered setting path: " + setting.tomlPath());
            }
        }
        return byPath;
    }

    private static CommentedConfig parse(Path source) throws PresetException {
        if (!Files.isRegularFile(source)) {
            throw new PresetException("Preset does not exist: " + source);
        }
        try (Reader reader = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            return new TomlParser().parse(reader);
        } catch (Exception e) {
            throw new PresetException("Malformed TOML preset: " + source, e);
        }
    }

    private static void validateEnvelope(CommentedConfig root) throws PresetException {
        if (!keys(root).equals(ROOT_KEYS)) {
            throw new PresetException("Preset root must contain exactly [preset] and [settings]");
        }
        Object metadataObject = root.get("preset");
        Object settingsObject = root.get("settings");
        if (!(metadataObject instanceof UnmodifiableConfig metadata)
                || !(settingsObject instanceof UnmodifiableConfig)) {
            throw new PresetException("[preset] and [settings] must be TOML tables");
        }
        if (!keys(metadata).equals(METADATA_KEYS)) {
            throw new PresetException("[preset] must contain exactly format-version, scope, and created-at");
        }
        Object version = metadata.get("format-version");
        if (!(version instanceof Number number)
                || number.doubleValue() != FORMAT_VERSION) {
            throw new PresetException("Unsupported preset format-version: " + version);
        }
        if (!SCOPE.equals(metadata.get("scope"))) {
            throw new PresetException("Unsupported preset scope: " + metadata.get("scope"));
        }
        Object createdAt = metadata.get("created-at");
        if (!(createdAt instanceof String timestamp)) {
            throw new PresetException("preset.created-at must be an ISO-8601 string");
        }
        try {
            Instant.parse(timestamp);
        } catch (DateTimeParseException e) {
            throw new PresetException("preset.created-at is not a valid ISO-8601 instant", e);
        }
    }

    private static Map<String, Object> flattenSettings(CommentedConfig root) throws PresetException {
        Object settings = root.get("settings");
        if (!(settings instanceof UnmodifiableConfig config)) {
            throw new PresetException("[settings] must be a TOML table");
        }
        Map<String, Object> flattened = new LinkedHashMap<>();
        flatten(config, "", flattened);
        return flattened;
    }

    private static void flatten(UnmodifiableConfig config, String prefix, Map<String, Object> output)
            throws PresetException {
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            if (value instanceof UnmodifiableConfig nested) {
                flatten(nested, path, output);
            } else if (output.put(path, value) != null) {
                throw new PresetException("Duplicate setting: settings." + path);
            }
        }
    }

    private static Set<String> keys(UnmodifiableConfig config) {
        Set<String> keys = new HashSet<>();
        for (UnmodifiableConfig.Entry entry : config.entrySet()) {
            keys.add(entry.getKey());
        }
        return keys;
    }

    static CommentedConfig buildActiveConfig(Map<String, Object> staged) {
        CommentedConfig replacement = CommentedConfig.inMemory();
        FluoriteConfig.writeComments(replacement);
        for (RuntimeSetting<?> setting : FluoriteConfig.settings()) {
            if (isPortable(setting)) {
                setting.writeExternalValue(replacement, staged.get(setting.tomlPath()));
            } else if (FluoriteConfig.backingFileContains(setting.tomlPath())) {
                // Preserve the old file's raw machine-local value, not the effective runtime value: the
                // latter may have come from a -D override that must never be leaked into a shared preset
                // import or silently made persistent.
                replacement.set(setting.tomlPath(), FluoriteConfig.backingFileValue(setting.tomlPath()));
            }
        }
        return replacement;
    }

    static Path replaceActiveConfig(CommentedConfig replacement, Path active) throws IOException {
        Path parent = requireParent(active);
        Files.createDirectories(parent);
        Path prepared = Files.createTempFile(parent, active.getFileName().toString(), ".import.tmp");
        try {
            writeToml(replacement, prepared);
            Path backup = active.resolveSibling(active.getFileName() + ".bak");
            Path backupResult = null;
            if (Files.isRegularFile(active)) {
                Path backupTemp = Files.createTempFile(parent, active.getFileName().toString(), ".backup.tmp");
                try {
                    Files.copy(active, backupTemp, StandardCopyOption.REPLACE_EXISTING);
                    moveReplacing(backupTemp, backup);
                    backupResult = backup;
                } finally {
                    Files.deleteIfExists(backupTemp);
                }
            }
            moveReplacing(prepared, active);
            return backupResult;
        } finally {
            Files.deleteIfExists(prepared);
        }
    }

    private static void writeTomlAtomically(CommentedConfig config, Path target) throws IOException {
        Path parent = requireParent(target);
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            writeToml(config, temporary);
            moveReplacing(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void writeToml(CommentedConfig config, Path path) throws IOException {
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            new TomlWriter().write(config, writer);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        // D158A requires transactional replacement. An unusual filesystem that cannot promise this must
        // fail safely instead of silently degrading to a delete-then-rename window.
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static Path requireParent(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent == null) {
            throw new IOException("Path has no parent: " + path);
        }
        return parent;
    }

    private static Path withTomlExtension(Path path) {
        String name = path.getFileName().toString();
        if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".toml")) {
            return path;
        }
        return path.resolveSibling(name + ".toml");
    }
}
