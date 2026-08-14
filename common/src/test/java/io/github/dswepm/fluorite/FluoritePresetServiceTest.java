package io.github.dswepm.fluorite;

import com.electronwill.nightconfig.core.CommentedConfig;
import io.github.dswepm.fluorite.FluoriteConfig.RuntimeSetting;
import io.github.dswepm.fluorite.FluoritePresetService.ApplyMode;
import io.github.dswepm.fluorite.FluoritePresetService.PresetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FluoritePresetServiceTest {
    @TempDir
    Path temporaryDirectory;
    private int presetIndex;

    @Test
    void exportRoundTripsEveryPortableSettingAndOmitsMachineLocalState() throws Exception {
        Path requested = temporaryDirectory.resolve("complete-preset");
        FluoritePresetService.ExportResult exported = FluoritePresetService.exportPreset(requested);

        assertEquals(temporaryDirectory.resolve("complete-preset.toml"), exported.path());
        FluoritePresetService.PreparedPreset prepared =
                FluoritePresetService.preparePreset(exported.path());
        assertEquals(exported.settingCount(), prepared.staged().size());

        String toml = Files.readString(exported.path());
        assertTrue(toml.contains("format-version = 1"));
        assertTrue(toml.contains("scope = \"portable-full\""));
        assertFalse(toml.contains("ngx.path"));
        assertFalse(toml.contains("[settings.diagnostics]"));
        assertFalse(toml.contains("[settings.frame-stats]"));
        assertFalse(toml.contains("dump-radius"));

        for (RuntimeSetting<?> setting : FluoriteConfig.settings()) {
            if (FluoritePresetService.isPortable(setting)) {
                assertNotNull(prepared.staged().get(setting.tomlPath()), setting.tomlPath());
            }
        }
    }

    @Test
    void missingSettingsUseShippedExternalDefaults() throws Exception {
        Path preset = writePreset("[settings]\n");
        Map<String, Object> staged = FluoritePresetService.preparePreset(preset).staged();

        assertEquals(1, staged.get("composite.spp"));
        assertEquals(0.6, (Double) staged.get("composite.sun-angular-radius-deg"), 1.0e-6);
        assertEquals("auto", staged.get("exposure.mode"));
    }

    @Test
    void unknownWrongTypeOutOfRangeAndNonFiniteValuesRejectTheWholePreset() throws Exception {
        int before = FluoriteConfig.Rt.Composite.SPP.value();
        assertRejects("[settings.future]\nunknown = true\n", "Unknown or non-portable setting");
        assertRejects("[settings.volumetrics]\ndensity-scale = \"dense\"\n", "must be a number");
        assertRejects("[settings.weather]\nrain-slant-degrees = 999.0\n", "outside its accepted range");
        assertRejects("[settings.weather]\nrain-slant-degrees = nan\n", "must be finite");
        assertRejects("[settings.post-processing]\noutput-transform = \"future-transform\"\n", "unknown value");
        assertEquals(before, FluoriteConfig.Rt.Composite.SPP.value());
    }

    @Test
    void unsupportedVersionAndUnknownMetadataReject() throws Exception {
        Path wrongVersion = temporaryDirectory.resolve("wrong-version.toml");
        Files.writeString(wrongVersion, """
                [preset]
                format-version = 2
                scope = "portable-full"
                created-at = "2026-08-14T00:00:00Z"

                [settings]
                """);
        assertTrue(assertThrows(PresetException.class,
                () -> FluoritePresetService.preparePreset(wrongVersion)).getMessage()
                .contains("Unsupported preset format-version"));

        Path unknownMetadata = temporaryDirectory.resolve("unknown-metadata.toml");
        Files.writeString(unknownMetadata, """
                [preset]
                format-version = 1
                scope = "portable-full"
                created-at = "2026-08-14T00:00:00Z"
                author = "surprise"

                [settings]
                """);
        assertTrue(assertThrows(PresetException.class,
                () -> FluoritePresetService.preparePreset(unknownMetadata)).getMessage()
                .contains("must contain exactly"));
    }

    @Test
    void activeReplacementCreatesBackupAndPublishesCompleteFile() throws Exception {
        Path active = temporaryDirectory.resolve("fluorite.toml");
        Files.writeString(active, "old-value = true\n");
        CommentedConfig replacement = CommentedConfig.inMemory();
        replacement.set("enabled", false);

        Path backup = FluoritePresetService.replaceActiveConfig(replacement, active);

        assertEquals(active.resolveSibling("fluorite.toml.bak"), backup);
        assertEquals("old-value = true\n", Files.readString(backup));
        assertTrue(Files.readString(active).contains("enabled = false"));
    }

    @Test
    void pendingRestartValueWinsInEveryOrdinarySaveSnapshotWithoutChangingRuntime() {
        Map<String, Object> oldPending = FluoriteConfig.pendingRestartValues();
        boolean liveHdr = FluoriteConfig.Rt.Hdr.ENABLED.value();
        try {
            FluoriteConfig.installPendingRestart(Map.of("hdr.enabled", !liveHdr));
            CommentedConfig snapshot = CommentedConfig.inMemory();
            FluoriteConfig.writeSettingsSnapshot(snapshot);

            assertEquals(!liveHdr, snapshot.<Boolean>get("hdr.enabled"));
            assertEquals(liveHdr, FluoriteConfig.Rt.Hdr.ENABLED.value());
        } finally {
            FluoriteConfig.installPendingRestart(oldPending);
        }
    }

    @Test
    void applyPolicyIsConservativeAndLocksKnownResourceBoundaries() {
        assertEquals(ApplyMode.LIVE,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.Volumetrics.DENSITY_SCALE));
        assertEquals(ApplyMode.LIVE,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.PostProcessing.BLOOM_ENABLED));
        assertEquals(ApplyMode.LIVE,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.Bsdf.MIS_ENABLED));
        assertEquals(ApplyMode.RESTART,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.Hdr.ENABLED));
        assertEquals(ApplyMode.RESTART,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.Water.WATER_DEFORM_MODE));
        assertEquals(ApplyMode.RESTART,
                FluoritePresetService.applyMode(FluoriteConfig.Rt.WORKER_THREADS));
    }

    @Test
    void transformedFloatKeepsTomlDegreesInsteadOfDoubleTransformingRuntimeRadians() {
        RuntimeSetting<?> sunRadius = FluoriteConfig.Rt.Composite.SUN_ANGULAR_RADIUS;
        Object oldExternal = sunRadius.externalValue();
        try {
            Object canonical = sunRadius.validateExternalValue(12.5);
            sunRadius.applyExternalValue(canonical);
            assertEquals(12.5, (Double) sunRadius.externalValue(), 1.0e-5);
        } finally {
            sunRadius.applyExternalValue(oldExternal);
        }
    }

    @Test
    void exportedFloatAtAuthoredMinimumIsAcceptedByTheSameCodec() throws Exception {
        RuntimeSetting<?> roughness = FluoriteConfig.Rt.Weather.PUDDLE_ROUGHNESS;
        Object oldExternal = roughness.externalValue();
        try {
            roughness.applyExternalValue(0.002);
            Path exported = FluoritePresetService.exportPreset(
                    temporaryDirectory.resolve("minimum-float.toml")).path();

            Map<String, Object> staged = FluoritePresetService.preparePreset(exported).staged();
            assertEquals(0.002, (Double) staged.get(roughness.tomlPath()), 1.0e-9);
        } finally {
            roughness.applyExternalValue(oldExternal);
        }
    }

    @Test
    void floatBelowAuthoredMinimumStillRejectsAfterBoundaryRoundTripFix() throws Exception {
        assertRejects("[settings.weather]\npuddle-roughness = 0.0019\n",
                "outside its accepted range");
    }

    @Test
    void exportUsesSelectedRestartValuesButKeepsActiveSystemOverridesAuthoritative() throws Exception {
        Map<String, Object> oldPending = FluoriteConfig.pendingRestartValues();
        RuntimeSetting<?> hdr = FluoriteConfig.Rt.Hdr.ENABLED;
        RuntimeSetting<?> spp = FluoriteConfig.Rt.Composite.SPP;
        Object oldSpp = spp.externalValue();
        String oldProperty = System.getProperty(spp.key());
        try {
            boolean selectedHdr = !FluoriteConfig.Rt.Hdr.ENABLED.value();
            FluoriteConfig.installPendingRestart(Map.of(
                    hdr.tomlPath(), selectedHdr,
                    spp.tomlPath(), 99));
            spp.applyExternalValue(3);
            System.setProperty(spp.key(), "7");

            Path path = FluoritePresetService.exportPreset(temporaryDirectory.resolve("precedence.toml")).path();
            Map<String, Object> staged = FluoritePresetService.preparePreset(path).staged();
            assertEquals(selectedHdr, staged.get(hdr.tomlPath()));
            assertEquals(3, staged.get(spp.tomlPath()));
        } finally {
            if (oldProperty == null) {
                System.clearProperty(spp.key());
            } else {
                System.setProperty(spp.key(), oldProperty);
            }
            spp.applyExternalValue(oldSpp);
            FluoriteConfig.installPendingRestart(oldPending);
        }
    }

    private Path writePreset(String settingsBody) throws Exception {
        Path preset = temporaryDirectory.resolve("preset-" + presetIndex++ + ".toml");
        Files.writeString(preset, """
                [preset]
                format-version = 1
                scope = "portable-full"
                created-at = "2026-08-14T00:00:00Z"

                """ + settingsBody);
        return preset;
    }

    private void assertRejects(String settingsBody, String expectedMessage) throws Exception {
        PresetException error = assertThrows(PresetException.class,
                () -> FluoritePresetService.preparePreset(writePreset(settingsBody)));
        assertTrue(error.getMessage().contains(expectedMessage), error.getMessage());
    }
}
