package incari.tools.survey;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;
import org.jspecify.annotations.Nullable;

/**
 * Hides the running JDK's own class files from TeaVM. When TeaVM's class library has no
 * replacement for a {@code java.*} class, TeaVM compiles the host JDK's class instead, pulling in
 * JDK internals that can't run in a browser. Hidden, the gap shows up as a missing class at the
 * place the game uses it, where Incari can fill it deliberately.
 */
public final class HostJdkHidingLoader extends ClassLoader {

    private final Origins origins;

    public HostJdkHidingLoader(ClassLoader parent, Origins origins) {
        super(parent);
        this.origins = origins;
    }

    @Override
    public @Nullable URL getResource(String name) {
        return hidden(name) ? null : super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException {
        return hidden(name) ? Collections.emptyEnumeration() : super.getResources(name);
    }

    private boolean hidden(String name) {
        if (!name.endsWith(".class")) {
            return false;
        }
        String className = name.substring(0, name.length() - 6).replace('/', '.');
        return Origins.HOST_JDK.equals(origins.of(className));
    }
}
