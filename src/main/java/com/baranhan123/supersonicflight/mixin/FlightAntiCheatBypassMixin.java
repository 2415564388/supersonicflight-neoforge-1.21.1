package com.baranhan123.supersonicflight.mixin;

import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bypasses server movement anti-cheat for players in CRUISE or SONIC flight states.
 * Injects into the ServerGamePacketListenerImpl to prevent "moved too quickly" kicks.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class FlightAntiCheatBypassMixin {

    @Shadow
    public ServerPlayer player;

    @Shadow
    private int receivedMovePacketCount;

    @Shadow
    private double firstGoodX, firstGoodY, firstGoodZ;

    /**
     * Reset the movement check counters at the beginning of handleMovePlayer
     * when the player is in high-speed flight mode.
     */
    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void resetMovementCheckOnFlight(CallbackInfo ci) {
        if (player instanceof SupersonicFlightPlayer flightPlayer) {
            FlightState state = flightPlayer.getFlightState();
            if (state == FlightState.LAUNCH || state == FlightState.SONIC) {
                // Reset movement check tracking to avoid false positives
                receivedMovePacketCount = 0;
                firstGoodX = player.getX();
                firstGoodY = player.getY();
                firstGoodZ = player.getZ();
            }
        }
    }
}
