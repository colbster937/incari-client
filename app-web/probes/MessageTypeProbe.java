import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Regression probe for tools/webbuild's TypeCaptures: a Netty encoder decides which
 * messages it takes from its superclass's type argument, read by reflection. The browser build
 * kept no generic superclasses for Netty's handlers, so every encoder took every message: the
 * game's {@code PacketEncoder} ({@code MessageToByteEncoder<Packet<T>>}) was handed its pipeline's
 * configuration messages and failed with a ClassCastException. Prints "probe passed" when an
 * encoder of the same shape takes its own messages and passes others by.
 */
public final class MessageTypeProbe {

    /** A generic message, like the game's {@code Packet<T>}. */
    interface Box<T> {}

    static final class StringBox implements Box<String> {}

    static final class BoxEncoder<T> extends MessageToByteEncoder<Box<T>> {
        @Override
        protected void encode(ChannelHandlerContext ctx, Box<T> message, ByteBuf out) {}
    }

    private MessageTypeProbe() {}

    public static void main(String[] args) throws Exception {
        BoxEncoder<String> encoder = new BoxEncoder<>();
        boolean takesBox = encoder.acceptOutboundMessage(new StringBox());
        boolean takesOther = encoder.acceptOutboundMessage("not a box");
        System.out.println(takesBox && !takesOther ? "probe passed" : "probe failed: " + takesBox + ", " + takesOther);
    }
}
