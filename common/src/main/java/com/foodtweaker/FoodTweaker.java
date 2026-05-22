package com.foodtweaker;

import com.foodtweaker.FoodTweakerConfig.EffectEntry;
import com.foodtweaker.FoodTweakerConfig.FoodOverride;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponentMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FoodTweaker {
    public static final String MOD_ID = "foodtweaker";
    public static final Logger LOGGER = LoggerFactory.getLogger("FoodTweaker");

    // Item stores its default data components in a private final field of type DataComponentMap.
    // We swap that map out at runtime so newly-created ItemStacks pick up our food overrides.
    private static final Field ITEM_COMPONENTS_FIELD = findComponentsField();

    // The components map each item had before we ever touched it, so a reload can revert cleanly.
    private static final Map<Item, DataComponentMap> ORIGINALS = new IdentityHashMap<>();

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
        if (ITEM_COMPONENTS_FIELD == null) {
            LOGGER.error("[FoodTweaker] Could not access the Item components field; cannot apply any overrides.");
            return 0;
        }

        // Revert anything we changed previously so removed/edited entries don't stack across reloads.
        for (Map.Entry<Item, DataComponentMap> e : ORIGINALS.entrySet()) {
            setComponents(e.getKey(), e.getValue());
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

        DataComponentMap original = ORIGINALS.computeIfAbsent(item, Item::components);
        FoodProperties existing = original.get(DataComponents.FOOD);

        Integer nutrition = o.nutrition != null ? o.nutrition : (existing != null ? existing.nutrition() : null);
        Float saturation = o.saturation != null ? o.saturation : (existing != null ? existing.saturation() : null);
        if (nutrition == null || saturation == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not currently a food, so 'nutrition' and 'saturation' are both required to make it edible - skipping.", idStr);
            return false;
        }

        boolean alwaysEat = o.canAlwaysEat != null ? o.canAlwaysEat : (existing != null && existing.canAlwaysEat());
        float eatSeconds = o.eatSeconds != null ? o.eatSeconds : (existing != null ? existing.eatSeconds() : 1.6f);

        List<FoodProperties.PossibleEffect> effects;
        if (o.effects != null) {
            effects = new ArrayList<>();
            for (EffectEntry ee : o.effects) {
                FoodProperties.PossibleEffect pe = buildEffect(ee, idStr);
                if (pe != null) {
                    effects.add(pe);
                }
            }
        } else {
            effects = existing != null ? existing.effects() : List.of();
        }

        // Preserve the "leftover" item (e.g. a bowl from stew) if the item already had one.
        Optional<ItemStack> usingConvertsTo = existing != null ? existing.usingConvertsTo() : Optional.empty();
        FoodProperties food = new FoodProperties(nutrition, saturation, alwaysEat, eatSeconds, usingConvertsTo, effects);
        DataComponentMap patched = DataComponentMap.builder()
                .addAll(original)
                .set(DataComponents.FOOD, food)
                .build();
        setComponents(item, patched);

        if (config.logChanges) {
            LOGGER.info("[FoodTweaker] {} -> nutrition={}, saturation={}, alwaysEdible={}, eatSeconds={}, effects={}",
                    idStr, nutrition, saturation, alwaysEat, eatSeconds, effects.size());
        }
        return true;
    }

    private static FoodProperties.PossibleEffect buildEffect(EffectEntry ee, String foodId) {
        if (ee.id == null) {
            LOGGER.warn("[FoodTweaker] An effect on '{}' has no 'id' - skipping that effect.", foodId);
            return null;
        }
        ResourceLocation effId = ResourceLocation.tryParse(ee.id);
        if (effId == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not a valid effect id (on food '{}') - skipping that effect.", ee.id, foodId);
            return null;
        }
        Optional<Holder.Reference<MobEffect>> holder =
                BuiltInRegistries.MOB_EFFECT.getHolder(ResourceKey.create(Registries.MOB_EFFECT, effId));
        if (holder.isEmpty()) {
            LOGGER.warn("[FoodTweaker] Effect '{}' is not registered (is that mod installed?) on food '{}' - skipping that effect.", ee.id, foodId);
            return null;
        }
        int durationTicks = Math.max(1, Math.round(ee.durationSeconds * 20f));
        MobEffectInstance instance = new MobEffectInstance(
                holder.get(), durationTicks, ee.amplifier, ee.ambient, ee.showParticles, ee.showIcon);
        return new FoodProperties.PossibleEffect(instance, ee.probability);
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

    private static void setComponents(Item item, DataComponentMap map) {
        try {
            ITEM_COMPONENTS_FIELD.set(item, map);
        } catch (IllegalAccessException e) {
            LOGGER.error("[FoodTweaker] Failed to update components on an item", e);
        }
    }

    private static Field findComponentsField() {
        try {
            Field f = Item.class.getDeclaredField("components");
            f.setAccessible(true);
            return f;
        } catch (NoSuchFieldException ignored) {
            // Fall back to locating it by type in case the name differs under some mapping set.
            for (Field f : Item.class.getDeclaredFields()) {
                if (DataComponentMap.class.equals(f.getType())) {
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
