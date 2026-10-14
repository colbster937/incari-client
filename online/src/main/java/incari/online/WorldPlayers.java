package incari.online;

import incari.framework.online.IncariPlayer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The world the player is in, as the service hears of it, and which players in its tab list are
 * Incari players (ADR 0058). Each name is looked up once as it appears, and the answer kept until
 * the player leaves; the service's {@code seen}, {@code gone} and {@code cosmetics} events keep it
 * current.
 *
 * <p>Game thread only.
 */
final class WorldPlayers {

    /** At most this many names a lookup (the service's limit). */
    static final int MAX_LOOKUP = 256;

    /** At least this long between lookups: the service allows ten a minute. */
    static final long LOOKUP_INTERVAL_MILLIS = 6_500;

    /** Where the player is: the server's hash, their name there, and the plain address. */
    record Place(String serverHash, String name, String address) {}

    private @Nullable Place place;
    /**
     * The tab list: the UUIDs under each name, and each UUID's name. One name can have several: a
     * server's NPCs often take a player's name. A name is forgotten when its last UUID goes.
     */
    private final Map<String, Set<UUID>> ids = new HashMap<>();

    private final Map<UUID, String> names = new HashMap<>();
    /** The Incari players among them, by name; and each one's name by their per-server id. */
    private final Map<String, IncariPlayer> players = new HashMap<>();

    private final Map<String, String> namesByPid = new HashMap<>();
    /** Names in the tab list not yet asked about. */
    private final Set<String> unasked = new LinkedHashSet<>();

    private long nextLookup;
    private long revision;

    /** The player joined a server through {@code address}, as {@code name}. Forgets the last world. */
    void join(String address, String name) {
        clear();
        place = new Place(ServerHash.of(address), name, address);
        revision++;
    }

    /** The player left the world (or is in singleplayer, which the service never hears of). */
    void leave() {
        clear();
        place = null;
        revision++;
    }

    private void clear() {
        ids.clear();
        names.clear();
        players.clear();
        namesByPid.clear();
        unasked.clear();
        nextLookup = 0;
    }

    @Nullable
    Place place() {
        return place;
    }

    /**
     * The tab list as it is now: names that are new are queued for a lookup, and players who left
     * are forgotten. Cheap when nothing changed.
     */
    void tabList(Map<UUID, String> now) {
        if (place == null || now.equals(names)) {
            return;
        }
        for (Map.Entry<UUID, String> entry : List.copyOf(names.entrySet())) {
            if (!entry.getValue().equals(now.get(entry.getKey()))) {
                String name = entry.getValue();
                names.remove(entry.getKey());
                Set<UUID> same = ids.get(name);
                if (same != null) {
                    UUID owner = uuidOf(name);
                    same.remove(entry.getKey());
                    if (!same.isEmpty()) {
                        // Someone else by that name is still here, so an NPC leaving takes nobody's
                        // badge. If the account's player left, look the name up again rather than
                        // give the account to whoever is left.
                        if (entry.getKey().equals(owner)) {
                            IncariPlayer gone = players.remove(name);
                            if (gone != null) {
                                namesByPid.remove(gone.pid());
                            }
                            if (!name.equals(place.name())) {
                                unasked.add(name);
                            }
                        }
                        revision++;
                        continue;
                    }
                }
                ids.remove(name);
                unasked.remove(name);
                IncariPlayer gone = players.remove(name);
                if (gone != null) {
                    namesByPid.remove(gone.pid());
                    revision++;
                }
            }
        }
        for (Map.Entry<UUID, String> entry : now.entrySet()) {
            if (!names.containsKey(entry.getKey())) {
                names.put(entry.getKey(), entry.getValue());
                Set<UUID> same = ids.computeIfAbsent(entry.getValue(), name -> new LinkedHashSet<>());
                same.add(entry.getKey());
                if (same.size() == 1 && !entry.getValue().equals(place.name())) {
                    unasked.add(entry.getValue());
                } else if (same.size() > 1) {
                    // Which of them the account is may change.
                    revision++;
                }
            }
        }
    }

