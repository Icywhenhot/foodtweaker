package com.foodtweaker;

import com.foodtweaker.FoodTweakerConfig.AttributeEntry;
import com.foodtweaker.FoodTweakerConfig.EffectEntry;
import com.foodtweaker.FoodTweakerConfig.FoodOverride;
import com.mojang.brigadier.CommandDispatcher;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.registry.ReloadListenerRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimplePreparableReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class FoodTweaker {
    public static final String MOD_ID = "foodtweaker";
    public static final Logger LOGGER = LoggerFactory.getLogger("FoodTweaker");

    private static final Field ITEM_FOOD_FIELD = findFoodField();

    private static final Map<Item, FoodProperties> ORIGINALS = new IdentityHashMap<>();

    private static final Map<Item, List<AttributeEntry>> ATTRIBUTES = new IdentityHashMap<>();

    private static FoodTweakerConfig config = new FoodTweakerConfig();

    public static void init() {
        config = FoodTweakerConfig.load();

        LifecycleEvent.SERVER_BEFORE_START.register(server -> applyOverrides());

        ReloadListenerRegistry.register(PackType.SERVER_DATA, new SimplePreparableReloadListener<Void>() {
            @Override
            protected Void prepare(ResourceManager manager, ProfilerFiller profiler) {
                return null;
            }

            @Override
            protected void apply(Void unused, ResourceManager manager, ProfilerFiller profiler) {
                FoodTweaker.reload();
            }
        }, new ResourceLocation(MOD_ID, "config"));

        CommandRegistrationEvent.EVENT.register(FoodTweaker::registerCommands);
        FoodTweakerAttributes.init();
    }

    public static List<AttributeEntry> attributesFor(Item item) {
        return ATTRIBUTES.get(item);
    }

    public static boolean logChanges() {
        return config.logChanges;
    }

    public static int reload() {
        config = FoodTweakerConfig.load();
        return applyOverrides();
    }

    public static int applyOverrides() {
        if (ITEM_FOOD_FIELD == null) {
            LOGGER.error("[FoodTweaker] Could not access the Item food field; cannot apply any overrides.");
            return 0;
        }

        for (Map.Entry<Item, FoodProperties> e : ORIGINALS.entrySet()) {
            setFood(e.getKey(), e.getValue());
        }
        ATTRIBUTES.clear();

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
            ORIGINALS.put(item, item.getFoodProperties());
        }
        FoodProperties existing = ORIGINALS.get(item);

        Integer nutrition = o.nutrition != null ? o.nutrition : (existing != null ? existing.getNutrition() : null);
        Float saturation = o.saturation != null ? o.saturation : (existing != null ? existing.getSaturationModifier() : null);
        if (nutrition == null || saturation == null) {
            LOGGER.warn("[FoodTweaker] '{}' is not currently a food, so 'nutrition' and 'saturation' are both required to make it edible - skipping.", idStr);
            return false;
        }

        boolean alwaysEat = o.canAlwaysEat != null ? o.canAlwaysEat : (existing != null && existing.canAlwaysEat());
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
            for (EffectEntry ee : o.effects) {
                if (addEffect(builder, ee, idStr)) {
                    effectCount++;
                }
            }
        } else if (existing != null) {
            for (com.mojang.datafixers.util.Pair<MobEffectInstance, Float> pair : existing.getEffects()) {
                builder.effect(new MobEffectInstance(pair.getFirst()), pair.getSecond());
                effectCount++;
            }
        }

        FoodProperties food = builder.build();
        setFood(item, food);

        int attributeCount = 0;
        if (o.attributeModifiers != null && !o.attributeModifiers.isEmpty()) {
            attributeCount = FoodTweakerAttributes.validate(BuiltInRegistries.ITEM.getKey(item).toString(), o.attributeModifiers);
            if (attributeCount > 0) {
                ATTRIBUTES.put(item, o.attributeModifiers);
            }
        }

        if (config.logChanges) {
            LOGGER.info("[FoodTweaker] {} -> nutrition={}, saturation={}, alwaysEdible={}, fast={}, effects={}, attributeModifiers={}",
                    idStr, nutrition, saturation, alwaysEat, fast, effectCount, attributeCount);
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
                    String error = FoodTweakerConfig.lastError();
                    if (error != null) {
                        ctx.getSource().sendFailure(Component.literal(
                                "[FoodTweaker] config/foodtweaker.json could not be read, so NOTHING is applied: "
                                        + error).withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal(
                            "[FoodTweaker] Reloaded config and applied " + n + " food override(s)."), true);
                    return n;
                }))
                .then(Commands.literal("reset")
                        .executes(ctx -> resetAttributes(ctx.getSource(),
                                List.of(ctx.getSource().getPlayerOrException())))
                        .then(Commands.argument("targets", EntityArgument.players())
                                .executes(ctx -> resetAttributes(ctx.getSource(),
                                        EntityArgument.getPlayers(ctx, "targets"))))));
    }

    private static int resetAttributes(CommandSourceStack source, Collection<ServerPlayer> targets) {
        int removed = 0;
        for (ServerPlayer player : targets) {
            removed += FoodTweakerAttributes.clear(player);
        }
        int total = removed;
        int playerCount = targets.size();
        source.sendSuccess(() -> Component.literal(
                "[FoodTweaker] Removed " + total + " permanent attribute modifier(s) from "
                        + playerCount + " player(s)."), true);
        return removed;
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
