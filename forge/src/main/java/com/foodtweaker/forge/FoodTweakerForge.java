package com.foodtweaker.forge;

import com.foodtweaker.FoodTweaker;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(FoodTweaker.MOD_ID)
public final class FoodTweakerForge {
    public FoodTweakerForge() {
        EventBuses.registerModEventBus(FoodTweaker.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        FoodTweaker.init();
    }
}
