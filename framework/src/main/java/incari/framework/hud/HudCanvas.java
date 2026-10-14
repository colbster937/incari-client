package incari.framework.hud;

import incari.framework.game.EffectView;
import incari.framework.game.GamePart;
import incari.framework.game.ItemView;

/**
 * What a HUD element draws with, in GUI units (the game's scaled pixels). The client implements it
 * over the game's GUI and its theme; elements draw at their own origin, already placed and scaled.
 *
 * <p>Render thread, during the HUD's extraction only. In the browser the HUD is drawn into a
 * texture a few dozen times a second and reused in between (ADR 0030), so an element is drawn
 * at most that often: draw from current state, never count frames here.
 */
public interface HudCanvas {

    /** The screen's width in GUI units. */
    int screenWidth();

    /** The screen's height in GUI units. */
    int screenHeight();

    /**
     * Whether the HUD editor is drawing: an element with nothing to show now draws a sample
     * instead (Toggle Sprint's line while nothing is latched), so the player can see where it goes.
     */
    default boolean editing() {
        return false;
    }

    /** How wide {@code text} is in the HUD's font. */
    int textWidth(String text);

    /** A line of text's height in the HUD's font. */
    int lineHeight();

    /** Draws {@code text} with its top left at {@code x, y}, in the theme's color for {@code tone}. */
    void text(String text, int x, int y, Tone tone);

    /** Draws {@code text} in a color the player chose, {@code 0xAARRGGBB}. */
    void text(String text, int x, int y, int argb);

    /** Fills a rectangle in the theme's color for {@code tone}. */
    void fill(int x, int y, int width, int height, Tone tone);

    /** Fills a rectangle in a color the player chose, {@code 0xAARRGGBB}. */
    void fill(int x, int y, int width, int height, int argb);

    /**
     * Draws {@code item}'s icon, 16 by 16, with its top left at {@code x, y}; with {@code
     * decorations}, the game's durability bar and stack count on it too. Nothing for an empty item.
     */
    void item(ItemView item, int x, int y, boolean decorations);

    /** Draws {@code effect}'s icon, 18 by 18, with its top left at {@code x, y}. */
    void effect(EffectView effect, int x, int y);

    /**
     * Whether the game has {@code part} to show now (a server's scoreboard, say). In the HUD editor
     * there's always one: a sample, if the game has none.
     */
    boolean hasPart(GamePart part);

    /** How wide {@code part} is as the game draws it, in GUI units; 0 if there's none. */
    int partWidth(GamePart part);

    /** How tall {@code part} is as the game draws it; 0 if there's none. */
    int partHeight(GamePart part);

    /** Draws the game's {@code part} with its top left at {@code x, y}; nothing if there's none. */
    void part(GamePart part, int x, int y);

    /**
     * Draws {@code shape} centred on {@code centerX, centerY}, {@code size} GUI units across, turned
     * {@code rotation} degrees clockwise, in the theme's color for {@code tone}, with a thin dark rim.
     */
    void shape(Shape shape, float centerX, float centerY, float size, float rotation, Tone tone);

    /** {@link #shape(Shape, float, float, float, float, Tone)} in a color the player chose. */
    void shape(Shape shape, float centerX, float centerY, float size, float rotation, int argb);

    /** Fills a circle, smooth at any size, in the theme's color for {@code tone}. */
    void disc(float centerX, float centerY, float radius, Tone tone);

    /** Draws a circle's outline {@code width} GUI units thick, just inside {@code radius}. */
    void ring(float centerX, float centerY, float radius, float width, Tone tone);

    /** {@link #disc(float, float, float, Tone)} in a color the player chose. */
    void disc(float centerX, float centerY, float radius, int argb);

    /** {@link #ring(float, float, float, float, Tone)} in a color the player chose. */
    void ring(float centerX, float centerY, float radius, float width, int argb);

    /**
     * Draws the part of a circle's outline from straight up, {@code fraction} of the way round
     * clockwise (a cooldown filling), {@code width} thick just inside {@code radius}.
     */
    void arc(float centerX, float centerY, float radius, float width, float fraction, int argb);

    /** {@link #arc(float, float, float, float, float, int)} in the theme's color for {@code tone}. */
    void arc(float centerX, float centerY, float radius, float width, float fraction, Tone tone);

    /**
     * Draws the loaded world around a point as seen from above (the minimap): {@code size} GUI units
     * across with its top left at {@code x, y}, a circle if {@code round}, else a square. Each block
     * is its top's color, shaded by the slope; where nothing is loaded is left clear.
     *
     * <p>Up on the map faces the compass bearing {@code rotation} (degrees clockwise from north: 0
     * is north up, 90 east up). North is toward the game's −z, east toward +x. Only what's drawn is
     * kept up to date, so a map nobody draws costs nothing.
     *
     * @param centerX the world x at the map's middle
     * @param centerZ the world z at the map's middle
     * @param blocksAcross how many blocks the map's width shows
     */
    void terrain(
            int x, int y, int size, boolean round, double centerX, double centerZ, float blocksAcross, float rotation);

    /**
     * The framework's, around each element: moves the origin to {@code x, y} and scales what follows
     * by {@code scale}, until {@link #pop()}. Elements may use it too, for what they place between
     * whole GUI units (a marker following the world).
     */
    void push(float x, float y, float scale);

    /** The framework's: undoes the last {@link #push}. */
    void pop();
}
