package incari.tools.webbuild;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.teavm.callgraph.CallGraph;
import org.teavm.callgraph.CallGraphNode;
import org.teavm.callgraph.CallSite;
import org.teavm.dependency.DependencyInfo;
import org.teavm.model.ClassReader;
import org.teavm.model.ElementModifier;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ProgramReader;
import org.teavm.model.VariableReader;
import org.teavm.model.instructions.AbstractInstructionReader;

/**
 * Explains why TeaVM treats a method as one that can suspend: the shortest call chain from it to a
 * method that holds a monitor ({@code synchronized}) or is {@code @Async}. TeaVM compiles every
 * such method as a coroutine, and follows virtual calls to every override, so one blocking
 * {@code toString} makes every {@code toString} call a suspension point. Written to
 * {@code async.txt} for methods TeaVM failed to compile, and on request.
 */
final class AsyncExplainer {

    private static final String ASYNC = "org.teavm.interop.Async";

    private final CallGraph callGraph;
    private final DependencyInfo dependencies;

    AsyncExplainer(DependencyInfo dependencies) {
        this.dependencies = dependencies;
        this.callGraph = dependencies.getCallGraph();
    }

    /**
     * Virtual methods the game calls everywhere. If any implementation of one can suspend, TeaVM
     * compiles every call to it as a suspension point, so none should.
     */
    private static final List<String> HOT = List.of(
            "java.lang.Object.toString()Ljava/lang/String;",
            "java.lang.Object.hashCode()I",
            "java.lang.Object.equals(Ljava/lang/Object;)Z",
            "java.lang.CharSequence.charAt(I)C",
            "java.lang.CharSequence.length()I",
            "java.lang.Comparable.compareTo(Ljava/lang/Object;)I",
            "java.lang.Iterable.iterator()Ljava/util/Iterator;",
            "java.util.Iterator.hasNext()Z",
            "java.util.Iterator.next()Ljava/lang/Object;",
            "java.util.Collection.size()I",
            "java.util.Collection.add(Ljava/lang/Object;)Z",
            "java.util.Collection.contains(Ljava/lang/Object;)Z",
            "java.util.Collection.isEmpty()Z",
            "java.util.List.get(I)Ljava/lang/Object;",
            "java.util.Map.get(Ljava/lang/Object;)Ljava/lang/Object;",
            "java.util.Map.put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            "java.util.Map.containsKey(Ljava/lang/Object;)Z",
            "java.util.Queue.offer(Ljava/lang/Object;)Z",
            "java.util.Queue.poll()Ljava/lang/Object;",
            "java.util.Comparator.compare(Ljava/lang/Object;Ljava/lang/Object;)I",
            "java.util.function.Function.apply(Ljava/lang/Object;)Ljava/lang/Object;",
            "java.util.function.Predicate.test(Ljava/lang/Object;)Z",
            "java.util.function.Consumer.accept(Ljava/lang/Object;)V");

