package incari.framework.online;

import java.util.List;

/**
 * A player in the world who is signed in to an Incari account, as the service describes them
 * (ADR 0058). The service never says which account it is: only an id for this server.
 *
 * @param pid the account's id on this server only: the same account has another on another server
 * @param friend whether they're the player's friend
 * @param cosmetics the ids of the cosmetics they wear, for the cosmetics ADR to draw
 * @param badge whether they let others see they use Incari: Incari's badge beside their name (ADR
 *     0062)
 */
public record IncariPlayer(String pid, boolean friend, List<String> cosmetics, boolean badge) {

    public IncariPlayer {
        cosmetics = List.copyOf(cosmetics);
    }

    /** One who shows the badge, as every account did before ADR 0062. */
    public IncariPlayer(String pid, boolean friend, List<String> cosmetics) {
        this(pid, friend, cosmetics, true);
    }
}
