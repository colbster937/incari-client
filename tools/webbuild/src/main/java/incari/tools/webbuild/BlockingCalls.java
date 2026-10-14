package incari.tools.webbuild;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.teavm.dependency.DependencyInfo;
import org.teavm.model.ClassHierarchy;
import org.teavm.model.ClassReader;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ProgramReader;
import org.teavm.model.TextLocation;
import org.teavm.model.ValueType;
import org.teavm.model.VariableReader;
import org.teavm.model.instructions.AbstractInstructionReader;
import org.teavm.model.instructions.InvocationType;

/**
 * Lists every call to a blocking API in the code the browser build links (the game, its libraries
 * and Incari's modules, not the class library that implements the APIs): {@code blocking.txt}, the
 * input to the threading audit ({@code docs/threading.md}).
 *
 * <p>In the page, threads are coroutines on one thread, so a blocking call suspends the calling
 * coroutine and runs the others. On a background thread that's harmless. On the render thread the
 * frame waits with it, and it deadlocks if what it waits for can only happen on the render thread
 * (a task queued for the game's own loop, which only runs between frames).
 */
final class BlockingCalls {

    /** A blocking method, by the type that declares it and its name. */
    private record Api(String owner, String name) {}

    private static final List<Api> APIS = List.of(
            new Api("java.lang.Object", "wait"),
            new Api("java.lang.Thread", "sleep"),
            new Api("java.lang.Thread", "join"),
            new Api("java.lang.Thread", "yield"),
            new Api("java.lang.Thread", "onSpinWait"),
            new Api("java.util.concurrent.Future", "get"),
            new Api("java.util.concurrent.CompletableFuture", "join"),
            new Api("java.util.concurrent.ForkJoinTask", "join"),
            new Api("java.util.concurrent.ForkJoinTask", "invoke"),
            new Api("java.util.concurrent.locks.LockSupport", "park"),
            new Api("java.util.concurrent.locks.LockSupport", "parkNanos"),
            new Api("java.util.concurrent.locks.LockSupport", "parkUntil"),
            new Api("java.util.concurrent.locks.Lock", "lock"),
            new Api("java.util.concurrent.locks.Lock", "lockInterruptibly"),
            new Api("java.util.concurrent.locks.Condition", "await"),
            new Api("java.util.concurrent.locks.Condition", "awaitNanos"),
            new Api("java.util.concurrent.locks.Condition", "awaitUninterruptibly"),
            new Api("java.util.concurrent.CountDownLatch", "await"),
            new Api("java.util.concurrent.CyclicBarrier", "await"),
            new Api("java.util.concurrent.Semaphore", "acquire"),
            new Api("java.util.concurrent.Semaphore", "acquireUninterruptibly"),
            new Api("java.util.concurrent.BlockingQueue", "take"),
            new Api("java.util.concurrent.BlockingQueue", "put"),
            new Api("java.util.concurrent.ExecutorService", "awaitTermination"),
            new Api("java.util.concurrent.ExecutorService", "invokeAll"),
            new Api("java.util.concurrent.ExecutorService", "invokeAny"),
            new Api("java.util.concurrent.ExecutorService", "close"),
            new Api("net.minecraft.util.thread.BlockableEventLoop", "managedBlock"),
            new Api("net.minecraft.util.thread.BlockableEventLoop", "waitForTasks"));

    /**
     * {@code Object.wait}'s own signatures. Every class is an {@code Object}, and a method of another
     * class that happens to be called {@code wait} (the game's reload barrier) doesn't block.
     */
    private static final Set<String> OBJECT_WAITS = Set.of("wait()V", "wait(J)V", "wait(JI)V");

    /** Packages of the class library and TeaVM, which implement the APIs rather than call them. */
    private static final List<String> IMPLEMENTATIONS =
            List.of("java.", "javax.", "jdk.", "sun.", "org.teavm.", "incari.classlib.");

    private BlockingCalls() {}

    static void write(DependencyInfo dependencies, Path file) throws IOException {
        ClassHierarchy hierarchy = new ClassHierarchy(dependencies.getClassSource());
        // API -> "caller (Source.java:line)"
        Map<String, Set<String>> byApi = new TreeMap<>();
        for (MethodReference reference : dependencies.getReachableMethods()) {
            String owner = reference.getClassName();
            if (IMPLEMENTATIONS.stream().anyMatch(owner::startsWith)) {
                continue;
            }
            ClassReader cls = dependencies.getClassSource().get(owner);
            MethodReader method = cls == null ? null : cls.getMethod(reference.getDescriptor());
            ProgramReader program = method == null ? null : method.getProgram();
            if (program == null) {
                continue;
            }
            Finder finder = new Finder(hierarchy, reference, byApi);
            for (int i = 0; i < program.basicBlockCount(); i++) {
                program.basicBlockAt(i).readAllInstructions(finder);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add("# Calls to blocking APIs in the linked program (docs/threading.md classifies them).");
        lines.add("# Each API, then its callers: method (source:line).");
        int total = 0;
        for (Map.Entry<String, Set<String>> entry : byApi.entrySet()) {
            lines.add("");
            lines.add(entry.getKey() + " (" + entry.getValue().size() + ")");
            for (String caller : entry.getValue()) {
                lines.add("    " + caller);
            }
            total += entry.getValue().size();
        }
        lines.add(1, "# " + total + " call sites.");
        Files.write(file, lines);
    }

    private static final class Finder extends AbstractInstructionReader {
        private final ClassHierarchy hierarchy;
        private final MethodReference caller;
        private final Map<String, Set<String>> byApi;
        private TextLocation location = TextLocation.EMPTY;

        Finder(ClassHierarchy hierarchy, MethodReference caller, Map<String, Set<String>> byApi) {
            this.hierarchy = hierarchy;
            this.caller = caller;
            this.byApi = byApi;
        }

        @Override
        public void location(TextLocation location) {
            this.location = location == null ? TextLocation.EMPTY : location;
        }

        @Override
        public void invoke(
                VariableReader receiver,
                VariableReader instance,
                MethodReference method,
                List<? extends VariableReader> arguments,
                InvocationType type) {
            for (Api api : APIS) {
                if (api.name().equals(method.getName())
                        && (method.getClassName().equals(api.owner())
                                || hierarchy.isSuperType(api.owner(), method.getClassName(), false))
                        && (!api.owner().equals("java.lang.Object")
                                || OBJECT_WAITS.contains(method.getDescriptor().toString()))) {
                    String where = location.isEmpty()
                            ? ""
                            : " (" + fileName(location.getFileName()) + ":" + location.getLine() + ")";
                    byApi.computeIfAbsent(api.owner() + "." + api.name() + signature(method), k -> new TreeSet<>())
                            .add(caller.getClassName() + "." + caller.getName() + where);
                    return;
                }
            }
        }

        private static String signature(MethodReference method) {
            StringBuilder text = new StringBuilder("(");
            ValueType[] parameters = method.getParameterTypes();
            for (int i = 0; i < parameters.length; i++) {
                if (i > 0) {
                    text.append(", ");
                }
                text.append(typeName(parameters[i]));
            }
            return text.append(")").toString();
        }

        private static String typeName(ValueType type) {
            if (type instanceof ValueType.Object object) {
                String name = object.getClassName();
                return name.substring(name.lastIndexOf('.') + 1);
            } else if (type instanceof ValueType.Array array) {
                return typeName(array.getItemType()) + "[]";
            } else if (type instanceof ValueType.Primitive primitive) {
                return primitive.getKind().name().toLowerCase(java.util.Locale.ROOT);
            }
            return type.toString();
        }

        private static String fileName(String path) {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }
}
