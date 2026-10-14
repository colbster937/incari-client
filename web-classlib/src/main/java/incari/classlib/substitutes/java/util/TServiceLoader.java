package incari.classlib.substitutes.java.util;

import incari.classlib.nio.ZipFileSystemProvider;
import java.nio.file.spi.FileSystemProvider;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * {@code java.util.ServiceLoader} for the browser build: providers are bound here, not found. The
 * game and its libraries mostly use service lookup for optional extras the browser build leaves out
 * (Mojang's server blocklist, Log4j's property sources, ThreeTen's extra zone providers), each with
 * a fallback when nothing is found; the few the browser build provides are listed in
 * {@link #providers}. Replaces TeaVM's, whose compile-time wiring of {@code META-INF/services} fails
 * on the game's class path.
 */
public final class TServiceLoader<S> implements Iterable<S> {

    private final Class<S> service;

    private TServiceLoader(Class<S> service) {
        this.service = service;
    }

    public static <S> TServiceLoader<S> load(Class<S> service) {
        return new TServiceLoader<>(service);
    }

    public static <S> TServiceLoader<S> load(Class<S> service, @Nullable ClassLoader loader) {
        return new TServiceLoader<>(service);
    }

    public static <S> TServiceLoader<S> loadInstalled(Class<S> service) {
        return new TServiceLoader<>(service);
    }

    /** The providers the browser build binds for a service type. */
    private List<S> providers() {
        if (service == FileSystemProvider.class) {
            // Util looks up the jar provider at startup (see ZipFileSystemProvider).
            return List.of(service.cast(new ZipFileSystemProvider()));
        }
        return List.of();
    }

    @Override
    public Iterator<S> iterator() {
        return providers().iterator();
    }

    public Stream<Provider<S>> stream() {
        return providers().stream().map(provider -> new Provider<S>() {
            @Override
            @SuppressWarnings("unchecked") // The provider's own class, which is a subtype of S.
            public Class<? extends S> type() {
                return (Class<? extends S>) provider.getClass();
            }

            @Override
            public S get() {
                return provider;
            }
        });
    }

    public Optional<S> findFirst() {
        List<S> all = providers();
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    public void reload() {}

    @Override
    public String toString() {
        return "java.util.ServiceLoader[" + service.getName() + "]";
    }

    /** A provider found by a lookup; none ever is. */
    public interface Provider<S> extends Supplier<S> {
        Class<? extends S> type();

        @Override
        S get();
    }
}
