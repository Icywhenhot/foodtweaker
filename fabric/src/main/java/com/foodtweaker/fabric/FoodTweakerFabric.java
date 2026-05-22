package com.foodtweaker.fabric;

import com.foodtweaker.FoodTweaker;
import net.fabricmc.api.ModInitializer;

public final class FoodTweakerFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        FoodTweaker.init();
    }
}
