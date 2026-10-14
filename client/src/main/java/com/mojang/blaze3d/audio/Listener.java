package com.mojang.blaze3d.audio;

import incari.platform.api.audio.AudioListener;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * Incari replacement: the player's ears, forwarded to the open device's {@link AudioListener}.
 *
 * <p>The game creates this once, before any device is open, and keeps it across device changes.
 * So it remembers the transform itself, and {@link Library} points it at each new device's
 * listener as it opens.
 */
public class Listener {

    private ListenerTransform transform = ListenerTransform.INITIAL;
    private @Nullable AudioListener target;

    public void setTransform(final ListenerTransform transform) {
        this.transform = transform;
        push();
    }

    public void reset() {
        setTransform(ListenerTransform.INITIAL);
    }

    public ListenerTransform getTransform() {
        return transform;
    }

    /** Sends every later transform to {@code target}, starting with the current one; null for no device. */
    void attach(final @Nullable AudioListener target) {
        this.target = target;
        push();
    }

    private void push() {
        AudioListener out = target;
        if (out == null) {
            return; // No device open: the transform is kept for the next one.
        }
        Vec3 position = transform.position();
        Vec3 forward = transform.forward();
        Vec3 up = transform.up();
        out.setPosition(position.x, position.y, position.z);
        out.setOrientation(forward.x, forward.y, forward.z, up.x, up.y, up.z);
    }
}
