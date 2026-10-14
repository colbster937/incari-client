package incari.features.keystrokes;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.event.ClickCounter;
import incari.framework.event.ClickListener;
import incari.framework.event.Events;
import incari.framework.game.Game;
import incari.framework.game.GameKey;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.IntSetting;
import org.jspecify.annotations.Nullable;

/**
 * Keystrokes: the movement keys, the mouse buttons and the space bar as the player has bound them,
 * each lit while held, with clicks a second under the mouse buttons.
 */
public final class Keystrokes extends Module {

    final IntSetting keySize = settings()
            .integer("key-size", "Key size", 14, 30, 20)
            .describedAs("Each key's width and height, in GUI units.");
    final BooleanSetting showMouse = settings()
            .bool("show-mouse", "Mouse buttons", true)
            .describedAs("Shows the attack and use buttons below the movement keys.");
    final BooleanSetting showCps = settings()
            .bool("show-cps", "Clicks a second", true)
            .describedAs("Shows clicks per second under each mouse button.")
            .visibleWhen(showMouse::get);
    final BooleanSetting showSpace =
            settings().bool("show-space", "Space bar", true).describedAs("Shows the jump key as a bar at the bottom.");
    final BooleanSetting showSneakSprint = settings()
            .bool("show-sneak-sprint", "Sneak and sprint", false)
            .describedAs("Adds the sneak and sprint keys as a row of their own.");
    final BooleanSetting customColor = settings()
            .bool("custom-color", "Custom pressed color", false)
            .describedAs("Off: held keys light up in the theme's accent.");
    final ColorSetting pressedColor =
            settings().color("pressed-color", "Pressed color", 0xA9DCFF).visibleWhen(customColor::get);

    private final ClickCounter left = new ClickCounter();
    private final ClickCounter right = new ClickCounter();

    /** Keystrokes, off until the player turns it on. */
    public Keystrokes() {
        super(
                "keystrokes",
                "Keystrokes",
                "Shows your movement keys and mouse buttons, lit while you hold them.",
                Category.HUD);
        on(Events.CLICK, this::onClick);
        hud(new Keys());
    }

    private void onClick(int button) {
        long now = system().game().millis();
        if (button == ClickListener.LEFT) {
            left.click(now);
        } else if (button == ClickListener.RIGHT) {
            right.click(now);
        }
    }

    @Override
    protected void onDisable() {
        left.clear();
        right.clear();
    }

    /** The keys, three across. */
    private final class Keys extends HudElement {

        private static final int GAP = 2;

        /** A row of sneak and sprint, and the space bar, are this much of a key's height. */
        private static final int SHORT_ROW_PERCENT = 60;

        // Each mouse button's "12 CPS", made again only when its count changes.
        private int cachedLeft = -1;
        private String leftText = "";
        private int cachedRight = -1;
        private String rightText = "";

        Keys() {
            // Below the minimap's place: the left is the chat's and the effects'.
            super("keys", "Keys", Anchor.TOP_RIGHT, 4, 108);
        }

        private int size() {
            return keySize.get();
        }

        /** The mouse row: tall enough for two lines when it writes clicks a second. */
        private int mouseRow(HudCanvas canvas) {
            return showCps.get() ? Math.max(size(), canvas.lineHeight() * 2 + 3) : size();
        }

        private int shortRow() {
            return Math.max(8, size() * SHORT_ROW_PERCENT / 100);
        }

        @Override
        public int width(HudCanvas canvas) {
            return size() * 3 + GAP * 2;
        }

        @Override
        public int height(HudCanvas canvas) {
            int height = size() * 2 + GAP;
            if (showMouse.get()) {
                height += GAP + mouseRow(canvas);
            }
            if (showSneakSprint.get()) {
                height += GAP + shortRow();
            }
            if (showSpace.get()) {
                height += GAP + shortRow();
            }
            return height;
        }

