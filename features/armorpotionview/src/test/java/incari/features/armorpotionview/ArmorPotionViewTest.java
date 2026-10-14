package incari.features.armorpotionview;

import static org.assertj.core.api.Assertions.assertThat;

import incari.features.armorpotionview.ArmorPotionView.Durability;
import incari.framework.game.EffectCategory;
import incari.framework.game.EquipmentSlot;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudElement;
import incari.framework.testing.FakePlayer;
import incari.framework.testing.TestGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ArmorPotionViewTest {

    private final ArmorPotionView module = new ArmorPotionView();
    private final TestGame game = new TestGame(module);
    private final HudElement armor = module.hudElements().get(0);
    private final HudElement effects = module.hudElements().get(1);

    @BeforeEach
    void atTheTopLeft() {
        module.setEnabled(true);
        for (HudElement element : module.hudElements()) {
            element.anchor().set(Anchor.TOP_LEFT);
            element.offsetX().set(0);
            element.offsetY().set(0);
        }
    }

    @Test
    void nothingOutsideAWorldOrWithNothingOn() {
        assertThat(game.drawHud().drawn).isEmpty();
        game.join();
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void eachPieceWithItsDurabilityColoredByWear() {
        game.join()
                .wear(EquipmentSlot.HEAD, "helmet", 0, 363)
                .wear(EquipmentSlot.CHEST, "chestplate", 300, 528)
                .wear(EquipmentSlot.FEET, "boots", 400, 429)
                .hold(EquipmentSlot.MAINHAND, "dirt", 64);
        effects.anchor().set(Anchor.BOTTOM_RIGHT);

        assertThat(game.drawHud().drawn)
                .containsExactly(
                        "item \"helmet\" at 0,0 +decorations",
                        "text \"100%\" at 18,4 POSITIVE",
                        "item \"chestplate\" at 0,17 +decorations",
                        "text \"43%\" at 18,21 WARNING",
                        "item \"boots\" at 0,34 +decorations",
                        "text \"6%\" at 18,38 NEGATIVE",
                        // Held, but nothing to wear out: no text.
                        "item \"dirt\" at 0,51 +decorations");
    }

    @Test
    void durabilityInEachFormat() {
        assertThat(ArmorElement.format(Durability.PERCENT, 312, 363)).isEqualTo("85%");
        assertThat(ArmorElement.format(Durability.REMAINING, 312, 363)).isEqualTo("312");
        assertThat(ArmorElement.format(Durability.REMAINING_OF_MAX, 312, 363)).isEqualTo("312/363");
        assertThat(ArmorElement.percent(1, 1000)).isEqualTo(1);
        assertThat(ArmorElement.percent(0, 1000)).isZero();
    }

    @Test
    void theBarAloneWritesNothingAndPlainColorsAreTheText() {
        game.join().wear(EquipmentSlot.HEAD, "helmet", 350, 363);
        module.durability.set(Durability.BAR);
        assertThat(game.drawHud().drawn).containsExactly("item \"helmet\" at 0,0 +decorations");

        module.durability.set(Durability.REMAINING);
        module.wearColors.set(false);
        assertThat(game.drawHud().drawn)
                .containsExactly("item \"helmet\" at 0,0 +decorations", "text \"13\" at 18,4 TEXT");
    }

    @Test
    void horizontalArmorRunsAcross() {
        game.join().wear(EquipmentSlot.HEAD, "helmet", 0, 100).wear(EquipmentSlot.LEGS, "leggings", 50, 100);
        module.showHeld.set(false);
        module.layout.set(ArmorPotionView.Layout.HORIZONTAL);
        // "100%" is 24 wide: the next cell starts at 16 + 2 + 24 + 4.
        assertThat(game.drawHud().drawn)
                .containsExactly(
                        "item \"helmet\" at 0,0 +decorations",
                        "text \"100%\" at 18,4 POSITIVE",
                        "item \"leggings\" at 46,0 +decorations",
                        "text \"50%\" at 64,4 POSITIVE");
    }

    @Test
    void effectsWithLevelsAndTimeLeft() {
        game.join()
                .effect("Speed", 1, 1660, EffectCategory.BENEFICIAL)
                .effect("Poison", 0, 150, EffectCategory.HARMFUL)
                .effect("Night Vision", 0, -1, EffectCategory.BENEFICIAL);

        assertThat(game.drawHud().drawn)
                .containsExactly(
                        "effect \"Speed\" at 0,0",
                        "text \"Speed II\" at 21,0 TEXT",
                        "text \"1:23\" at 21,9 MUTED",
                        "effect \"Poison\" at 0,20",
                        "text \"Poison\" at 21,20 TEXT",
                        // The last ten seconds stand out.
                        "text \"0:07\" at 21,29 WARNING",
                        "effect \"Night Vision\" at 0,40",
                        "text \"Night Vision\" at 21,40 TEXT",
                        "text \"∞\" at 21,49 MUTED");
    }

    @Test
    void effectsColoredByKindIconsOnTheRightTimesOnly() {
        game.join()
                .effect("Speed", 0, 400, EffectCategory.BENEFICIAL)
                .effect("Weakness", 2, 9000, EffectCategory.HARMFUL);
        module.kindColors.set(true);
        assertThat(game.drawHud().drawn)
                .contains("text \"Speed\" at 21,0 POSITIVE", "text \"Weakness III\" at 21,20 NEGATIVE");

        module.side.set(ArmorPotionView.Side.RIGHT);
        module.effectNames.set(false);
        // Width: 18 + 3 + "7:30" (24) = 45; the times end 3 left of the icons.
        assertThat(game.drawHud().drawn)
                .containsExactly(
                        "effect \"Speed\" at 27,0",
                        "text \"0:20\" at 0,5 MUTED",
                        "effect \"Weakness\" at 27,20",
                        "text \"7:30\" at 0,25 MUTED");
    }

    @Test
    void levelsAndClocks() {
        assertThat(EffectsElement.level(0)).isEmpty();
        assertThat(EffectsElement.level(3)).isEqualTo(" IV");
        assertThat(EffectsElement.level(254)).isEqualTo(" 255");
        assertThat(EffectsElement.clock(8)).isEqualTo("0:08");
        assertThat(EffectsElement.clock(3723)).isEqualTo("1:02:03");
    }

    @Test
    void theHudEditorShowsTheSampleWhenThePlayerHasNothing() {
        game.join();
        FakePlayer sample = game.sample;
        sample.wear(EquipmentSlot.HEAD, "sample helmet", 0, 363).effect("Speed", 0, 600, EffectCategory.BENEFICIAL);
        assertThat(game.drawHud(true).drawn)
                .contains("item \"sample helmet\" at 0,0 +decorations", "effect \"Speed\" at 0,0");
        assertThat(game.drawHud().drawn).isEmpty();
    }

    @Test
    void eachHalfTurnsOffOnItsOwn() {
        game.join().wear(EquipmentSlot.HEAD, "helmet", 0, 100).effect("Speed", 0, 400, EffectCategory.BENEFICIAL);
        module.showArmor.set(false);
        assertThat(armor.isPlaceable()).isFalse();
        assertThat(game.drawHud().drawn).noneMatch(line -> line.startsWith("item"));
        module.showEffects.set(false);
        assertThat(game.drawHud().drawn).isEmpty();
    }
}
