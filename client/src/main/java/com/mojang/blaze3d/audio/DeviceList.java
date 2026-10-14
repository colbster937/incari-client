package com.mojang.blaze3d.audio;

import incari.platform.api.Platforms;
import incari.platform.api.audio.AudioDeviceList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Incari replacement: the output devices the platform offers, in the game's own type. The options
 * screen lists {@link #allDevices}; the sound engine compares snapshots to notice changes, which
 * is why this stays a record (value equality).
 */
public record DeviceList(@Nullable String defaultDevice, List<String> allDevices) {

    public static final DeviceList EMPTY = new DeviceList(null, List.of());

    /** Asks the platform now. May be slow on the desktop (it probes the system), so the game calls it off the render thread. */
    public static DeviceList query() {
        return from(Platforms.current().audio().devices());
    }

    static DeviceList from(final AudioDeviceList devices) {
        return new DeviceList(devices.defaultDevice(), devices.all());
    }
}
