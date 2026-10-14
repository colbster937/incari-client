import com.viaversion.viaversion.libs.gson.Gson;
import com.viaversion.viaversion.libs.gson.reflect.TypeToken;
import java.util.Map;

/**
 * Regression probe for TypeCaptures' entry for Via's bundled Gson: Via reads its 1.12-to-
 * 1.13 block ids as {@code Map<String, String[]>} through an anonymous {@code TypeToken}, which
 * reads its type argument from its generic superclass. TeaVM keeps no generic signatures unless
 * TypeCaptures records them; without the entry, Via's 1.13 data failed to load and no server older
 * than 1.13 could be joined. Prints "probe passed" when the map's values come back as arrays.
 */
public final class ViaTypeTokenProbe {

    private ViaTypeTokenProbe() {}

    public static void main(String[] args) {
        Thread thread = new Thread(ViaTypeTokenProbe::run, "probe");
        thread.start();
    }

    private static void run() {
        try {
            Map<String, String[]> map = new Gson()
                    .fromJson("{\"stone\": [\"minecraft:stone\", \"minecraft:granite\"]}",
                            new TypeToken<Map<String, String[]>>() {}.getType());
            String[] ids = map.get("stone");
            System.out.println(ids != null && ids.length == 2 && ids[1].equals("minecraft:granite")
                    ? "probe passed"
                    : "probe failed: " + map);
        } catch (Throwable t) {
            System.out.println("probe failed: " + t);
        }
    }
}
