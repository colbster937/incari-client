package incari.platform.api.rtc;

import incari.platform.api.net.NetConnection;

/**
 * One WebRTC peer connection. Descriptions (offers and answers) are JSON objects with {@code type}
 * and {@code sdp}, as the browser writes them; the signalling that carries them (a game server, a
 * relay) is the caller's. Events reach the {@link Listener} on the game's thread, once a frame.
 */
public interface RtcPeer {

    /** Sends the microphone's audio to this peer (voice chat). Call before {@link #createOffer}. */
    void sendMicrophone();

    /**
     * Opens a data channel to the peer, carrying binary messages in order. Call before {@link
     * #createOffer}. The listener hears {@link NetConnection.Listener#connected} when it opens.
     */
    NetConnection openDataChannel(String label, NetConnection.Listener listener);

    /** Makes an offer; {@link Listener#localDescription} hears it. */
    void createOffer();

    /** The peer's description. An offer is answered at once ({@link Listener#localDescription}). */
    void setRemoteDescription(String json);

    /** One of the peer's ICE candidates. */
    void addIceCandidate(String candidate, int sdpMLineIndex);

    /** The peer's voice, once {@link Listener#remoteAudio} has come; silent before. */
    RemoteVoice voice();

    /** Closes the connection; the listener hears nothing more. */
    void close();

    /** Hears a peer connection's events, on the game's thread. */
    interface Listener {

        /** This side's offer or answer, for the peer. */
        void localDescription(String json);

        /** One of this side's ICE candidates, for the peer. */
        void iceCandidate(String candidate, int sdpMLineIndex);

        /** This side has no more ICE candidates. */
        default void iceGatheringComplete() {}

        /** The peer's audio arrived; {@link RtcPeer#voice} plays it. */
        default void remoteAudio() {}

        /** The connection's state changed: "connecting", "connected", "disconnected", "failed", "closed". */
        default void stateChanged(String state) {}

        /**
         * The peer opened a data channel called {@code label} (a shared world's guest, ADR 0062):
         * {@link IncomingChannel#accept} takes it. One not accepted here is closed.
         */
        default void dataChannel(String label, IncomingChannel channel) {}
    }

    /** A data channel the peer opened, waiting to be taken. */
    interface IncomingChannel {

        /**
         * Takes the channel: {@code listener} hears it as {@link #openDataChannel}'s listener would,
         * its opening (at once, if it's open already) and its messages, none lost before this.
         */
        NetConnection accept(NetConnection.Listener listener);
    }
}
