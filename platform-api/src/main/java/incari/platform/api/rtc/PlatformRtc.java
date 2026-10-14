package incari.platform.api.rtc;

import java.util.List;

/**
 * WebRTC, where the platform has it (ADR 0019): the browser. A platform without it says so
 * with {@link #supported}; the rest of the interface then throws.
 */
public interface PlatformRtc {

    /** Whether peer connections work here. */
    boolean supported();

    /** The microphone. */
    Microphone microphone();

    /** Starts a peer connection that reaches the other side through {@code iceServers}. */
    RtcPeer createPeer(List<IceServer> iceServers, RtcPeer.Listener listener);

    /** WebRTC for a platform that has none. */
    PlatformRtc UNSUPPORTED = new PlatformRtc() {
        @Override
        public boolean supported() {
            return false;
        }

        @Override
        public Microphone microphone() {
            throw new UnsupportedOperationException("No WebRTC on this platform");
        }

        @Override
        public RtcPeer createPeer(List<IceServer> iceServers, RtcPeer.Listener listener) {
            throw new UnsupportedOperationException("No WebRTC on this platform");
        }
    };
}
