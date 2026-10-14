import io.netty.util.ReferenceCounted;
import java.lang.reflect.Method;

/**
 * Regression probe for tools/teavm-patches' CoroutineTransformation fix for non-nullable locals
 *: a method called through reflection that takes two or more arguments and suspends. TeaVM
 * generates a caller for it that keeps the argument array's data in a non-nullable local, and the
 * coroutine transformation's save and restore code read that local where WebAssembly doesn't count
 * it as set: the module failed to validate ("uninitialized local").
 *
 * <p>The method is the one that showed it, Netty's {@code ReferenceCountUtil.touch(Object, Object)},
 * which the browser build's reflection policy (tools/webbuild ReflectionDeclarations) already
 * declares. It calls {@link ReferenceCounted#touch(Object)}, which suspends here, as it can in the
 * game's build, so its reflection caller is a coroutine. Prints "probe passed" when the call
 * reaches the object and returns it.
 */
public final class SuspendingReflectionProbe {

    /** A reference-counted object whose {@code touch} suspends. */
    static final class Touched implements ReferenceCounted {
        String hint = "";

        @Override
        public ReferenceCounted touch(Object hint) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            this.hint = String.valueOf(hint);
            return this;
        }

        @Override
        public ReferenceCounted touch() {
            return touch(null);
        }

        @Override
        public int refCnt() {
            return 1;
        }

        @Override
        public ReferenceCounted retain() {
            return this;
        }

        @Override
        public ReferenceCounted retain(int increment) {
            return this;
        }

        @Override
        public boolean release() {
            return false;
        }

        @Override
        public boolean release(int decrement) {
            return false;
        }
    }

    private SuspendingReflectionProbe() {}

    public static void main(String[] args) throws ReflectiveOperationException {
        Method touch = Class.forName("io.netty.util.ReferenceCountUtil").getMethod("touch", Object.class, Object.class);
        Touched message = new Touched();
        Object result = touch.invoke(null, message, "hint");
        boolean passed = result == message && message.hint.equals("hint");
        System.out.println(passed ? "probe passed" : "probe failed: " + result + ", hint " + message.hint);
    }
}
