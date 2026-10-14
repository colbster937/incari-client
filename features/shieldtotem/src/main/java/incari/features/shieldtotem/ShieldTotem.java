package incari.features.shieldtotem;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.EquipmentSlot;
import incari.framework.game.ItemView;
import incari.framework.game.PlayerView;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import incari.framework.setting.BooleanSetting;
import org.jspecify.annotations.Nullable;

/**
 * Shield &amp; Totem HUD, beside Armor &amp; Potion View: the shield the player holds with its
 * durability, and whether an axe has disabled it; and how many totems of undying they carry.
 */
public final class ShieldTotem extends Module {

    static final String SHIELD = "minecraft:shield";
    static final String TOTEM = "minecraft:totem_of_undying";

    final BooleanSetting showShield = settings()
            .bool("show-shield", "Show shield", true)
            .describedAs("Shows the shield you hold, its durability and when it's disabled.");
    final BooleanSetting wearColors = settings()
            .bool("wear-colors", "Color by wear", true)
            .describedAs("Durability turns amber below half and red below a fifth.")
            .visibleWhen(showShield::get);
    final BooleanSetting showTotems = settings()
            .bool("show-totems", "Show totems", true)
            .describedAs("Shows how many totems of undying you carry.");
    final BooleanSetting showNoTotems = settings()
            .bool("show-no-totems", "Warn with none", true)
            .describedAs("Shows a red 0 when you carry none; off hides the count instead.")
            .visibleWhen(showTotems::get);

    /** Shield &amp; Totem HUD, off until the player turns it on. */
    public ShieldTotem() {
        super(
                "shieldtotem",
                "Shield & Totem HUD",
                "Shows your shield's durability and cooldown, and the totems you carry.",
                Category.HUD);
        hud(new Shield());
        hud(new Totems());
    }

    /** "86%" of a shield's durability: whole percent, rounded down, 1 while anything's left. */
    static int percent(int remaining, int max) {
        if (max <= 0 || remaining <= 0) {
            return 0;
        }
        return Math.max(1, (int) ((long) remaining * 100 / max));
    }

    private static final int ICON = 16;
    private static final int TEXT_GAP = 2;

    /** The held shield: off hand first, where it's usually carried. */
    private final class Shield extends HudElement {

        private int cachedPercent = -1;
        private String percentText = "";

        Shield() {
            // Right of the hotbar, where nothing of the game's is: the off hand's slot is on its left.
            super("shield", "Shield", Anchor.BOTTOM, 122, 3);
        }

        private @Nullable ItemView shield(HudCanvas canvas) {
            PlayerView player = system().game().player();
            ItemView held = player == null ? null : held(player);
            if (held == null && canvas.editing()) {
                return system().game().item(SHIELD);
            }
            return held;
        }

        private @Nullable ItemView held(PlayerView player) {
            ItemView off = player.item(EquipmentSlot.OFFHAND);
            if (off.id().equals(SHIELD)) {
                return off;
            }
            ItemView main = player.item(EquipmentSlot.MAINHAND);
            return main.id().equals(SHIELD) ? main : null;
        }

        @Override
        public boolean isVisible() {
            PlayerView player = system().game().player();
            return showShield.get() && player != null && held(player) != null;
        }

        @Override
        public boolean isPlaceable() {
            return showShield.get();
        }

        private String text(ItemView shield) {
            if (shield.cooldown() > 0) {
                return "Disabled";
            }
            int percent = percent(shield.remaining(), shield.maxDamage());
            if (shield.maxDamage() <= 0) {
                percent = 100;
            }
            if (percent != cachedPercent) {
                cachedPercent = percent;
                percentText = percent + "%";
            }
            return percentText;
        }

        @Override
        public int width(HudCanvas canvas) {
            ItemView shield = shield(canvas);
            return shield == null ? 0 : ICON + TEXT_GAP + canvas.textWidth(text(shield));
        }

        @Override
        public int height(HudCanvas canvas) {
            return ICON;
        }

        @Override
        public void draw(HudCanvas canvas) {
            ItemView shield = shield(canvas);
            if (shield == null) {
                return;
            }
            canvas.item(shield, 0, 0, true);
            float cooldown = shield.cooldown();
            int textY = (ICON - canvas.lineHeight()) / 2 + 1;
            canvas.text(text(shield), ICON + TEXT_GAP, textY, tone(shield, cooldown));
            if (cooldown > 0) {
                // What's left of the cooldown, as a bar under the word.
                int width = canvas.textWidth("Disabled");
                canvas.fill(ICON + TEXT_GAP, ICON - 1, Math.round(width * cooldown), 1, Tone.NEGATIVE);
            }
        }

        private Tone tone(ItemView shield, float cooldown) {
            if (cooldown > 0) {
                return Tone.NEGATIVE;
            }
            if (!wearColors.get() || shield.maxDamage() <= 0) {
                return Tone.TEXT;
            }
            long remaining = shield.remaining();
            long max = shield.maxDamage();
            if (remaining * 5 < max) {
                return Tone.NEGATIVE;
            }
            return remaining * 2 < max ? Tone.WARNING : Tone.POSITIVE;
        }
    }

    /** The totem's icon and how many are carried. */
    private final class Totems extends HudElement {

        private int cachedCount = -1;
        private String countText = "";

        Totems() {
            super("totems", "Totems", Anchor.BOTTOM, 170, 3);
        }

        private int count(HudCanvas canvas) {
            PlayerView player = system().game().player();
            if (player == null) {
                return canvas.editing() ? 2 : -1;
            }
            return player.count(TOTEM);
        }

        @Override
        public boolean isVisible() {
            PlayerView player = system().game().player();
            return showTotems.get() && player != null && (showNoTotems.get() || player.count(TOTEM) > 0);
        }

        @Override
        public boolean isPlaceable() {
            return showTotems.get();
        }

        private String text(int count) {
            if (count != cachedCount) {
                cachedCount = count;
                countText = "x" + count;
            }
            return countText;
        }

        @Override
        public int width(HudCanvas canvas) {
            int count = count(canvas);
            return count < 0 ? 0 : ICON + TEXT_GAP + canvas.textWidth(text(count));
        }

        @Override
        public int height(HudCanvas canvas) {
            return ICON;
        }

        @Override
        public void draw(HudCanvas canvas) {
            int count = count(canvas);
            if (count < 0) {
                return;
            }
            canvas.item(system().game().item(TOTEM), 0, 0, false);
            int textY = (ICON - canvas.lineHeight()) / 2 + 1;
            canvas.text(text(count), ICON + TEXT_GAP, textY, count == 0 ? Tone.NEGATIVE : Tone.TEXT);
        }
    }
}
