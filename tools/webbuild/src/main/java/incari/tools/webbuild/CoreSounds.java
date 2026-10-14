package incari.tools.webbuild;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The sounds the single HTML file carries where ffmpeg isn't there to make them smaller
 * ({@link SmallSounds}; ADR 0057, ADR 0062): every sound is 377 MB, music and records 293 of it,
 * more than a file a 4 GB device opens should hold. These are about 10 MB, as they are: digging and footsteps of the classic materials, the interface, items, players,
 * damage, liquids, fire, explosions, everyday blocks (chests, doors, furnaces) and the hostile mobs a
 * fight depends on hearing. No music, records, ambience, or the newer blocks' and other mobs' own
 * sounds.
 *
 * <p>The sound list ({@code sounds.json}) is trimmed to match ({@link #trim}), so the game lists only
 * sounds the file has: no warning for a missing file at start, and no event that plays nothing.
 */
final class CoreSounds {

    /** Folders under {@code minecraft/sounds/} whose files are kept. */
    private static final List<String> FOLDERS = List.of(
            "damage/",
            "dig/",
            "enchant/",
            "entity/",
            "event/",
            "fire/",
            "fireworks/",
            "item/",
            "liquid/",
            "minecart/",
            "note/",
            "portal/",
            "random/",
            "step/",
            "tile/",
            "ui/",
            "block/barrel/",
            "block/beacon/",
            "block/brewing_stand/",
            "block/chest/",
            "block/enchantment_table/",
            "block/fence_gate/",
            "block/furnace/",
            "block/grindstone/",
            "block/iron_door/",
            "block/iron_trapdoor/",
            "block/lantern/",
            "block/smithing_table/",
            "block/wooden_door/",
            "block/wooden_trapdoor/",
            "mob/creeper/",
            "mob/endermen/",
            "mob/skeleton/",
            "mob/spider/",
            "mob/zombie/");

    private static final String SOUNDS = "minecraft/sounds/";

    private CoreSounds() {}

    /** Whether the asset index's {@code name} is a sound file the single file carries. */
    static boolean keeps(String name) {
        if (!name.startsWith(SOUNDS) || !name.endsWith(".ogg")) {
            return false;
        }
        String path = name.substring(SOUNDS.length());
        for (String folder : FOLDERS) {
            if (path.startsWith(folder)) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@code soundList} (the game's {@code sounds.json}) with only the sounds whose files {@code
     * kept} names (by asset name) and the events those leave something to play. An event entry
     * ({@code "type": "event"}) stays while the event it names does; repeated until nothing changes.
     */
    static JsonObject trim(JsonObject soundList, Predicate<String> kept) {
        JsonObject events = soundList.deepCopy();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, JsonElement> event : List.copyOf(events.entrySet())) {
                JsonObject definition = event.getValue().getAsJsonObject();
                JsonArray sounds = definition.getAsJsonArray("sounds");
                if (sounds == null) {
                    continue;
                }
                JsonArray left = new JsonArray();
                for (JsonElement sound : sounds) {
                    if (plays(sound, events, kept)) {
                        left.add(sound);
                    }
                }
                if (left.isEmpty()) {
                    events.remove(event.getKey());
                    changed = true;
                } else if (left.size() != sounds.size()) {
                    definition.add("sounds", left);
                    changed = true;
                }
            }
        }
        return events;
    }

    private static boolean plays(JsonElement sound, JsonObject events, Predicate<String> kept) {
        String name;
        boolean event = false;
        if (sound.isJsonPrimitive()) {
            name = sound.getAsString();
        } else {
            JsonObject entry = sound.getAsJsonObject();
            name = entry.get("name").getAsString();
            JsonElement type = entry.get("type");
            event = type != null && type.getAsString().equals("event");
        }
        String namespace = "minecraft";
        int colon = name.indexOf(':');
        if (colon >= 0) {
            namespace = name.substring(0, colon);
            name = name.substring(colon + 1);
        }
        if (event) {
            // Another event of this list, which plays while it's still here.
            return namespace.equals("minecraft") && events.has(name);
        }
        return kept.test(namespace + "/sounds/" + name + ".ogg");
    }
}
