package incari.framework.testing;

import incari.framework.game.EffectCategory;
import incari.framework.game.EffectView;
import incari.framework.game.EquipmentSlot;
import incari.framework.game.ItemView;
import incari.framework.game.PlayerView;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A player for tests: the test puts items in slots and effects on them, and moves them about. They
 * start at 0, 64, 0 in the overworld, facing north.
 */
public final class FakePlayer implements PlayerView {

    private final Map<EquipmentSlot, ItemView> items = new EnumMap<>(EquipmentSlot.class);
    private final List<EffectView> effects = new ArrayList<>();
    private double x;
    private double y = 64;
    private double z;
    private float heading;
    private String dimension = OVERWORLD;
    private boolean dead;
    private String biome = "Plains";
    private final Map<String, Integer> carried = new HashMap<>();

    /** Moves them to {@code x, y, z}. */
    public FakePlayer at(double x, double y, double z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    /** Turns them to face {@code heading}, degrees clockwise from north. */
    public FakePlayer facing(float heading) {
        this.heading = heading;
        return this;
    }

    /** Puts them in another dimension. */
    public FakePlayer in(String dimension) {
        this.dimension = dimension;
        return this;
    }

    /** Kills them, or respawns them. */
    public FakePlayer dead(boolean dead) {
        this.dead = dead;
        return this;
    }

    /** Puts them in the biome called {@code name}. */
    public FakePlayer biome(String name) {
        this.biome = name;
        return this;
    }

    /** Gives them {@code count} of the item {@code itemId} somewhere in the inventory. */
    public FakePlayer carry(String itemId, int count) {
        carried.put(itemId, count);
        return this;
    }

    /**
     * Puts the item {@code itemId} in {@code slot} (also its name on the canvas), worn {@code damage}
     * of {@code maxDamage}, with {@code cooldown} of its cooldown left.
     */
    public FakePlayer equip(EquipmentSlot slot, String itemId, int damage, int maxDamage, float cooldown) {
        items.put(slot, new Item(itemId, 1, damage, maxDamage, itemId, cooldown));
        return this;
    }

    /** Puts a damageable item, called {@code name} on the canvas, in {@code slot}. */
    public FakePlayer wear(EquipmentSlot slot, String name, int damage, int maxDamage) {
        items.put(slot, new Item(name, 1, damage, maxDamage));
        return this;
    }

    /** Puts a stack that doesn't wear out in {@code slot}. */
    public FakePlayer hold(EquipmentSlot slot, String name, int count) {
        items.put(slot, new Item(name, count, 0, 0));
        return this;
    }

    /** Empties {@code slot}. */
    public FakePlayer clear(EquipmentSlot slot) {
        items.remove(slot);
        return this;
    }

    /** Adds an effect; {@code duration} -1 for one that never runs out. */
    public FakePlayer effect(String name, int amplifier, int duration, EffectCategory category) {
        effects.add(new Effect(name, amplifier, duration, category));
        return this;
    }

    /** Removes every effect. */
    public FakePlayer clearEffects() {
        effects.clear();
        return this;
    }

    @Override
    public ItemView item(EquipmentSlot slot) {
        ItemView item = items.get(slot);
        return item == null ? NO_ITEM : item;
    }

    @Override
    public int effectCount() {
        return effects.size();
    }

    @Override
    public EffectView effect(int index) {
        return effects.get(index);
    }

    @Override
    public double x() {
        return x;
    }

    @Override
    public double y() {
        return y;
    }

    @Override
    public double z() {
        return z;
    }

    @Override
    public float heading() {
        return heading;
    }

    @Override
    public String dimension() {
        return dimension;
    }

    @Override
    public boolean isDead() {
        return dead;
    }

    @Override
    public String biome() {
        return biome;
    }

    /** What {@link #carry} gave, plus what's in the slots with that id. */
    @Override
    public int count(String itemId) {
        int count = carried.getOrDefault(itemId, 0);
        for (ItemView item : items.values()) {
            if (item.id().equals(itemId)) {
                count += item.count();
            }
        }
        return count;
    }

    /** A fake stack: its name is what the canvas records. */
    record Item(String name, int count, int damage, int maxDamage, String id, float cooldown) implements ItemView {

        Item(String name, int count, int damage, int maxDamage) {
            this(name, count, damage, maxDamage, "", 0);
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public boolean isDamageable() {
            return maxDamage > 0;
        }
    }

    /** A fake effect. */
    record Effect(String name, int amplifier, int duration, EffectCategory category) implements EffectView {

        @Override
        public boolean isInfinite() {
            return duration < 0;
        }
    }
}
