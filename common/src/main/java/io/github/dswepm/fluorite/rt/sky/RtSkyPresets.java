package io.github.dswepm.fluorite.rt.sky;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.dswepm.fluorite.FluoriteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;

import java.io.Reader;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Versioned, resource-pack-overridable dimension sky presets. */
public final class RtSkyPresets {
    public static final int FORMAT = 2;
    public static final RtSkyPresets EMPTY = new RtSkyPresets(Map.of());
    private static final String RESOURCE_ROOT = "fluorite/sky/";

    private final Map<Identifier, RtSkyPreset> presets;

    private RtSkyPresets(Map<Identifier, RtSkyPreset> presets) {
        this.presets = Map.copyOf(presets);
    }

    public static RtSkyPresets load() {
        Map<Identifier, Resource> resources = Minecraft.getInstance().getResourceManager().listResources(
                "fluorite/sky", id -> id.getPath().endsWith(".json"));
        Map<Identifier, RtSkyPreset> parsed = new HashMap<>();
        var orderedResources = resources.entrySet().stream()
                .sorted(Comparator.comparing(entry -> entry.getKey().toString()))
                .toList();
        for (Map.Entry<Identifier, Resource> entry : orderedResources) {
            try (Reader reader = entry.getValue().openAsReader()) {
                Identifier dimension = dimensionFromResource(entry.getKey());
                parsed.put(dimension, parse(JsonParser.parseReader(reader).getAsJsonObject(), entry.getKey()));
            } catch (Exception exception) {
                // One bad resource-pack dimension must not remove the atmosphere from every other world.
                FluoriteMod.LOGGER.warn("Ignoring invalid RT sky preset {}", entry.getKey(), exception);
            }
        }
        FluoriteMod.LOGGER.info("RT sky presets: format={}, dimensions={}", FORMAT, parsed.size());
        return parsed.isEmpty() ? EMPTY : new RtSkyPresets(parsed);
    }

    /** Unknown dimensions deliberately receive the complete atmosphere, never a heuristic downgrade. */
    public RtSkyPreset forDimension(Identifier dimension) {
        return presets.getOrDefault(dimension, RtSkyPreset.FULL_ATMOSPHERE);
    }

    /** Distinct environment resources that must be uploaded for this resource epoch. */
    public Collection<RtSkyPreset.Environment> environments() {
        return presets.values().stream()
                .map(RtSkyPreset::environment)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
    }

    static RtSkyPresets of(Map<Identifier, RtSkyPreset> presets) {
        return new RtSkyPresets(presets);
    }

    static Identifier dimensionFromResource(Identifier source) {
        if (!"fluorite".equals(source.getNamespace())) {
            throw new IllegalArgumentException("Sky presets must use the fluorite resource namespace");
        }
        String path = source.getPath();
        if (!path.startsWith(RESOURCE_ROOT) || !path.endsWith(".json")) {
            throw new IllegalArgumentException("Sky preset must be under " + RESOURCE_ROOT);
        }
        String relative = path.substring(RESOURCE_ROOT.length(), path.length() - ".json".length());
        int slash = relative.indexOf('/');
        if (slash <= 0 || slash == relative.length() - 1) {
            throw new IllegalArgumentException("Sky preset path must be <namespace>/<dimension>.json");
        }
        return Identifier.fromNamespaceAndPath(relative.substring(0, slash), relative.substring(slash + 1));
    }