    /**
     * Lines for {@code async.txt}: each hot method that TeaVM will compile as a suspension point,
     * with up to three implementations that make it one and why.
     */
    List<String> hotReport() {
        // Reachable names include arrays and generated classes with no class reader behind them.
        java.util.Set<String> reachable = new java.util.HashSet<>();
        for (String name : dependencies.getReachableClasses()) {
            if (dependencies.getClassSource().get(name) != null) {
                reachable.add(name);
            }
        }
        org.teavm.model.ListableClassReaderSource source = new org.teavm.model.ListableClassReaderSource() {
            @Override
            public java.util.Set<String> getClassNames() {
                return reachable;
            }

            @Override
            public @Nullable ClassReader get(String name) {
                return dependencies.getClassSource().get(name);
            }
        };
        org.teavm.model.util.AsyncMethodFinder finder =
                new org.teavm.model.util.AsyncMethodFinder(callGraph, dependencies);
        finder.find(source);
        org.teavm.model.ClassHierarchy hierarchy = new org.teavm.model.ClassHierarchy(dependencies.getClassSource());
        List<String> lines = new ArrayList<>();
        // How much of the program is compiled as coroutines at all (ADR 0027): each of these pays
        // suspend and resume bookkeeping around its calls that may suspend, and inlines less.
        int reachableMethods = dependencies.getReachableMethods().size();
        java.util.Map<String, Integer> byPackage = new java.util.TreeMap<>();
        for (MethodReference method : finder.getAsyncMethods()) {
            String className = method.getClassName();
            int lastDot = className.lastIndexOf('.');
            byPackage.merge(lastDot < 0 ? "" : className.substring(0, lastDot), 1, Integer::sum);
        }
        lines.add(
                "# Methods compiled as coroutines: " + finder.getAsyncMethods().size() + " of " + reachableMethods
                        + " reachable. The packages with the most:");
        byPackage.entrySet().stream()
                .sorted(java.util.Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(25)
                .forEach(entry -> lines.add("#   " + entry.getValue() + "  " + entry.getKey()));
        for (String hot : HOT) {
            int paren = hot.indexOf('(');
            int dot = hot.lastIndexOf('.', paren);
            String owner = hot.substring(0, dot);
            org.teavm.model.MethodDescriptor descriptor =
                    org.teavm.model.MethodDescriptor.parse(hot.substring(dot + 1));
            List<MethodReference> offenders = new ArrayList<>();
            for (MethodReference method : finder.getAsyncMethods()) {
                if (method.getDescriptor().equals(descriptor)
                        && hierarchy.isSuperType(owner, method.getClassName(), false)) {
                    offenders.add(method);
                }
            }
            if (offenders.isEmpty()) {
                continue;
            }
            lines.add("SUSPENDS " + hot + " (" + offenders.size() + " implementations)");
            for (MethodReference offender : offenders.subList(0, Math.min(3, offenders.size()))) {
                lines.add("  " + offender);
                for (MethodReference step : explain(offender)) {
                    lines.add("      -> " + step);
                }
            }
        }
        return lines;
    }

    /** The chain from {@code start} to a suspension source, start first; empty if there's none. */
    List<MethodReference> explain(MethodReference start) {
        Map<MethodReference, @Nullable MethodReference> cameFrom = new HashMap<>();
        Deque<MethodReference> queue = new ArrayDeque<>();
        cameFrom.put(start, null);
        queue.add(start);
        while (!queue.isEmpty()) {
            MethodReference method = queue.removeFirst();
            if (suspends(method)) {
                List<MethodReference> chain = new ArrayList<>();
                for (MethodReference at = method; at != null; at = cameFrom.get(at)) {
                    chain.addFirst(at);
                }
                return chain;
            }
            CallGraphNode node = callGraph.getNode(method);
            if (node == null) {
                continue;
            }
            for (CallSite site : node.getCallSites()) {
                for (CallGraphNode callee : site.getCalledMethods()) {
                    if (!cameFrom.containsKey(callee.getMethod())) {
                        cameFrom.put(callee.getMethod(), method);
                        queue.addLast(callee.getMethod());
                    }
                }
            }
        }
        return List.of();
    }

    private boolean suspends(MethodReference reference) {
        ClassReader cls = dependencies.getClassSource().get(reference.getClassName());
        MethodReader method = cls == null ? null : cls.getMethod(reference.getDescriptor());
        if (method == null) {
            return false;
        }
        if (method.getAnnotations().get(ASYNC) != null || method.hasModifier(ElementModifier.SYNCHRONIZED)) {
            return true;
        }
        ProgramReader program = method.getProgram();
        if (program == null) {
            return false;
        }
        MonitorFinder finder = new MonitorFinder();
        for (int i = 0; i < program.basicBlockCount() && !finder.found; i++) {
            program.basicBlockAt(i).readAllInstructions(finder);
        }
        return finder.found;
    }

    private static final class MonitorFinder extends AbstractInstructionReader {
        boolean found;

        @Override
        public void monitorEnter(VariableReader objectRef) {
            found = true;
        }
    }
}
