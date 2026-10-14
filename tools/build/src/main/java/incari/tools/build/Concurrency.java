package incari.tools.build;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

final class Concurrency {

    private Concurrency() {}

    /** Waits for {@code future}, rethrowing its failure unwrapped so CLI errors keep their message. */
    static void join(Future<?> future) throws IOException, InterruptedException {
        try {
            future.get();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause == null) {
                throw new IllegalStateException(e);
            }
            switch (cause) {
                case IOException io -> throw io;
                case RuntimeException runtime -> throw runtime;
                case InterruptedException interrupted -> throw interrupted;
                case Throwable other -> throw new IllegalStateException(other);
            }
        }
    }
}