    static RtSkyPreset parse(JsonObject root, Identifier source) {
        int format = requiredInt(root, "format");
        if (format < 1 || format > FORMAT) {
            throw new IllegalArgumentException("Unsupported sky preset format " + format + " in " + source);
        }

        JsonObject sky = requiredObject(root, "sky");
        RtSkyPreset.SkyProvider provider = switch (requiredString(sky, "provider")) {
            case "atmosphere" -> RtSkyPreset.SkyProvider.ATMOSPHERE;
            case "local_ambient" -> RtSkyPreset.SkyProvider.LOCAL_AMBIENT;
            case "environment" -> {
                if (format < 2) {
                    throw new IllegalArgumentException("The environment provider requires sky preset format 2");
                }
                yield RtSkyPreset.SkyProvider.ENVIRONMENT;
            }
            default -> throw new IllegalArgumentException("Unknown sky provider in " + source);
        };
        RtSkyPreset.Rgb ambient = rgb(sky, "ambient_radiance", 0f, Float.MAX_VALUE);
        RtSkyPreset.Environment environment = provider == RtSkyPreset.SkyProvider.ENVIRONMENT
                ? environment(requiredObject(sky, "environment"), source) : null;

        boolean weather = requiredBoolean(requiredObject(root, "weather"), "enabled");
        boolean clouds = requiredBoolean(requiredObject(root, "clouds"), "enabled");
        JsonObject fogObject = requiredObject(root, "fog");
        RtSkyPreset.FogProfile profile = switch (requiredString(fogObject, "profile")) {
            case "off" -> RtSkyPreset.FogProfile.OFF;
            case "height" -> RtSkyPreset.FogProfile.HEIGHT;
            case "homogeneous" -> RtSkyPreset.FogProfile.HOMOGENEOUS;
            default -> throw new IllegalArgumentException("Unknown fog profile in " + source);
        };
        float density = finiteFloat(fogObject, "density", 0f, Float.MAX_VALUE);
        RtSkyPreset.Rgb extinction = rgb(fogObject, "extinction", 0f, Float.MAX_VALUE);
        RtSkyPreset.Rgb albedo = rgb(fogObject, "albedo", 0f, 1f);
        float phaseG = finiteFloat(fogObject, "phase_g", -0.9f, 0.9f);
        float start = finiteFloat(fogObject, "start_distance", 0f, Float.MAX_VALUE);
        float cull = finiteFloat(fogObject, "cull_distance", 0f, Float.MAX_VALUE);
        if (cull < start) {
            throw new IllegalArgumentException("Fog cull_distance is before start_distance in " + source);
        }
        float heightScale = finiteFloat(fogObject, "height_scale", 0f, Float.MAX_VALUE);
        if (profile == RtSkyPreset.FogProfile.HEIGHT && heightScale <= 0f) {
            throw new IllegalArgumentException("Height fog requires height_scale > 0 in " + source);
        }
        float heightBase = finiteFloat(fogObject, "height_base", -Float.MAX_VALUE, Float.MAX_VALUE);
        boolean noise = requiredBoolean(fogObject, "noise");
        RtSkyPreset.AmbientVisibility ambientVisibility = switch (requiredString(fogObject, "ambient_visibility")) {
            case "sky" -> RtSkyPreset.AmbientVisibility.SKY;
            case "unoccluded" -> RtSkyPreset.AmbientVisibility.UNOCCLUDED;
            default -> throw new IllegalArgumentException("Unknown fog ambient_visibility in " + source);
        };
        boolean localLights = requiredBoolean(fogObject, "local_lights");

        if (provider == RtSkyPreset.SkyProvider.ENVIRONMENT
                && (weather || clouds || profile != RtSkyPreset.FogProfile.OFF || density != 0f)) {
            throw new IllegalArgumentException("Environment providers require weather, clouds and fog off in " + source);
        }
        return new RtSkyPreset(provider, ambient, weather, clouds,
                new RtSkyPreset.Fog(profile, density, extinction, albedo, phaseG,
                        start, cull, heightScale, heightBase, noise, ambientVisibility, localLights),
                environment);
    }

