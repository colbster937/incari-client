package incari.tools.webbuild;

import org.teavm.extension.spi.substitution.SimpleSubstitutionPolicy;
import org.teavm.extension.spi.substitution.SubstitutionSink;

/**
 * Lets {@code web-classlib} stand in for classes: a class
 * {@code incari.classlib.substitutes.<package>.T<Name>} replaces {@code <package>.<Name>} everywhere
 * in the browser build, the way TeaVM's own class library maps {@code java.util.Foo} to
 * {@code org.teavm.classlib.java.util.TFoo}.
 *
 * <p>TeaVM tries each policy's rule in turn and takes the first substitute that exists, so a class
 * this rule finds wins over TeaVM's. {@code web-classlib} only defines what TeaVM lacks, or
 * replaces a class TeaVM has in part (named in that class's comment).
 *
 * <p>The mapping also runs backwards over everything under the package: any top-level class there
 * whose name starts with {@code T} is taken for a substitute. Helpers go elsewhere in
 * {@code incari.classlib}.
 *
 * <p>Registered in {@code META-INF/services}; TeaVM loads it from the compile's class loader.
 */
public final class ClasslibPolicy extends SimpleSubstitutionPolicy {

    /** The package the substitutes live under. */
    public static final String PACKAGE = "incari.classlib.substitutes";

    @Override
    public void contribute(SubstitutionSink sink) {
        sink.selectClasses(name -> !name.startsWith(PACKAGE + "."))
                .packagePrefix(PACKAGE)
                .simpleNamePrefix("T");
    }
}
