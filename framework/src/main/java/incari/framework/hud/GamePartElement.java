package incari.framework.hud;

import incari.framework.game.GamePart;

/**
 * A part of the game's own HUD, taken over by a module (Custom Scoreboard's sidebar): while the
 * module is on, the game no longer draws the part in its place ({@code ModuleSystem.holds}), and
 * this element draws it where the player puts it, at the scale they choose.
 */
public class GamePartElement extends HudElement {

    private final GamePart part;

    /**
     * @param part what it draws
     * @param id the config key, unique within the module
     * @param anchor where it goes by default
     * @param offsetX how far in from the anchor by default
     * @param offsetY how far in from the anchor by default
     */
    public GamePartElement(GamePart part, String id, Anchor anchor, int offsetX, int offsetY) {
        super(id, part.label(), anchor, offsetX, offsetY);
        this.part = part;
    }

    /** What it draws. */
    public final GamePart part() {
        return part;
    }

    @Override
    public int width(HudCanvas canvas) {
        return canvas.partWidth(part);
    }

    @Override
    public int height(HudCanvas canvas) {
        return canvas.partHeight(part);
    }

    @Override
    public void draw(HudCanvas canvas) {
        if (canvas.hasPart(part)) {
            canvas.part(part, 0, 0);
        }
    }
}
