package incari.client.legacy;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * 1.8's cut-off for slow movement (ADR 0020): each axis of the player's motion under 0.005
 * a tick stops, where later versions stop a player's horizontal motion under 0.003 overall. The
 * difference decides where a player comes to rest after being knocked back or sliding off an edge,
 * which a 1.8 server checks against its own simulation.
 */
public final class LegacyMovement {

    private static final double CUT_OFF_18 = 0.005;

    private LegacyMovement() {}

    /** {@code LivingEntity.aiStep}: whether {@code entity}'s slow motion stops as in 1.8. */
    public static boolean cutsLikeOld(LivingEntity entity) {
        return LegacyRules.movement18 && entity instanceof LocalPlayer;
    }

    /** {@code movement} with each axis under 1.8's cut-off stopped. */
    public static Vec3 cut(Vec3 movement) {
        return new Vec3(
                Math.abs(movement.x) < CUT_OFF_18 ? 0 : movement.x,
                Math.abs(movement.y) < CUT_OFF_18 ? 0 : movement.y,
                Math.abs(movement.z) < CUT_OFF_18 ? 0 : movement.z);
    }
}
