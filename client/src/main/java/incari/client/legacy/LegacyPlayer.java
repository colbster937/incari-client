package incari.client.legacy;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Players' size and poses as older versions had them (ADR 0020):
 *
 * <ul>
 *   <li>sneaking lowers the eyes to 1.54 and keeps the box 1.8 high (1.8), or makes it 1.65
 *       (1.9 to 1.13); since 1.14 it's 1.5 with the eyes at 1.27, which lets a player under a
 *       slab where the server says they can't be, and moves the camera and the hitbox other
 *       players aim at;
 *   <li>before 1.13 there's no swimming or crawling: sprinting in water is fast walking, and a
 *       player in a one-block gap stands in it.
 * </ul>
 */
public final class LegacyPlayer {

    private static final EntityDimensions SNEAKING_18 =
            EntityDimensions.scalable(0.6F, 1.8F).withEyeHeight(1.54F);
    private static final EntityDimensions SNEAKING_19 =
            EntityDimensions.scalable(0.6F, 1.65F).withEyeHeight(1.54F);

    private LegacyPlayer() {}

    /** {@code Avatar.getDefaultDimensions}: an old version's size for {@code pose}, or null. */
    public static @Nullable EntityDimensions dimensions(Pose pose) {
        if (!LegacyRules.oldSneak || pose != Pose.CROUCHING) {
            return null;
        }
        return LegacyRules.sneak18() ? SNEAKING_18 : SNEAKING_19;
    }

    /**
     * {@code Player.updatePlayerPose}: before 1.13, sets the pose the old way (sleeping, gliding,
     * sneaking or standing, never swimming or crawling) and returns true; false otherwise.
     */
    public static boolean updatePose(Player player) {
        if (!LegacyRules.noSwimming) {
            return false;
        }
        Pose pose;
        if (player.isSleeping()) {
            pose = Pose.SLEEPING;
        } else if (player.isFallFlying()) {
            pose = Pose.FALL_FLYING;
        } else if (player.isShiftKeyDown() && !player.getAbilities().flying) {
            pose = Pose.CROUCHING;
        } else {
            pose = Pose.STANDING;
        }
        player.setPose(pose);
        return true;
    }

    /** {@code Entity.updateSwimming}: before 1.13 nobody swims; true if that's the rule now. */
    public static boolean noSwimming(Entity entity) {
        if (!LegacyRules.noSwimming || !(entity instanceof Player)) {
            return false;
        }
        entity.setSwimming(false);
        return true;
    }
}
