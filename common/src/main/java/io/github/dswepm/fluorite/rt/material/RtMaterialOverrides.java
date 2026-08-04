package io.github.dswepm.fluorite.rt.material;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.dswepm.fluorite.FluoriteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.level.block.state.BlockState;

import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Optional resource-pack material properties compiled ahead of LabPBR and engine heuristics. */
public final class RtMaterialOverrides {
    /**
     * Format 2 adds the Disney parameter blocks (sheen, clearcoat, specular, anisotropy, subsurface).
     * Format 1 files remain loadable and mean exactly what they meant: no Disney parameters, so no
     * extension record and no change in appearance.
     */
    public static final int FORMAT = 2;
    public static final RtMaterialOverrides EMPTY = new RtMaterialOverrides(List.of());

    private final List<Rule> rules;

    private RtMaterialOverrides(List<Rule> rules) {
        this.rules = List.copyOf(rules);
    }

    public static RtMaterialOverrides load() {
        Map<Identifier, Resource> resources = Minecraft.getInstance().getResourceManager().listResources(
                "fluorite/materials", id -> id.getPath().endsWith(".json"));
        List<Map.Entry<Identifier, Resource>> ordered = new ArrayList<>(resources.entrySet());
        ordered.sort(Map.Entry.comparingByKey(Comparator.comparing(Identifier::toString)));
        List<Rule> rules = new ArrayList<>();
        for (Map.Entry<Identifier, Resource> entry : ordered) {
            try (Reader reader = entry.getValue().openAsReader()) {
                rules.add(parse(JsonParser.parseReader(reader).getAsJsonObject(), entry.getKey()));
            } catch (Throwable throwable) {
                FluoriteMod.LOGGER.warn("Ignoring invalid RT material override {}", entry.getKey(), throwable);
            }
        }
        // More-specific block+sprite rules win over sprite-wide rules. Ties use the resource identifier,
        // while the resource manager has already selected the highest-priority pack for each identifier.
        rules.sort(Comparator.comparing((Rule rule) -> rule.block() == null)
                .thenComparing(rule -> rule.source().toString()));
        FluoriteMod.LOGGER.info("RT material overrides: format={}, rules={}", FORMAT, rules.size());
        return rules.isEmpty() ? EMPTY : new RtMaterialOverrides(rules);
    }

