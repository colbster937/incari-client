package incari.framework.event;

/** Receives {@link Events#KILL}. */
@FunctionalInterface
public interface KillListener {

    /** An entity the player hit in the last few seconds died. */
    void onKill();
}
