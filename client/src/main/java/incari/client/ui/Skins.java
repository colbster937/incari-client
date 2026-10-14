package incari.client.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The {@link GuiSkin} the game's GUI is drawn with, and the hooks that ask it: {@code
 * AbstractWidget} before drawing a widget, {@code GuiGraphicsExtractor} before drawing a sprite or a
 * texture, {@code Screen} before drawing its backdrop. With no skin, the game's look, and each hook
 * costs a field read.
 *
 * <p>Render thread. Hot paths: nothing here allocates.
 */
public final class Skins {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static @Nullable GuiSkin skin;

    /** The skin is drawing: its own draws go to the game as asked, not back to it. */
    private static boolean drawing;

    private Skins() {}

    /** Draws the GUI with {@code skin} from the next frame, or with the game's look if null. */
    public static void use(@Nullable GuiSkin skin) {
        Skins.skin = skin;
    }

    /** The skin in use, or null for the game's look. */
    public static @Nullable GuiSkin current() {
        return skin;
    }

    /**
     * Asks for the mouse cursor a widget the skin drew should show, as the game's buttons do: a
     * pointing hand over an active one, "not allowed" over an inactive one.
     */
    public static void cursor(GuiGraphicsExtractor graphics, AbstractWidget widget) {
        if (widget.isHovered()) {
            graphics.requestCursor(widget.isActive() ? CursorTypes.POINTING_HAND : CursorTypes.NOT_ALLOWED);
        }
    }

    /** {@code AbstractWidget.extractRenderState}: whether the skin drew {@code widget}. */
    public static boolean widget(
            GuiGraphicsExtractor graphics, AbstractWidget widget, int mouseX, int mouseY, float a) {
        GuiSkin current = skin;
        if (current == null || drawing) {
            return false;
        }
        drawing = true;
        try {
            return current.widget(graphics, widget, mouseX, mouseY, a);
        } catch (RuntimeException e) {
            return failed(current, e);
        } finally {
            drawing = false;
        }
    }

    /** {@code GuiGraphicsExtractor.blitSprite}: whether the skin drew {@code sprite}. */
    public static boolean sprite(
            GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int height, int color) {
        GuiSkin current = skin;
        if (current == null || drawing) {
            return false;
        }
        drawing = true;
        try {
            return current.sprite(graphics, sprite, x, y, width, height, color);
        } catch (RuntimeException e) {
            return failed(current, e);
        } finally {
            drawing = false;
        }
    }

    /** {@code GuiGraphicsExtractor.blitSprite}, for part of a sprite: whether the skin drew it. */
    public static boolean spritePart(
            GuiGraphicsExtractor graphics,
            Identifier sprite,
            int spriteWidth,
            int spriteHeight,
            int textureX,
            int textureY,
            int x,
            int y,
            int width,
            int height,
            int color) {
        GuiSkin current = skin;
        if (current == null || drawing) {
            return false;
        }
        drawing = true;
        try {
            return current.spritePart(
                    graphics, sprite, spriteWidth, spriteHeight, textureX, textureY, x, y, width, height, color);
        } catch (RuntimeException e) {
            return failed(current, e);
        } finally {
            drawing = false;
        }
    }

    /** {@code GuiGraphicsExtractor.blit}: whether the skin drew {@code texture}'s region. */
    public static boolean texture(
            GuiGraphicsExtractor graphics,
            Identifier texture,
            int x,
            int y,
            int width,
            int height,
            float u,
            float v,
            int srcWidth,
            int srcHeight,
            int textureWidth,
            int textureHeight,
            int color) {
        GuiSkin current = skin;
        if (current == null || drawing) {
            return false;
        }
        drawing = true;
        try {
            return current.texture(
                    graphics,
                    texture,
                    x,
                    y,
                    width,
                    height,
                    u,
                    v,
                    srcWidth,
                    srcHeight,
                    textureWidth,
                    textureHeight,
                    color);
        } catch (RuntimeException e) {
            return failed(current, e);
        } finally {
            drawing = false;
        }
    }

    /** {@code Screen.extractBackground}: whether the skin drew {@code screen}'s backdrop. */
    public static boolean background(GuiGraphicsExtractor graphics, Screen screen, float a) {
        GuiSkin current = skin;
        if (current == null || drawing) {
            return false;
        }
        drawing = true;
        try {
            return current.background(graphics, screen, a);
        } catch (RuntimeException e) {
            return failed(current, e);
        } finally {
            drawing = false;
        }
    }

    /** A skin that throws is dropped, so the GUI stays usable in the game's look. */
    @SuppressWarnings("ReferenceEquality") // the same skin object, not an equal one
    private static boolean failed(GuiSkin current, RuntimeException e) {
        LOGGER.error(
                "The GUI skin {} failed; back to the game's look",
                current.getClass().getName(),
                e);
        if (skin == current) {
            skin = null;
        }
        return false;
    }
}
