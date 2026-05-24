package com.foodtweaker.forge;

import com.foodtweaker.FoodTweaker;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(FoodTweaker.MOD_ID)
public final class FoodTweakerForge {
    public FoodTweakerForge() {
        // Submit our event bus to let Architectury API register our content at the right time.
        EventBuses.registerModEventBus(FoodTweaker.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        FoodTweaker.init();
    }
}
