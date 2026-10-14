package incari.tools.webbuild;

import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ReferenceCache;
import org.teavm.parsing.ClassRefsRenamer;

/**
 * Gives {@code web-classlib}'s helpers the same view of names the substitutes have. TeaVM rewrites
 * the references inside a substitute ({@code TFileChannel} becomes {@code FileChannel}
 * everywhere in it), but not inside other classes, so a helper that names a substitute directly
 * would keep the {@code T} name and miss the renamed class. This rewrites those references in
 * every other {@code incari.classlib} class, in place.
 */
public final class ClasslibRenamer implements ClassHolderTransformer {

    private static final String PREFIX = ClasslibPolicy.PACKAGE + ".";

    private final ClassRefsRenamer renamer = new ClassRefsRenamer(new ReferenceCache(), ClasslibRenamer::original);

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        if (cls.getName().startsWith("incari.classlib.")) {
            // The name stays the same, so the renamer updates the class itself.
            renamer.rename(cls);
        }
    }

    /** {@code incari.classlib.substitutes.java.nio.channels.TFileChannel} to {@code java.nio.channels.FileChannel}. */
    static String original(String name) {
        if (!name.startsWith(PREFIX)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        if (dot < PREFIX.length() || name.charAt(dot + 1) != 'T') {
            return name;
        }
        return name.substring(PREFIX.length(), dot + 1) + name.substring(dot + 2);
    }
}
