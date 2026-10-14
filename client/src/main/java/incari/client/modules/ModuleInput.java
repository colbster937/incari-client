package incari.client.modules;

import incari.framework.ModuleSystem;
import incari.framework.event.ClickListener;
import incari.framework.event.Events;
import incari.framework.event.MovementInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.jspecify.annotations.Nullable;

/**
 * The movement event ({@link Events#MOVEMENT}): the local player's movement keys, after the game
 * reads them each tick, handed to modules that may change them (Toggle Sprint latches sprint).
 * Game thread, once a tick in a world.
 */
public final class ModuleInput {

    /** One object for every tick: the event is on the hot path. */
    private static final MovementInput INPUT = new MovementInput();

    private ModuleInput() {}

    /**
     * Hook ({@code KeyboardInput.tick}, after the keys are read): the keys as modules leave them.
     *
     * @return {@code keys} if no module changed anything, else a new input
     */
    public static Input adjust(Input keys, Options options) {
        // The sprint and sneak keys' presses are taken every tick, listened to or not: the game
        // never reads them, and a module turned on later must not see a backlog.
        int sneakPresses = drain(options.keyShift);
        int sprintPresses = drain(options.keySprint);
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.MOVEMENT) == 0) {
            return keys;
        }
        INPUT.reset(
                keys.forward(),
                keys.backward(),
                keys.left(),
                keys.right(),
                keys.jump(),
                keys.shift(),
                keys.sprint(),
                sneakPresses,
                sprintPresses);
        INPUT.gameToggles(options.toggleCrouch().get(), options.toggleSprint().get());
        if (!system.events().fireMovement(INPUT)) {
            return keys;
        }
        return new Input(
                INPUT.forward(),
                INPUT.backward(),
                INPUT.left(),
                INPUT.right(),
                INPUT.jump(),
                INPUT.sneak(),
                INPUT.sprint());
    }

    private static int drain(KeyMapping key) {
        int presses = 0;
        while (key.consumeClick()) {
            presses++;
        }
        return presses;
    }

    /**
     * Hook ({@code MouseHandler.onButton}, a press over the world): the click event, with the
     * game's button numbers (1 left, 2 middle, 3 right) turned into the framework's.
     */
    public static void click(int button) {
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.CLICK) == 0) {
            return;
        }
        int framework =
                switch (button) {
                    case 1 -> ClickListener.LEFT;
                    case 2 -> ClickListener.MIDDLE;
                    case 3 -> ClickListener.RIGHT;
                    default -> button - 1;
                };
        system.events().fireClick(framework);
    }

    /**
     * Hook ({@code Minecraft.startAttack}, a piercing weapon's stab): the server decides what a stab
     * hits; the entity the crosshair is on counts as hit, as a plain attack's would.
     */
    public static void stabbed(LocalPlayer player, @Nullable HitResult hit) {
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            attacked(player, hit);
        }
    }

    /**
     * Hook ({@code Minecraft.startAttack}, an entity hit): the attack event, with how far from the
     * player's eyes the crosshair met the target.
     */
    public static void attacked(LocalPlayer player, HitResult hit) {
        if (hit instanceof EntityHitResult entityHit) {
            // Kept for Hit & Kill Effects: where to show a hit, and whether it dies.
            ModuleEffects.attacked(entityHit.getEntity());
        }
        ModuleSystem system = IncariModules.current();
        if (system == null || system.events().count(Events.ATTACK) == 0) {
            return;
        }
        system.events().fireAttack(player.getEyePosition().distanceTo(hit.getLocation()));
    }
}
