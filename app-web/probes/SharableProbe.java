import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerAdapter;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Regression probe for tools/webbuild's RemovalTransformer: Netty's {@code @Sharable} check
 * reads the annotation at run time, through a class TeaVM generates for it
 * ({@code ChannelHandler$Sharable$$_impl}). The removal step took that class for a missing one,
 * since it doesn't exist until after transformers run, and replaced it with a throwing reference
 * under the catch-all Netty rule: adding any handler to a pipeline threw. Prints "probe passed"
 * when a sharable handler reads as sharable and another doesn't.
 */
public final class SharableProbe {

    @ChannelHandler.Sharable
    static final class Shared extends ChannelInboundHandlerAdapter {}

    static final class NotShared extends ChannelInboundHandlerAdapter {}

    private SharableProbe() {}

    public static void main(String[] args) {
        ChannelHandlerAdapter shared = new Shared();
        ChannelHandlerAdapter notShared = new NotShared();
        boolean passed = shared.isSharable() && !notShared.isSharable();
        System.out.println(passed ? "probe passed" : "probe failed: " + shared.isSharable() + ", " + notShared.isSharable());
    }
}
