package incari.client.render.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * The swing an entity is drawn with. A swing outlives the item that started it, and a new one
 * can't replace it in its first half, so a spear's stab went on playing on the item swapped to
 * (a pearl thrown just after) in first and third person. A stab is drawn only while the swinging
 * hand holds an item that stabs; otherwise it's the ordinary swing, for the time it had left.
 */
public final class HeldSwings {

    private HeldSwings() {}

    /**
     * Hook ({@code LivingEntity.getCurrentSwing}, read only by the renderers): the swing as drawn.
     * Allocates only while a stab is left on an item that doesn't stab.
     */
    public static LivingEntity.@Nullable SwingDescription drawn(
            LivingEntity entity, LivingEntity.@Nullable SwingDescription swing) {
        if (swing == null || swing.animation().type() != SwingAnimationType.STAB) {
            return swing;
        }
        ItemStack held = entity.getItemInHand(swing.hand());
        if (held.getAttackAnimation().type() == SwingAnimationType.STAB
                || held.getInteractAnimation().type() == SwingAnimationType.STAB) {
            return swing;
        }
        return new LivingEntity.SwingDescription(
                swing.hand(),
                new SwingAnimation(SwingAnimationType.WHACK, swing.animation().duration()),
                swing.durationTicks());
    }
}
