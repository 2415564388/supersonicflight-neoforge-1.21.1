package com.baranhan123.supersonicflight.registry;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

public class ModSounds {
    public static final String MOD_ID = "supersonicflight";

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, MOD_ID);

    public static final Supplier<SoundEvent> SONIC_BOOM =
            SOUNDS.register("sonic_boom", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "sonic_boom")));

    public static final Supplier<SoundEvent> WIND_LOOP =
            SOUNDS.register("wind_loop", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "wind_loop")));

    public static final Supplier<SoundEvent> TAKEOFF =
            SOUNDS.register("takeoff", () -> SoundEvent.createVariableRangeEvent(
                    ResourceLocation.fromNamespaceAndPath(MOD_ID, "takeoff")));
}
