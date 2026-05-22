package com.foodtweaker.neoforge;

import com.foodtweaker.FoodTweaker;
import net.neoforged.fml.common.Mod;

@Mod(FoodTweaker.MOD_ID)
public final class FoodTweakerNeoForge {
    public FoodTweakerNeoForge() {
        FoodTweaker.init();
    }
}
