package incari.classlib.substitutes.java.lang;

/**
 * {@code java.lang.Module}, which TeaVM lacks. A page is one program with no module system: every
 * class is in this one module, in the boot layer, which holds no named modules. So a check for an
 * optional JDK module ({@code jdk.jfr} for the game's profiler) finds none, as on a runtime
 * without it.
 */
public final class TModule {

    /** The module every class is in; public for {@code ClassAdditions.getModule}. */
    public static final TModule PROGRAM = new TModule();

    private TModule() {}

    /** The boot layer, which this one module stands for. */
    public TModuleLayer getLayer() {
        return TModuleLayer.boot();
    }

    @Override
    public String toString() {
        return "module (browser build)";
    }
}