    private static RtSkyPreset.Environment environment(JsonObject object, Identifier source) {
        Identifier radiance = identifier(object, "radiance_texture", source);
        Identifier transfer = identifier(object, "transfer_texture", source);
        Identifier diskEntry = identifier(object, "disk_entry_texture", source);
        Identifier diskExit = identifier(object, "disk_exit_texture", source);
        RtSkyPreset.Vec3 direction = normalizedVec3(object, "direction", source);
        RtSkyPreset.Vec3 up = normalizedVec3(object, "up", source);
        float dot = direction.x() * up.x() + direction.y() * up.y() + direction.z() * up.z();
        if (Math.abs(dot) > 0.999f) {
            throw new IllegalArgumentException("Environment direction and up are parallel in " + source);
        }
        float halfAngle = finiteFloat(object, "light_half_angle", 0.001f, 1.2f);
        return new RtSkyPreset.Environment(radiance, transfer, diskEntry, diskExit,
                direction, up, halfAngle);
    }

    private static Identifier identifier(JsonObject object, String name, Identifier source) {
        try {
            return Identifier.parse(requiredString(object, name));
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid resource identifier " + name + " in " + source, exception);
        }
    }

    private static RtSkyPreset.Vec3 normalizedVec3(JsonObject root, String name, Identifier source) {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            throw new IllegalArgumentException("Missing vector " + name + " in " + source);
        }
        JsonArray value = root.getAsJsonArray(name);
        if (value.size() != 3) throw new IllegalArgumentException(name + " must have three components");
        float x = finiteFloat(value.get(0), name, -1f, 1f);
        float y = finiteFloat(value.get(1), name, -1f, 1f);
        float z = finiteFloat(value.get(2), name, -1f, 1f);
        float length = (float) Math.sqrt(x * x + y * y + z * z);
        if (length < 1.0e-4f) throw new IllegalArgumentException(name + " is zero in " + source);
        return new RtSkyPreset.Vec3(x / length, y / length, z / length);
    }

    private static RtSkyPreset.Rgb rgb(JsonObject root, String name, float min, float max) {
        if (!root.has(name) || !root.get(name).isJsonArray()) {
            throw new IllegalArgumentException("Missing RGB array " + name);
        }
        JsonArray value = root.getAsJsonArray(name);
        if (value.size() != 3) {
            throw new IllegalArgumentException(name + " must have exactly three components");
        }
        return new RtSkyPreset.Rgb(
                finiteFloat(value.get(0), name, min, max),
                finiteFloat(value.get(1), name, min, max),
                finiteFloat(value.get(2), name, min, max));
    }

    private static JsonObject requiredObject(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonObject()) {
            throw new IllegalArgumentException("Missing object " + name);
        }
        return root.getAsJsonObject(name);
    }

    private static String requiredString(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonPrimitive()
                || !root.getAsJsonPrimitive(name).isString()) {
            throw new IllegalArgumentException("Missing string " + name);
        }
        return root.get(name).getAsString();
    }

    private static int requiredInt(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonPrimitive()
                || !root.getAsJsonPrimitive(name).isNumber()) {
            throw new IllegalArgumentException("Missing integer " + name);
        }
        double value = root.get(name).getAsDouble();
        if (!Double.isFinite(value) || value != Math.rint(value)
                || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is not an integer");
        }
        return (int) value;
    }

    private static boolean requiredBoolean(JsonObject root, String name) {
        if (!root.has(name) || !root.get(name).isJsonPrimitive()
                || !root.getAsJsonPrimitive(name).isBoolean()) {
            throw new IllegalArgumentException("Missing boolean " + name);
        }
        return root.get(name).getAsBoolean();
    }

    private static float finiteFloat(JsonObject root, String name, float min, float max) {
        if (!root.has(name)) {
            throw new IllegalArgumentException("Missing number " + name);
        }
        return finiteFloat(root.get(name), name, min, max);
    }

    private static float finiteFloat(JsonElement element, String name, float min, float max) {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber()) {
            throw new IllegalArgumentException("Missing number " + name);
        }
        return finiteFloat(element.getAsFloat(), name, min, max);
    }

    private static float finiteFloat(float value, String name, float min, float max) {
        if (!Float.isFinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " is outside [" + min + ", " + max + "]");
        }
        return value;
    }
}
