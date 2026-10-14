package incari.client.net;

import incari.client.IncariBuild;
import incari.client.online.IncariOnline;
import incari.client.ui.RoundRects;
import incari.client.ui.Theme;
import incari.online.OnlineService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.ServerList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import org.jspecify.annotations.Nullable;

/**
 * Featured servers (ADR 0062): pinned atop the server list, marked Sponsored so it's plain they're
 * there because they're featured, and kept out of the player's own list: they can't be edited,
 * deleted or moved, and they're never written to {@code servers.dat}, so they come back each time
 * the list loads. The list is built in ({@code incari.featured} in incari.properties); the online
 * service's {@code hello} can replace it for a run.
 *
 * <p>Game thread.
 */
public final class FeaturedServers {

    /** The label on each featured entry. */
    public static final String LABEL = "Sponsored";

    /**
     * Every featured entry made and still in use, told apart from the player's by identity
     * ({@code ServerData} has no {@code equals}). Never cleared: several lists live at once (the
     * multiplayer screen's, and the one {@code ServerList.saveSingleServer} loads on the IO thread
     * when a server is joined), and clearing it for one made the others' featured entries look like
     * the player's, so they were saved, one more copy each join. Weak, so old lists' entries go.
     */
    private static final Set<ServerData> PINNED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private FeaturedServers() {}

    /** A featured server: what the list calls it and where it is. */
    record Entry(String name, String address) {}

    /** The built-in list, or the service's if it named one. */
    static List<Entry> entries() {
        OnlineService online = IncariOnline.service();
        List<OnlineService.Featured> named = online.featuredServers();
        if (named != null) {
            List<Entry> entries = new ArrayList<>();
            for (OnlineService.Featured server : named) {
                entries.add(new Entry(server.name(), server.address()));
            }
            return entries;
        }
        return parse(IncariBuild.FEATURED);
    }

    /** {@code incari.featured}'s "name|address" entries, joined by ";"; malformed ones left out. */
    static List<Entry> parse(String built) {
        List<Entry> entries = new ArrayList<>();
        for (String part : built.split(";", -1)) {
            int bar = part.indexOf('|');
            if (bar > 0 && bar < part.length() - 1) {
                entries.add(new Entry(
                        part.substring(0, bar).strip(), part.substring(bar + 1).strip()));
            }
        }
        return entries;
    }

    /** Hook ({@code ServerList.load}, at its end): the featured servers, first. */
    public static void addTo(List<ServerData> servers) {
        addTo(servers, entries());
    }

    static void addTo(List<ServerData> servers, List<Entry> entries) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry entry = entries.get(i);
            ServerData data = new ServerData(entry.name(), entry.address(), ServerData.Type.OTHER);
            PINNED.add(data);
            servers.add(0, data);
        }
    }

    /**
     * The key {@link #markOwn} puts in each entry {@code servers.dat} holds: the player's own. The
     * copies of featured servers that builds before it saved don't have it.
     */
    static final String OWN = "incari_own";

    /**
     * Hook ({@code ServerList.save}, before writing): marks every entry written as the player's, so
     * that one with a featured server's name and address, added by the player, is kept on the next
     * load. Featured entries themselves are never written.
     */
    public static void markOwn(ListTag servers) {
        for (Tag each : servers) {
            if (each instanceof CompoundTag server) {
                server.putBoolean(OWN, true);
            }
        }
    }

    /**
     * Hook ({@code ServerList.load}, each saved server): whether {@code server} is a copy of a
     * featured one that an earlier build saved (after each join), left out of the list; the next
     * save drops it from {@code servers.dat}. Only entries without {@link #OWN}: one written since
     * is the player's, even with a featured server's name and address.
     */
    public static boolean isSavedCopy(CompoundTag saved, ServerData server) {
        return isSavedCopy(saved, server, entries());
    }

    static boolean isSavedCopy(CompoundTag saved, ServerData server, List<Entry> entries) {
        return !saved.getBooleanOr(OWN, false) && isSavedCopy(server, entries);
    }

    static boolean isSavedCopy(ServerData server, List<Entry> entries) {
        for (Entry entry : entries) {
            if (entry.name().equals(server.name) && entry.address().equalsIgnoreCase(server.ip)) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code server} is a featured entry: not the player's to edit, delete, move or save. */
    public static boolean is(@Nullable ServerData server) {
        return server != null && PINNED.contains(server);
    }

    /**
     * Hook ({@code ServerSelectionList.OnlineServerEntry.swap}): whether a move from {@code from} to
     * {@code to} touches a featured entry, which stays where it is.
     */
    public static boolean pinned(ServerList servers, int from, int to) {
        return from < 0
                || to < 0
                || from >= servers.size()
                || to >= servers.size()
                || is(servers.get(from))
                || is(servers.get(to));
    }

    /**
     * Hook ({@code ServerSelectionList.OnlineServerEntry.extractContent}, after the name): the
     * Sponsored label after a featured server's name, at ({@code x}, {@code y}).
     */
    public static void decorate(GuiGraphicsExtractor graphics, Font font, ServerData server, int x, int y) {
        if (!is(server)) {
            return;
        }
        int width = font.width(LABEL) + 8;
        RoundRects.fill(graphics, x, y - 1, width, 10, 3, Theme.ACCENT & 0x40FFFFFF);
        graphics.text(font, LABEL, x + 4, y, Theme.ACCENT, false);
    }
}
