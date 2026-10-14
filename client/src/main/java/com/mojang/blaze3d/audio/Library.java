package com.mojang.blaze3d.audio;

import com.mojang.logging.LogUtils;
import incari.platform.api.Platforms;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.PlatformAudio;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.SharedConstants;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Incari replacement: the sound engine's output, over the platform's {@link PlatformAudio}.
 *
 * <p>It owns the open device and hands out {@link Channel}s from two pools, so that a burst of
 * short sounds can never take the sources music and records need: most sources go to
 * {@link Pool#STATIC} sounds, and a few (about the square root of the total) are kept for
 * {@link Pool#STREAMING} ones.
 */
public class Library {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** What {@link #currentDeviceName} reports while nothing is open. */
    private static final String NO_DEVICE_NAME = "(None)";

    private static final int MIN_STREAMING = 2;
    private static final int MAX_STREAMING = 8;
    private static final int MIN_STATIC = 8;
    private static final int MAX_STATIC = 255;

    private @Nullable AudioDevice device;
    private String currentDeviceName = NO_DEVICE_NAME;
    private ChannelPool staticChannels = ChannelPool.NONE;
    private ChannelPool streamingChannels = ChannelPool.NONE;
    private final Listener listener = new Listener();

    /**
     * Opens a device: the player's choice, else the system default the game last saw, else
     * whatever the platform picks.
     *
     * @param preferredDevice the device from the options, or null to use the default
     * @param currentDevices the devices as the game last listed them
     * @param useHrtf whether to spatialize with HRTF ("Directional Audio")
     * @throws IllegalStateException if no device can be opened
     */
    public void init(final @Nullable String preferredDevice, final DeviceList currentDevices, final boolean useHrtf) {
        init(Platforms.current().audio(), preferredDevice, currentDevices, useHrtf);
    }

    void init(
            final PlatformAudio audio,
            final @Nullable String preferredDevice,
            final DeviceList currentDevices,
            final boolean useHrtf) {
        currentDeviceName = NO_DEVICE_NAME;
        AudioDevice opened = openWithFallback(audio, preferredDevice, currentDevices.defaultDevice(), useHrtf);
        device = opened;
        currentDeviceName = opened.name();

        int total = opened.maxSources();
        int streaming = streamingPoolSize(total);
        staticChannels = new ChannelPool(opened, staticPoolSize(total, streaming));
        streamingChannels = new ChannelPool(opened, streaming);
        listener.attach(opened.listener());
        LOGGER.info("Sound output opened on device {}", currentDeviceName);
    }

    /** Sources kept for streams: the square root of the total, between 2 and 8. */
    static int streamingPoolSize(final int totalSources) {
        int root = (int) Math.sqrt(totalSources);
        return Math.clamp(root, MIN_STREAMING, MAX_STREAMING);
    }

    /**
     * Sources for everything else: the rest, between 8 and 255. The floor can promise more than a
     * tiny device has; then acquiring just fails early, as the device runs out.
     */
    static int staticPoolSize(final int totalSources, final int streamingSources) {
        return Math.clamp(totalSources - streamingSources, MIN_STATIC, MAX_STATIC);
    }

    /**
     * Tries each candidate in turn, skipping repeats, and returns the first that opens. A failed
     * one is only logged: an unplugged favourite shouldn't leave the game silent.
     */
    static AudioDevice openWithFallback(
            final PlatformAudio audio,
            final @Nullable String preferredDevice,
            final @Nullable String systemDefault,
            final boolean useHrtf) {
        List<@Nullable String> candidates = new ArrayList<>(3);
        if (preferredDevice != null) {
            candidates.add(preferredDevice);
        }
        if (systemDefault != null && !systemDefault.equals(preferredDevice)) {
            candidates.add(systemDefault);
        }
        candidates.add(null); // The platform's own default.

        @Nullable AudioException lastFailure = null;
        for (String candidate : candidates) {
            try {
                return audio.open(candidate, useHrtf);
            } catch (AudioException e) {
                LOGGER.warn(
                        "Couldn't open sound device {}: {}",
                        candidate == null ? "(default)" : candidate,
                        e.getMessage());
                lastFailure = e;
            }
        }
        throw new IllegalStateException("Failed to open a sound device", lastFailure);
    }

    public @Nullable String currentDeviceName() {
        return currentDeviceName;
    }

    /** Frees every channel and closes the device. Safe to call with nothing open. */
    public void cleanup() {
        staticChannels.cleanup();
        streamingChannels.cleanup();
        // Nothing more can be handed out until the next init: the device is going away.
        staticChannels = ChannelPool.NONE;
        streamingChannels = ChannelPool.NONE;
        listener.attach(null);
        AudioDevice open = device;
        if (open != null) {
            open.close();
        }
        device = null;
    }

    public Listener getListener() {
        return listener;
    }

    /** A channel from {@code pool}, or null if the pool or the device is out of sources. */
    public @Nullable Channel acquireChannel(final Pool pool) {
        return (pool == Pool.STREAMING ? streamingChannels : staticChannels).acquire();
    }

    /**
     * Destroys a channel and returns its slot to its pool.
     *
     * @throws IllegalStateException if it didn't come from this library's current pools
     */
    public void releaseChannel(final Channel channel) {
        if (!staticChannels.release(channel) && !streamingChannels.release(channel)) {
            throw new IllegalStateException("Tried to release unknown channel");
        }
    }

    /** The "Sounds:" line of the debug screen: used/size of the static then the streaming pool. */
    public String getChannelDebugString() {
        return String.format(
                Locale.ROOT,
                "Sounds: %d/%d + %d/%d",
                staticChannels.usedCount(),
                staticChannels.limit(),
                streamingChannels.usedCount(),
                streamingChannels.limit());
    }

    public boolean isCurrentDeviceDisconnected() {
        AudioDevice open = device;
        return open != null && open.isDisconnected();
    }

    /**
     * A tracker for the device list: pushed changes when the platform announces them, polling
     * otherwise.
     */
    public static DeviceTracker createDeviceTracker() {
        PlatformAudio audio = Platforms.current().audio();
        DeviceList devices = DeviceList.from(audio.devices());
        if (audio.reportsDeviceChanges()) {
            LOGGER.debug("Tracking audio device changes through platform notifications");
            return CallbackDeviceTracker.createAndInstall(audio, devices);
        }
        LOGGER.debug("Tracking audio device changes by polling");
        return new PollingDeviceTracker(devices);
    }

    /** A capped set of live channels on one device. */
    static final class ChannelPool {

        /** Before the first device opens and after it closes: hands out nothing. */
        static final ChannelPool NONE = new ChannelPool(null, 0);

        private final @Nullable AudioDevice device;
        private final int limit;
        private final Set<Channel> active = Collections.newSetFromMap(new IdentityHashMap<>());

        ChannelPool(final @Nullable AudioDevice device, final int limit) {
            this.device = device;
            this.limit = limit;
        }

        @Nullable
        Channel acquire() {
            AudioDevice target = device;
            if (target == null) {
                return null;
            }
            if (active.size() >= limit) {
                if (SharedConstants.IS_RUNNING_IN_IDE) {
                    LOGGER.warn("Maximum sound pool size {} reached", limit);
                }
                return null;
            }
            Channel channel = Channel.create(target);
            if (channel != null) {
                active.add(channel);
            }
            return channel;
        }

        boolean release(final Channel channel) {
            if (!active.remove(channel)) {
                return false;
            }
            channel.destroy();
            return true;
        }

        void cleanup() {
            active.forEach(Channel::destroy);
            active.clear();
        }

        int limit() {
            return limit;
        }

        int usedCount() {
            return active.size();
        }
    }

    public enum Pool {
        STATIC,
        STREAMING
    }
}
