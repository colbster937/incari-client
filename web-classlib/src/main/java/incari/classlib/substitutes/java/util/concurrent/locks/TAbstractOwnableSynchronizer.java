package incari.classlib.substitutes.java.util.concurrent.locks;

import java.io.Serializable;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.concurrent.locks.AbstractOwnableSynchronizer}: records which thread owns a
 * synchronizer exclusively. Guava's {@code InterruptibleTask.Blocker} extends it. The owner is
 * transient, like the JDK's: a deserialized synchronizer has none.
 */
public abstract class TAbstractOwnableSynchronizer implements Serializable {

    private static final long serialVersionUID = 1L;

    private transient @Nullable Thread exclusiveOwnerThread;

    protected TAbstractOwnableSynchronizer() {}

    protected final void setExclusiveOwnerThread(@Nullable Thread thread) {
        exclusiveOwnerThread = thread;
    }

    protected final @Nullable Thread getExclusiveOwnerThread() {
        return exclusiveOwnerThread;
    }
}
