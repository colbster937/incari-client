package incari.platform.web;

import org.teavm.interop.Async;
import org.teavm.interop.AsyncCallback;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;

/**
 * The background pool's slots in a worker's copy of the game (ADR 0039): a worker has no frames to
 * fit between, so a slot is the next turn of its event loop, a few milliseconds long. Ending each
 * slot hands the worker's thread back to its event loop, where the server's own thread (waiting
 * for its next tick, or for the pool's work) and the page's messages get their turns.
 */
final class WorkerSlots implements IdleTimeExecutor.Slots {

    /** How long the pool runs before the worker's other work gets a turn. */
    private static final double SLOT_MILLIS = 8;

    @Override
    public void arm() {}

    @Override
    public double await() {
        nextTurn();
        return Dom.nowMillis() + SLOT_MILLIS;
    }

    @Override
    public double now() {
        return Dom.nowMillis();
    }

    /** Suspends until the event loop's next turn: a message posted to itself, not a clamped timer. */
    @Async
    private static native Void nextTurn();

    private static void nextTurn(AsyncCallback<Void> callback) {
        post(() -> callback.complete(null));
    }

    @JSFunctor
    interface Turn extends JSObject {
        void run();
    }

    @JSBody(
            params = "turn",
            script = "var c = self.__incariTurns; if (!c) { c = new MessageChannel(); c.waiting = [];"
                    + "  c.port1.onmessage = function() { var t = c.waiting.shift(); if (t) t(); };"
                    + "  self.__incariTurns = c; }"
                    + "c.waiting.push(turn); c.port2.postMessage(0);")
    private static native void post(Turn turn);
}
