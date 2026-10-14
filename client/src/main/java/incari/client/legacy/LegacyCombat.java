package incari.client.legacy;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import org.jspecify.annotations.Nullable;

/**
 * 1.8's combat, on 1.8 servers (ADR 0020):
 *
 * <ul>
 *   <li><b>No attack cooldown.</b> Every hit is at full strength, so the held item never dips after
 *       a swing and the attack indicator never shows; the server decides the damage as 1.8 did.
 *   <li><b>Sword blocking.</b> Using a sword raises it to block, as long as the use key is held; the
 *       game sends the use and its release as for a shield, which Via turns into 1.8's block and
 *       unblock, and the server halves the damage. The first- and third-person poses are the game's
 *       own for a blocking item that isn't a shield.
 *   <li><b>The swing before the attack.</b> 1.8 clients swing, then attack or dig; servers' combat
 *       checks count on that order.
 *   <li><b>A 0.1 margin around every entity's box</b> when aiming at it, as 1.8 aimed.
 * </ul>
 */
public final class LegacyCombat {

    /** How far 1.8 grows an entity's box when picking it with the crosshair. */
    private static final float PICK_MARGIN_18 = 0.1F;
    /** How long a sword can be held up: as long as the key is, like a shield. */
    private static final int BLOCK_TICKS = 72000;

    private LegacyCombat() {}

    /** {@code Player.getAttackStrengthScale}: full strength always, or null for the game's rule. */
    public static boolean noCooldown() {
        return LegacyRules.combat18;
    }

    /** {@code Entity.getPickRadius}: 1.8's margin, or {@code current} when that's off. */
    public static float pickRadius(float current) {
        return LegacyRules.combat18 ? PICK_MARGIN_18 : current;
    }

    /** Whether {@code stack} is a sword that blocks here. */
    public static boolean blocksWith(ItemStack stack) {
        return LegacyRules.combat18 && !stack.isEmpty() && isSword(stack);
    }

    /** {@code Item.use}: raises a sword to block, or null to use the item as the game does. */
    public static @Nullable InteractionResult use(Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!blocksWith(stack)) {
            return null;
        }
        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    /** {@code Item.getUseDuration}: a blocking sword's, or -1 for the item's own. */
    public static int useDuration(ItemStack stack) {
        return blocksWith(stack) ? BLOCK_TICKS : -1;
    }

    /**
     * {@code Minecraft.startAttack}, before the attack: swings now if 1.8's order is on, and says
     * whether it did, so the swing after the attack is skipped.
     */
    public static boolean swingFirst(LocalPlayer player, SwingAnimation animation) {
        if (!LegacyRules.combat18) {
            return false;
        }
        player.swing(InteractionHand.MAIN_HAND, animation, false);
        return true;
    }

    private static boolean isSword(ItemStack stack) {
        // By name, not by tag: the tags an old server's world comes with are Via's, and may not
        // list swords.
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_sword");
    }
}
