package incari.online;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import incari.framework.config.ConfigStorage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The player's own cosmetics (ADR 0061): what they wear, what they own, their saved outfits, and
 * whether they see other players' cosmetics. Kept on this device in {@code wardrobe.json} under the
 * client's {@code incari/online}, so what's worn shows at once and without an account; while
 * signed in, the account's wardrobe on the service is the truth and this follows it.
 *
 * <p>Changes apply here at once, so the wardrobe screen never waits on the service;
 * {@link OnlineService} sends {@code equip} once the player stops, at most once a second.
 *
 * <p>Game thread only.
 */
public final class Wardrobe {

    static final String FILE = "wardrobe.json";

    /** The most outfits kept. */
    public static final int MAX_OUTFITS = 12;

    /** The longest outfit name. */
    public static final int MAX_OUTFIT_NAME = 24;

    /** A loadout the player saved under a name, to put on again in one click. */
    public record Outfit(String name, Loadout loadout) {}

    private final ConfigStorage storage;
    private final OnlineService.Log log;

    private Loadout worn = Loadout.EMPTY;
    /** The non-free cosmetics the account owns, as the service last said. */
    private final Set<String> owned = new HashSet<>();

    private final List<Outfit> outfits = new ArrayList<>();
    private boolean showOthers = true;

    /** Whether the account's wardrobe arrived on this connection: what's worn is then the account's. */
    private boolean synced;
    /** Whether it arrived at all since signing in (a reconnect keeps changes made while away). */
    private boolean accountSeen;
    /** Whether what's worn changed here and the service hasn't heard yet. */
    private boolean unsent;

    private long revision;

    Wardrobe(ConfigStorage storage, OnlineService.Log log) {
        this.storage = storage;
        this.log = log;
        load();
    }

    // ---------------------------------------------------------------------------------------------
    // What the screens read
    // ---------------------------------------------------------------------------------------------

    /** What the player wears now. */
    public Loadout worn() {
        return worn;
    }

    /** Whether the player may wear (or play) {@code cosmetic}. */
    public boolean owns(Cosmetic cosmetic) {
        return cosmetic.free() || owned.contains(cosmetic.id());
    }

    /** The emotes the player owns, in the catalog's order: the emote wheel's. */
    public List<Cosmetic> emotes() {
        List<Cosmetic> emotes = new ArrayList<>();
        for (Cosmetic each : Catalog.inSlot(Cosmetic.Slot.EMOTE)) {
            if (owns(each)) {
                emotes.add(each);
            }
        }
        return emotes;
    }

    /** The saved outfits, oldest first. */
    public List<Outfit> outfits() {
        return List.copyOf(outfits);
    }

    /** Whether other players' cosmetics are drawn (the player may find them distracting). */
    public boolean showOthers() {
        return showOthers;
    }

    /**
     * Whether other Incari players see what's worn: signed in, with the account's wardrobe here.
     * Otherwise only this player sees it.
     */
    public boolean shared() {
        return synced;
    }

    /** Goes up whenever anything here changes. */
    public long revision() {
        return revision;
    }

    // ---------------------------------------------------------------------------------------------
    // What the screens change
    // ---------------------------------------------------------------------------------------------

    /** Puts {@code cosmetic} on, in place of what was in its slot. Ignored for one not owned. */
    public void wear(Cosmetic cosmetic) {
        if (owns(cosmetic) && cosmetic.slot().worn()) {
            change(worn.with(cosmetic));
        }
    }

    /** Takes off what's worn in {@code slot}. */
    public void takeOff(Cosmetic.Slot slot) {
        change(worn.without(slot));
    }

    /** Puts on {@code loadout} whole (an outfit, or nothing), leaving out what isn't owned. */
    public void wear(Loadout loadout) {
        change(loadout.ownedBy(this::owns));
    }

    /**
     * Saves what's worn as an outfit called {@code name} (cut to {@link #MAX_OUTFIT_NAME}), replacing
     * one of the same name.
     *
     * @return false if there's no room for another
     */
    public boolean saveOutfit(String name) {
        String trimmed = name.strip();
        if (trimmed.length() > MAX_OUTFIT_NAME) {
            trimmed = trimmed.substring(0, MAX_OUTFIT_NAME);
        }
        if (trimmed.isEmpty()) {
            trimmed = "Outfit " + (outfits.size() + 1);
        }
        for (int i = 0; i < outfits.size(); i++) {
            if (outfits.get(i).name().equalsIgnoreCase(trimmed)) {
                outfits.set(i, new Outfit(trimmed, worn));
                saved();
                return true;
            }
        }
        if (outfits.size() >= MAX_OUTFITS) {
            return false;
        }
        outfits.add(new Outfit(trimmed, worn));
        saved();
        return true;
    }

