package incari.framework.event;

/** Receives {@link Events#TURN}. */
@FunctionalInterface
public interface TurnListener {

    /** The mouse moved over the world. */
    void onTurn(Turn turn);
}
