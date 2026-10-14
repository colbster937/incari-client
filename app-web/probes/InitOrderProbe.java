import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Regression probe for tools/teavm-patches' ClassInitializerInsertionTransformer fix: a
 * class's static initializer runs after its superclass's (JLS 12.4.2). TeaVM ran a subclass's first
 * when the first use was {@code new Sub()}. The game numbers entity data by class this way
 * ({@code SynchedEntityData.defineId} counts on from the superclass's last id), so creating the
 * player on joining a server failed with "Duplicate id value for 0!". Prints "probe passed" when
 * ids count up from the root class, as on the JVM.
 */
public final class InitOrderProbe {

    static final List<String> ORDER = new ArrayList<>();
    static final Map<Class<?>, Integer> LAST_IDS = new HashMap<>();

    private InitOrderProbe() {}

    /** The next id for {@code type}, after its own or its nearest superclass's last one. */
    static int define(Class<?> type) {
        Integer last = LAST_IDS.get(type);
        Class<?> current = type;
        while (last == null && (current = current.getSuperclass()) != Object.class) {
            last = LAST_IDS.get(current);
        }
        int id = last == null ? 0 : last + 1;
        LAST_IDS.put(type, id);
        ORDER.add(type.getSimpleName() + "=" + id);
        return id;
    }

    static class Root {
        static final int FIRST = define(Root.class);
        static final int SECOND = define(Root.class);
    }

    static class Middle extends Root {
        static final int THIRD = define(Middle.class);
    }

    /** No initializer of its own between two classes that have one. */
    static class Plain extends Middle {}

    static final class Leaf extends Plain {
        static final int FOURTH = define(Leaf.class);
    }

    public static void main(String[] args) {
        new Leaf();
        String expected = "[Root=0, Root=1, Middle=2, Leaf=3]";
        String actual = ORDER.toString();
        System.out.println(actual.equals(expected) ? "probe passed" : "probe failed: " + actual);
    }
}
