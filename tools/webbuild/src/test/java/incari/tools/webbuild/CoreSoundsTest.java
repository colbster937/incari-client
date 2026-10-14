package incari.tools.webbuild;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CoreSoundsTest {

    @Test
    void keepsTheEverydaySoundsAndLeavesMusicAndTheRestOut() {
        assertThat(CoreSounds.keeps("minecraft/sounds/dig/stone1.ogg")).isTrue();
        assertThat(CoreSounds.keeps("minecraft/sounds/random/explode1.ogg")).isTrue();
        assertThat(CoreSounds.keeps("minecraft/sounds/block/chest/open.ogg")).isTrue();
        assertThat(CoreSounds.keeps("minecraft/sounds/mob/creeper/say1.ogg")).isTrue();

        assertThat(CoreSounds.keeps("minecraft/sounds/music/game/calm1.ogg")).isFalse();
        assertThat(CoreSounds.keeps("minecraft/sounds/records/cat.ogg")).isFalse();
        assertThat(CoreSounds.keeps("minecraft/sounds/ambient/cave/cave1.ogg")).isFalse();
        assertThat(CoreSounds.keeps("minecraft/sounds/block/deepslate/break1.ogg"))
                .isFalse();
        assertThat(CoreSounds.keeps("minecraft/sounds/mob/pig/say1.ogg")).isFalse();
        // Only the sound files themselves.
        assertThat(CoreSounds.keeps("minecraft/sounds.json")).isFalse();
        assertThat(CoreSounds.keeps("minecraft/lang/dig/x.ogg")).isFalse();
    }

    @Test
    void trimsEventsToSoundsLeftAndDropsEventsLeftEmpty() {
        JsonObject list = JsonParser.parseString("""
                {"a": {"sounds": ["dig/stone1", "dig/gone"]},
                 "b": {"sounds": [{"name": "minecraft:dig/gone", "volume": 0.5}]},
                 "c": {"sounds": [{"name": "b", "type": "event"}, {"name": "a", "type": "event"}]},
                 "d": {"sounds": [{"name": "b", "type": "event"}]},
                 "e": {"replace": true}}
                """).getAsJsonObject();

        JsonObject trimmed = CoreSounds.trim(list, Set.of("minecraft/sounds/dig/stone1.ogg")::contains);

        assertThat(trimmed.keySet()).containsExactlyInAnyOrder("a", "c", "e");
        assertThat(trimmed.getAsJsonObject("a").getAsJsonArray("sounds")).hasSize(1);
        assertThat(trimmed.getAsJsonObject("c").getAsJsonArray("sounds")).hasSize(1);
        // The input is left as it was.
        assertThat(list.keySet()).hasSize(5);
    }
}
