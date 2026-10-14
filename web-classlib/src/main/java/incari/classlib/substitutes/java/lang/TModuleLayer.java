package incari.classlib.substitutes.java.lang;

import java.util.Optional;

/**
 * {@code java.lang.ModuleLayer}, which TeaVM lacks: the boot layer only, with no named modules in
 * it ({@link TModule}).
 */
public final class TModuleLayer {

    private static final TModuleLayer BOOT = new TModuleLayer();

    private TModuleLayer() {}

    public static TModuleLayer boot() {
        return BOOT;
    }

    /** Always empty: the browser build has no named modules to find. */
    public Optional<TModule> findModule(String name) {
        return Optional.empty();
    }

    @Override
    public String toString() {
        return "boot layer (browser build)";
    }
}
