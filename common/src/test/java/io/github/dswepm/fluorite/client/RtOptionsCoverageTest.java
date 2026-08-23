package io.github.dswepm.fluorite.client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every knob the settings screen names must exist in all three locales, and vice versa.
 *
 * <p>THIS EXISTS BECAUSE THE SAME MISTAKE KEEPS BEING MADE, always in the same shape: a concept that
 * lives in more than one file gets updated in whichever file was being edited at the time. M25 spent a
 * session on a struct whose two construction sites disagreed, another on a clamp raised in the config
 * while the slider kept its old ceiling, and M26 opened by shipping a setting with no way to reach it --
 * the config had it, the settings screen did not, so from inside the game the feature did not exist.
 *
 * <p>None of those are typos and none produce an error. A missing translation renders as a raw key, a
 * missing option renders as nothing at all, and a raw key is the friendlier of the two because at least
 * it appears. This test makes the quiet one loud.
 */
final class RtOptionsCoverageTest {

    private static final List<String> LOCALES = List.of("en_us", "zh_cn", "zh_tw");

    /**
     * zh_tw's standing debt, as a RATCHET rather than an exemption.
     *
     * <p>It is 259 option keys behind the other two -- the post-processing, rain-surface and cirrus
     * blocks were never carried across -- and that predates this test by a long way. Filling it is a
     * translation job, not a rendering one, and doing it inside an unrelated milestone would bury it.
     *
     * <p>So the number is written down and may only fall. New options must reach all three locales,
     * because a new key pushes the count past this ceiling and fails; the existing gap is recorded as
     * debt instead of being quietly tolerated. Lower this line whenever the debt is paid down, and
     * delete it when it reaches zero.
     */
    private static final int ZH_TW_KNOWN_GAP = 123;

    /** Keys the screen builds an option from, which the locales therefore have to carry. */
    @Test
    void everyOptionTheScreenNamesIsTranslatedInEveryLocale() throws IOException {
        Set<String> keys = optionKeys();
        assertTrue(keys.size() > 50, "expected the settings screen to name many options, found " + keys);
        for (String locale : LOCALES) {
            String lang = source("common/src/main/resources/assets/fluorite/lang/" + locale + ".json");
            List<String> missing = new ArrayList<>();
            for (String key : keys) {
                if (!lang.contains('"' + key + '"')) {
                    missing.add(key);
                }
            }
            int allowed = locale.equals("zh_tw") ? ZH_TW_KNOWN_GAP : 0;
            assertTrue(missing.size() <= allowed,
                    () -> locale + " is missing " + missing.size() + " option name(s), "
                            + allowed + " allowed: " + missing);
        }
    }

    /**
     * A setting reachable only by editing the config file is a setting most players do not have.
     *
     * <p>Checked against the switches this project actually treats as user-facing rather than against
     * every field in FluoriteConfig: diagnostics that exist for one measurement, and internal clamps,
     * legitimately have no screen entry. The list is explicit so that adding to it is a decision.
     */
    @Test
    void featureSwitchesAreReachableFromTheSettingsScreen() throws IOException {
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        List<String> mustBeReachable = List.of(
                "LIGHT_POOL",
                "LIGHT_POOL_DEPTH",
                "VOLUME_EMITTER_NEE",
                "SCATTER_VERTEX",
                "MULTI_SCATTER",
                "FOG_NOISE_ENABLED",
                "SUN_SHADOW_RAYS",
                "INSCATTER_SEGMENTS");
        List<String> unreachable = new ArrayList<>();
        for (String setting : mustBeReachable) {
            if (!options.contains(setting)) {
                unreachable.add(setting);
            }
        }
        assertTrue(unreachable.isEmpty(),
                () -> "settings with no way to reach them in game: " + unreachable);
    }

    private static Set<String> optionKeys() throws IOException {
        String options = source(
                "common/src/main/java/io/github/dswepm/fluorite/client/RtVideoOptions.java");
        // Only the leading key of a quoted "fluorite.options.rt.*" literal. Tooltips and enum value
        // labels are derived from it by suffix, and the locales are checked for the base name alone --
        // asserting on every derived suffix would encode this file's naming habits rather than a rule.
        Matcher matcher = Pattern.compile("\"(fluorite\\.options\\.rt\\.[A-Za-z0-9.]+)\"")
                .matcher(options);
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (key.endsWith(".tooltip")) {
                key = key.substring(0, key.length() - ".tooltip".length());
            }
            // Section and category headers are assembled by concatenation elsewhere; skip the stems that
            // are never a complete key on their own.
            if (key.endsWith(".") || key.equals("fluorite.options.rt.debugView")) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    private static String source(String relativePath) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("Could not locate repository root from "
                    + System.getProperty("user.dir"));
        }
        return String.join("\n", Files.readAllLines(root.resolve(relativePath)));
    }
}
