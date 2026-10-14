package incari.render.webgl2.shaders;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.resources.Identifier;

/**
 * Names one translated shader stage: which source file, which stage, compiled with which defines.
 * Two pipelines that share all three share the stage. The build tool files each translation under
 * this key and the browser looks it up the same way, so both sides must produce the same string;
 * that's why the defines are sorted rather than kept in the game's map order.
 */
public record ShaderStageKey(ShaderType type, Identifier id, String defines) {

    public static ShaderStageKey of(ShaderType type, Identifier id, ShaderDefines defines) {
        return new ShaderStageKey(type, id, canonicalDefines(defines));
    }

    /** {@code KEY=value} pairs sorted by key, then bare flags sorted, joined by {@code ;}. */
    static String canonicalDefines(ShaderDefines defines) {
        List<String> parts = new ArrayList<>();
        defines.values().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> parts.add(entry.getKey() + "=" + entry.getValue()));
        defines.flags().stream().sorted().forEach(parts::add);
        return String.join(";", parts);
    }

    /**
     * The define the game's shader compiler adds when the device's depth range is 0 to 1 (clip
     * control); the build translates a second variant of each stage whose source reads it.
     */
    public static final String DEPTH_IS_ZERO_TO_ONE = "RENDERPEARL_DEPTH_IS_ZERO_TO_ONE";

    /** The same stage compiled with one more bare define (a flag), in canonical order. */
    public ShaderStageKey withFlag(String flag) {
        List<String> values = new ArrayList<>();
        List<String> flags = new ArrayList<>();
        if (!defines.isEmpty()) {
            for (String part : defines.split(";", -1)) {
                (part.contains("=") ? values : flags).add(part);
            }
        }
        if (!flags.contains(flag)) {
            flags.add(flag);
        }
        flags.sort(null);
        values.addAll(flags);
        return new ShaderStageKey(type, id, String.join(";", values));
    }

    /** The manifest's key: {@code "<stage> <id> <defines>"}. */
    public String asString() {
        return type.getName() + " " + id + " " + defines;
    }

    @Override
    public String toString() {
        return asString();
    }
}
