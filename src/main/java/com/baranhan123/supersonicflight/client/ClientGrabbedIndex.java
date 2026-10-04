package com.baranhan123.supersonicflight.client;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.util.GrabbedEntityIndex;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Keeps {@link GrabbedEntityIndex} fresh on the client, once per tick.
 *
 * <p>The index is what lets the render mixins answer "is this entity held?" in O(1) instead of
 * scanning the player list for every entity, every frame. One pass per tick here replaces roughly
 * ten thousand per-frame iterations when several players and a few hundred entities are on screen.
 *
 * <p>Lives on its own rather than inside one of the VFX managers so that removing or reworking
 * those cannot silently leave the index stale. The server does not need this — it checks the
 * {@code SupersonicGrabbed} tag directly.
 */
@EventBusSubscriber(modid = SupersonicFlight.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientGrabbedIndex {

    private ClientGrabbedIndex() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        // refresh(null) clears the index when leaving the world.
        GrabbedEntityIndex.refresh(Minecraft.getInstance().level);
    }
}
