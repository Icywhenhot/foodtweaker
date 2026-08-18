package com.foodtweaker;

import com.foodtweaker.FoodTweakerConfig.AttributeEntry;
import dev.architectury.event.events.common.PlayerEvent;
import dev.architectury.event.events.common.TickEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class FoodTweakerAttributes {
    public static final String MODIFIER_PREFIX = "FoodTweaker: ";

    private static final class UseSnapshot {
        Item item;
        int remainingTicks;
        int useStat;
    }

    private static final Map<UUID, UseSnapshot> SNAPSHOTS = new HashMap<>();

    public static void init() {
        TickEvent.PLAYER_POST.register(FoodTweakerAttributes::onPlayerTick);
        PlayerEvent.PLAYER_QUIT.register(player -> SNAPSHOTS.remove(player.getUUID()));
    }

    private static void onPlayerTick(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        UUID id = serverPlayer.getUUID();
        UseSnapshot previous = SNAPSHOTS.get(id);

        if (previous != null && previous.remainingTicks == 1) {
            int useStat = serverPlayer.getStats().getValue(Stats.ITEM_USED.get(previous.item));
            if (useStat > previous.useStat) {
                onFoodEaten(serverPlayer, previous.item);
            }
        }

        Item using = serverPlayer.isUsingItem() ? serverPlayer.getUseItem().getItem() : null;
        if (using == null || FoodTweaker.attributesFor(using) == null) {
            SNAPSHOTS.remove(id);
            return;
        }
        UseSnapshot snapshot = previous != null ? previous : new UseSnapshot();
        snapshot.item = using;
        snapshot.remainingTicks = serverPlayer.getUseItemRemainingTicks();
        snapshot.useStat = serverPlayer.getStats().getValue(Stats.ITEM_USED.get(using));
        SNAPSHOTS.put(id, snapshot);
    }

    private static void onFoodEaten(ServerPlayer player, Item item) {
        List<AttributeEntry> entries = FoodTweaker.attributesFor(item);
        if (entries == null) {
            return;
        }
        String foodId = BuiltInRegistries.ITEM.getKey(item).toString();
        boolean touchedMaxHealth = false;
        for (AttributeEntry entry : entries) {
            touchedMaxHealth |= apply(player, foodId, entry);
        }
        if (touchedMaxHealth) {
            player.setHealth(player.getHealth());
        }
    }

    private static boolean apply(ServerPlayer player, String foodId, AttributeEntry entry) {
        Attribute attribute = resolve(entry.id).orElse(null);
        if (attribute == null) {
            return false;
        }
        AttributeInstance instance = player.getAttribute(attribute);
        if (instance == null) {
            return false;
        }

        UUID modifierId = modifierId(foodId, entry.id, entry.operation);
        AttributeModifier existing = instance.getModifier(modifierId);
        double amount = entry.amount;

        if (existing != null) {
            if (!entry.stacks) {
                return false;
            }
            amount = clamp(existing.getAmount() + entry.amount, entry.maxTotal);
            if (amount == existing.getAmount()) {
                return false;
            }
            instance.removeModifier(modifierId);
        } else {
            amount = clamp(amount, entry.maxTotal);
        }

        instance.addPermanentModifier(
                new AttributeModifier(modifierId, MODIFIER_PREFIX + foodId, amount, entry.operation));

        if (FoodTweaker.logChanges()) {
            FoodTweaker.LOGGER.info("[FoodTweaker] {} ate {} -> {} {} {}",
                    player.getGameProfile().getName(), foodId, entry.id, entry.operation, amount);
        }
        return attribute == Attributes.MAX_HEALTH;
    }

    public static int clear(ServerPlayer player) {
        int removed = 0;
        for (Attribute attribute : BuiltInRegistries.ATTRIBUTE) {
            AttributeInstance instance = player.getAttribute(attribute);
            if (instance == null) {
                continue;
            }
            for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
                if (modifier.getName().startsWith(MODIFIER_PREFIX)) {
                    instance.removeModifier(modifier.getId());
                    removed++;
                }
            }
        }
        if (removed > 0) {
            player.setHealth(player.getHealth());
        }
        return removed;
    }

    public static int validate(String foodId, List<AttributeEntry> entries) {
        int usable = 0;
        for (AttributeEntry entry : entries) {
            if (entry.id == null) {
                FoodTweaker.LOGGER.warn("[FoodTweaker] An attribute modifier on '{}' has no 'id' - skipping it.", foodId);
                continue;
            }
            if (resolve(entry.id).isEmpty()) {
                FoodTweaker.LOGGER.warn("[FoodTweaker] Attribute '{}' is not registered (is that mod installed?) on food '{}' - skipping it.", entry.id, foodId);
                continue;
            }
            if (entry.amount == 0.0) {
                FoodTweaker.LOGGER.warn("[FoodTweaker] The attribute modifier '{}' on food '{}' has an amount of 0 and will do nothing.", entry.id, foodId);
            }
            usable++;
        }
        return usable;
    }

    private static Optional<Attribute> resolve(String id) {
        if (id == null) {
            return Optional.empty();
        }
        ResourceLocation key = ResourceLocation.tryParse(id);
        return key == null ? Optional.empty() : BuiltInRegistries.ATTRIBUTE.getOptional(key);
    }

    private static UUID modifierId(String foodId, String attributeId, AttributeModifier.Operation operation) {
        String key = "foodtweaker|" + foodId + "|" + attributeId + "|" + operation.toValue();
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private static double clamp(double amount, Double max) {
        if (max == null) {
            return amount;
        }
        double limit = Math.abs(max);
        return Math.max(-limit, Math.min(limit, amount));
    }

    private FoodTweakerAttributes() {
    }
}
