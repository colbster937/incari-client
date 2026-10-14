package incari.framework.event;

/** Receives {@link Events#JOIN}. */
@FunctionalInterface
public interface JoinListener {

    /**
     * The player is in a world.
     *
     * @param server the server's address as the server list has it, or empty for singleplayer
     */
    void onJoin(String server);
}
