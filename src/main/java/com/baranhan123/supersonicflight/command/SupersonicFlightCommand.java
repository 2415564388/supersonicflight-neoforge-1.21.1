package com.baranhan123.supersonicflight.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public class SupersonicFlightCommand {

    @SubscribeEvent
    public static void onCommandRegister(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(
            Commands.literal("pulsar")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("super")
                    .executes(ctx -> executeToggle(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                    .then(Commands.argument("target", EntityArgument.player())
                        .executes(ctx -> executeToggle(
                            ctx.getSource(),
                            EntityArgument.getPlayer(ctx, "target")
                        ))
                    )
                )
        );
    }

    private static int executeToggle(CommandSourceStack source, ServerPlayer target) {
        com.baranhan123.supersonicflight.util.SupersonicFlightPlayer flightPlayer =
                (com.baranhan123.supersonicflight.util.SupersonicFlightPlayer) target;
        boolean wasEnabled = flightPlayer.isFlightEnabled();
        flightPlayer.setFlightEnabled(!wasEnabled);

        String msg = wasEnabled ? "脉门-关" : "脉门-开";
        target.sendSystemMessage(Component.literal(msg));

        return 1;
    }
}
