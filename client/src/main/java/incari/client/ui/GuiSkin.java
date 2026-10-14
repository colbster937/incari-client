package incari.client.ui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

/**
 * How the game's GUI looks, redrawn: every method is asked before the game draws that part, and
 * returns whether it drew it, so a skin takes over only what it knows and the game draws the rest.
 * The restyle is one of these, set with {@link Skins#use}.
 *
 * <p>Three levels, coarse to fine:
 *
 * <ul>
 *   <li>{@link #widget}: a whole widget, its label and state included: a button with a hover
 *       animation, a slider, a checkbox.
 *   <li>{@link #sprite}, {@link #spritePart} and {@link #texture}: one of the GUI's sprites or
 *       textures by name, wherever it's drawn: a button's background ({@code widget/button},
 *       {@code widget/button_highlighted}, {@code widget/button_disabled}), a text field's, a
 *       scroller, a tab, a tooltip's frame, a list's separators, a container's background, the
 *       HUD's hotbar and hearts. The name carries the state the game drew it in.
 *   <li>{@link #background}: a screen's default backdrop (the panorama, the blur and the menu
 *       texture, or the in-game dim).
 * </ul>
 *
 * <p>While a skin draws, its own calls to {@code graphics} reach the game unskinned, so it can fall
 * back on the game's drawing for parts of what it draws. A skin that throws is logged and dropped,
 * and the game's look comes back.
 *
 * <p>Render thread, every frame: these are hot paths, so don't allocate, and look sprites up by
 * identity or in a map made once.
 */
public interface GuiSkin {

    /**
     * Draws {@code widget} whole, or returns false for the game to. {@code widget.isHovered()} is
     * already current. A widget drawn here asks for its own mouse cursor ({@link Skins#cursor}).
     */
    default boolean widget(GuiGraphicsExtractor graphics, AbstractWidget widget, int mouseX, int mouseY, float a) {
        return false;
    }

    /**
     * Draws the GUI sprite {@code sprite} into the rectangle, or returns false for the game to.
     *
     * @param color the ARGB tint the game asked for; its alpha is the widget's fade
     */
    default boolean sprite(
            GuiGraphicsExtractor graphics, Identifier sprite, int x, int y, int width, int height, int color) {
        return false;
    }

    /**
     * Draws the part of the sprite {@code sprite} (of {@code spriteWidth} by {@code spriteHeight})
     * from ({@code textureX}, {@code textureY}) into the rectangle, as bars filling up do (the
     * experience bar, a boss's health), or returns false for the game to.
     */
    default boolean spritePart(
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
        return false;
    }

    /**
     * Draws the region ({@code u}, {@code v}, {@code srcWidth} by {@code srcHeight}) of the texture
     * {@code texture} (of {@code textureWidth} by {@code textureHeight}) into the rectangle, or
     * returns false for the game to. Menu backgrounds, header and footer separators, and the
     * containers' screens are drawn this way.
     */
    default boolean texture(
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
        return false;
    }

    /** Draws {@code screen}'s backdrop, or returns false for the game to. */
    default boolean background(GuiGraphicsExtractor graphics, Screen screen, float a) {
        return false;
    }
}
