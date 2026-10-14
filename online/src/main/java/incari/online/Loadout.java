package incari.online;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;

/**
 * What a player wears (ADR 0061): at most one cosmetic in each worn slot. Read from the ids the
 * service sends, forgivingly: an id this client doesn't know, an emote, or a second cosmetic for a
 * slot is left out.
 *
 * <p>Immutable; any thread.
 */
public final class Loadout {

    /** Nothing worn. */
    public static final Loadout EMPTY = new Loadout(new EnumMap<>(Cosmetic.Slot.class));

    private final Map<Cosmetic.Slot, Cosmetic> bySlot;
    private final List<String> ids;

    private Loadout(EnumMap<Cosmetic.Slot, Cosmetic> bySlot) {
        this.bySlot = bySlot;
        List<String> list = new ArrayList<>();
        for (Cosmetic each : bySlot.values()) {
            list.add(each.id());
        }
        this.ids = List.copyOf(list);
    }

    /** The loadout these ids describe, leaving out what this client can't draw. */
    public static Loadout of(List<String> ids) {
        if (ids.isEmpty()) {
            return EMPTY;
        }
        EnumMap<Cosmetic.Slot, Cosmetic> bySlot = new EnumMap<>(Cosmetic.Slot.class);
        for (String id : ids) {
            Cosmetic cosmetic = Catalog.byId(id);
            if (cosmetic != null && cosmetic.slot().worn()) {
                bySlot.putIfAbsent(cosmetic.slot(), cosmetic);
            }
        }
        return bySlot.isEmpty() ? EMPTY : new Loadout(bySlot);
    }

    /** What's worn in {@code slot}, or null. */
    public @Nullable Cosmetic in(Cosmetic.Slot slot) {
        return bySlot.get(slot);
    }

    /** Whether {@code cosmetic} is worn. */
    public boolean wears(Cosmetic cosmetic) {
        return cosmetic.equals(bySlot.get(cosmetic.slot()));
    }

    /** This loadout with {@code cosmetic} in its slot, in place of what was there. */
    public Loadout with(Cosmetic cosmetic) {
        if (!cosmetic.slot().worn()) {
            throw new IllegalArgumentException(cosmetic.id() + " isn't worn");
        }
        EnumMap<Cosmetic.Slot, Cosmetic> next = copy();
        next.put(cosmetic.slot(), cosmetic);
        return new Loadout(next);
    }

    /** This loadout with nothing in {@code slot}. */
    public Loadout without(Cosmetic.Slot slot) {
        if (!bySlot.containsKey(slot)) {
            return this;
        }
        EnumMap<Cosmetic.Slot, Cosmetic> next = copy();
        next.remove(slot);
        return next.isEmpty() ? EMPTY : new Loadout(next);
    }

    /** This loadout with only what {@code owns} allows. */
    Loadout ownedBy(Predicate<Cosmetic> owns) {
        EnumMap<Cosmetic.Slot, Cosmetic> next = copy();
        if (!next.values().removeIf(each -> !owns.test(each))) {
            return this;
        }
        return next.isEmpty() ? EMPTY : new Loadout(next);
    }

    private EnumMap<Cosmetic.Slot, Cosmetic> copy() {
        EnumMap<Cosmetic.Slot, Cosmetic> next = new EnumMap<>(Cosmetic.Slot.class);
        next.putAll(bySlot);
        return next;
    }

    /** The ids worn, in slot order: what {@code equip} sends. */
    public List<String> ids() {
        return ids;
    }

    /** Whether nothing is worn. */
    public boolean isEmpty() {
        return ids.isEmpty();
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return other instanceof Loadout loadout && bySlot.equals(loadout.bySlot);
    }

    @Override
    public int hashCode() {
        return bySlot.hashCode();
    }

    @Override
    public String toString() {
        return "Loadout" + ids;
    }
}
