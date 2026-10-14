package incari.tools.webbuild;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.teavm.model.BasicBlock;
import org.teavm.model.ClassHolder;
import org.teavm.model.ClassHolderTransformer;
import org.teavm.model.ClassHolderTransformerContext;
import org.teavm.model.ClassReader;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.ElementModifier;
import org.teavm.model.Instruction;
import org.teavm.model.MethodHolder;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.model.ProgramReader;
import org.teavm.model.ValueType;
import org.teavm.model.VariableReader;
import org.teavm.model.instructions.AbstractInstructionReader;
import org.teavm.model.instructions.InvocationType;
import org.teavm.model.instructions.InvokeInstruction;
import org.teavm.model.instructions.MonitorEnterInstruction;
import org.teavm.model.instructions.MonitorExitInstruction;

/**
 * Drops monitors that guard no suspension point.
 *
 * <p>In the page, threads are coroutines that switch only where one suspends, so code that can't
 * suspend already runs exclusively; a monitor only matters if a thread suspends while holding it.
 * But TeaVM compiles any method that takes a monitor, and all its callers through every override,
 * as able to suspend: one {@code synchronized toString} makes every {@code toString} call a
 * coroutine (slower, and TeaVM's coroutine transformation has gaps). Hashtable, Guava's
 * synchronized collections and the class library all did that.
 *
 * <p>Each method with a monitor is sorted by what it (or the static, private and class library
 * methods it calls) does:
 * <ul>
 *   <li>waits, or calls a blocking concurrency API ({@code Future.get}, {@code join},
 *       {@code Lock.lock}, {@code BlockingQueue.take}...): its monitors stay, a real suspension;
 *   <li>only notifies ({@code offer}, {@code complete}, {@code unlock} wake waiters), or neither:
 *       its body can't suspend, so it runs exclusively anyway, and its monitors go.
 * </ul>
 * Every {@code notify} and {@code notifyAll} goes through {@code incari.classlib.concurrent.Wakeups},
 * which holds the monitor only for the call (with TeaVM's non-suspending {@code monitorEnterSync},
 * reentrant), so notifying never needs a removed monitor and never makes a coroutine.
 *
 * <p>Limit: a {@code synchronized} method that blocks only through an ordinary virtual call would
 * lose a monitor it needed. Nothing the browser build runs does that.
 */
public final class MonitorElision implements ClassHolderTransformer {

    /** The class library's classes, under the names the compile sees; {@link Main} sets them. */
    static volatile Set<String> classes = Set.of();

    private static final Set<String> BLOCKING_NAMES = Set.of(
            "sleep",
            "join",
            "yield",
            "onSpinWait",
            "park",
            "parkNanos",
            "parkUntil",
            "await",
            "awaitNanos",
            "awaitUninterruptibly",
            "awaitUntil",
            "awaitTermination",
            "awaitAdvance",
            "tryLock",
            "writeLock",
            "readLock",
            "tryOptimisticRead",
            "acquire",
            "acquireUninterruptibly",
            "tryAcquire",
            "take",
            "put",
            "offer",
            "poll",
            "transfer",
            "get",
            "invoke",
            "invokeAll",
            "invokeAny",
            "close",
            "managedBlock");

    private static final MethodReference ENTER_SYNC = new MethodReference(
            "java.lang.Object", "monitorEnterSync", ValueType.object("java.lang.Object"), ValueType.VOID);
    private static final MethodReference EXIT_SYNC = new MethodReference(
            "java.lang.Object", "monitorExitSync", ValueType.object("java.lang.Object"), ValueType.VOID);

    /** The class library's notification helper (see {@link #routeToWakeups}). */
    static final String WAKEUPS = "incari.classlib.concurrent.Wakeups";

    /** The class library's wait helper, which times the render thread's waits (see {@link #routeToWaits}). */
    static final String WAITS = "incari.classlib.concurrent.Waits";

