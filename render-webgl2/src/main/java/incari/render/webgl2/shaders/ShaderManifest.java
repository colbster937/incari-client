package incari.render.webgl2.shaders;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * Every shader stage the game can ask for, translated when the browser build is made
 * ({@code tools/shaders}) and fetched by the page as {@code shaders.json}. It holds translated
 * Mojang shaders, so like every build output it is never committed or published.
 *
 * <p>Read and written as Gson trees, not through Gson's reflective binding, which the browser build
 * doesn't support.
 */
public final class ShaderManifest {

    /** Bumped when the layout changes, so a stale file fails clearly instead of half-working. */
    public static final int FORMAT = 1;

    private final String minecraftVersion;
    private final Map<String, TranslatedStage> stages;

    public ShaderManifest(String minecraftVersion, Collection<TranslatedStage> stages) {
        this.minecraftVersion = minecraftVersion;
        // Sorted, so the file is the same from one build to the next.
        Map<String, TranslatedStage> byKey = new TreeMap<>();
        for (TranslatedStage stage : stages) {
            byKey.put(stage.key(), stage);
        }
        this.stages = byKey;
    }

    public String minecraftVersion() {
        return minecraftVersion;
    }

    public @Nullable TranslatedStage stage(ShaderStageKey key) {
        return stages.get(key.asString());
    }

    public Collection<TranslatedStage> stages() {
        return stages.values();
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("format", FORMAT);
        root.addProperty("minecraft", minecraftVersion);
        JsonObject all = new JsonObject();
        for (TranslatedStage stage : stages.values()) {
            JsonObject json = new JsonObject();
            json.addProperty("sourceHash", stage.sourceHash());
            String unsupported = stage.unsupported();
            if (unsupported != null) {
                json.addProperty("unsupported", unsupported);
            } else {
                json.addProperty("glsl", stage.glsl());
                JsonArray attributes = new JsonArray();
                for (TranslatedStage.Attribute attribute : stage.attributes()) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("name", attribute.name());
                    entry.addProperty("location", attribute.location());
                    entry.addProperty("integer", attribute.integer());
                    attributes.add(entry);
                }
                json.add("attributes", attributes);
                json.add("uniformBlocks", strings(stage.uniformBlocks()));
                json.add("samplers", strings(stage.samplers()));
            }
            all.add(stage.key(), json);
        }
        root.add("stages", all);
        return root.toString();
    }

    public static ShaderManifest parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        int format = root.get("format").getAsInt();
        if (format != FORMAT) {
            throw new IllegalArgumentException(
                    "shaders.json is format " + format + ", this build reads format " + FORMAT + "; rebuild it");
        }
        List<TranslatedStage> stages = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry :
                root.getAsJsonObject("stages").entrySet()) {
            JsonObject json = entry.getValue().getAsJsonObject();
            String sourceHash = json.get("sourceHash").getAsString();
            JsonElement unsupported = json.get("unsupported");
            if (unsupported != null) {
                stages.add(TranslatedStage.unsupported(entry.getKey(), sourceHash, unsupported.getAsString()));
                continue;
            }
            List<TranslatedStage.Attribute> attributes = new ArrayList<>();
            for (JsonElement element : json.getAsJsonArray("attributes")) {
                JsonObject attribute = element.getAsJsonObject();
                attributes.add(new TranslatedStage.Attribute(
                        attribute.get("name").getAsString(),
                        attribute.get("location").getAsInt(),
                        attribute.get("integer").getAsBoolean()));
            }
            stages.add(new TranslatedStage(
                    entry.getKey(),
                    sourceHash,
                    json.get("glsl").getAsString(),
                    attributes,
                    strings(json.getAsJsonArray("uniformBlocks")),
                    strings(json.getAsJsonArray("samplers")),
                    null));
        }
        return new ShaderManifest(root.get("minecraft").getAsString(), stages);
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> strings(JsonArray array) {
        List<String> values = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            values.add(element.getAsString());
        }
        return values;
    }
}
