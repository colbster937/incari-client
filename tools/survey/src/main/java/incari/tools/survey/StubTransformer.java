package incari.tools.survey;

import java.util.Map;
import java.util.Set;
import org.teavm.model.AccessLevel;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ElementModifier;
import org.teavm.model.FieldHolder;
import org.teavm.model.MethodDescriptor;
import org.teavm.model.MethodHolder;
import org.teavm.model.ValueType;
import org.teavm.model.emit.ProgramEmitter;

/**
 * Adds stub members to existing classes as TeaVM loads them (see {@link Stubs}).
 *
 * <p>TeaVM instantiates transformers by class name with a no-argument constructor, so the
 * current round's members are handed over through a static field. The survey runs one
 * compile at a time, so there's never more than one set in play.
 */
public final class StubTransformer implements ClassHolderTransformer {

    static volatile Map<String, Set<Stubs.ExtraMember>> members = Map.of();

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        Set<Stubs.ExtraMember> extra = members.get(cls.getName());
        if (extra == null) {
            return;
        }
        for (Stubs.ExtraMember member : extra) {
            if (member.method()) {
                MethodDescriptor descriptor = MethodDescriptor.parse(member.nameAndDesc());
                if (cls.getMethod(descriptor) != null) {
                    continue;
                }
                MethodHolder method = new MethodHolder(descriptor);
                method.setLevel(AccessLevel.PUBLIC);
                if (member.isStatic()) {
                    method.getModifiers().add(ElementModifier.STATIC);
                }
                ProgramEmitter pe = ProgramEmitter.create(method, context.getHierarchy());
                if (descriptor.getResultType() == ValueType.VOID) {
                    pe.exit();
                } else {
                    pe.defaultValue(descriptor.getResultType()).returnValue();
                }
                cls.addMethod(method);
            } else {
                String[] parts = member.nameAndDesc().split(":", 2);
                if (cls.getField(parts[0]) != null) {
                    continue;
                }
                FieldHolder field = new FieldHolder(parts[0]);
                field.setType(ValueType.parse(parts[1]));
                field.setLevel(AccessLevel.PUBLIC);
                if (member.isStatic()) {
                    field.getModifiers().add(ElementModifier.STATIC);
                }
                cls.addField(field);
            }
        }
    }
}