    /** The class library's non-waiting lock helper (see {@link #routeToLockNow}). */
    static final String LOCKS = "incari.classlib.concurrent.Locks";

    private final Map<MethodReference, Effect> effects = new HashMap<>();
    private org.teavm.model.@org.jspecify.annotations.Nullable ClassHierarchy hierarchy;

    @Override
    public void transformClass(ClassHolder cls, ClassHolderTransformerContext context) {
        ClassReaderSource source = context.getHierarchy().getClassSource();
        hierarchy = context.getHierarchy();
        boolean wakeups = cls.getName().equals(WAKEUPS);
        // The helper's own waits, and Object's (its wait(long) calls wait(long, int)): routing
        // those would recurse without end.
        boolean waits = cls.getName().equals(WAITS) || cls.getName().equals("java.lang.Object");
        for (MethodHolder method : cls.getMethods()) {
            if (method.getProgram() == null) {
                continue;
            }
            boolean blocks = effect(method.getReference(), source) == Effect.BLOCKS;
            boolean dropMonitors = hasMonitor(method) && !blocks;
            if (dropMonitors) {
                method.getModifiers().remove(ElementModifier.SYNCHRONIZED);
            }
            for (int i = 0; i < method.getProgram().basicBlockCount(); i++) {
                BasicBlock block = method.getProgram().basicBlockAt(i);
                for (Instruction insn = block.getFirstInstruction(); insn != null; ) {
                    Instruction next = insn.getNext();
                    if (wakeups) {
                        // The helper itself: take the monitor for notify, without suspending.
                        takeWithoutSuspending(insn);
                    } else if (insn instanceof InvokeInstruction call && isNotify(call.getMethod())) {
                        // Everywhere, so a notify never depends on a monitor this pass removed.
                        routeToWakeups(call);
                    } else if (!waits && insn instanceof InvokeInstruction call && isObjectWait(call.getMethod())) {
                        // Everywhere but the helper itself, so every wait on the render thread is timed.
                        routeToWaits(call);
                    } else if (!blocks && insn instanceof InvokeInstruction call && isLock(call.getMethod())) {
                        routeToLockNow(call);
                    } else if (dropMonitors
                            && (insn instanceof MonitorEnterInstruction || insn instanceof MonitorExitInstruction)) {
                        insn.delete();
                    }
                    insn = next;
                }
            }
        }
    }

    /** {@code lock.lock()} becomes {@code Locks.lockNow(lock)}: take it now, or fail loudly. */
    private static void routeToLockNow(InvokeInstruction call) {
        InvokeInstruction now = new InvokeInstruction();
        now.setType(InvocationType.SPECIAL);
        now.setMethod(new MethodReference(
                LOCKS, "lockNow", ValueType.object("java.util.concurrent.locks.Lock"), ValueType.VOID));
        now.setArguments(call.getInstance());
        now.setLocation(call.getLocation());
        call.replace(now);
    }

    /**
     * {@code lock()} or {@code lockInterruptibly()} on a {@code Lock}. Not counted as blocking: in a
     * method that doesn't otherwise block, it becomes a non-waiting acquisition (see
     * {@link #routeToLockNow}); a method that blocks anyway keeps the real one.
     */
    private boolean isLock(MethodReference method) {
        String descriptor = method.getDescriptor().toString();
        return (descriptor.equals("lock()V") || descriptor.equals("lockInterruptibly()V"))
                && hierarchy != null
                && hierarchy.isSuperType("java.util.concurrent.locks.Lock", method.getClassName(), false);
    }

