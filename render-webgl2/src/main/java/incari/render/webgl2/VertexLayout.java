package incari.render.webgl2;

import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
import incari.render.webgl2.shaders.TranslatedStage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.webgl.WebGL2RenderingContext;

/**
 * Where each vertex shader input reads from: which vertex buffer slot, at what offset and stride,
 * in what format. Inputs match the vertex format's elements by name, as the game's own pipeline
 * builder matches them; an element the shader doesn't read is skipped. Pure logic, tested on a JVM.
 */
final class VertexLayout {

    /**
     * One attribute.
     *
     * @param divisor 0 to advance per vertex, otherwise per that many instances
     * @param integer whether the shader reads it as an integer ({@code vertexAttribIPointer})
     */
    record Binding(
            int slot,
            int location,
            int offset,
            int stride,
            int divisor,
            GlFormats.AttributeFormat format,
            boolean integer) {}

    /** Thrown when the shader reads an input no vertex format element provides. */
    static final class MismatchException extends Exception {
        private static final long serialVersionUID = 1L;

        MismatchException(String message) {
            super(message);
        }
    }

    /**
     * A pipeline's per-instance inputs read as rows by {@code gl_DrawID} instead of as attributes
     * ({@link DrawIdRewrite}).
     *
     * @param slot the vertex buffer slot the instance data comes from
     * @param stride bytes per instance, at most a row
     * @param inputs the inputs, where each is in a row
     * @param attributes the bindings still read as attributes (the per-vertex ones)
     */
    record DrawRowLayout(int slot, int stride, List<DrawIdRewrite.Input> inputs, List<Binding> attributes) {}

    private VertexLayout() {}

    /**
     * How {@code bindings}' per-instance inputs fit in a row, or null if there are none or they
     * don't: they must all come from one slot, in 32-bit components, within a row's 16 bytes.
     */
    static @Nullable DrawRowLayout drawRows(List<Binding> bindings, List<TranslatedStage.Attribute> inputs) {
        Map<Integer, String> names = new HashMap<>();
        for (TranslatedStage.Attribute input : inputs) {
            names.put(input.location(), input.name());
        }
        int slot = -1;
        int stride = 0;
        List<DrawIdRewrite.Input> rows = new ArrayList<>();
        List<Binding> attributes = new ArrayList<>();
        for (Binding binding : bindings) {
            if (binding.divisor() == 0) {
                attributes.add(binding);
                continue;
            }
            GlFormats.AttributeFormat format = binding.format();
            DrawIdRewrite.Kind kind = format.normalized()
                    ? null
                    : switch (format.type()) {
                        case WebGL2RenderingContext.INT -> binding.integer() ? DrawIdRewrite.Kind.INT : null;
                        case WebGL2RenderingContext.UNSIGNED_INT -> binding.integer() ? DrawIdRewrite.Kind.UINT : null;
                        case WebGL2RenderingContext.FLOAT -> binding.integer() ? null : DrawIdRewrite.Kind.FLOAT;
                        default -> null;
                    };
            String name = names.get(binding.location());
            if (kind == null
                    || name == null
                    || (slot >= 0 && binding.slot() != slot)
                    || binding.stride() > DrawIdRewrite.ROW_BYTES
                    || binding.offset() % 4 != 0
                    || binding.offset() + 4 * format.size() > DrawIdRewrite.ROW_BYTES) {
                return null;
            }
            slot = binding.slot();
            stride = binding.stride();
            rows.add(new DrawIdRewrite.Input(name, binding.offset() / 4, kind));
        }
        return rows.isEmpty() ? null : new DrawRowLayout(slot, stride, List.copyOf(rows), List.copyOf(attributes));
    }

    static List<Binding> of(List<@Nullable VertexFormat> formats, List<TranslatedStage.Attribute> inputs)
            throws MismatchException {
        Map<String, TranslatedStage.Attribute> byName = new HashMap<>();
        for (TranslatedStage.Attribute input : inputs) {
            byName.put(input.name(), input);
        }
        Map<Integer, TranslatedStage.Attribute> covered = new HashMap<>();
        List<Binding> bindings = new ArrayList<>();
        for (int slot = 0; slot < formats.size(); slot++) {
            VertexFormat format = formats.get(slot);
            if (format == null) {
                continue;
            }
            String previousName = null;
            int previousLocation = 0;
            for (VertexFormatElement element : format.getElements()) {
                TranslatedStage.Attribute input = byName.get(element.name());
                if (input == null) {
                    continue;
                }
                // An element repeating the previous one's name fills the next location of the same
                // input (a matrix, or an array of vectors), as in the game's pipeline builder.
                int location = element.name().equals(previousName) ? previousLocation + 1 : input.location();
                bindings.add(new Binding(
                        slot,
                        location,
                        element.offset(),
                        format.getVertexSize(),
                        format.getStepRate(),
                        GlFormats.attribute(element.format()),
                        input.integer()));
                covered.put(location, input);
                previousName = element.name();
                previousLocation = location;
            }
        }
        for (TranslatedStage.Attribute input : inputs) {
            if (!covered.containsKey(input.location())) {
                throw new MismatchException("vertex input " + input.name() + " has no matching vertex format element");
            }
        }
        return bindings;
    }
}
