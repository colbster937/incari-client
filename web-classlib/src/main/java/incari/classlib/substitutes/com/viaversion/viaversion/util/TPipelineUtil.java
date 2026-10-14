package incari.classlib.substitutes.com.viaversion.viaversion.util;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Via's {@code PipelineUtil} for the browser build (ADR 0017). Via's own looks up methods of
 * Netty's codecs reflectively when the class loads, for server platforms that wrap other codecs;
 * the browser can't reflect on methods, so the class failed to load, and with it the cause checks
 * Via runs whenever a packet it drops on purpose comes through an error path. This one has the
 * same methods, written for the build: the checks and pipeline lookups work, and calling another
 * codec, which the client never asks for, says so.
 */
@SuppressWarnings({"rawtypes", "unused"}) // Via's signatures take raw codec types
public final class TPipelineUtil {

    private TPipelineUtil() {}

    public static List<Object> callDecode(ByteToMessageDecoder decoder, ChannelHandlerContext ctx, Object input) {
        throw unsupported();
    }

    public static void callEncode(MessageToByteEncoder encoder, ChannelHandlerContext ctx, Object msg, ByteBuf output) {
        throw unsupported();
    }

    public static List<Object> callDecode(MessageToMessageDecoder decoder, ChannelHandlerContext ctx, Object msg) {
        throw unsupported();
    }

    /** Whether {@code failure} or one of its causes is a {@code type}. */
    public static boolean containsCause(Throwable failure, Class<?> type) {
        return getCause(failure, type) != null;
    }

    /** The first of {@code failure} and its causes that is a {@code type}, or null. */
    @SuppressWarnings("unchecked")
    public static <T> @Nullable T getCause(@Nullable Throwable failure, Class<T> type) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return (T) cause;
            }
        }
        return null;
    }

    /** The context of the handler that comes after the one named {@code name} (any case). */
    public static @Nullable ChannelHandlerContext getContextBefore(String name, ChannelPipeline pipeline) {
        List<String> names = pipeline.names();
        for (int i = 0; i < names.size() - 1; i++) {
            if (names.get(i).equalsIgnoreCase(name)) {
                return pipeline.context(names.get(i + 1));
            }
        }
        return null;
    }

    /** The context of the handler that comes before the one named {@code name}. */
    public static @Nullable ChannelHandlerContext getPreviousContext(String name, ChannelPipeline pipeline) {
        String before = null;
        for (String current : pipeline.toMap().keySet()) {
            if (current.equals(name)) {
                return before == null ? null : pipeline.context(before);
            }
            before = current;
        }
        return null;
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Calling another Netty codec isn't part of the browser build");
    }
}
