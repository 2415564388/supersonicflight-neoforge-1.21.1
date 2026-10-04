package com.baranhan123.supersonicflight.util;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.HashSet;
import java.util.Set;

/**
 * Per-tick index of the entity ids currently held by a player.
 *
 * <p>Exists purely as a hot-path gate. The render mixins and the physics mixin all need to answer
 * "is this entity being held?" for <em>every</em> entity, every frame. Doing that by scanning the
 * player list each time is O(entities x players) — roughly 10k iterations per frame on a busy
 * eight-player screen, and that scan ran even when nobody was holding anything, which is the
 * overwhelmingly common case. Rebuilding this set once per tick turns each of those checks into an
 * O(1) hash lookup that short-circuits on an empty set.
 *
 * <p>Deliberately side-neutral — no client-only imports — so the common {@code Entity#push} mixin
 * can reference it without breaking a dedicated server. That mixin checks the
 * {@code SupersonicGrabbed} tag first, which is what works server-side; tags are not part of
 * {@code SynchedEntityData}, so on a client the tag is never present and this index is what makes
 * the check work there.
 */
public final class GrabbedEntityIndex {

    private static final Set<Integer> GRABBED_IDS = new HashSet<>();

    private GrabbedEntityIndex() {
    }

    /**
     * Rebuilds the index. Cheap and side-neutral: call it once per tick from whichever side needs
     * it. Passing null (no level) clears it.
     */
    public static void refresh(Level level) {
        GRABBED_IDS.clear();
        if (level == null) return;

        for (Player player : level.players()) {
            if (player instanceof SupersonicFlightPlayer core) {
                LivingEntity target = core.getGrabbedTarget();
                if (target != null) {
                    GRABBED_IDS.add(target.getId());
                }
            }
        }
    }

    /**
     * True if a player on this side is currently holding this entity.
     *
     * <p>Hash lookup, and the empty-set check short-circuits before hashing in the common case
     * where nothing is being held at all.
     */
    public static boolean contains(Entity entity) {
        return !GRABBED_IDS.isEmpty() && GRABBED_IDS.contains(entity.getId());
    }
}
