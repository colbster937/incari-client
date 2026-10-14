package incari.online;

import incari.framework.config.ConfigStorage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/** One client against a {@link FakeService}: a service, its storage, a clock the test moves. */
final class Client {

    final MemoryStorage storage = new MemoryStorage();
    final List<String> warnings = new ArrayList<>();
    long now = 1_000_000;
    final OnlineService service;

    Client(FakeService fake) {
        this(fake, new MemoryStorage());
    }

    Client(FakeService fake, MemoryStorage files) {
        this.storage.files.putAll(files.files);
        this.service = new OnlineService(
                "wss://online.test/v1/ws",
                "test",
                fake,
                storage,
                Runnable::run,
                () -> {},
                () -> now,
                () -> 0.5,
                (message, error) -> warnings.add(message));
    }

    /** Ticks a few times, for queued messages and their answers to settle. */
    Client settle() {
        for (int i = 0; i < 6; i++) {
            service.tick();
        }
        return this;
    }

    /** Runs {@code call} with a callback, settles, and returns what the callback heard. */
    <T> Result<T> await(Consumer<Consumer<Result<T>>> call) {
        List<Result<T>> heard = new ArrayList<>();
        call.accept(heard::add);
        settle();
        if (heard.size() != 1) {
            throw new AssertionError("Heard " + heard.size() + " answers");
        }
        return heard.get(0);
    }

    /** Files by path, in memory. */
    static final class MemoryStorage implements ConfigStorage {
        final Map<String, String> files = new HashMap<>();

        @Override
        public @Nullable String read(String path) {
            return files.get(path);
        }

        @Override
        public void write(String path, String text) {
            files.put(path, text);
        }

        @Override
        public void delete(String path) {
            files.remove(path);
        }
    }
}