    static Rule parse(JsonObject root, Identifier source) {
        int format = requiredInt(root, "format");
        if (format < 1 || format > FORMAT) throw new IllegalArgumentException("Unsupported material format " + format);
        JsonObject match = requiredObject(root, "match");
        Identifier sprite = Identifier.parse(requiredString(match, "sprite"));
        Identifier block = match.has("block") ? Identifier.parse(match.get("block").getAsString()) : null;

        Integer model = null;
        if (root.has("model")) {
            // Every dielectric is a volume now, so the old thin/volume names described nothing. "water"
            // is the animated fluid surface (waves, caustics, biome-tint absorption); "dielectric" is
            // every other transparent material.
            model = switch (root.get("model").getAsString()) {
                case "opaque" -> RtMaterialRegistry.MODEL_OPAQUE;
                case "water" -> RtMaterialRegistry.MODEL_WATER;
                case "dielectric" -> RtMaterialRegistry.MODEL_DIELECTRIC;
                default -> throw new IllegalArgumentException("Unknown material model");
            };
        }
        Float roughness = null;
        Float metalness = null;
        if (root.has("base")) {
            JsonObject base = root.getAsJsonObject("base");
            // "roughness" is LINEAR roughness (GGX alpha), the same units LabPBR stores and
            // RtMaterials.Profile carries — NOT perceptual roughness. alpha = (1 - smoothness)^2.
            roughness = optionalFloat(base, "roughness");
            metalness = optionalFloat(base, "metalness");
        }
        Float emissionStrength = null;
        if (root.has("emission")) {
            JsonObject emission = root.getAsJsonObject("emission");
            emissionStrength = optionalFloat(emission, "strength");
            if (emission.has("color_source") && !"albedo".equals(emission.get("color_source").getAsString())) {
                throw new IllegalArgumentException("format 1 only supports emission color_source=albedo");
            }
        }
        Float transmission = null;
        Float ior = null;
        if (root.has("transmission")) {
            JsonObject value = root.getAsJsonObject("transmission");
            transmission = optionalFloat(value, "factor");
            ior = optionalFloat(value, "ior");
        }
        // Disney parameters. No source format in Minecraft's ecosystem carries these — LabPBR 1.3 has
        // no channel for sheen, clearcoat or anisotropy — so the material JSON is where they come from,
        // and a pack that mentions none of them leaves every one at zero and gets no extension record.
        // That is what keeps an unmodified LabPBR pack working byte for byte.
        RtMaterialDesc.Disney disney = null;
        if (root.has("sheen") || root.has("clearcoat") || root.has("specular")
                || root.has("anisotropy") || root.has("subsurface")) {
            float sheen = 0.0f;
            float sheenTint = 0.5f;      // Disney's own default: tint the sheen halfway toward albedo
            if (root.has("sheen")) {
                JsonObject value = root.getAsJsonObject("sheen");
                sheen = orDefault(optionalFloat(value, "amount"), sheen);
                sheenTint = orDefault(optionalFloat(value, "tint"), sheenTint);
            }
            float clearcoat = 0.0f;
            float clearcoatGloss = 1.0f; // Disney's default; 1 is a fully polished coat
            if (root.has("clearcoat")) {
                JsonObject value = root.getAsJsonObject("clearcoat");
                clearcoat = orDefault(optionalFloat(value, "amount"), clearcoat);
                clearcoatGloss = orDefault(optionalFloat(value, "gloss"), clearcoatGloss);
            }
            float specularTint = 0.0f;
            if (root.has("specular")) {
                specularTint = orDefault(optionalFloat(root.getAsJsonObject("specular"), "tint"), 0.0f);
            }
            float anisotropy = root.has("anisotropy")
                    ? orDefault(optionalFloat(root.getAsJsonObject("anisotropy"), "amount"), 0.0f) : 0.0f;
            float sssWeight = 0.0f;
            float sssPhaseG = 0.0f;
            float radiusR = 0.0f;
            float radiusG = 0.0f;
            float radiusB = 0.0f;
            if (root.has("subsurface")) {
                JsonObject value = root.getAsJsonObject("subsurface");
                sssWeight = orDefault(optionalFloat(value, "weight"), 0.0f);
                sssPhaseG = orDefault(optionalFloat(value, "phase"), 0.0f);
                if (value.has("radius")) {
                    var radius = value.getAsJsonArray("radius");
                    if (radius.size() != 3) {
                        throw new IllegalArgumentException("subsurface.radius must be three numbers");
                    }
                    radiusR = radius.get(0).getAsFloat();
                    radiusG = radius.get(1).getAsFloat();
                    radiusB = radius.get(2).getAsFloat();
                }
            }
            validate01("sheen.amount", sheen);
            validate01("sheen.tint", sheenTint);
            validate01("clearcoat.amount", clearcoat);
            validate01("clearcoat.gloss", clearcoatGloss);
            validate01("specular.tint", specularTint);
            validate01("subsurface.weight", sssWeight);
            if (anisotropy < -1.0f || anisotropy > 1.0f || !Float.isFinite(anisotropy)) {
                throw new IllegalArgumentException("anisotropy.amount must be in [-1,1]");
            }
            if (sssPhaseG < -1.0f || sssPhaseG > 1.0f || !Float.isFinite(sssPhaseG)) {
                throw new IllegalArgumentException("subsurface.phase must be in [-1,1]");
            }
            for (float r : new float[] {radiusR, radiusG, radiusB}) {
                if (!Float.isFinite(r) || r < 0.0f) {
                    throw new IllegalArgumentException("subsurface.radius must be non-negative");
                }
            }
            disney = new RtMaterialDesc.Disney(sheen, sheenTint, clearcoat, clearcoatGloss,
                    specularTint, anisotropy, sssWeight, sssPhaseG, radiusR, radiusG, radiusB);
        }

        validate01("roughness", roughness);
        validate01("metalness", metalness);
        validate01("transmission.factor", transmission);
        if (ior != null && (!Float.isFinite(ior) || ior <= 0.0f)) {
            throw new IllegalArgumentException("transmission.ior must be positive");
        }
        if (emissionStrength != null && !Float.isFinite(emissionStrength)) {
            throw new IllegalArgumentException("emission.strength must be finite");
        }
        if (emissionStrength != null && (emissionStrength < 0.0f || emissionStrength > 5.0f)) {
            float clamped = Math.max(0.0f, Math.min(5.0f, emissionStrength));
            FluoriteMod.LOGGER.warn("RT material override {}: emission.strength {} out of range [0,5], clamping to {}",
                    source, emissionStrength, clamped);
            emissionStrength = clamped;
        }
        return new Rule(source, sprite, block, model, roughness, metalness, ior, transmission,
                disney, emissionStrength);
    }

