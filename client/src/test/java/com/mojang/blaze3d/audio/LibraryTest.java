package com.mojang.blaze3d.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class LibraryTest {

    @Test
    void splitsSourcesBetweenThePools() {
        // OpenAL Soft's usual 255 mono sources.
        assertThat(Library.streamingPoolSize(255)).isEqualTo(8);
        assertThat(Library.staticPoolSize(255, 8)).isEqualTo(247);
        // The fallback count when a device doesn't say.
        assertThat(Library.streamingPoolSize(30)).isEqualTo(5);
        assertThat(Library.staticPoolSize(30, 5)).isEqualTo(25);
        // Floors and ceilings.
        assertThat(Library.streamingPoolSize(1)).isEqualTo(2);
        assertThat(Library.staticPoolSize(1, 2)).isEqualTo(8);
        assertThat(Library.streamingPoolSize(10_000)).isEqualTo(8);
        assertThat(Library.staticPoolSize(10_000, 8)).isEqualTo(255);
    }

    @Test
    void opensThePreferredDeviceFirst() {
        FakeAudio audio = new FakeAudio();
        assertThat(Library.openWithFallback(audio, "Headset", "Speakers", false).name())
                .isEqualTo("Headset");
        assertThat(audio.openAttempts).containsExactly("Headset");
    }

    @Test
    void fallsBackToTheSystemDefaultThenThePlatformDefault() {
        FakeAudio audio = new FakeAudio();
        audio.unopenable = Set.of("Headset", "Speakers");
        assertThat(Library.openWithFallback(audio, "Headset", "Speakers", false).name())
                .isEqualTo("Fake default");
        assertThat(audio.openAttempts).containsExactly("Headset", "Speakers", null);
    }

    @Test
    void doesntTryTheSameDeviceTwice() {
        FakeAudio audio = new FakeAudio();
        audio.unopenable = Set.of("Speakers");
        Library.openWithFallback(audio, "Speakers", "Speakers", false);
        assertThat(audio.openAttempts).containsExactly("Speakers", null);
    }

    @Test
    void failsWhenNothingOpens() {
        FakeAudio audio = new FakeAudio();
        audio.unopenable = Set.of("Speakers", "Fake default");
        assertThatThrownBy(() -> Library.openWithFallback(audio, null, "Speakers", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void handsOutChannelsUpToEachPoolsLimit() {
        FakeAudio audio = new FakeAudio();
        audio.maxSources = 30;
        Library library = new Library();
        library.init(audio, null, DeviceList.EMPTY, false);
        assertThat(library.currentDeviceName()).isEqualTo("Fake default");

        List<Channel> streaming = new ArrayList<>();
        Channel channel;
        while ((channel = library.acquireChannel(Library.Pool.STREAMING)) != null) {
            streaming.add(channel);
        }
        assertThat(streaming).hasSize(5);
        assertThat(library.getChannelDebugString()).isEqualTo("Sounds: 0/25 + 5/5");

        // The static pool is untouched by a full streaming pool.
        Channel single = library.acquireChannel(Library.Pool.STATIC);
        assertThat(single).isNotNull();
        assertThat(library.getChannelDebugString()).isEqualTo("Sounds: 1/25 + 5/5");

        library.releaseChannel(streaming.getFirst());
        assertThat(library.getChannelDebugString()).isEqualTo("Sounds: 1/25 + 4/5");
        assertThat(audio.opened.getFirst().openSources()).isEqualTo(5);
        assertThatThrownBy(() -> library.releaseChannel(streaming.getFirst()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cleanupFreesEverythingAndClosesTheDevice() {
        FakeAudio audio = new FakeAudio();
        Library library = new Library();
        library.init(audio, null, DeviceList.EMPTY, false);
        library.acquireChannel(Library.Pool.STATIC);
        library.acquireChannel(Library.Pool.STREAMING);

        library.cleanup();

        FakeAudio.Device device = audio.opened.getFirst();
        assertThat(device.closed).isTrue();
        assertThat(device.openSources()).isZero();
        assertThat(library.acquireChannel(Library.Pool.STATIC)).isNull();
        assertThat(library.isCurrentDeviceDisconnected()).isFalse();
    }

    @Test
    void cleanupWithNothingOpenIsHarmless() {
        new Library().cleanup();
    }

    @Test
    void theListenerFollowsTheDeviceOpenedAfterIt() {
        FakeAudio audio = new FakeAudio();
        Library library = new Library();
        // The game moves the listener before any device is open.
        library.getListener()
                .setTransform(new ListenerTransform(new Vec3(1, 2, 3), new Vec3(1, 0, 0), new Vec3(0, 1, 0)));
        library.init(audio, null, DeviceList.EMPTY, false);

        FakeAudio.Listener target = audio.opened.getFirst().listener;
        assertThat(target.position).containsExactly(1, 2, 3);
        assertThat(target.orientation).containsExactly(1, 0, 0, 0, 1, 0);

        library.getListener().reset();
        assertThat(target.position).containsExactly(0, 0, 0);
        assertThat(target.orientation).containsExactly(0, 0, -1, 0, 1, 0);
    }
}