    /**
     * The UUID an Incari account under {@code name} goes with: of several under one name, the first
     * that looks like a player's (version 4, online mode; version 3, offline mode), since NPCs
     * that copy a player's name usually have other versions (Citizens uses 2); else the first.
     */
    private @Nullable UUID uuidOf(String name) {
        Set<UUID> same = ids.get(name);
        if (same == null || same.isEmpty()) {
            return null;
        }
        for (UUID each : same) {
            if (each.version() == 4 || each.version() == 3) {
                return each;
            }
        }
        return same.iterator().next();
    }

    /**
     * The names to look up now, at most {@link #MAX_LOOKUP}, or an empty list if there are none or
     * the last lookup was too recent. The names are taken: if the lookup fails, {@link #askAgain}.
     */
    List<String> takeLookup(long now) {
        if (place == null || unasked.isEmpty() || now < nextLookup) {
            return List.of();
        }
        nextLookup = now + LOOKUP_INTERVAL_MILLIS;
        List<String> batch = new ArrayList<>(Math.min(MAX_LOOKUP, unasked.size()));
        var each = unasked.iterator();
        while (each.hasNext() && batch.size() < MAX_LOOKUP) {
            batch.add(each.next());
            each.remove();
        }
        return batch;
    }

    /** A lookup that didn't get through: its names (those still in the tab list) are asked again later. */
    void askAgain(List<String> batch) {
        for (String name : batch) {
            if (ids.containsKey(name) && !players.containsKey(name)) {
                unasked.add(name);
            }
        }
    }

    /** Every name in the tab list is asked about again: after a reconnect, the service forgot them. */
    void askAll() {
        if (place == null) {
            return;
        }
        for (String name : ids.keySet()) {
            if (!name.equals(place.name())) {
                unasked.add(name);
            }
        }
        if (!players.isEmpty()) {
            players.clear();
            namesByPid.clear();
            revision++;
        }
        nextLookup = 0;
    }

    /**
     * {@code name} is an Incari player here ({@code seen}, or a lookup's answer). Ignored for a name
     * not in the tab list: the answer came after they left.
     */
    void seen(String name, IncariPlayer player) {
        if (!ids.containsKey(name)) {
            return;
        }
        IncariPlayer old = players.put(name, player);
        if (old != null) {
            namesByPid.remove(old.pid());
        }
        namesByPid.put(player.pid(), name);
        revision++;
    }

    /** The account with this per-server id left, signed out, or blocked the player. */
    void gone(String pid) {
        String name = namesByPid.remove(pid);
        if (name != null) {
            players.remove(name);
            revision++;
        }
    }

    /** The account with this per-server id wears other cosmetics now. */
    void cosmetics(String pid, List<String> cosmetics) {
        String name = namesByPid.get(pid);
        IncariPlayer old = name == null ? null : players.get(name);
        if (name != null && old != null) {
            players.put(name, new IncariPlayer(pid, old.friend(), cosmetics, old.badge()));
            revision++;
        }
    }

    /** The service's {@code badge} event: an Incari player here showed or hid the badge. */
    void badge(String pid, boolean badge) {
        String name = namesByPid.get(pid);
        IncariPlayer old = name == null ? null : players.get(name);
        if (name != null && old != null && old.badge() != badge) {
            players.put(name, new IncariPlayer(pid, old.friend(), old.cosmetics(), badge));
            revision++;
        }
    }

    /** The UUID of the player whose account has this per-server id, or null. */
    @Nullable
    UUID uuid(String pid) {
        String name = namesByPid.get(pid);
        return name == null ? null : uuidOf(name);
    }

    /**
     * The Incari player with this UUID in the world, or null. Of several players under one name
     * only one is the account's ({@link #uuidOf}): an NPC by the same name shows no badge.
     */
    @Nullable
    IncariPlayer player(UUID id) {
        String name = names.get(id);
        return name == null || !id.equals(uuidOf(name)) ? null : players.get(name);
    }

    /** The Incari players in the world by name, for the blocking and reporting screen. */
    Map<String, IncariPlayer> players() {
        return Map.copyOf(players);
    }

    long revision() {
        return revision;
    }
}
