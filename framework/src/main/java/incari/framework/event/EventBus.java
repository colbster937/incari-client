package incari.framework.event;

import incari.framework.hud.WorldCanvas;
import incari.framework.hud.WorldOverlay;

/**
 * The listeners of every {@link Channel}, and the game's way to fire them. Game thread only.
 *
 * <p>Each channel's listeners are one array, replaced on every subscribe or unsubscribe: firing
 * walks the array it read without allocating, and a listener may (un)subscribe during a fire,
 * taking effect at the next one. A channel no enabled module listens to costs only a length check.
 *
 * <p>A listener that throws is reported to the {@link Failures} with its owner, and the fire goes
 * on to the next listener: one broken module doesn't take the game down.
 */
public final class EventBus {

    /** What happens to a listener's exception. */
    @FunctionalInterface
    public interface Failures {

        /**
         * A listener threw.
         *
         * @param owner the object it was subscribed with (its module)
         * @param channel the event being fired
         * @param error what it threw
         */
        void failed(Object owner, Channel<?> channel, RuntimeException error);
    }

    private static final Object[] EMPTY = new Object[0];

    /** Per channel: owner, listener, owner, listener... */
    private final Object[][] lists = new Object[Events.COUNT][];

    private final Failures failures;

    /** A bus with no listeners. */
    public EventBus(Failures failures) {
        this.failures = failures;
        for (int i = 0; i < lists.length; i++) {
            lists[i] = EMPTY;
        }
    }

    /** Subscribes {@code listener} to {@code channel} on behalf of {@code owner}. */
    public <L> void add(Channel<L> channel, Object owner, L listener) {
        Object[] old = lists[channel.index];
        Object[] grown = new Object[old.length + 2];
        System.arraycopy(old, 0, grown, 0, old.length);
        grown[old.length] = owner;
        grown[old.length + 1] = listener;
        lists[channel.index] = grown;
    }

    /** Unsubscribes {@code listener} (the same object) from {@code channel}; nothing if it isn't there. */
    @SuppressWarnings("ReferenceEquality") // A listener is the object subscribed, not an equal one.
    public <L> void remove(Channel<L> channel, L listener) {
        Object[] old = lists[channel.index];
        for (int i = 1; i < old.length; i += 2) {
            if (old[i] == listener) {
                Object[] shrunk = old.length == 2 ? EMPTY : new Object[old.length - 2];
                System.arraycopy(old, 0, shrunk, 0, i - 1);
                System.arraycopy(old, i + 1, shrunk, i - 1, old.length - i - 1);
                lists[channel.index] = shrunk;
                return;
            }
        }
    }

    /** How many listeners {@code channel} has. */
    public int count(Channel<?> channel) {
        return lists[channel.index].length / 2;
    }

    /** Fires {@link Events#TICK}. */
    public void fireTick() {
        Object[] list = lists[Events.TICK.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((TickListener) list[i + 1]).onTick();
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.TICK, e);
            }
        }
    }

    /**
     * Fires {@link Events#MOVEMENT}.
     *
     * @return whether a listener changed {@code input}
     */
    public boolean fireMovement(MovementInput input) {
        Object[] list = lists[Events.MOVEMENT.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((MovementListener) list[i + 1]).onMovementInput(input);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.MOVEMENT, e);
            }
        }
        return input.changed();
    }

    /** Fires {@link Events#JOIN}. */
    public void fireJoin(String server) {
        Object[] list = lists[Events.JOIN.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((JoinListener) list[i + 1]).onJoin(server);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.JOIN, e);
            }
        }
    }

    /** Fires {@link Events#LEAVE}. */
    public void fireLeave() {
        Object[] list = lists[Events.LEAVE.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((LeaveListener) list[i + 1]).onLeave();
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.LEAVE, e);
            }
        }
    }

    /**
     * Fires {@link Events#FIRE_OVERLAY}.
     *
     * @return how far down the listeners moved the flames, in flame heights
     */
    public float fireFireOverlay(FireOverlay overlay) {
        Object[] list = lists[Events.FIRE_OVERLAY.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((FireOverlayListener) list[i + 1]).onFireOverlay(overlay);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.FIRE_OVERLAY, e);
            }
        }
        return overlay.lower();
    }

    /** Fires {@link Events#OVERLAY}. */
    public void fireOverlay(WorldOverlay overlay) {
        Object[] list = lists[Events.OVERLAY.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((OverlayListener) list[i + 1]).onOverlay(overlay);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.OVERLAY, e);
            }
        }
    }

    /** Fires {@link Events#WORLD}. */
    public void fireWorld(WorldCanvas canvas) {
        Object[] list = lists[Events.WORLD.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((WorldListener) list[i + 1]).onWorld(canvas);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.WORLD, e);
            }
        }
    }

    /** Fires {@link Events#CLICK}. */
    public void fireClick(int button) {
        Object[] list = lists[Events.CLICK.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((ClickListener) list[i + 1]).onClick(button);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.CLICK, e);
            }
        }
    }

    /** Fires {@link Events#ATTACK}. */
    public void fireAttack(double reach) {
        Object[] list = lists[Events.ATTACK.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((AttackListener) list[i + 1]).onAttack(reach);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.ATTACK, e);
            }
        }
    }

    /** Fires {@link Events#CROSSHAIR}. */
    public void fireCrosshair(Crosshair crosshair) {
        Object[] list = lists[Events.CROSSHAIR.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((CrosshairListener) list[i + 1]).onCrosshair(crosshair);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.CROSSHAIR, e);
            }
        }
    }

    /** Fires {@link Events#TURN}. */
    public void fireTurn(Turn turn) {
        Object[] list = lists[Events.TURN.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((TurnListener) list[i + 1]).onTurn(turn);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.TURN, e);
            }
        }
    }

    /** Fires {@link Events#CAMERA}. */
    public void fireCamera(CameraAngles camera) {
        Object[] list = lists[Events.CAMERA.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((CameraListener) list[i + 1]).onCamera(camera);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.CAMERA, e);
            }
        }
    }

    /** Fires {@link Events#HELD_ITEM}. */
    public void fireHeldItem(HeldItem item) {
        Object[] list = lists[Events.HELD_ITEM.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((HeldItemListener) list[i + 1]).onHeldItem(item);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.HELD_ITEM, e);
            }
        }
    }

    /** Fires {@link Events#CONTAINER_TOOLTIP}. */
    public void fireContainerTooltip(ContainerTooltip tooltip) {
        Object[] list = lists[Events.CONTAINER_TOOLTIP.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((ContainerTooltipListener) list[i + 1]).onContainerTooltip(tooltip);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.CONTAINER_TOOLTIP, e);
            }
        }
    }

    /** Fires {@link Events#SCREENSHOT}. */
    public void fireScreenshot(String fileName) {
        Object[] list = lists[Events.SCREENSHOT.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((ScreenshotListener) list[i + 1]).onScreenshot(fileName);
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.SCREENSHOT, e);
            }
        }
    }

    /** Fires {@link Events#KILL}. */
    public void fireKill() {
        Object[] list = lists[Events.KILL.index];
        for (int i = 0; i < list.length; i += 2) {
            try {
                ((KillListener) list[i + 1]).onKill();
            } catch (RuntimeException e) {
                failures.failed(list[i], Events.KILL, e);
            }
        }
    }
}
