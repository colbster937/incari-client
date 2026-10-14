package com.mojang.blaze3d.audio;

import static org.assertj.core.api.Assertions.assertThat;

import incari.platform.api.audio.AudioDeviceList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeviceTrackingTest {

    @Test
    void copiesThePlatformsList() {
        DeviceList list = DeviceList.from(new AudioDeviceList("B", List.of("A", "B")));
        assertThat(list).isEqualTo(new DeviceList("B", List.of("A", "B")));
        assertThat(DeviceList.from(AudioDeviceList.EMPTY)).isEqualTo(DeviceList.EMPTY);
    }

    @Test
    void aPlatformNoticeRequestsAnUpdate() {
        FakeAudio audio = new FakeAudio();
        CallbackDeviceTracker tracker = CallbackDeviceTracker.createAndInstall(audio, DeviceList.EMPTY);
        assertThat(tracker.isUpdateRequested()).isFalse();

        Runnable listener = audio.deviceChangeListener;
        assertThat(listener).isNotNull();
        listener.run();
        assertThat(tracker.isUpdateRequested()).isTrue();

        tracker.discardUpdateRequest();
        assertThat(tracker.isUpdateRequested()).isFalse();
    }
}
