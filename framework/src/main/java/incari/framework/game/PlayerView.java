package incari.framework.game;

/**
 * The local player as modules see it: where they are and which way they face, what they wear and
 * hold, and their effects. Reused by the client and refreshed each time {@link Game#player()} hands
 * it out; what it returns is reused too. On the render thread the position and heading are where
 * the player is drawn this frame (between ticks), so what follows them moves smoothly.
 */
public interface PlayerView {

    /** A player with nothing: no items, no effects. */
    PlayerView EMPTY = new PlayerView() {
        @Override
        public ItemView item(EquipmentSlot slot) {
            return NO_ITEM;
        }

        @Override
        public int effectCount() {
            return 0;
        }

        @Override
        public EffectView effect(int index) {
            throw new IndexOutOfBoundsException(index);
        }

        @Override
        public double x() {
            return 0;
        }

        @Override
        public double y() {
            return 0;
        }

        @Override
        public double z() {
            return 0;
        }

        @Override
        public float heading() {
            return 0;
        }

        @Override
        public String dimension() {
            return OVERWORLD;
        }

        @Override
        public boolean isDead() {
            return false;
        }
    };

    /** The overworld's {@link #dimension()}. */
    String OVERWORLD = "minecraft:overworld";

    /** The Nether's. */
    String NETHER = "minecraft:the_nether";

    /** The End's. */
    String END = "minecraft:the_end";

    /** An empty stack. */
    ItemView NO_ITEM = new ItemView() {
        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public int count() {
            return 0;
        }

        @Override
        public boolean isDamageable() {
            return false;
        }

        @Override
        public int damage() {
            return 0;
        }

        @Override
        public int maxDamage() {
            return 0;
        }
    };

    /** What's in {@code slot}; {@link ItemView#isEmpty()} if nothing. */
    ItemView item(EquipmentSlot slot);

    /** How many effects show, as the game's HUD would show them (hidden ones left out). */
    int effectCount();

    /** The {@code index}th effect, the game's HUD order (longest-lasting first). */
    EffectView effect(int index);

    /** Where the player's feet are: x, toward the east. */
    double x();

    /** Their feet's height. */
    double y();

    /** z, toward the south. */
    double z();

    /** The compass bearing they face, degrees clockwise from north: 0 north, 90 east, 180 south. */
    float heading();

    /** The dimension they're in, by the game's id ({@link #OVERWORLD}, {@link #NETHER}, {@link #END}). */
    String dimension();

    /** Whether they've died and not yet respawned. */
    boolean isDead();

    /** The biome at their feet, by name in the player's language ("Plains"); empty if unknown. */
    default String biome() {
        return "";
    }

    /**
     * How many of the item with this id ({@code minecraft:totem_of_undying}) they carry: the
     * inventory, both hands and the armor.
     */
    default int count(String itemId) {
        return 0;
    }
}
