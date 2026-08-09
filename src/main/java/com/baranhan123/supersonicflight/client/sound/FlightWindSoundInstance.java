package com.baranhan123.supersonicflight.client.sound;

import com.baranhan123.supersonicflight.config.SupersonicConfigClient;
import com.baranhan123.supersonicflight.util.FlightState;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

public class FlightWindSoundInstance extends AbstractTickableSoundInstance {

    private final LocalPlayer player;
    private final SupersonicFlightPlayer flightPlayer;

    public FlightWindSoundInstance(LocalPlayer player, SoundEvent soundEvent) {
        super(soundEvent, SoundSource.PLAYERS, RandomSource.create());
        this.player = player;
        this.flightPlayer = (SupersonicFlightPlayer) player;
        this.looping = true;
        this.delay = 0;
        this.volume = 0.0f;
        this.pitch = 1.0f;
        this.relative = true;
        this.attenuation = SoundInstance.Attenuation.NONE;
    }

    @Override
    public void tick() {
        if (player.isRemoved()) {
            this.stop();
            return;
        }

        FlightState state = flightPlayer.getFlightState();
        if (state == FlightState.NONE) {
            this.stop();
            return;
        }

        float throttle = flightPlayer.getFlightThrottle();
        float targetVolume = throttle * SupersonicConfigClient.INSTANCE.windVolumeMultiplier;

        // Smooth volume transition
        this.volume += (targetVolume - this.volume) * 0.1f;

        // Adjust pitch based on speed
        float throttleLerped = flightPlayer.getLerpedFlightThrottle(1.0f);
        this.pitch = 0.5f + throttleLerped * 1.5f;

        // Update position
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }

    @Override
    public boolean canPlaySound() {
        return !player.isSilent();
    }
}
