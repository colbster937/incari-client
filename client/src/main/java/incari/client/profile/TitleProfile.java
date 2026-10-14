package incari.client.profile;

import incari.client.cosmetics.Cosmetics;
import incari.client.cosmetics.PlayerPreview;
import incari.client.cosmetics.WardrobeScreen;
import incari.client.online.AccountScreen;
import incari.client.skins.OwnSkin;
import incari.client.startup.DeferredModels;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.PlainTextButton;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.jspecify.annotations.Nullable;

/**
 * The player on the title screen, to the right of its buttons: their model in 3D with their skin,
 * turning to follow the pointer, their name above their head as a name tag, and "Edit Profile",
 * "Cosmetics" and "Incari Account" buttons below. While they wear cosmetics, the model wears them
 * too ({@link PlayerPreview}). A click on the player opens Edit Profile too. A screen too narrow for it gets a
 * link in the top left corner instead.
 */
public final class TitleProfile {

    private static final int MENU_HALF_WIDTH = 100;
    private static final int NAME_TAG_HEIGHT = 12;
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 4;
    private static final int MIN_MODEL_HEIGHT = 56;
    private static final int MAX_MODEL_HEIGHT = 120;

    private TitleProfile() {}

    /** Hook ({@code TitleScreen.init}, after its widgets): the player and the button, or the link. */
    public static void add(Screen title, Consumer<AbstractWidget> addWidget) {
        Minecraft minecraft = Minecraft.getInstance();
        // The column of main buttons, as the title screen laid it out.
        int middle = title.width / 2;
        int top = Integer.MAX_VALUE;
        int bottom = Integer.MIN_VALUE;
        for (GuiEventListener child : title.children()) {
            if (child instanceof AbstractWidget widget
                    && widget.getX() >= middle - MENU_HALF_WIDTH
                    && widget.getRight() <= middle + MENU_HALF_WIDTH
                    && widget.getY() > title.height / 4) {
                top = Math.min(top, widget.getY());
                bottom = Math.max(bottom, widget.getBottom());
            }
        }
        int free = title.width - (middle + MENU_HALF_WIDTH);
        int modelHeight = Math.min(MAX_MODEL_HEIGHT, bottom - top - NAME_TAG_HEIGHT - 3 * BUTTON_HEIGHT - 4 * GAP);
        int modelWidth = modelHeight * 11 / 20;
        int buttonWidth = Math.min(100, free - 16);
        if (top > bottom || modelHeight < MIN_MODEL_HEIGHT || buttonWidth < 70 || modelWidth > free - 16) {
            addWidget.accept(link(title));
            addWidget.accept(cosmeticsLink(title));
            addWidget.accept(accountLink(title));
            return;
        }
        int centre = middle + MENU_HALF_WIDTH + free / 2;
        int modelTop = top + NAME_TAG_HEIGHT + GAP;
        addWidget.accept(new PlayerView(
                centre - modelWidth / 2, modelTop, modelWidth, modelHeight, true, () -> open(minecraft, title)));
        addWidget.accept(Button.builder(Component.literal("Edit Profile"), button -> open(minecraft, title))
                .bounds(centre - buttonWidth / 2, modelTop + modelHeight + GAP, buttonWidth, BUTTON_HEIGHT)
                .build());
        addWidget.accept(Button.builder(
                        Component.literal("Cosmetics"), button -> minecraft.gui.setScreen(new WardrobeScreen(title)))
                .bounds(
                        centre - buttonWidth / 2,
                        modelTop + modelHeight + 2 * GAP + BUTTON_HEIGHT,
                        buttonWidth,
                        BUTTON_HEIGHT)
                .build());
        addWidget.accept(Button.builder(
                        Component.literal("Incari Account"),
                        button -> minecraft.gui.setScreen(new AccountScreen(title)))
                .bounds(
                        centre - buttonWidth / 2,
                        modelTop + modelHeight + 3 * GAP + 2 * BUTTON_HEIGHT,
                        buttonWidth,
                        BUTTON_HEIGHT)
                .build());
    }

    /**
     * The player's model alone, turning to follow the pointer, for a screen of the client's own
     * (the restyled title screen's player card): a click opens Edit Profile over {@code title}.
     */
    public static AbstractWidget playerView(Screen title, int x, int y, int width, int height, boolean nameTag) {
        Minecraft minecraft = Minecraft.getInstance();
        return new PlayerView(x, y, width, height, nameTag, () -> open(minecraft, title));
    }

    /** Opens Edit Profile over {@code title}. */
    public static void openProfile(Screen title) {
        open(Minecraft.getInstance(), title);
    }

    private static void open(Minecraft minecraft, Screen title) {
        minecraft.gui.setScreen(ProfileScreen.from(title));
    }

    /** The small screens' link in the top left corner. */
    public static PlainTextButton link(Screen title) {
        Minecraft minecraft = Minecraft.getInstance();
        Component text = Component.literal("Edit Profile (" + PlayerName.name() + ")");
        return new PlainTextButton(
                2, 2, minecraft.font.width(text), 10, text, button -> open(minecraft, title), minecraft.font);
    }

