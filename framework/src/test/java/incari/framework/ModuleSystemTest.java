package incari.framework;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import incari.framework.event.Events;
import incari.framework.hud.Anchor;
import incari.framework.hud.HudCanvas;
import incari.framework.hud.HudElement;
import incari.framework.hud.Tone;
import incari.framework.setting.BooleanSetting;
import incari.framework.setting.IntSetting;
import incari.framework.setting.Key;
import incari.framework.setting.KeySetting;
import incari.framework.testing.TestGame;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ModuleSystemTest {

    /** A module with a bit of everything, counting what happens to it. */
    static final class Sample extends Module {

        final BooleanSetting flag = settings().bool("flag", "Flag", false);
        final IntSetting amount = settings().integer("amount", "Amount", 0, 10, 3);
        final KeySetting action = settings().key("action", "Action", Key.keyboard(9));
        final List<String> calls = new ArrayList<>();
        boolean throwOnTick;

        Sample(String id) {
            super(id, "Sample " + id, "For tests.", Category.UTILITY);
            on(Events.TICK, this::tick);
            action.onPress(() -> calls.add("action"));
            hud(new Label());
        }

        private void tick() {
            if (throwOnTick) {
                throw new IllegalStateException("broken");
            }
            calls.add("tick");
        }

        @Override
        protected void onEnable() {
            calls.add("enable flag=" + flag.get());
        }

        @Override
        protected void onDisable() {
            calls.add("disable");
        }

        static final class Label extends HudElement {

            Label() {
                super("label", "Label", Anchor.TOP_RIGHT, 2, 2);
            }

            @Override
            public int width(HudCanvas canvas) {
                return canvas.textWidth("hi");
            }

            @Override
            public int height(HudCanvas canvas) {
                return canvas.lineHeight();
            }

            @Override
            public void draw(HudCanvas canvas) {
                canvas.text("hi", 0, 0, Tone.ACCENT);
            }
        }
    }

    @Test
    void listenersRunOnlyWhileEnabled() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);

        game.tick();
        assertThat(game.system().events().count(Events.TICK)).isZero();
        sample.setEnabled(true);
        game.tick();
        sample.setEnabled(false);
        game.tick();

        assertThat(sample.calls).containsExactly("enable flag=false", "tick", "disable");
        assertThat(game.system().events().count(Events.TICK)).isZero();
    }

    @Test
    void theToggleKeyWorksWhileOffAndOtherKeysOnlyWhileOn() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);
        sample.toggleKey().set(Key.keyboard(40));

        game.press(Key.keyboard(9));
        game.tick();
        assertThat(sample.calls).isEmpty();

        game.press(Key.keyboard(40));
        game.tick();
        assertThat(sample.isEnabled()).isTrue();

        game.press(Key.keyboard(9));
        game.press(Key.keyboard(9));
        game.tick();
        assertThat(sample.calls).containsExactly("enable flag=false", "tick", "action", "action", "tick");
    }

    @Test
    void unboundKeysNeverFire() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);
        sample.setEnabled(true);
        sample.action.set(Key.NONE);
        sample.calls.clear();

        // A key the platform can't name arrives as "no key", which is what unbound keys hold.
        game.press(Key.NONE);
        game.hold(Key.NONE, true);
        game.tick();

        assertThat(sample.isEnabled()).isTrue();
        assertThat(sample.action.isDown()).isFalse();
        assertThat(sample.calls).containsExactly("tick");
    }

    @Test
    void heldKeysReadAsDownOnlyWhileTheirModuleIsOn() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);
        game.hold(Key.keyboard(9), true);

        game.tick();
        assertThat(sample.action.isDown()).isFalse();
        sample.setEnabled(true);
        game.tick();
        assertThat(sample.action.isDown()).isTrue();
    }

    @Test
    void aModuleThatThrowsIsTurnedOffAndLogged() {
        Sample broken = new Sample("broken");
        Sample fine = new Sample("fine");
        TestGame game = new TestGame(broken, fine);
        broken.setEnabled(true);
        fine.setEnabled(true);
        broken.throwOnTick = true;

        game.tick();
        game.tick();

        assertThat(broken.isEnabled()).isFalse();
        assertThat(fine.calls).containsExactly("enable flag=false", "tick", "tick");
        assertThat(game.warnings).hasSize(1).first().asString().contains("broken", "tick", "turned off");
        // The player is told, once.
        assertThat(game.messages)
                .containsExactly("Sample broken was turned off: It hit a problem. You can turn it on again.");
    }

    @Test
    void theHudDrawsEnabledModulesElementsWherePlaced() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);

        assertThat(game.system().hasHud()).isFalse();
        assertThat(game.drawHud().drawn).isEmpty();

        sample.setEnabled(true);
        HudElement label = sample.hudElements().get(0);
        label.scale().set(2);
        TestGame.Canvas canvas = game.drawHud();

        // 12 units wide at 2x is 24, 2 in from the right of 320.
        assertThat(canvas.drawn).containsExactly("text \"hi\" at 294,2 ACCENT");
        assertThat(canvas.balanced()).isTrue();
    }

    @Test
    void changesAreSavedASecondAfterTheLastOne() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);

        sample.amount.set(7);
        game.ticks(19);
        assertThat(game.files).isEmpty();
        sample.amount.set(8);
        game.ticks(19);
        assertThat(game.files).isEmpty();
        game.tick();

        assertThat(game.files).containsKeys("profiles.json", "profiles/Default.json");
        assertThat(game.files.get("profiles/Default.json")).contains("\"amount\": 8");
    }

    @Test
    void aToggleByKeyIsSavedAtOnce() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);
        sample.toggleKey().set(Key.keyboard(40));
        game.system().flush();
        game.files.clear();

        game.press(Key.keyboard(40));
        game.tick();

        assertThat(game.files.get("profiles/Default.json")).contains("\"enabled\": true");
    }

    @Test
    void aSavedProfileComesBackInANewSession() {
        Sample sample = new Sample("sample");
        TestGame game = new TestGame(sample);
        sample.flag.set(true);
        sample.setEnabled(true);
        sample.action.set(Key.mouse(3));
        sample.hudElements().get(0).anchor().set(Anchor.BOTTOM);
        game.system().flush();

        Sample again = new Sample("sample");
        TestGame next = new TestGame(again);
        next.files.putAll(game.files);
        next.system().load();

        assertThat(again.isEnabled()).isTrue();
        assertThat(again.flag.get()).isTrue();
        assertThat(again.action.get()).isEqualTo(Key.mouse(3));
        assertThat(again.hudElements().get(0).anchor().get()).isEqualTo(Anchor.BOTTOM);
        // onEnable ran after the settings were read.
        assertThat(again.calls).containsExactly("enable flag=true");
        // Loading isn't a change to save.
        next.files.clear();
        next.ticks(30);
        assertThat(next.files).isEmpty();
    }

    @Test
    void twoModulesCantShareAnId() {
        assertThatThrownBy(() -> new TestGame(new Sample("same"), new Sample("same")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modulesSubscribeOnlyInTheirConstructor() {
        Sample sample = new Sample("sample");
        new TestGame(sample);
        assertThatThrownBy(() -> sample.on(Events.TICK, () -> {})).isInstanceOf(IllegalStateException.class);
    }

    /** A module with one live element and one drawn with the HUD. */
    static final class Live extends Module {

        Live() {
            super("live", "Live", "For tests.", Category.HUD);
            hud(new Sample.Label());
            hud(new HudElement("compass", "Compass", Anchor.TOP_LEFT, 0, 0) {
                @Override
                public boolean isLive() {
                    return true;
                }

                @Override
                public int width(HudCanvas canvas) {
                    return 10;
                }

                @Override
                public int height(HudCanvas canvas) {
                    return 10;
                }

                @Override
                public void draw(HudCanvas canvas) {
                    canvas.text("N", 0, 0, Tone.ACCENT);
                }
            });
        }
    }

    @Test
    void liveElementsAreDrawnEveryFrameNotWithTheHud() {
        TestGame game = new TestGame(new Live());
        assertThat(game.system().hasLiveHud()).isFalse();
        game.module().setEnabled(true);
        assertThat(game.system().hasHud()).isTrue();
        assertThat(game.system().hasLiveHud()).isTrue();
        assertThat(game.drawHud().drawn).containsExactly("text \"hi\" at 306,2 ACCENT");
        assertThat(game.drawLive().drawn).containsExactly("text \"N\" at 0,0 ACCENT");
    }

    @Test
    void anActionIsAButtonNotAValue() {
        List<String> pressed = new ArrayList<>();
        Sample sample = new Sample("sample");
        incari.framework.setting.ActionSetting open =
                sample.settings().action("open", "Things", "Open...", () -> pressed.add("open"));
        TestGame game = new TestGame(sample);
        open.run();
        assertThat(pressed).containsExactly("open");
        sample.flag.set(true);
        game.system().flush();
        assertThat(game.files.values()).noneMatch(text -> text.contains("\"open\""));
    }

    /** A part of the client rather than a choice. */
    static final class Built extends Module {

        Built() {
            super("built", "Built", "For tests.", Category.UTILITY);
            alwaysOn();
        }
    }

    @Test
    void anAlwaysOnModuleIsOnAfterLoadingAndCantBeTurnedOff() {
        Built built = new Built();
        TestGame game = new TestGame(built, new Sample("sample"));
        game.files.put("profiles/Default.json", "{\"modules\": {\"built\": {\"enabled\": false}}}");
        game.system().load();
        assertThat(built.isEnabled()).isTrue();
        built.setEnabled(false);
        built.toggle();
        assertThat(built.isEnabled()).isTrue();
        assertThat(built.toggleKey().isHidden()).isTrue();
    }

    @Test
    void aPresetMakesItsProfileAndSetsWhichModulesAreOn() {
        Sample a = new Sample("a");
        Sample b = new Sample("b");
        Built built = new Built();
        TestGame game = new TestGame(a, b, built);
        game.system().load();
        a.setEnabled(true);
        b.amount.set(7);

        assertThat(game.system().applyPreset("QoL", java.util.Set.of("b", "missing")))
                .isTrue();

        assertThat(game.system().activeProfile()).isEqualTo("QoL");
        assertThat(a.isEnabled()).isFalse();
        assertThat(b.isEnabled()).isTrue();
        assertThat(b.amount.get()).isEqualTo(7);
        assertThat(built.isEnabled()).isTrue();
        // Applied again later, it switches back to the same profile.
        game.system().switchProfile("Default");
        assertThat(a.isEnabled()).isTrue();
        assertThat(game.system().applyPreset("qol", java.util.Set.of("a"))).isTrue();
        assertThat(game.system().profiles()).containsExactly("Default", "QoL");
        assertThat(a.isEnabled()).isTrue();
        assertThat(b.isEnabled()).isFalse();
    }

    @Test
    void aPresetCanSetSettings() {
        Sample a = new Sample("a");
        TestGame game = new TestGame(a);
        game.system().load();

        assertThat(game.system().applyPreset("1.8 PvP", java.util.Set.of("a"), java.util.Map.of("a.amount", "9")))
                .isTrue();

        assertThat(game.system().activeProfile()).isEqualTo("1.8 PvP");
        assertThat(a.amount.get()).isEqualTo(9);
        // A value the setting can't take leaves it.
        game.system().applyPreset("1.8 PvP", java.util.Set.of("a"), java.util.Map.of("a.amount", "lots"));
        assertThat(a.amount.get()).isEqualTo(9);
    }
}
