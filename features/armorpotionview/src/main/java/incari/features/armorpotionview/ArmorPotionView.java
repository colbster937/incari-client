package incari.features.armorpotionview;

import incari.framework.Category;
import incari.framework.Module;
import incari.framework.game.PlayerView;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.ChoiceSetting;
import org.jspecify.annotations.Nullable;

/**
 * Armor &amp; Potion View: two HUD elements, placed apart. One shows the armor the player wears (and
 * what they hold) with how worn each piece is, so a breaking helmet is seen before it breaks; the
 * other lists the active effects with their levels and the time left.
 */
public final class ArmorPotionView extends Module {

    /** How an item's durability is written beside it. */
    public enum Durability {
        /** "86%". */
        PERCENT("Percent"),
        /** "312": uses left. */
        REMAINING("Uses left"),
        /** "312/363". */
        REMAINING_OF_MAX("Uses left of max"),
        /** Nothing beside it: the game's bar on the icon only. */
        BAR("Bar only");

        private final String label;

        Durability(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** Which way the armor runs. */
    public enum Layout {
        /** Helmet at the top, boots at the bottom. */
        VERTICAL("Vertical"),
        /** Helmet on the left, boots on the right. */
        HORIZONTAL("Horizontal");

        private final String label;

        Layout(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    /** Which side of the effects' text their icons go. */
    public enum Side {
        /** Icons on the left, text after. */
        LEFT("Left"),
        /** Icons on the right, text before: for the screen's right side. */
        RIGHT("Right");

        private final String label;

        Side(String label) {
            this.label = label;
        }

        /** What the settings screen calls it. */
        public String label() {
            return label;
        }
    }

    final BooleanSetting showArmor = settings()
            .bool("show-armor", "Show armor", true)
            .describedAs("Shows the armor you wear and its durability.");
    final BooleanSetting showHeld = settings()
            .bool("show-held", "Show held item", true)
            .describedAs("Adds the item in your main hand below the armor.")
            .visibleWhen(showArmor::get);
    final ChoiceSetting<Durability> durability = settings()
            .choice("durability", "Durability", Durability.values(), Durability.PERCENT, Durability::label)
            .describedAs("How durability is shown beside each piece.")
            .visibleWhen(showArmor::get);
    final BooleanSetting wearColors = settings()
            .bool("wear-colors", "Color by wear", true)
            .describedAs("Durability turns amber below half and red below a fifth.")
            .visibleWhen(() -> showArmor.get() && durability.get() != Durability.BAR);
    final ChoiceSetting<Layout> layout = settings()
            .choice("layout", "Armor layout", Layout.values(), Layout.VERTICAL, Layout::label)
            .visibleWhen(showArmor::get);
    final BooleanSetting showEffects = settings()
            .bool("show-effects", "Show effects", true)
            .describedAs("Lists your active effects with the time they have left.");
    final BooleanSetting effectNames = settings()
            .bool("effect-names", "Effect names", true)
            .describedAs("Off: icons and times only.")
            .visibleWhen(showEffects::get);
    final BooleanSetting kindColors = settings()
            .bool("kind-colors", "Color by kind", false)
            .describedAs("Helpful effects' names in green, harmful ones' in red.")
            .visibleWhen(() -> showEffects.get() && effectNames.get());
    final ChoiceSetting<Side> side = settings()
            .choice("side", "Effect icons on the", Side.values(), Side.LEFT, Side::label)
            .visibleWhen(showEffects::get);

    /** Armor &amp; Potion View, off until the player turns it on. */
    public ArmorPotionView() {
        super(
                "armorpotionview",
                "Armor & Potion View",
                "Shows your armor's durability and your active effects on the HUD.",
                Category.HUD);
        hud(new ArmorElement(this));
        hud(new EffectsElement(this));
    }

    /** The player, or null outside a world. */
    @Nullable
    PlayerView player() {
        return system().game().player();
    }

    /** The HUD editor's stand-in player. */
    PlayerView samplePlayer() {
        return system().game().samplePlayer();
    }
}
