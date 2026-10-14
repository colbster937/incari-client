package incari.framework.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventBusTest {

    private final List<String> failures = new ArrayList<>();
    private final EventBus bus =
            new EventBus((owner, channel, error) -> failures.add(owner + " " + channel + " " + error.getMessage()));

    @Test
    void firesListenersInTheOrderTheySubscribed() {
        List<String> calls = new ArrayList<>();
        TickListener first = () -> calls.add("first");
        TickListener second = () -> calls.add("second");
        bus.add(Events.TICK, "a", first);
        bus.add(Events.TICK, "b", second);

        bus.fireTick();

        assertThat(calls).containsExactly("first", "second");
        assertThat(bus.count(Events.TICK)).isEqualTo(2);
    }

    @Test
    void removesOnlyTheListenerGiven() {
        List<String> calls = new ArrayList<>();
        TickListener first = () -> calls.add("first");
        TickListener second = () -> calls.add("second");
        TickListener third = () -> calls.add("third");
        bus.add(Events.TICK, "a", first);
        bus.add(Events.TICK, "b", second);
        bus.add(Events.TICK, "c", third);

        bus.remove(Events.TICK, second);
        bus.remove(Events.TICK, second);
        bus.fireTick();

        assertThat(calls).containsExactly("first", "third");
        bus.remove(Events.TICK, first);
        bus.remove(Events.TICK, third);
        assertThat(bus.count(Events.TICK)).isZero();
    }

    @Test
    void aListenerRemovedDuringAFireStillRunsThatFire() {
        List<String> calls = new ArrayList<>();
        TickListener[] second = new TickListener[1];
        TickListener first = () -> {
            calls.add("first");
            bus.remove(Events.TICK, second[0]);
        };
        second[0] = () -> calls.add("second");
        bus.add(Events.TICK, "a", first);
        bus.add(Events.TICK, "b", second[0]);

        bus.fireTick();
        bus.fireTick();

        assertThat(calls).containsExactly("first", "second", "first");
    }

    @Test
    void aThrowingListenerIsReportedAndTheRestStillRun() {
        List<String> calls = new ArrayList<>();
        bus.add(Events.TICK, "broken", (TickListener) () -> {
            throw new IllegalStateException("boom");
        });
        bus.add(Events.TICK, "fine", (TickListener) () -> calls.add("fine"));

        bus.fireTick();

        assertThat(failures).containsExactly("broken tick boom");
        assertThat(calls).containsExactly("fine");
    }

    @Test
    void movementReportsWhetherAListenerChangedTheInput() {
        MovementInput input = new MovementInput();
        input.reset(true, false, false, false, false, false, false, 0, 0);
        assertThat(bus.fireMovement(input)).isFalse();

        bus.add(Events.MOVEMENT, "m", (MovementListener) in -> in.setSprint(true));
        input.reset(true, false, false, false, false, false, false, 0, 0);
        assertThat(bus.fireMovement(input)).isTrue();
        assertThat(input.sprint()).isTrue();

        // Setting what's already there isn't a change.
        input.reset(true, false, false, false, false, false, true, 0, 0);
        assertThat(bus.fireMovement(input)).isFalse();
    }

    @Test
    void joinAndLeaveReachTheirListeners() {
        List<String> calls = new ArrayList<>();
        bus.add(Events.JOIN, "m", (JoinListener) server -> calls.add("join " + server));
        bus.add(Events.LEAVE, "m", (LeaveListener) () -> calls.add("leave"));

        bus.fireJoin("ws://example.net");
        bus.fireLeave();

        assertThat(calls).containsExactly("join ws://example.net", "leave");
    }
}
