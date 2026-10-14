package incari.online;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Friends, friend requests and conversations (ADR 0059), as the client knows them: the chat menu
 * draws this, and {@link OnlineService} keeps it current from the service's answers and events.
 *
 * <p>Conversations with friends are kept by the service and loaded a page at a time. Those with
 * other Incari players on the same server live only here and go when the player leaves, since a
 * player's {@code pid} means nothing on another server.
 *
 * <p>Game thread only.
 */
public final class Social {

    /** At most this many messages a conversation keeps in memory; older ones load again on demand. */
    static final int MAX_KEPT = 300;

    /** Who may message the player. */
    public enum MessagesFrom {
        /** Friends, and Incari players on the same server. */
        EVERYONE("everyone", "Friends and players on your server"),
        /** Friends only: the default. */
        FRIENDS("friends", "Friends only"),
        /** Nobody: messaging is off. */
        NOBODY("nobody", "Nobody (off)");

        final String wire;
        private final String label;

        MessagesFrom(String wire, String label) {
            this.wire = wire;
            this.label = label;
        }

        /** What the settings call it. */
        public String label() {
            return label;
        }

        static MessagesFrom of(String wire) {
            for (MessagesFrom each : values()) {
                if (each.wire.equals(wire)) {
                    return each;
                }
            }
            return FRIENDS;
        }
    }

    /**
     * A friend.
     *
     * @param fid the service's id for the friendship
     * @param username their Incari username: friends know each other's
     * @param online whether they're connected to the service now
     * @param server the server they're on, if they share it with friends and are on one
     */
    public record Friend(
            String fid,
            String username,
            boolean online,
            @Nullable String server) {}

    /**
     * A friend request, either way.
     *
     * @param label the sender's username for one received; for one sent, the name it was sent to
     */
    public record Request(String rid, boolean incoming, String label) {}

    /** Where a message is: sent, still going, or turned away. */
    public enum Delivery {
        SENT,
        SENDING,
        /** The service wouldn't deliver it: they don't take messages from the player, or it was too fast. */
        FAILED
    }

    /** One message in a conversation. */
    public record Message(String mid, boolean mine, String text, long at, Delivery delivery) {}

    /**
     * Who a conversation is with: a friend (by {@code fid}) or an Incari player on this server (by
     * {@code pid}). {@link #key} names the conversation.
     */
    public record Peer(boolean friend, String id, String label) {
        /** The conversation's name: the same for one person whatever their label. */
        public String key() {
            return (friend ? "f:" : "p:") + id;
        }
    }

    /** One conversation's messages, oldest first. */
    public static final class Conversation {
        private final List<Message> messages = new ArrayList<>();
        private int unread;
        /** Whether the service has older messages than the first here (friends only). */
        private boolean more = true;

        private boolean loading;
        private boolean loaded;
        /**
         * Whether messages may have come while the client was disconnected: if so, the newest page
         * is reloaded before anything is marked read.
         */
        private boolean stale;

        public List<Message> messages() {
            return messages;
        }

        public int unread() {
            return unread;
        }

        /** Whether older messages can be loaded. */
        public boolean hasMore() {
            return more;
        }

        /** Whether a page of older messages is on its way. */
        public boolean loading() {
            return loading;
        }

        boolean loaded() {
            return loaded;
        }

        void startLoading() {
            loading = true;
        }

        /** Older messages arrived, oldest first. */
        void older(List<Message> page, boolean hasMore) {
            loading = false;
            loaded = true;
            more = hasMore;
            List<Message> merged = new ArrayList<>(page.size() + messages.size());
            for (Message each : page) {
                if (find(each.mid()) < 0) {
                    merged.add(each);
                }
            }
            merged.addAll(messages);
            messages.clear();
            messages.addAll(merged);
        }

        boolean stale() {
            return stale;
        }

        /**
         * The newest page arrived again, oldest first, after a reconnect. Joined to what's held if
         * they meet; if they don't (more came meanwhile than a page), the page replaces them, and
         * scrolling up loads the rest again. The player's own unsent messages stay either way.
         */
        void newest(List<Message> page, boolean hasMore) {
            loading = false;
            loaded = true;
            stale = false;
            boolean meets = messages.isEmpty();
            for (Message each : page) {
                if (find(each.mid()) >= 0) {
                    meets = true;
                    break;
                }
            }
            List<Message> merged = new ArrayList<>(page.size() + messages.size());
            if (meets) {
                if (messages.isEmpty()) {
                    more = hasMore;
                }
                merged.addAll(messages);
                for (Message each : page) {
                    if (find(each.mid()) < 0) {
                        merged.add(each);
                    }
                }
            } else {
                more = hasMore;
                merged.addAll(page);
                for (Message each : messages) {
                    if (each.delivery() != Delivery.SENT) {
                        merged.add(each);
                    }
                }
            }
            merged.sort((a, b) -> Long.compare(a.at(), b.at()));
            while (merged.size() > MAX_KEPT) {
                merged.remove(0);
                more = true;
            }
            messages.clear();
            messages.addAll(merged);
        }

