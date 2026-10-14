package incari.classlib.additions.java.util.stream;

import java.util.Spliterator;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

/**
 * {@code java.util.stream.StreamSupport} methods TeaVM lacks: primitive streams, built over the
 * boxed stream TeaVM supports. Sequential only; a page has no parallel streams.
 */
public final class StreamSupportAdditions {

    private StreamSupportAdditions() {}

    public static IntStream intStream(Spliterator.OfInt spliterator, boolean parallel) {
        return StreamSupport.stream(spliterator, false).mapToInt(Integer::intValue);
    }

    public static LongStream longStream(Spliterator.OfLong spliterator, boolean parallel) {
        return StreamSupport.stream(spliterator, false).mapToLong(Long::longValue);
    }
}