        @Override
        public void draw(HudCanvas canvas) {
            Game game = system().game();
            int s = size();
            int width = width(canvas);
            key(canvas, game, GameKey.FORWARD, s + GAP, 0, s, s, null);
            int y = s + GAP;
            key(canvas, game, GameKey.LEFT, 0, y, s, s, null);
            key(canvas, game, GameKey.BACK, s + GAP, y, s, s, null);
            key(canvas, game, GameKey.RIGHT, (s + GAP) * 2, y, s, s, null);
            y += s + GAP;
            int half = (width - GAP) / 2;
            if (showMouse.get()) {
                String leftCps = null;
                String rightCps = null;
                if (showCps.get()) {
                    long now = game.millis();
                    leftCps = leftText(left.perSecond(now));
                    rightCps = rightText(right.perSecond(now));
                }
                int mouse = mouseRow(canvas);
                key(canvas, game, GameKey.ATTACK, 0, y, half, mouse, leftCps);
                key(canvas, game, GameKey.USE, half + GAP, y, width - half - GAP, mouse, rightCps);
                y += mouse + GAP;
            }
            if (showSneakSprint.get()) {
                key(canvas, game, GameKey.SNEAK, 0, y, half, shortRow(), null);
                key(canvas, game, GameKey.SPRINT, half + GAP, y, width - half - GAP, shortRow(), null);
                y += shortRow() + GAP;
            }
            if (showSpace.get()) {
                boolean down = game.isDown(GameKey.JUMP);
                background(canvas, 0, y, width, shortRow(), down);
                // A bar, not the key's name: "Space" doesn't fit a short row at small sizes.
                int barWidth = width / 3;
                int barY = y + shortRow() / 2;
                if (down) {
                    pressedFill(canvas, (width - barWidth) / 2, barY, barWidth, 1, true);
                } else {
                    canvas.fill((width - barWidth) / 2, barY, barWidth, 1, Tone.TEXT);
                }
            }
        }

        private String leftText(int cps) {
            if (cps != cachedLeft) {
                cachedLeft = cps;
                leftText = cps + " CPS";
            }
            return leftText;
        }

        private String rightText(int cps) {
            if (cps != cachedRight) {
                cachedRight = cps;
                rightText = cps + " CPS";
            }
            return rightText;
        }

        private void key(HudCanvas canvas, Game game, GameKey key, int x, int y, int w, int h, @Nullable String below) {
            boolean down = game.isDown(key);
            background(canvas, x, y, w, h, down);
            String name = game.keyName(key);
            int line = canvas.lineHeight();
            int textHeight = below == null ? line - 1 : line * 2 - 1;
            int ty = y + (h - textHeight + 1) / 2;
            label(canvas, name, x + (w - canvas.textWidth(name)) / 2, ty, down, false);
            if (below != null) {
                label(canvas, below, x + (w - canvas.textWidth(below)) / 2, ty + line, down, true);
            }
        }

        private void background(HudCanvas canvas, int x, int y, int w, int h, boolean down) {
            if (down) {
                pressedFill(canvas, x, y, w, h, false);
            } else {
                canvas.fill(x, y, w, h, Tone.BACKGROUND);
            }
        }

        private void pressedFill(HudCanvas canvas, int x, int y, int w, int h, boolean onPressed) {
            if (onPressed) {
                // The space bar's line on its lit key.
                canvas.fill(x, y, w, h, Tone.ON_ACCENT);
            } else if (customColor.get()) {
                canvas.fill(x, y, w, h, pressedColor.get());
            } else {
                canvas.fill(x, y, w, h, Tone.ACCENT);
            }
        }

        private void label(HudCanvas canvas, String text, int x, int y, boolean down, boolean muted) {
            if (down) {
                canvas.text(text, x, y, Tone.ON_ACCENT);
            } else {
                canvas.text(text, x, y, muted ? Tone.MUTED : Tone.TEXT);
            }
        }
    }
}
