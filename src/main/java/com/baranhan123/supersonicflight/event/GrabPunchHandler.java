package com.baranhan123.supersonicflight.event;

import com.baranhan123.supersonicflight.SupersonicFlight;
import com.baranhan123.supersonicflight.config.SupersonicConfig;
import com.baranhan123.supersonicflight.util.GrabPunchManager;
import com.baranhan123.supersonicflight.util.SupersonicFlightPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * Right-click wiring for the grab skill.
 *
 * <p>Holding the configured item (default {@code l2weaponry:sculkium_claw}) in both hands and
 * right-clicking a hostile mob grabs it; right-clicking again lets it go. The heavy punch is
 * **left-click only** (see {@code PlayerEntityMixin#onAttackGrabbedTarget}).
 *
 * <p>Only {@link PlayerInteractEvent.EntityInteractSpecific} performs the action.
 * {@link PlayerInteractEvent.EntityInteract} is cancelled as a backstop but deliberately does
 * nothing: vanilla can deliver both events for a single click, and with the grab being a toggle,
 * acting on both would release the mob and then immediately grab it again.
 *
 * <p>Both events must be cancelled. Cancelling only the specific one is not enough — on the client a
 * non-{@code SUCCESS} cancellation result makes vanilla fall through to {@code EntityInteract},
 * which is what actually runs {@code Item#interactLivingEntity}, so the held claw would fire its
 * own ability.
 */
@EventBusSubscriber(modid = SupersonicFlight.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public final class GrabPunchHandler {

    private GrabPunchHandler() {
    }

    @SubscribeEvent
    public static void onEntityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        Player player = event.getEntity();
        if (!isOurs(player, event.getTarget())) return;

        if (!player.level().isClientSide()) {
            SupersonicFlightPlayer core = (SupersonicFlightPlayer) player;
            if (core.getGrabbedTarget() != null) {
                // Already holding someone: this click lets go again.
                GrabPunchManager.releaseTarget(core, player);
            } else {
                core.setTryingToGrab(true);
            }
        }

        // SUCCESS is load-bearing — see the class doc.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        Player player = event.getEntity();
        if (!isOurs(player, event.getTarget())) return;

        // Cancel only; the action belongs to the specific event above.
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    /**
     * @return true if this right-click belongs to the grab skill, so vanilla should be suppressed.
     */
    private static boolean isOurs(Player player, Entity target) {
        if (!SupersonicConfig.INSTANCE.grabEnabled) return false;
        // The skill is part of the superhero power set, so it is only available once the player has
        // been granted flight via `/pulsar super`.
        if (!((SupersonicFlightPlayer) player).isFlightEnabled()) return false;
        if (!(target instanceof LivingEntity living) || !living.isAlive()) return false;
        return GrabPunchManager.isHoldingGrabItem(player);
    }
}
