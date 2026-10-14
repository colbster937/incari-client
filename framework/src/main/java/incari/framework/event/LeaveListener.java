package incari.framework.event;

/** Receives {@link Events#LEAVE}. */
@FunctionalInterface
public interface LeaveListener {

    /** The player left the world. */
    void onLeave();
}
