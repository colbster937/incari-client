package incari.framework.event;

/** Receives {@link Events#ATTACK}. */
@FunctionalInterface
public interface AttackListener {

    /**
     * The player attacked an entity.
     *
     * @param reach how far the hit was from the player's eyes, in blocks: to the point on the
     *     target's box the crosshair met
     */
    void onAttack(double reach);
}