        void loadFailed() {
            loading = false;
        }

        /** A new message at the end; drops the oldest past {@link #MAX_KEPT}. */
        void add(Message message) {
            if (find(message.mid()) >= 0) {
                return;
            }
            messages.add(message);
            if (messages.size() > MAX_KEPT) {
                messages.remove(0);
                more = true;
            }
        }

        void replace(String mid, Message message) {
            int at = find(mid);
            if (at >= 0) {
                messages.set(at, message);
            }
        }

        int find(String mid) {
            for (int i = messages.size() - 1; i >= 0; i--) {
                if (messages.get(i).mid().equals(mid)) {
                    return i;
                }
            }
            return -1;
        }

        void unread(int count) {
            unread = count;
        }
    }

    /**
     * Something to tell the player about while the chat menu is closed: a toast.
     *
     * @param who whose it is, for the picture
     * @param request whether it's about friends (a request, a new friend) rather than a message
     */
    public record Notice(String who, boolean request, String title, String text) {}

    private @Nullable Boolean available;
    private boolean loaded;
    private final Map<String, Friend> friends = new LinkedHashMap<>();
    private final Map<String, Request> requests = new LinkedHashMap<>();
    private final Map<String, Conversation> conversations = new LinkedHashMap<>();
    /** The names of the Incari players on this server whose conversations are here, by pid. */
    private final Map<String, String> playerNames = new LinkedHashMap<>();
    /** The pid each request sent to a player on this server went to, by rid. */
    private final Map<String, String> sentToPid = new HashMap<>();

    private final List<Notice> notices = new ArrayList<>();
    private MessagesFrom messagesFrom = MessagesFrom.FRIENDS;
    private boolean friendRequests = true;
    private long revision;

    // ---------------------------------------------------------------------------------------------
    // What the chat menu reads
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the service has friends and messages: null until it answers, false for a service that
     * doesn't speak them yet.
     */
    public @Nullable Boolean available() {
        return available;
    }

    /** Whether the friend list has arrived since signing in. */
    public boolean loaded() {
        return loaded;
    }

    /** Every friend: online ones first, then by name. */
    public List<Friend> friends() {
        List<Friend> list = new ArrayList<>(friends.values());
        list.sort((a, b) -> a.online() != b.online()
                ? (a.online() ? -1 : 1)
                : String.CASE_INSENSITIVE_ORDER.compare(a.username(), b.username()));
        return list;
    }

    public @Nullable Friend friend(String fid) {
        return friends.get(fid);
    }

    /** Friend requests: received ones first, newest last within each. */
    public List<Request> requests() {
        List<Request> list = new ArrayList<>();
        for (Request each : requests.values()) {
            if (each.incoming()) {
                list.add(each);
            }
        }
        for (Request each : requests.values()) {
            if (!each.incoming()) {
                list.add(each);
            }
        }
        return list;
    }

    /**
     * The friend request the player sent to {@code peer}, an Incari player on this server, still
     * waiting; null if there's none. Never a request received: its label is the sender's Incari
     * username, which says nothing about who they are here. Known by the player's {@code pid} when
     * it was sent from here; otherwise (sent before a reconnect, or elsewhere) by the name it was
     * sent to.
     */
    public @Nullable Request sentTo(Peer peer) {
        if (peer.friend()) {
            return null;
        }
        for (Request each : requests.values()) {
            if (!each.incoming() && peer.id().equals(sentToPid.get(each.rid()))) {
                return each;
            }
        }
        for (Request each : requests.values()) {
            if (!each.incoming()
                    && !sentToPid.containsKey(each.rid())
                    && each.label().equalsIgnoreCase(peer.label())) {
                return each;
            }
        }
        return null;
    }

    /** The conversation with {@code peer}, made empty if there's none yet. */
    public Conversation conversation(Peer peer) {
        return conversations.computeIfAbsent(peer.key(), key -> new Conversation());
    }

    /** The conversation with {@code peer}, or null if nothing was said yet. */
    public @Nullable Conversation existing(Peer peer) {
        return conversations.get(peer.key());
    }

    /**
     * Incari players on this server who messaged the player, by pid, with the names they had: they
     * stay in the list after leaving, until the player does.
     */
    public Map<String, String> playersMessaged() {
        return Map.copyOf(playerNames);
    }

    /** Unread messages across every conversation. */
    public int unread() {
        int total = 0;
        for (Conversation each : conversations.values()) {
            total += each.unread;
        }
        return total;
    }

    /** Received friend requests waiting for an answer. */
    public int pendingRequests() {
        int count = 0;
        for (Request each : requests.values()) {
            count += each.incoming() ? 1 : 0;
        }
        return count;
    }

