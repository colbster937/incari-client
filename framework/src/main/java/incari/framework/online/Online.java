package incari.framework.online;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The player's Incari account and the Incari players around them (ADR 0058), as features see it.
 * Accounts are optional: with none, or with the service unreachable, everything reads as nobody
 * signed in and nobody an Incari player, and the game carries on.
 *
 * <p>Game thread. Every method is a field read or a map lookup; none waits on the service.
 */
public interface Online {

    /** Where the client stands with the service. */
    enum Status {
        /** This build has no service to talk to (no URL set). */
        OFF,
        /** Opening the connection, or waiting to try again after it failed. */
        CONNECTING,
        /** Connected, and not signed in. */
        SIGNED_OUT,
        /** Connected and signed in: {@link #accountName} is the account's. */
        SIGNED_IN,
        /** The service no longer speaks this client's protocol; nothing more is tried until an update. */
        UPDATE_NEEDED
    }

    /** No service at all: what a module system without one sees. */
    Online NONE = new Online() {
        @Override
        public Status status() {
            return Status.OFF;
        }

        @Override
        public @Nullable String accountName() {
            return null;
        }

        @Override
        public @Nullable IncariPlayer player(UUID id) {
            return null;
        }

        @Override
        public long revision() {
            return 0;
        }
    };

    /** Where the client stands with the service now. */
    Status status();

    /** Whether the player is signed in to an Incari account. */
    default boolean signedIn() {
        return status() == Status.SIGNED_IN;
    }

    /**
     * The signed-in account's Incari username; while reconnecting, the one last signed in on this
     * device. Null when signed out.
     */
    @Nullable
    String accountName();

    /**
     * The player with this UUID in the current world, if the service says they're an Incari player
     * here; null for anyone else, outside a world, or while not signed in.
     */
    @Nullable
    IncariPlayer player(UUID id);

    /** A number that goes up whenever anything here changes: a screen or module compares it to refresh. */
    long revision();
}
