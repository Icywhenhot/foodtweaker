package com.foodtweaker;

import com.foodtweaker.FoodTweakerConfig.EffectEntry;
import com.foodtweaker.FoodTweakerConfig.FoodOverride;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Optional;

public final class FoodTweaker {
    public static final String MOD_ID = "foodtweaker";
    public static final Logger LOGGER = LoggerFactory.getLogger("FoodTweaker");

    // 1.20.1 stores an item's food data in a private final FoodProperties field on Item.
    // We swap that field out at runtime so the item becomes edible/non-edible with our values.
    private static final Field ITEM_FOOD_FIELD = findFoodField();

    // The FoodProperties each item had before we ever touched it (null = was not edible),
    // so a reload can revert cleanly. IdentityHashMap permits null values.
    private static final Map<Item, FoodProperties> ORIGINALS = new IdentityHashMap<>();

    private static FoodTweakerConfig config = new FoodTweakerConfig();

    public static void init() {
        config = FoodTweakerConfig.load();

        // Registries are frozen by the time a server (integrated or dedicated) is about to start,
        // so every modded item exists and can be patched.
        LifecycleEvent.SERVER_BEFORE_START.register(server -> applyOverrides());

        CommandRegistrationEvent.EVENT.register(FoodTweaker::registerCommands);
    }

    /** Re-reads the config from disk and re-applies it. Returns the number of foods applied. */
    public static int reload() {
        config = FoodTweakerConfig.load();
        return applyOverrides();
    }

    public static int applyOverrides() {
        if (ITEM_FOOD_FIELD == null) {
            LOGGER.error("[FoodTweaker] Could not access the Item food field; cannot apply any overrides.");
            return 0;
        }

        // Revert anything we changed previously so removed/edited entries don't stack across reloads.
        for (Map.Entry<Item, FoodProperties> e : ORIGINALS.entrySet()) {
            setFood(e.getKey(), e.getValue());
        }

        if (!config.enabled) {
            LOGGER.info("[FoodTweaker] Disabled in config; reverted to vanilla food values.");
            return 0;
        }

        int applied = 0;
        for (Map.Entry<String, FoodOverride> entry : config.foods.entrySet()) {
            if (applyOne(entry.getKey(), entry.getValue())) {
                applied++;
            }
        }
        LOGGER.info("[FoodTweaker] Applied {} food override(s).", applied);
        return applied;
    }

    private static boolean applyOne(String idStr, FoodOverride o) {
        ResourceLocation id = ResourceLocation.tryParse(idStr);
        if (id == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not a valid item id - skipping.", idStr);
            return false;
        }
        Optional<Item> itemOpt = BuiltInRegistries.ITEM.getOptional(id);
        if (itemOpt.isEmpty()) {
            LOGGER.warn("[FoodTweaker] Item '{}' is not registered (is that mod installed?) - skipping.", idStr);
            return false;
        }
        Item item = itemOpt.get();

        if (!ORIGINALS.containsKey(item)) {
            ORIGINALS.put(item, item.getFoodProperties()); // may be null if the item was not edible
        }
        FoodProperties existing = ORIGINALS.get(item);

        Integer nutrition = o.nutrition != null ? o.nutrition : (existing != null ? existing.getNutrition() : null);
        Float saturation = o.saturation != null ? o.saturation : (existing != null ? existing.getSaturationModifier() : null);
        if (nutrition == null || saturation == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not currently a food, so 'nutrition' and 'saturation' are both required to make it edible - skipping.", idStr);
            return false;
        }

        boolean alwaysEat = o.canAlwaysEat != null ? o.canAlwaysEat : (existing != null && existing.canAlwaysEat());
        // 1.20.1 has no eat-time value, only a "fast" flag (fast foods take ~0.8s instead of ~1.6s).
        boolean fast = o.eatSeconds != null ? (o.eatSeconds <= 0.8f) : (existing != null && existing.isFastFood());
        boolean meat = existing != null && existing.isMeat();

        FoodProperties.Builder builder = new FoodProperties.Builder()
                .nutrition(nutrition)
                .saturationMod(saturation);
        if (alwaysEat) {
            builder.alwaysEat();
        }
        if (fast) {
            builder.fast();
        }
        if (meat) {
            builder.meat();
        }

        int effectCount = 0;
        if (o.effects != null) {
            // Explicit list replaces any existing effects.
            for (EffectEntry ee : o.effects) {
                if (addEffect(builder, ee, idStr)) {
                    effectCount++;
                }
            }
        } else if (existing != null) {
            // Keep the item's current effects.
            for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> pair : existing.getEffects()) {
                builder.effect(new MobEffectInstance(pair.getFirst()), pair.getSecond());
                effectCount++;
            }
        }

        FoodProperties food = builder.build();
        setFood(item, food);

        if (config.logChanges) {
            LOGGER.info("[FoodTweaker] {} -> nutrition={}, saturation={}, alwaysEdible={}, fast={}, effects={}",
                    idStr, nutrition, saturation, alwaysEat, fast, effectCount);
        }
        return true;
    }

    private static boolean addEffect(FoodProperties.Builder builder, EffectEntry ee, String foodId) {
        if (ee.id == null) {
            LOGGER.warn("[FoodTweaker] An effect on '{}' has no 'id' - skipping that effect.", foodId);
            return false;
        }
        ResourceLocation effId = ResourceLocation.tryParse(ee.id);
        if (effId == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not a valid effect id (on food '{}') - skipping that effect.", ee.id, foodId);
            return false;
        }
        Optional<MobEffect> effect = BuiltInRegistries.MOB_EFFECT.getOptional(effId);
        if (effect.isEmpty()) {
            LOGGER.warn("[FoodTweaker] Effect '{}' is not registered (is that mod installed?) on food '{}' - skipping that effect.", ee.id, foodId);
            return false;
        }
        int durationTicks = Math.max(1, Math.round(ee.durationSeconds * 20f));
        MobEffectInstance instance = new MobEffectInstance(
                effect.get(), durationTicks, ee.amplifier, ee.ambient, ee.showParticles, ee.showIcon);
        builder.effect(instance, ee.probability);
        return true;
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                         CommandBuildContext context,
                                         Commands.CommandSelection selection) {
        dispatcher.register(Commands.literal("foodtweaker")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reload").executes(ctx -> {
                    int n = reload();
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[FoodTweaker] Reloaded config and applied " + n
                                    + " food override(s). Re-grab items (e.g. /give) to see the new values."), true);
                    return n;
                })));
    }

    private static void setFood(Item item, FoodProperties food) {
        try {
            ITEM_FOOD_FIELD.set(item, food);
        } catch (IllegalAccessException e) {
            LOGGER.error("[FoodTweaker] Failed to update food on an item", e);
        }
    }

    private static Field findFoodField() {
        try {
            Field f = Item.class.getDeclaredField("foodProperties");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {
            // Fall back to locating it by type in case the name differs under some mapping set.
            for (Field f : Item.class.getDeclaredFields()) {
                if (FoodProperties.class.equals(f.getType())) {
                    f.setAccessible(true);
                    return f;
                }
            }
            return null;
        }
    }

    private FoodTweaker() {
    }
}