    public List<Rule> rules() {
        return rules;
    }

    public record Rule(Identifier source, Identifier sprite, Identifier block, Integer model,
                       Float roughness, Float metalness, Float ior, Float transmission,
                       /**
                        * Disney parameters, or null when the rule mentions none. Replaces rather than
                        * multiplies: unlike emission there is no natural value to scale, because no
                        * source format carries one.
                        */
                       RtMaterialDesc.Disney disney,
                       /**
                        * Multiplier on whatever emission the material naturally resolves to (LabPBR
                        * {@code _s}, heuristic mask, or state-uniform block light) — NOT a replacement.
                        * A material with no natural emission stays unlit no matter this value; this
                        * cannot make a block glow that wasn't already emissive.
                        */
                       Float emissionStrength) {
        boolean matchesSprite(TextureAtlasSprite value) {
            return value != null && sprite.equals(value.contents().name());
        }

        boolean matchesEntity(Identifier value) {
            return block == null && sprite.equals(value);
        }

        boolean matches(TextureAtlasSprite value, BlockState state) {
            if (!matchesSprite(value)) return false;
            return block == null || state != null && block.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        }

        RtMaterialDesc apply(RtMaterialDesc base) {
            int nextModel = model != null ? model : base.model();
            float nextRoughness = roughness != null ? roughness : base.roughness();
            float nextMetalness = metalness != null ? metalness : base.metalness();
            float nextIor = ior != null ? ior
                    : (model != null ? defaultIor(nextModel) : base.ior());
            float nextTransmission = transmission != null ? transmission
                    : (model != null ? defaultTransmission(nextModel) : base.transmission());
            // A multiplier on the base's already-resolved strength (0 when emissionSource is NONE):
            // this can brighten/dim an existing emitter but never light up a genuinely non-emissive one.
            float nextEmissionStrength = emissionStrength != null
                    ? base.emissionStrength() * emissionStrength : base.emissionStrength();
            // Mark the channels this rule actually named, so the hit shader knows to keep them through
            // the LabPBR decode. Source.OVERRIDE alone cannot say it: a rule that only sets sheen also
            // produces an OVERRIDE desc, and its inherited roughness must still lose to a real _s
            // texture. See MATERIAL_FEATURE_ROUGHNESS_AUTHORED.
            int nextFeatures = base.features()
                    | (roughness != null ? RtMaterialRegistry.FEATURE_ROUGHNESS_AUTHORED : 0)
                    | (metalness != null ? RtMaterialRegistry.FEATURE_METALNESS_AUTHORED : 0);
            return new RtMaterialDesc(nextModel, RtMaterialDesc.Source.OVERRIDE, nextFeatures,
                    nextRoughness, nextMetalness, nextIor, nextTransmission,
                    base.emissionSource(), nextEmissionStrength, base.emissionSummary(),
                    disney != null ? disney : base.disney());
        }

        private static float defaultIor(int model) {
            return model == RtMaterialRegistry.MODEL_WATER ? RtDielectrics.WATER_IOR
                    : model == RtMaterialRegistry.MODEL_DIELECTRIC ? RtDielectrics.GLASS_IOR : 1.0f;
        }

        private static float defaultTransmission(int model) {
            return model == RtMaterialRegistry.MODEL_WATER || model == RtMaterialRegistry.MODEL_DIELECTRIC
                    ? 1.0f : 0.0f;
        }
    }

    private static JsonObject requiredObject(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonObject()) throw new IllegalArgumentException("Missing object " + name);
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing string " + name);
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive()) throw new IllegalArgumentException("Missing integer " + name);
        return element.getAsInt();
    }

    private static float orDefault(Float value, float fallback) {
        return value != null ? value : fallback;
    }

    private static Float optionalFloat(JsonObject object, String name) {
        return object.has(name) ? object.get(name).getAsFloat() : null;
    }

    private static void validate01(String name, Float value) {
        if (value != null && (!Float.isFinite(value) || value < 0.0f || value > 1.0f)) {
            throw new IllegalArgumentException(name + " must be in [0,1]");
        }
    }
}