    public MessagesFrom messagesFrom() {
        return messagesFrom;
    }

    /** Whether others can send the player friend requests. */
    public boolean friendRequests() {
        return friendRequests;
    }

    /** What happened since the last call, to show as toasts; taken. */
    public List<Notice> takeNotices() {
        if (notices.isEmpty()) {
            return List.of();
        }
        List<Notice> taken = List.copyOf(notices);
        notices.clear();
        return taken;
    }

    /** A number that goes up whenever anything here changes. */
    public long revision() {
        return revision;
    }

    // ---------------------------------------------------------------------------------------------
    // What the service changes
    // ---------------------------------------------------------------------------------------------

    void settings(MessagesFrom from, boolean requests) {
        messagesFrom = from;
        friendRequests = requests;
        revision++;
    }

    void available(boolean yes) {
        available = yes;
        revision++;
    }

    /**
     * The full list, after signing in (and after each reconnect): replaces what was here. The
     * friends' conversations still held may have missed messages meanwhile: they're marked stale,
     * and their newest page loads again when next shown. Their unread counts are the service's.
     */
    void loaded(List<Friend> friendList, List<Request> requestList, Map<String, Integer> unread) {
        loaded = true;
        friends.clear();
        for (Friend each : friendList) {
            friends.put(each.fid(), each);
        }
        requests.clear();
        for (Request each : requestList) {
            requests.put(each.rid(), each);
        }
        sentToPid.keySet().retainAll(requests.keySet());
        for (var each = conversations.entrySet().iterator(); each.hasNext(); ) {
            Map.Entry<String, Conversation> entry = each.next();
            String key = entry.getKey();
            if (!key.startsWith("f:")) {
                continue;
            }
            if (!friends.containsKey(key.substring(2))) {
                // Unfriended while the client wasn't connected: the conversation went with it.
                each.remove();
                continue;
            }
            entry.getValue().stale = true;
            entry.getValue().unread(0);
        }
        for (Map.Entry<String, Integer> each : unread.entrySet()) {
            conversation(new Peer(true, each.getKey(), "")).unread(each.getValue());
        }
        revision++;
    }

    void friend(Friend friend, boolean announce) {
        Friend old = friends.put(friend.fid(), friend);
        if (old == null && announce) {
            notices.add(new Notice(friend.username(), true, "New friend", friend.username() + " is now your friend."));
        }
        revision++;
    }

    void unfriended(String fid) {
        if (friends.remove(fid) != null) {
            conversations.remove("f:" + fid);
            revision++;
        }
    }

    void request(Request request) {
        if (requests.put(request.rid(), request) == null && request.incoming()) {
            notices.add(new Notice(request.label(), true, request.label(), "Sent you a friend request"));
        }
        revision++;
    }

    /** The request {@code rid} was sent to the Incari player here with this per-server id. */
    void requestSentTo(String rid, String pid) {
        sentToPid.put(rid, pid);
        revision++;
    }

    void requestGone(String rid) {
        sentToPid.remove(rid);
        if (requests.remove(rid) != null) {
            revision++;
        }
    }

    /** A message arrived, or one of the player's was sent. */
    void message(Peer peer, Message message, boolean viewing) {
        Conversation conversation = conversation(peer);
        conversation.add(message);
        if (!peer.friend()) {
            playerNames.put(peer.id(), peer.label());
        }
        if (!message.mine() && !viewing) {
            conversation.unread(conversation.unread + 1);
            notices.add(new Notice(peer.label(), false, peer.label(), message.text()));
        }
        revision++;
    }

    void replace(Peer peer, String mid, Message message) {
        conversation(peer).replace(mid, message);
        revision++;
    }

    void older(Peer peer, List<Message> page, boolean more) {
        conversation(peer).older(page, more);
        revision++;
    }

    void newest(Peer peer, List<Message> page, boolean more) {
        conversation(peer).newest(page, more);
        revision++;
    }

    void loadFailed(Peer peer) {
        conversation(peer).loadFailed();
        revision++;
    }

    void read(Peer peer) {
        Conversation conversation = conversations.get(peer.key());
        if (conversation != null && conversation.unread > 0) {
            conversation.unread(0);
            revision++;
        }
    }

    /** The player left the server: conversations with its players go, their ids meaning nothing now. */
    void leftServer() {
        if (playerNames.isEmpty()) {
            return;
        }
        for (String pid : playerNames.keySet()) {
            conversations.remove("p:" + pid);
        }
        playerNames.clear();
        revision++;
    }

    /** Signed out: everything goes. */
    void clear() {
        available = null;
        loaded = false;
        friends.clear();
        requests.clear();
        conversations.clear();
        playerNames.clear();
        sentToPid.clear();
        notices.clear();
        messagesFrom = MessagesFrom.FRIENDS;
        friendRequests = true;
        revision++;
    }
}
