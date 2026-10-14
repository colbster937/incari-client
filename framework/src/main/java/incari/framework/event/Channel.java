package incari.framework.event;

/**
 * One kind of event and the listener interface that receives it. The channels are the constants in
 * {@link Events}; a module subscribes to them in its constructor ({@code Module.on}).
 *
 * @param <L> the listener interface
 */
// L is only read by the compiler: it ties a channel to its listener type in Module.on and EventBus.add.
@SuppressWarnings("UnusedTypeParameter")
public final class Channel<L> {

    final int index;
    private final String name;

    Channel(int index, String name) {
        this.index = index;
        this.name = name;
    }

    /** The channel's name, for logs. */
    public String name() {
        return name;
    }

    @Override
    public String toString() {
        return name;
    }
}
