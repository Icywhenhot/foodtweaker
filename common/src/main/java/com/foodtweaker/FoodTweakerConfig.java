package com.foodtweaker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.architectury.platform.Platform;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class FoodTweakerConfig {
    public boolean enabled = true;
    public boolean logChanges = true;
    public Map<String, FoodOverride> foods = new LinkedHashMap<>();

    public static final class FoodOverride {
        public Integer nutrition;
        public Float saturation;
        public Boolean canAlwaysEat;
        public Float eatSeconds;
        public List<EffectEntry> effects;
        public List<AttributeEntry> attributeModifiers;
    }

    public static final class AttributeEntry {
        public String id;
        public double amount;
        public AttributeModifier.Operation operation = AttributeModifier.Operation.ADDITION;
        public boolean stacks = false;
        public Double maxTotal;
    }

    public static final class EffectEntry {
        public String id;
        public int amplifier = 0;
        public float durationSeconds = 30f;
        public float probability = 1.0f;
        public boolean ambient = false;
        public boolean showParticles = true;
        public boolean showIcon = true;
    }

    private static Path configPath() {
        return Platform.getConfigFolder().resolve("foodtweaker.json");
    }

    private static String lastError;

    public static String lastError() {
        return lastError;
    }

    public static FoodTweakerConfig load() {
        lastError = null;
        Path path = configPath();
        if (!Files.exists(path)) {
            writeDefault(path);
            return new FoodTweakerConfig();
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            return parse(root);
        } catch (Exception e) {
            lastError = e.getMessage() != null ? e.getMessage() : e.toString();
            FoodTweaker.LOGGER.error("[FoodTweaker] Failed to read config at {} - using defaults. Error: {}", path, e.toString());
            return new FoodTweakerConfig();
        }
    }

    private static FoodTweakerConfig parse(JsonObject root) {
        FoodTweakerConfig cfg = new FoodTweakerConfig();
        if (root.has("enabled")) {
            cfg.enabled = root.get("enabled").getAsBoolean();
        }
        if (root.has("logChanges")) {
            cfg.logChanges = root.get("logChanges").getAsBoolean();
        }
        if (root.has("foods") && root.get("foods").isJsonObject()) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("foods").entrySet()) {
                if (e.getKey().startsWith("_")) {
                    continue;
                }
                if (e.getValue().isJsonObject()) {
                    cfg.foods.put(e.getKey(), parseFood(e.getValue().getAsJsonObject()));
                }
            }
        }
        return cfg;
    }

    private static FoodOverride parseFood(JsonObject o) {
        FoodOverride f = new FoodOverride();
        if (o.has("nutrition")) {
            f.nutrition = o.get("nutrition").getAsInt();
        }
        if (o.has("saturation")) {
            f.saturation = o.get("saturation").getAsFloat();
        }
        if (o.has("can_always_eat")) {
            f.canAlwaysEat = o.get("can_always_eat").getAsBoolean();
        }
        if (o.has("eat_seconds")) {
            f.eatSeconds = o.get("eat_seconds").getAsFloat();
        }
        if (o.has("effects") && o.get("effects").isJsonArray()) {
            f.effects = new ArrayList<>();
            JsonArray arr = o.getAsJsonArray("effects");
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    f.effects.add(parseEffect(el.getAsJsonObject()));
                }
            }
        }
        if (o.has("attribute_modifiers") && o.get("attribute_modifiers").isJsonArray()) {
            f.attributeModifiers = new ArrayList<>();
            for (JsonElement el : o.getAsJsonArray("attribute_modifiers")) {
                if (el.isJsonObject()) {
                    f.attributeModifiers.add(parseAttribute(el.getAsJsonObject()));
                }
            }
        }
        return f;
    }

    private static AttributeEntry parseAttribute(JsonObject o) {
        AttributeEntry a = new AttributeEntry();
        if (o.has("id")) {
            a.id = o.get("id").getAsString();
        }
        if (o.has("amount")) {
            a.amount = o.get("amount").getAsDouble();
        }
        if (o.has("operation")) {
            a.operation = parseOperation(o.get("operation").getAsString());
        }
        if (o.has("stacks")) {
            a.stacks = o.get("stacks").getAsBoolean();
        }
        if (o.has("max_total")) {
            a.maxTotal = o.get("max_total").getAsDouble();
        }
        return a;
    }

    private static AttributeModifier.Operation parseOperation(String raw) {
        switch (raw.toLowerCase(Locale.ROOT)) {
            case "addition":
            case "add":
            case "0":
                return AttributeModifier.Operation.ADDITION;
            case "multiply_base":
            case "1":
                return AttributeModifier.Operation.MULTIPLY_BASE;
            case "multiply_total":
            case "2":
                return AttributeModifier.Operation.MULTIPLY_TOTAL;
            default:
                FoodTweaker.LOGGER.warn("[FoodTweaker] '{}' is not a known attribute operation - using 'addition'. Valid values: addition, multiply_base, multiply_total.", raw);
                return AttributeModifier.Operation.ADDITION;
        }
    }

    private static EffectEntry parseEffect(JsonObject o) {
        EffectEntry e = new EffectEntry();
        if (o.has("id")) {
            e.id = o.get("id").getAsString();
        }
        if (o.has("amplifier")) {
            e.amplifier = o.get("amplifier").getAsInt();
        }
        if (o.has("duration_seconds")) {
            e.durationSeconds = o.get("duration_seconds").getAsFloat();
        }
        if (o.has("probability")) {
            e.probability = o.get("probability").getAsFloat();
        }
        if (o.has("ambient")) {
            e.ambient = o.get("ambient").getAsBoolean();
        }
        if (o.has("show_particles")) {
            e.showParticles = o.get("show_particles").getAsBoolean();
        }
        if (o.has("show_icon")) {
            e.showIcon = o.get("show_icon").getAsBoolean();
        }
        return e;
    }

    private static void writeDefault(Path path) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
            FoodTweaker.LOGGER.info("[FoodTweaker] Wrote a default config to {}", path);
        } catch (IOException e) {
            FoodTweaker.LOGGER.error("[FoodTweaker] Could not write the default config", e);
        }
    }

    private static final String DEFAULT_JSON = """
            {
              "_comment": "FoodTweaker config. Add entries under \\"foods\\" to override food stats for ANY item (vanilla or modded). With an empty \\"foods\\" object this mod changes nothing. Run /foodtweaker reload (or plain /reload) in-game to apply edits without restarting. Every per-food field is optional - omit a field to keep the item's current value.",
              "enabled": true,
              "logChanges": true,
              "_field_reference": {
                "id_format": "Use the item id, e.g. minecraft:apple or somemod:super_steak",
                "nutrition": "int  - hunger points restored (2 = one half-drumstick)",
                "saturation": "float - saturation MODIFIER, like vanilla. Saturation gained = nutrition * saturation * 2",
                "can_always_eat": "bool  - if true, edible even on a full hunger bar (like golden apples)",
                "eat_seconds": "float - eat speed. <= 0.8 marks the food as 'fast' (eaten in ~0.8s, like dried kelp); anything higher is normal speed (~1.6s). 1.20.1 only supports these two speeds.",
                "effects": "array - status effects applied on eating. Works with vanilla AND modded effect ids.",
                "attribute_modifiers": "array - PERMANENT stat changes applied on eating (e.g. +2 max health forever). Saved with the player. Use /foodtweaker reset <player> to take them back."
              },
              "_effect_field_reference": {
                "id": "effect id, e.g. minecraft:absorption or minecraft:hunger",
                "amplifier": "int  - 0 = level I, 1 = level II, ...",
                "duration_seconds": "float - how long the effect lasts",
                "probability": "float - 0.0..1.0 chance to apply (pufferfish-style)",
                "ambient": "bool  - true = softer/beacon-style particles",
                "show_particles": "bool - show effect particles",
                "show_icon": "bool - show the effect icon in the HUD"
              },
              "_attribute_field_reference": {
                "id": "attribute id, e.g. minecraft:generic.max_health, minecraft:generic.movement_speed, minecraft:generic.attack_damage, minecraft:generic.armor, minecraft:generic.knockback_resistance, minecraft:generic.luck. Modded attribute ids work too.",
                "amount": "float - how much to grant per meal. For max_health, 2.0 = one heart.",
                "operation": "addition | multiply_base | multiply_total (default: addition). Same meaning as vanilla equipment modifiers.",
                "stacks": "bool - false (default) grants the bonus ONCE, no matter how often the food is eaten. true accumulates on every meal.",
                "max_total": "float - optional cap on the accumulated amount when stacks is true. Omit for no cap."
              },
              "_example": {
                "minecraft:apple": {
                  "nutrition": 8,
                  "saturation": 1.0,
                  "can_always_eat": true,
                  "eat_seconds": 0.8,
                  "effects": [
                    { "id": "minecraft:absorption", "amplifier": 0, "duration_seconds": 60, "probability": 1.0 }
                  ]
                },
                "minecraft:golden_apple": {
                  "_note": "eat once for a permanent extra heart; eat cooked beef repeatedly for up to +4 hearts total",
                  "attribute_modifiers": [
                    { "id": "minecraft:generic.max_health", "amount": 2.0, "operation": "addition" }
                  ]
                },
                "minecraft:cooked_beef": {
                  "attribute_modifiers": [
                    { "id": "minecraft:generic.max_health", "amount": 1.0, "operation": "addition", "stacks": true, "max_total": 8.0 }
                  ]
                },
                "minecraft:rotten_flesh": {
                  "nutrition": 4,
                  "saturation": 0.8,
                  "effects": [
                    { "id": "minecraft:regeneration", "amplifier": 1, "duration_seconds": 5 },
                    { "id": "minecraft:hunger", "amplifier": 0, "duration_seconds": 10, "probability": 0.4 }
                  ]
                }
              },
              "foods": {}
            }
            """;

    public FoodTweakerConfig() {
    }
}
