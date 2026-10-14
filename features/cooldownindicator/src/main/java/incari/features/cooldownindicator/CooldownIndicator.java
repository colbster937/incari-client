package incari.features.cooldownindicator;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.Game;
import incari.framework.game.GameOption;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import incari.framework.setting.ColorSetting;
import incari.framework.setting.IntSetting;

/**
 * Cooldown Indicator: 1.9's attack cooldown the way the player wants it, in place of the game's
 * indicator (whose option it holds at Off while on). A bar, a ring round the crosshair, or the
 * charge as a percentage; hidden once charged, or shown full while the crosshair is on a target.
 */
public final class CooldownIndicator extends Module {

    /** How the charge is drawn. */
    public enum Style {
        BAR("Bar"),
        RING("Ring"),
        NUMBER("Percent");

        private final String label;

        Style(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** The ring's thickness, in GUI units. */
    static final float RING_WIDTH = 1.5F;

    /** The charge the HUD editor shows. */
    static final float SAMPLE = 0.6F;

    final ChoiceSetting<Style> style = settings()
            .choice("style", "Style", Style.values(), Style.BAR, Style::label)
            .describedAs("A bar, a ring around the crosshair, or a percentage.");
    final IntSetting barWidth =
            settings().integer("bar-width", "Bar width", 8, 80, 24).visibleWhen(() -> style.get() == Style.BAR);
    final IntSetting barHeight =
            settings().integer("bar-height", "Bar height", 1, 6, 2).visibleWhen(() -> style.get() == Style.BAR);
    final IntSetting ringSize = settings()
            .integer("ring-size", "Ring size", 6, 24, 10)
            .describedAs("The ring's radius, in GUI units.")
            .visibleWhen(() -> style.get() == Style.RING);
    final BooleanSetting hideWhenReady = settings()
            .bool("hide-when-ready", "Hide when charged", true)
            .describedAs("Shows the indicator only while the attack charges.");
    final BooleanSetting readyOnTarget = settings()
            .bool("ready-on-target", "Show charged on target", true)
            .describedAs("Shows it full when the crosshair is on a mob or player in reach.")
            .visibleWhen(hideWhenReady::get);
    final BooleanSetting customColors = settings()
            .bool("custom-colors", "Custom colors", false)
            .describedAs("Off: charging in the accent, charged in green.");
    final ColorSetting chargingColor =
            settings().color("charging-color", "Charging color", 0xA9DCFF).visibleWhen(customColors::get);
    final ColorSetting readyColor =
            settings().color("ready-color", "Charged color", 0x8FE3B0).visibleWhen(customColors::get);

    /** Cooldown Indicator, off until the player turns it on. */
    public CooldownIndicator() {
        super(
                "cooldownindicator",
                "Cooldown Indicator",
                "Replaces the attack indicator with a bar, ring or percentage.",
                Category.COMBAT);
        // The game's own indicator is off while this one is on.
        holdOption(GameOption.ATTACK_INDICATOR, () -> 0);
        hud(new Bar());
        hud(new Ring());
    }

    /** The charge now, or -1 if nothing should show. */
    float charge(HudCanvas canvas) {
        Game game = system().game();
        if (game.player() == null) {
            return canvas.editing() ? SAMPLE : -1;
        }
        float strength = Math.min(1, game.attackStrength());
        if (strength >= 1 && hideWhenReady.get() && !(readyOnTarget.get() && game.onTarget())) {
            return canvas.editing() ? SAMPLE : -1;
        }
        return strength;
    }

    int color(float charge) {
        if (customColors.get()) {
            return charge >= 1 ? readyColor.get() : chargingColor.get();
        }
        return 0;
    }

    Tone tone(float charge) {
        return charge >= 1 ? Tone.POSITIVE : Tone.ACCENT;
    }

    /** "60%". */
    static String percent(float charge) {
        return Math.round(charge * 100) + "%";
    }

    /** The bar, or the percentage: below the crosshair by default. */
    private final class Bar extends HudElement {

        private int cachedPercent = -1;
        private String cachedText = "";

        Bar() {
            super("bar", "Cooldown", Anchor.CENTER, 0, 12);
        }

        @Override
        public boolean isLive() {
            return true;
        }

        @Override
        public boolean isPlaceable() {
            return style.get() != Style.RING;
        }

        @Override
        public boolean isVisible() {
            return style.get() != Style.RING && system().game().player() != null;
        }

        private String text(float charge) {
            int percent = Math.round(charge * 100);
            if (percent != cachedPercent) {
                cachedPercent = percent;
                cachedText = percent(charge);
            }
            return cachedText;
        }

        @Override
        public int width(HudCanvas canvas) {
            return style.get() == Style.NUMBER ? canvas.textWidth("100%") : barWidth.get();
        }

        @Override
        public int height(HudCanvas canvas) {
            return style.get() == Style.NUMBER ? canvas.lineHeight() : barHeight.get();
        }

        @Override
        public void draw(HudCanvas canvas) {
            float charge = charge(canvas);
            if (charge < 0) {
                return;
            }
            if (style.get() == Style.NUMBER) {
                String text = text(charge);
                int x = (width(canvas) - canvas.textWidth(text)) / 2;
                if (customColors.get()) {
                    canvas.text(text, x, 0, color(charge));
                } else {
                    canvas.text(text, x, 0, tone(charge));
                }
                return;
            }
            int w = barWidth.get();
            int h = barHeight.get();
            canvas.fill(0, 0, w, h, Tone.BACKGROUND);
            int filled = Math.round(w * charge);
            if (customColors.get()) {
                canvas.fill(0, 0, filled, h, color(charge));
            } else {
                canvas.fill(0, 0, filled, h, tone(charge));
            }
        }
    }

    /** The ring round the crosshair. */
    private final class Ring extends HudElement {

        Ring() {
            super("ring", "Cooldown ring", Anchor.CENTER, 0, 0);
        }

        @Override
        public boolean isLive() {
            return true;
        }

        @Override
        public boolean isPlaceable() {
            return style.get() == Style.RING;
        }

        @Override
        public boolean isVisible() {
            return style.get() == Style.RING && system().game().player() != null;
        }

        @Override
        public int width(HudCanvas canvas) {
            return ringSize.get() * 2;
        }

        @Override
        public int height(HudCanvas canvas) {
            return ringSize.get() * 2;
        }

        @Override
        public void draw(HudCanvas canvas) {
            float charge = charge(canvas);
            if (charge < 0) {
                return;
            }
            float r = ringSize.get();
            canvas.ring(r, r, r, RING_WIDTH, Tone.BACKGROUND);
            if (customColors.get()) {
                canvas.arc(r, r, r, RING_WIDTH, charge, color(charge));
            } else {
                canvas.arc(r, r, r, RING_WIDTH, charge, tone(charge));
            }
        }
    }
}