    /** Forgets an outfit. */
    public void deleteOutfit(Outfit outfit) {
        if (outfits.remove(outfit)) {
            saved();
        }
    }

    /** Draws other players' cosmetics, or not. */
    public void setShowOthers(boolean show) {
        if (show != showOthers) {
            showOthers = show;
            saved();
        }
    }

    private void change(Loadout next) {
        if (next.equals(worn)) {
            return;
        }
        worn = next;
        unsent = true;
        saved();
    }

    // ---------------------------------------------------------------------------------------------
    // The service's side (OnlineService)
    // ---------------------------------------------------------------------------------------------

    /**
     * The account's wardrobe arrived. On signing in, what the account wears replaces what's worn
     * here, unless the account wears nothing yet and this device does: then the device's is kept and
     * sent, so what a player picked before making an account carries over. Later (a reconnect, or
     * another session's change), a change made here and not yet sent wins: the player made it after
     * the service's answer left.
     *
     * @return whether what's worn here should be sent
     */
    boolean fromAccount(Set<String> ownedIds, Loadout equipped) {
        owned.clear();
        owned.addAll(ownedIds);
        boolean keep = accountSeen ? unsent : equipped.isEmpty() && !worn.isEmpty();
        synced = true;
        accountSeen = true;
        if (keep) {
            worn = worn.ownedBy(this::owns);
        } else {
            worn = equipped.ownedBy(this::owns);
        }
        unsent = keep;
        saved();
        return unsent;
    }

    /** The service took what was sent; {@code equipped} is what it keeps. */
    void sent(Loadout sentLoadout, Loadout equipped) {
        if (worn.equals(sentLoadout)) {
            unsent = false;
            if (!equipped.equals(worn)) {
                worn = equipped.ownedBy(this::owns);
                saved();
            }
        }
    }

    /** What's worn here, if the service hasn't heard of it yet and is to; null otherwise. */
    @Nullable
    Loadout unsent() {
        return synced && unsent ? worn : null;
    }

    /** Signed out: what the device keeps is what was worn, less what only the account owned. */
    void signedOut() {
        synced = false;
        accountSeen = false;
        unsent = false;
        owned.clear();
        worn = worn.ownedBy(this::owns);
        saved();
    }

    /** The connection dropped: the account's wardrobe is asked for again when it's back. */
    void disconnected() {
        if (synced) {
            synced = false;
            revision++;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The file
    // ---------------------------------------------------------------------------------------------

    private void saved() {
        revision++;
        JsonObject json = new JsonObject();
        json.add("worn", ids(worn.ids()));
        json.add("owned", ids(List.copyOf(owned)));
        JsonArray list = new JsonArray();
        for (Outfit outfit : outfits) {
            JsonObject each = new JsonObject();
            each.addProperty("name", outfit.name());
            each.add("worn", ids(outfit.loadout().ids()));
            list.add(each);
        }
        json.add("outfits", list);
        json.addProperty("showOthers", showOthers);
        try {
            storage.write(FILE, json.toString());
        } catch (IOException e) {
            log.warn("Couldn't save the wardrobe", e);
        }
    }

    private void load() {
        try {
            String text = storage.read(FILE);
            if (text == null) {
                return;
            }
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();
            for (String id : strings(json, "owned")) {
                owned.add(id);
            }
            worn = Loadout.of(strings(json, "worn")).ownedBy(this::owns);
            if (json.get("outfits") instanceof JsonArray list) {
                for (JsonElement each : list) {
                    if (each instanceof JsonObject outfit && outfit.has("name") && outfits.size() < MAX_OUTFITS) {
                        outfits.add(new Outfit(outfit.get("name").getAsString(), Loadout.of(strings(outfit, "worn"))));
                    }
                }
            }
            if (json.has("showOthers")) {
                showOthers = json.get("showOthers").getAsBoolean();
            }
        } catch (IOException | RuntimeException e) {
            // A broken file costs the player their outfits, not their game.
            log.warn("Couldn't read the wardrobe; starting with nothing worn", e);
        }
    }

    private static JsonArray ids(List<String> ids) {
        JsonArray array = new JsonArray(ids.size());
        for (String id : ids) {
            array.add(id);
        }
        return array;
    }

    private static List<String> strings(JsonObject json, String key) {
        List<String> out = new ArrayList<>();
        if (json.get(key) instanceof JsonArray array) {
            for (JsonElement each : array) {
                if (each.isJsonPrimitive()) {
                    out.add(each.getAsString());
                }
            }
        }
        return out;
    }
}
