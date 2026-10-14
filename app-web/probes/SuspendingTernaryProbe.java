import java.util.ArrayList;
import java.util.List;

/**
 * Regression probe for tools/teavm-patches' CoroutineTransformation fix: a conditional
 * expression whose one branch suspends, left on the operand stack as an argument of a call that
 * suspends too. The two branches build different subclasses of the declared result type, the else
 * branch's being the narrower one. Before the fix, the coroutine transformation typed the restore
 * block after the conditional with the else branch's class, and the module failed to validate
 * ("type error in branch"): the shape of the game's {@code Connection.configureSerialization}
 * with Netty's pipeline. Prints "probe passed" when the handlers arrive in order.
 */
public final class SuspendingTernaryProbe {

    interface Handler {
        String id();
    }

    abstract static class Base implements Handler {}

    static class Wide extends Base {
        @Override
        public String id() {
            return "wide";
        }
    }

    static final class Narrow extends Wide {
        @Override
        public String id() {
            return "narrow";
        }
    }

    /** A handler whose constructor suspends, so the conditional that makes it does too. */
    static final class Suspending extends Base {
        private final int value;

        Suspending(int value) {
            this.value = value;
            pause();
        }

        @Override
        public String id() {
            return "suspending" + value;
        }
    }

    /** An interface call that suspends, with the conditional's value among its arguments. */
    interface Pipeline {
        Pipeline add(String name, Handler handler);

        Pipeline add(Handler... handlers);
    }

    static final class ListPipeline implements Pipeline {
        final List<String> names = new ArrayList<>();

        @Override
        public Pipeline add(String name, Handler handler) {
            pause();
            names.add(name + "=" + handler.id());
            return this;
        }

        @Override
        public Pipeline add(Handler... handlers) {
            for (Handler handler : handlers) {
                add("anonymous", handler);
            }
            return this;
        }
    }

    enum Flow {
        IN,
        OUT;

        Flow opposite() {
            return this == IN ? OUT : IN;
        }
    }

    private static final Integer INITIAL = 7;

    private SuspendingTernaryProbe() {}

    private static void pause() {
        try {
            Thread.sleep(1);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String name(boolean configure) {
        return configure ? "configured" : "unconfigured";
    }

    private static Handler framing(boolean local) {
        return local ? new Wide() : new Narrow();
    }

    static void configure(Pipeline pipeline, Flow inbound, boolean local) {
        Flow outbound = inbound.opposite();
        boolean configureInbound = inbound == Flow.OUT;
        boolean configureOutbound = outbound == Flow.OUT;
        pipeline.add("splitter", framing(local))
                .add(new Wide())
                .add(name(configureInbound), configureInbound ? new Suspending(INITIAL) : new Narrow())
                .add("prepender", framing(local))
                .add(name(configureOutbound), configureOutbound ? new Suspending(INITIAL) : new Narrow());
    }

    public static void main(String[] args) {
        ListPipeline pipeline = new ListPipeline();
        configure(pipeline, Flow.IN, false);
        configure(pipeline, Flow.OUT, true);
        String expected = "[splitter=narrow, anonymous=wide, unconfigured=narrow, prepender=narrow,"
                + " configured=suspending7, splitter=wide, anonymous=wide, configured=suspending7,"
                + " prepender=wide, unconfigured=narrow]";
        String actual = pipeline.names.toString();
        System.out.println(actual.equals(expected) ? "probe passed" : "probe failed: " + actual);
    }
}
