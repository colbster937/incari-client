package com.mojang.blaze3d.audio;

import incari.platform.api.Platforms;
import incari.platform.api.audio.PlatformAudio;

/**
 * Incari replacement: tracks device changes the platform announces, instead of polling. The
 * platform's notice only raises a flag; the list itself is re-read on the game's next tick, as
 * {@link AbstractDeviceTracker} does for every tracker.
 */
public class CallbackDeviceTracker extends AbstractDeviceTracker {

    /** Written by the platform's notification thread, read and cleared by the game's tick. */
    private volatile boolean updateRequested;

    public CallbackDeviceTracker(final DeviceList deviceList) {
        super(deviceList);
    }

    @Override
    protected boolean isUpdateRequested() {
        return updateRequested;
    }

    @Override
    protected void discardUpdateRequest() {
        updateRequested = false;
    }

    /** Whether the running platform announces device changes. */
    public static boolean isSupported() {
        return Platforms.current().audio().reportsDeviceChanges();
    }

    /** A tracker that the running platform's announcements flag for an update. */
    public static CallbackDeviceTracker createAndInstall(final DeviceList deviceList) {
        return createAndInstall(Platforms.current().audio(), deviceList);
    }

    static CallbackDeviceTracker createAndInstall(final PlatformAudio audio, final DeviceList deviceList) {
        CallbackDeviceTracker tracker = new CallbackDeviceTracker(deviceList);
        audio.setDeviceChangeListener(tracker::requestUpdate);
        return tracker;
    }

    // Must not block or touch the game: it runs on the platform's notification thread.
    private void requestUpdate() {
        updateRequested = true;
    }
}
