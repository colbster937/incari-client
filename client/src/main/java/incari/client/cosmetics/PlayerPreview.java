package incari.client.cosmetics;

import incari.client.skins.OwnSkin;
import incari.client.startup.DeferredModels;
import incari.online.Cosmetic;
import incari.online.Loadout;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * The player in 3D wearing a loadout (ADR 0061), drawn by the game's own player renderer with every
 * layer, so what shows here is what others see: the Wardrobe's preview, and the title screen's
 * player while they wear something. Dragged to turn; left alone, it turns to show what was picked
 * last (the back for a cape or wings, the front otherwise).
 *
 * <p>Render thread. The game draws one 3D picture of this kind a frame, so a screen has one of these.
 */
public final class PlayerPreview extends AbstractWidget {

    /** Blocks of height the widget's height holds: a player is 1.8, a halo or wings stand above. */
    private static final float BLOCKS = 2.6F;

    /** Turned to show the front, a little from the side. */
    private static final float FRONT = -28;

    /** Turned to show the back: a cape, wings. */
    private static final float BACK = 152;

    /** A stand-in id: the preview's name tag is styled by name, not looked up by player. */
    private static final UUID PREVIEW = new UUID(0, 0);

    private final Supplier<Loadout> loadout;
    private final long start = Util.getMillis();

    private float yaw = FRONT;
    private float targetYaw = FRONT;
    private long draggedAt = -1;
    private @Nullable Cosmetic emote;
    private float emoteStart;
    private Worn worn = Worn.NONE;
    private @Nullable Loadout wornFor;
    private @Nullable Cosmetic wornEmote;

    public PlayerPreview(int x, int y, int width, int height, Supplier<Loadout> loadout) {
        super(x, y, width, height, Component.literal("Preview"));
        this.loadout = loadout;
    }

    /** Turns to show {@code cosmetic} best: capes and wings from behind, the rest from the front. */
    public void show(Cosmetic cosmetic) {
        show(cosmetic.slot());
    }

    /** Turns to show {@code slot} best; the first time, at once. */
    public void show(Cosmetic.Slot slot) {
        targetYaw = slot == Cosmetic.Slot.CAPE || slot == Cosmetic.Slot.BACK ? BACK : FRONT;
        draggedAt = -1;
    }

    /** Stands turned as {@link #show} would, without turning there. */
    public void face(Cosmetic.Slot slot) {
        show(slot);
        yaw = targetYaw;
    }

    /** Plays {@code emote} now, from the front. */
    public void play(Cosmetic emote) {
        this.emote = emote;
        this.emoteStart = ticks();
        targetYaw = FRONT;
        draggedAt = -1;
    }

    private float ticks() {
        return (Util.getMillis() - start) / 50.0F;
    }

    @Override
    @SuppressWarnings("ReferenceEquality") // the same loadout object, not an equal one
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        // The player renderer comes with the entity models, just after the title screen shows.
        if (!DeferredModels.ready()) {
            return;
        }
        float age = ticks();
        Cosmetic playing = emote;
        if (playing != null && age - emoteStart >= Emotes.duration(playing)) {
            emote = null;
            playing = null;
        }
        Loadout now = loadout.get();
        if (now != wornFor || playing != wornEmote) {
            wornFor = now;
            wornEmote = playing;
            worn = new Worn(now, playing, emoteStart);
        }
        if (draggedAt < 0 || Util.getMillis() - draggedAt > 4_000) {
            yaw += (targetYaw - yaw) * 0.12F;
        }
        AvatarRenderState state = new AvatarRenderState();
        state.skin = OwnSkin.shared().playerSkin();
        state.ageInTicks = age;
        state.bodyRot = 180 + yaw;
        state.yRot = 0;
        state.xRot = 0;
        state.boundingBoxWidth = 0.6F;
        state.boundingBoxHeight = 1.8F;
        state.scale = 1;
        state.capeLean = 6;
        state.outlineColor = 0;
        Cosmetics.dress(state, worn, PREVIEW);
        float scale = getHeight() / BLOCKS;
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf tilt = new Quaternionf().rotateX(-0.12F);
        rotation.mul(tilt);
        // Centered on the middle of what's shown: from the feet to a halo's top.
        graphics.entity(
                state, scale, new Vector3f(0, 1.12F, 0), rotation, tilt, getX(), getY(), getRight(), getBottom());
    }

    @Override
    public void onClick(MouseButtonEvent event, boolean doubleClick) {
        draggedAt = Util.getMillis();
    }

    @Override
    protected void onDrag(MouseButtonEvent event, double dx, double dy) {
        yaw -= (float) dx * 2.5F;
        targetYaw = yaw;
        draggedAt = Util.getMillis();
    }

    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }

    @Override
    public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
        // For the pointer: turned by dragging.
        return null;
    }
}
