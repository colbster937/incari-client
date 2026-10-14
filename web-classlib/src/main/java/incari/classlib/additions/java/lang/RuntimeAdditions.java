package incari.classlib.additions.java.lang;

import incari.classlib.InstanceMethod;
import incari.classlib.Replaces;
import incari.classlib.memory.PageHeap;

/** {@code java.lang.Runtime} methods TeaVM lacks, and its memory figures from the page's heap. */
public final class RuntimeAdditions {

    private RuntimeAdditions() {}

    /** A page has no shutdown to hook; closing the tab just stops it. The hook never runs. */
    @InstanceMethod
    public static void addShutdownHook(Runtime self, Thread hook) {}

    /** The heap's ceiling, where the browser reports it ({@link PageHeap}). */
    @InstanceMethod
    public static long maxMemory(Runtime self) {
        return PageHeap.max();
    }

    /** TeaVM reports {@link Integer#MAX_VALUE} on WebAssembly GC; the page's heap is known. */
    @InstanceMethod
    @Replaces
    public static long totalMemory(Runtime self) {
        return PageHeap.total();
    }

    /** TeaVM reports {@link Integer#MAX_VALUE} (all of it free); the page's heap is known. */
    @InstanceMethod
    @Replaces
    public static long freeMemory(Runtime self) {
        return PageHeap.free();
    }
}