    /** The small screens' Cosmetics link, under {@link #link}. */
    public static PlainTextButton cosmeticsLink(Screen title) {
        Minecraft minecraft = Minecraft.getInstance();
        Component text = Component.literal("Cosmetics");
        return new PlainTextButton(
                2,
                13,
                minecraft.font.width(text),
                10,
                text,
                button -> minecraft.gui.setScreen(new WardrobeScreen(title)),
                minecraft.font);
    }

    /** The small screens' Incari Account link, under {@link #cosmeticsLink}. */
    public static PlainTextButton accountLink(Screen title) {
        Minecraft minecraft = Minecraft.getInstance();
        Component text = Component.literal("Incari Account");
        return new PlainTextButton(
                2,
                24,
                minecraft.font.width(text),
                10,
                text,
                button -> minecraft.gui.setScreen(new AccountScreen(title)),
                minecraft.font);
    }

    /** The player's model and name tag. */
    private static final class PlayerView extends AbstractWidget {
        /** The model's height in blocks, and how much of the widget's height it fills. */
        private static final float MODEL_BLOCKS = 2.125F;

        private static final float FILL = 0.97F;

        private final Runnable onClick;
        private final boolean nameTag;
        private final OwnSkin skin = OwnSkin.shared();
        private Model.@Nullable Simple wide;
        private Model.@Nullable Simple slim;
        private @Nullable PlayerPreview preview;
        /** Where the model looks, eased toward the pointer frame by frame. */
        private float yaw = 25;

        private float pitch = -5;

        PlayerView(int x, int y, int width, int height, boolean nameTag, Runnable onClick) {
            super(x, y, width, height, Component.literal("Edit Profile"));
            this.nameTag = nameTag;
            this.onClick = onClick;
        }

        @Override
        protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
            if (nameTag) {
                extractNameTag(graphics);
            }
            // Worn cosmetics need the game's player renderer, which draws a fresh picture each
            // frame; with none worn, the kept picture of the skin alone is enough (ADR 0061).
            if (Cosmetics.wearsAny()) {
                PlayerPreview worn = preview;
                if (worn == null) {
                    worn = new PlayerPreview(getX(), getY(), getWidth(), getHeight(), Cosmetics::ownLoadout);
                    preview = worn;
                }
                worn.setRectangle(getWidth(), getHeight(), getX(), getY());
                worn.extractRenderState(graphics, mouseX, mouseY, a);
                return;
            }
            // The entity models load just after the title screen first shows (ADR 0026).
            if (wide == null || slim == null) {
                if (!DeferredModels.ready()) {
                    return;
                }
                EntityModelSet models = Minecraft.getInstance().getEntityModels();
                wide = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER), RenderTypes::entityTranslucent);
                slim = new Model.Simple(models.bakeLayer(ModelLayers.PLAYER_SLIM), RenderTypes::entityTranslucent);
            }
            // Turned toward the pointer, as far as a glance: the eyes are near the top.
            float eyeY = getY() + getHeight() * 0.12F;
            float targetYaw = Mth.clamp(
                    (float) Math.toDegrees(Math.atan((mouseX - getX() - getWidth() / 2.0F) / 60.0F)), -55, 55);
            float targetPitch = Mth.clamp((float) Math.toDegrees(Math.atan((eyeY - mouseY) / 120.0F)) * 0.5F, -20, 20);
            yaw = ease(yaw, targetYaw);
            pitch = ease(pitch, targetPitch);
            PlayerSkin playerSkin = skin.playerSkin();
            Model.Simple model = playerSkin.model() == PlayerModelType.SLIM ? slim : wide;
            float scale = FILL * getHeight() / MODEL_BLOCKS;
            graphics.skin(
                    model,
                    playerSkin.body().texturePath(),
                    scale,
                    pitch,
                    yaw,
                    -MODEL_BLOCKS / 2,
                    getX(),
                    getY(),
                    getRight(),
                    getBottom());
        }

        /**
         * A fifth of the way from {@code value} to {@code target}, and there once within a tenth of a
         * degree (under a pixel at this size): a model that holds still is the same picture each
         * frame, which the game then keeps instead of drawing it again ({@code SkinPictures}).
         * Without the snap, the last tenth took some 30 frames of redraws nobody could see.
         */
        private static float ease(float value, float target) {
            float next = value + (target - value) * 0.2F;
            return Math.abs(target - next) < 0.1F ? target : next;
        }

        /** The name over the head, as the game draws a name tag: on a faint dark plate. */
        private void extractNameTag(GuiGraphicsExtractor graphics) {
            Font font = Minecraft.getInstance().font;
            String name = PlayerName.name();
            int width = font.width(name);
            int x = getX() + getWidth() / 2 - width / 2;
            int y = getY() - NAME_TAG_HEIGHT - GAP + 2;
            graphics.fill(x - 2, y - 1, x + width + 1, y + 9, 0x40000000);
            graphics.text(font, name, x, y, isHovered() ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }

        @Override
        public void onClick(MouseButtonEvent event, boolean doubleClick) {
            onClick.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }

        @Override
        public @Nullable ComponentPath nextFocusPath(FocusNavigationEvent navigationEvent) {
            // The button below takes the keyboard's focus; the model is for the pointer.
            return null;
        }
    }
}