    /**
     * {@code monitor.wait(timeout)} becomes {@code Waits.wait(monitor, timeout)}, likewise the other
     * two forms: the helper times the render thread's waits for the page's frame monitor. The
     * caller still holds the monitor, as {@code wait} needs, and still counts as blocking.
     */
    private static void routeToWaits(InvokeInstruction call) {
        ValueType[] parameters = call.getMethod().getParameterTypes();
        ValueType[] signature = new ValueType[parameters.length + 2];
        signature[0] = ValueType.object("java.lang.Object");
        System.arraycopy(parameters, 0, signature, 1, parameters.length);
        signature[signature.length - 1] = ValueType.VOID;
        List<org.teavm.model.Variable> arguments = new ArrayList<>();
        arguments.add(call.getInstance());
        arguments.addAll(call.getArguments());
        InvokeInstruction timed = new InvokeInstruction();
        timed.setType(InvocationType.SPECIAL);
        timed.setMethod(new MethodReference(WAITS, "wait", signature));
        timed.setArguments(arguments.toArray(org.teavm.model.Variable[]::new));
        timed.setLocation(call.getLocation());
        call.replace(timed);
    }

    /** {@code Object.wait} in its three forms (final in {@code Object}, so matched whatever class the call names). */
    static boolean isObjectWait(MethodReference method) {
        String descriptor = method.getDescriptor().toString();
        return !method.getClassName().equals(WAITS)
                && (descriptor.equals("wait()V") || descriptor.equals("wait(J)V") || descriptor.equals("wait(JI)V"));
    }

    /** {@code monitor.notifyAll()} becomes {@code Wakeups.notifyAll(monitor)}; likewise notify. */
    private static void routeToWakeups(InvokeInstruction call) {
        String name = call.getMethod().getName();
        InvokeInstruction wakeup = new InvokeInstruction();
        wakeup.setType(InvocationType.SPECIAL);
        wakeup.setMethod(new MethodReference(WAKEUPS, name, ValueType.object("java.lang.Object"), ValueType.VOID));
        wakeup.setArguments(call.getInstance());
        wakeup.setLocation(call.getLocation());
        call.replace(wakeup);
    }

    /** Replaces a monitor instruction with TeaVM's non-suspending monitor call. */
    private static void takeWithoutSuspending(Instruction insn) {
        MethodReference target;
        org.teavm.model.Variable object;
        if (insn instanceof MonitorEnterInstruction enter) {
            target = ENTER_SYNC;
            object = enter.getObjectRef();
        } else if (insn instanceof MonitorExitInstruction exit) {
            target = EXIT_SYNC;
            object = exit.getObjectRef();
        } else {
            return;
        }
        InvokeInstruction call = new InvokeInstruction();
        call.setType(InvocationType.SPECIAL);
        call.setMethod(target);
        call.setArguments(object);
        call.setLocation(insn.getLocation());
        insn.replace(call);
    }

    private static boolean hasMonitor(MethodHolder method) {
        if (method.hasModifier(ElementModifier.SYNCHRONIZED)) {
            return true;
        }
        for (int i = 0; i < method.getProgram().basicBlockCount(); i++) {
            for (Instruction insn : method.getProgram().basicBlockAt(i)) {
                if (insn instanceof MonitorEnterInstruction) {
                    return true;
                }
            }
        }
        return false;
    }

    /** What a method does that its monitors matter for, itself or through the calls it follows. */
    enum Effect {
        NONE,
        NOTIFIES,
        BLOCKS
    }

    private Effect effect(MethodReference reference, ClassReaderSource source) {
        Effect known = effects.get(reference);
        if (known != null) {
            return known;
        }
        // Assume nothing while looking, so a recursive cycle ends; a waiting or notifying method in
        // the cycle is still found from wherever the search reaches it.
        effects.put(reference, Effect.NONE);
        Effect result = scan(reference, source);
        effects.put(reference, result);
        return result;
    }

