package com.mojang.blaze3d.audio;

import incari.platform.api.audio.AudioBuffer;
import incari.platform.api.audio.AudioDevice;
import incari.platform.api.audio.AudioDeviceList;
import incari.platform.api.audio.AudioException;
import incari.platform.api.audio.AudioListener;
import incari.platform.api.audio.AudioSource;
import incari.platform.api.audio.DecodedAudio;
import incari.platform.api.audio.PcmFormat;
import incari.platform.api.audio.PlatformAudio;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** A sound system in memory: records what the game asks of it, plays nothing. */
final class FakeAudio implements PlatformAudio {

    AudioDeviceList devices = AudioDeviceList.EMPTY;
    Set<String> unopenable = Set.of();
    int maxSources = 30;
    final List<@Nullable String> openAttempts = new ArrayList<>();
    final List<Device> opened = new ArrayList<>();

    @Nullable
    Runnable deviceChangeListener;

    @Override
    public AudioDeviceList devices() {
        return devices;
    }

    @Override
    public boolean reportsDeviceChanges() {
        return true;
    }

    @Override
    public void setDeviceChangeListener(@Nullable Runnable listener) {
        deviceChangeListener = listener;
    }

    @Override
    public AudioDevice open(@Nullable String deviceName, boolean spatialize) {
        openAttempts.add(deviceName);
        String name = deviceName == null ? "Fake default" : deviceName;
        if (unopenable.contains(name)) {
            throw new AudioException("unplugged: " + name);
        }
        Device device = new Device(name, maxSources);
        opened.add(device);
        return device;
    }

    static final class Device implements AudioDevice {
        final String name;
        final int maxSources;
        final List<Source> sources = new ArrayList<>();
        final List<Buffer> buffers = new ArrayList<>();
        final Listener listener = new Listener();
        boolean closed;
        boolean failUploads;
        boolean streams;

        Device(String name, int maxSources) {
            this.name = name;
            this.maxSources = maxSources;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean isDisconnected() {
            return false;
        }

        @Override
        public int maxSources() {
            return maxSources;
        }

        @Override
        public AudioListener listener() {
            return listener;
        }

        long openSources() {
            return sources.stream().filter(s -> !s.closed).count();
        }

        @Override
        public @Nullable AudioSource createSource() {
            if (openSources() >= maxSources) {
                return null;
            }
            Source source = new Source();
            sources.add(source);
            return source;
        }

        @Override
        public AudioBuffer createBuffer(ByteBuffer samples, PcmFormat format) {
            if (failUploads) {
                throw new AudioException("upload failed");
            }
            Buffer buffer = new Buffer(format, samples.remaining());
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public AudioBuffer createBuffer(DecodedAudio audio) {
            if (failUploads) {
                throw new AudioException("upload failed");
            }
            Buffer buffer = new Buffer(new PcmFormat(audio.sampleRate(), audio.channels(), 16), audio.sizeInBytes());
            buffers.add(buffer);
            return buffer;
        }

        @Override
        public boolean streamsOggVorbis() {
            return streams;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static final class Listener implements AudioListener {
        double[] position = new double[3];
        double[] orientation = new double[6];

        @Override
        public void setPosition(double x, double y, double z) {
            position = new double[] {x, y, z};
        }

        @Override
        public void setOrientation(
                double forwardX, double forwardY, double forwardZ, double upX, double upY, double upZ) {
            orientation = new double[] {forwardX, forwardY, forwardZ, upX, upY, upZ};
        }
    }

    static final class Source implements AudioSource {
        State state = State.INITIAL;

        @Nullable
        AudioBuffer buffer;

        final ArrayDeque<AudioBuffer> queue = new ArrayDeque<>();
        int processed;
        boolean closed;
        float gain = 1;

        @Nullable
        Float maxDistance;

        @Override
        public void play() {
            state = State.PLAYING;
        }

        @Override
        public void pause() {
            state = State.PAUSED;
        }

        @Override
        public void stop() {
            state = State.STOPPED;
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void setPosition(double x, double y, double z) {}

        @Override
        public void setPitch(float pitch) {}

        @Override
        public void setLooping(boolean looping) {}

        @Override
        public void setGain(float gain) {
            this.gain = gain;
        }

        @Override
        public void setRelative(boolean relative) {}

        @Override
        public void disableAttenuation() {
            maxDistance = null;
        }

        @Override
        public void setLinearAttenuation(float maxDistance) {
            this.maxDistance = maxDistance;
        }

        @Override
        public void setBuffer(AudioBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void queue(AudioBuffer buffer) {
            queue.addLast(buffer);
        }

        byte @Nullable [] streamed;
        boolean streamLooping;

        @Override
        public void streamOggVorbis(byte[] file, boolean looping) {
            streamed = file;
            streamLooping = looping;
        }

        @Override
        public int releaseProcessedBuffers() {
            int released = Math.min(processed, queue.size());
            for (int i = 0; i < released; i++) {
                queue.removeFirst().close();
            }
            processed = 0;
            return released;
        }

        @Override
        public void close() {
            closed = true;
            queue.forEach(AudioBuffer::close);
            queue.clear();
        }
    }

    static final class Buffer implements AudioBuffer {
        final PcmFormat format;
        final int size;
        boolean closed;

        Buffer(PcmFormat format, int size) {
            this.format = format;
            this.size = size;
        }

        @Override
        public PcmFormat format() {
            return format;
        }

        @Override
        public int sizeInBytes() {
            return size;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
