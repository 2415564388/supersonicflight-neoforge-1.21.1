package com.baranhan123.supersonicflight;

import com.baranhan123.supersonicflight.command.SupersonicFlightCommand;
import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.registry.ModSounds;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;

@Mod(SupersonicFlight.MOD_ID)
public class SupersonicFlight {
    public static final String MOD_ID = "supersonicflight";

    public SupersonicFlight(IEventBus modEventBus) {
        SupersonicConfig.load();
        ModSounds.SOUNDS.register(modEventBus);

        // Register command
        NeoForge.EVENT_BUS.register(SupersonicFlightCommand.class);
    }
}