    private Effect scan(MethodReference reference, ClassReaderSource source) {
        ClassReader cls = source.get(reference.getClassName());
        MethodReader method = cls == null ? null : cls.getMethod(reference.getDescriptor());
        ProgramReader program = method == null ? null : method.getProgram();
        if (program == null) {
            return Effect.NONE;
        }
        CallFinder calls = new CallFinder();
        for (int i = 0; i < program.basicBlockCount(); i++) {
            program.basicBlockAt(i).readAllInstructions(calls);
        }
        Effect result = calls.effect;
        for (MethodReference callee : calls.followed) {
            if (result == Effect.BLOCKS) {
                break;
            }
            Effect calleeEffect = effect(callee, source);
            if (calleeEffect.compareTo(result) > 0) {
                result = calleeEffect;
            }
        }
        return result;
    }

    /**
     * {@code wait} (final in {@code Object}, so matched by name whatever class the call names) and
     * blocking-named methods of {@code Thread}, the locks, and the {@code java.util.concurrent}
     * types that can block.
     */
    static boolean isBlocking(MethodReference method) {
        String name = method.getName();
        String descriptor = method.getDescriptor().toString();
        if (name.equals("wait")
                && (descriptor.equals("wait()V") || descriptor.equals("wait(J)V") || descriptor.equals("wait(JI)V"))) {
            return true;
        }
        if (name.equals("wait") && method.getClassName().equals(WAITS)) {
            // A wait this pass has already routed to the helper (see routeToWaits).
            return true;
        }
        if ((name.equals("tryLock") || name.equals("tryAcquire")) && method.parameterCount() == 0) {
            // The untimed forms answer at once.
            return false;
        }
        if ((name.equals("offer") && method.parameterCount() == 1)
                || (name.equals("poll") && method.parameterCount() == 0)) {
            // A queue's untimed offer and poll answer at once too; only the forms with a timeout
            // wait. Counting them kept ThreadPoolExecutor.execute's monitor, which made every
            // Executor.execute, and through CompletableFuture every Function.apply, a coroutine.
            return false;
        }
        String owner = method.getClassName();
        if (owner.equals("java.lang.Thread")) {
            return BLOCKING_NAMES.contains(name);
        }
        if (!owner.startsWith("java.util.concurrent.")) {
            return false;
        }
        String simpleName = owner.substring(owner.lastIndexOf('.') + 1);
        return BLOCKING_NAMES.contains(name)
                && (owner.startsWith("java.util.concurrent.locks.")
                        || BLOCKING_TYPES.stream().anyMatch(simpleName::contains));
    }

    /** {@code notify} and {@code notifyAll}, final in {@code Object}. */
    static boolean isNotify(MethodReference method) {
        String descriptor = method.getDescriptor().toString();
        return descriptor.equals("notify()V") || descriptor.equals("notifyAll()V");
    }

    /** The {@code java.util.concurrent} types whose methods can block ({@code ConcurrentHashMap} can't). */
    private static final List<String> BLOCKING_TYPES = List.of(
            "Future",
            "ForkJoin",
            "Blocking",
            "Latch",
            "Semaphore",
            "Barrier",
            "Phaser",
            "Exchanger",
            "Executor",
            "CompletionService",
            "TransferQueue",
            "SynchronousQueue",
            "DelayQueue");

    private final class CallFinder extends AbstractInstructionReader {
        Effect effect = Effect.NONE;
        final List<MethodReference> followed = new ArrayList<>();

        @Override
        public void invoke(
                VariableReader receiver,
                VariableReader instance,
                MethodReference method,
                List<? extends VariableReader> arguments,
                InvocationType type) {
            if (isLock(method)) {
                // Neither blocking nor followed: see isLock.
                return;
            }
            if (isBlocking(method)) {
                effect = Effect.BLOCKS;
            } else if (isNotify(method)) {
                if (effect == Effect.NONE) {
                    effect = Effect.NOTIFIES;
                }
            } else if (type == InvocationType.SPECIAL || classes.contains(method.getClassName())) {
                // Static, private and constructor calls have one target; the class library's
                // virtual calls stay inside code whose waiting this can see.
                followed.add(method);
            }
        }
    }
}
