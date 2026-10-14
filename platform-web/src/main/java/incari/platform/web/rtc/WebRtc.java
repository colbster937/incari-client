package incari.platform.web.rtc;

import incari.platform.api.net.NetConnection;
import incari.platform.api.rtc.IceServer;
import incari.platform.api.rtc.Microphone;
import incari.platform.api.rtc.PlatformRtc;
import incari.platform.api.rtc.RemoteVoice;
import incari.platform.api.rtc.RtcPeer;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.ArrayBuffer;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * WebRTC in the browser (ADR 0019): peer connections for Eagler voice and for worlds shared
 * through a relay, and the microphone.
 *
 * <p>As with sockets, the browser's callbacks never call Java: each peer, data channel and the
 * microphone queue what happened in a JavaScript array, and {@link #deliver} hands it to the
 * listeners once a frame, on the game's thread.
 *
 * <p>Voices play through the game's own audio context (ADR 0013), each through a gain and a
 * stereo panner the caller sets from where the speaker stands, with an analyser for whether they're
 * talking. The microphone goes through a gain into one stream, whose track every voice peer sends;
 * each peer gets an audio transceiver when it's made, so a microphone that opens later (the
 * permission prompt) is swapped in without renegotiating.
 */
public final class WebRtc implements PlatformRtc {

    private final List<Peer> peers = new ArrayList<>();
    private final List<Channel> channels = new ArrayList<>();
    private final WebMicrophone microphone = new WebMicrophone();
    private boolean checked;
    private boolean supported;

    @Override
    public boolean supported() {
        if (!checked) {
            checked = true;
            supported = hasWebRtc();
            if (supported) {
                installHelpers();
            }
        }
        return supported;
    }

    @Override
    public Microphone microphone() {
        requireSupport();
        return microphone;
    }

    @Override
    public RtcPeer createPeer(List<IceServer> iceServers, RtcPeer.Listener listener) {
        requireSupport();
        StringBuilder json = new StringBuilder("[");
        for (IceServer server : iceServers) {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append("{\"urls\":").append(quote(server.url()));
            if (server.username() != null && server.credential() != null) {
                json.append(",\"username\":").append(quote(server.username()));
                json.append(",\"credential\":").append(quote(server.credential()));
            }
            json.append('}');
        }
        json.append(']');
        Peer peer = new Peer(newPeer(json.toString()), listener);
        peers.add(peer);
        return peer;
    }

    /** Delivers everything the page queued for peers, channels and the microphone. Game thread. */
    public void deliver() {
        if (!supported) {
            return;
        }
        microphone.update();
        if (!peers.isEmpty()) {
            for (Peer peer : new ArrayList<>(peers)) {
                peer.deliver();
                if (peer.closed) {
                    peers.remove(peer);
                }
            }
        }
        if (!channels.isEmpty()) {
            for (Channel channel : new ArrayList<>(channels)) {
                channel.deliver();
                if (channel.finished) {
                    channels.remove(channel);
                }
            }
        }
    }

    private void requireSupport() {
        if (!supported()) {
            throw new UnsupportedOperationException("This browser has no WebRTC");
        }
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\');
            }
            out.append(c < 0x20 ? ' ' : c);
        }
        return out.append('"').toString();
    }

    // --- Peers -------------------------------------------------------------------------------

    private final class Peer implements RtcPeer, RemoteVoice {

        private final JSObject handle;
        private final RtcPeer.Listener listener;
        private boolean closed;

        Peer(JSObject handle, RtcPeer.Listener listener) {
            this.handle = handle;
            this.listener = listener;
        }

        @Override
        public void sendMicrophone() {
            if (!closed) {
                addMicrophone(handle);
            }
        }

        @Override
        public NetConnection openDataChannel(String label, NetConnection.Listener channelListener) {
            Channel channel = new Channel(newDataChannel(handle, label), label, channelListener);
            channels.add(channel);
            return channel;
        }

        @Override
        public void createOffer() {
            if (!closed) {
                offer(handle);
            }
        }

        @Override
        public void setRemoteDescription(String json) {
            if (!closed) {
                remoteDescription(handle, json);
            }
        }

        @Override
        public void addIceCandidate(String candidate, int sdpMLineIndex) {
            if (!closed) {
                iceCandidate(handle, candidate, sdpMLineIndex);
            }
        }

        @Override
        public RemoteVoice voice() {
            return this;
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closePeer(handle);
            }
        }

        @Override
        public void setVolume(float volume) {
            if (!closed) {
                voiceVolume(handle, volume);
            }
        }

        @Override
        public void setPan(float pan) {
            if (!closed) {
                voicePan(handle, Math.max(-1, Math.min(1, pan)));
            }
        }

        @Override
        public float level() {
            return closed ? 0 : (float) voiceLevel(handle);
        }

        void deliver() {
            int count = queued(handle);
            for (int i = 0; i < count && !closed; i++) {
                switch (queuedType(handle, i)) {
                    case "desc" -> listener.localDescription(queuedString(handle, i, 1));
                    case "ice" -> listener.iceCandidate(queuedString(handle, i, 1), queuedInt(handle, i, 2));
                    case "iceDone" -> listener.iceGatheringComplete();
                    case "audio" -> listener.remoteAudio();
                    case "state" -> listener.stateChanged(queuedString(handle, i, 1));
                    case "error" -> listener.stateChanged("failed");
                    case "channel" -> incoming(queuedString(handle, i, 1), queuedObject(handle, i, 2));
                    default -> {}
                }
            }
            dropQueued(handle, count);
        }

        /** A data channel the peer opened: the listener's to take, else closed. */
        private void incoming(String label, JSObject dataChannel) {
            boolean[] taken = {false};
            listener.dataChannel(label, channelListener -> {
                taken[0] = true;
                Channel channel = new Channel(dataChannel, label, channelListener);
                channels.add(channel);
                return channel;
            });
            if (!taken[0]) {
                channelClose(dataChannel);
            }
        }
    }

    // --- Data channels -----------------------------------------------------------------------

    private static final class Channel implements NetConnection {

        private final JSObject handle;
        private final String label;
        private final NetConnection.Listener listener;
        private boolean open;
        private boolean closedByUs;
        private boolean finished;

        Channel(JSObject handle, String label, NetConnection.Listener listener) {
            this.handle = handle;
            this.label = label;
            this.listener = listener;
        }

        @Override
        public void send(ByteBuffer data) {
            if (!open || closedByUs) {
                return;
            }
            byte[] bytes = new byte[data.remaining()];
            data.get(bytes);
            channelSend(handle, Int8Array.copyFromJavaArray(bytes));
        }

        @Override
        public void close() {
            if (!closedByUs && !finished) {
                closedByUs = true;
                open = false;
                channelClose(handle);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public String remoteDescription() {
            return "WebRTC data channel '" + label + "'";
        }

        void deliver() {
            if (finished) {
                return;
            }
            int count = queued(handle);
            for (int i = 0; i < count && !finished; i++) {
                switch (queuedType(handle, i)) {
                    case "open" -> {
                        if (!closedByUs) {
                            open = true;
                            listener.connected();
                        }
                    }
                    case "message" -> {
                        if (!closedByUs) {
                            byte[] message = new Int8Array(queuedData(handle, i)).copyToJavaArray();
                            listener.received(ByteBuffer.wrap(message));
                        }
                    }
                    case "close" -> finish(closedByUs ? null : new IOException("The data channel closed"));
                    default -> {}
                }
            }
            dropQueued(handle, count);
            if (closedByUs && !finished) {
                finish(null);
            }
        }

        private void finish(@Nullable Throwable cause) {
            finished = true;
            open = false;
            listener.closed(cause);
        }
    }

    // --- The microphone ----------------------------------------------------------------------

    private static final class WebMicrophone implements Microphone {

        private State state = State.CLOSED;

        void update() {
            if (state == State.OPENING) {
                String now = micState();
                if (now.equals("open")) {
                    state = State.OPEN;
                } else if (now.equals("unavailable")) {
                    state = State.UNAVAILABLE;
                }
            }
        }

        @Override
        public State state() {
            return state;
        }

        @Override
        public void open() {
            if (state == State.CLOSED) {
                state = State.OPENING;
                openMic();
            }
        }

        @Override
        public void close() {
            if (state != State.CLOSED) {
                state = State.CLOSED;
                closeMic();
            }
        }

        @Override
        public void setTransmitting(boolean transmitting) {
            micTransmitting(transmitting);
        }

        @Override
        public void setVolume(float volume) {
            micVolume(volume);
        }

        @Override
        public float level() {
            return (float) micLevel();
        }
    }

    // --- The page side -----------------------------------------------------------------------

    @JSBody(
            script = "return typeof RTCPeerConnection !== 'undefined' && !!navigator.mediaDevices"
                    + " && typeof navigator.mediaDevices.getUserMedia === 'function';")
    private static native boolean hasWebRtc();

    /*
     * globalThis.incariRtc: the audio context voices play through (the game's), the microphone's
     * graph, and the level of a node from its analyser. Peers are plain objects {pc, q, ...};
     * q holds queued events as arrays: ["desc", json], ["ice", candidate, index], ["iceDone"],
     * ["audio"], ["state", state], ["error", message]. Data channels queue ["open"],
     * ["message", ArrayBuffer], ["close"].
     */
    @JSBody(
            script = "var r = globalThis.incariRtc = { mic: { state: 'closed', track: null, gain: null,"
                    + "  analyser: null, transmitting: false, volume: 1, peers: [] } };"
                    + "r.context = function() { var a = globalThis.incariAudio;"
                    + "  if (a && a.context) return a.context;"
                    + "  if (!r.ownContext) { var C = window.AudioContext || window.webkitAudioContext; r.ownContext = new C(); }"
                    + "  return r.ownContext; };"
                    + "r.level = function(analyser) { if (!analyser) return 0;"
                    + "  var data = r.levelData || (r.levelData = new Float32Array(256));"
                    + "  analyser.getFloatTimeDomainData(data); var sum = 0;"
                    + "  for (var i = 0; i < data.length; i++) sum += data[i] * data[i];"
                    + "  return Math.min(1, Math.sqrt(sum / data.length) * 3); };"
                    + "r.attachMic = function(p) { if (p.transceiver && r.mic.track)"
                    + "  p.transceiver.sender.replaceTrack(r.mic.track).catch(function() {}); };")
    private static native void installHelpers();

    /**
     * A peer connection. Its voice plays through a muted media element as well as Web Audio:
     * Chrome feeds a remote stream to Web Audio only while a media element plays it.
     */
    @JSBody(
            params = "iceJson",
            script = "var r = globalThis.incariRtc;"
                    + "var pc = new RTCPeerConnection({ iceServers: JSON.parse(iceJson) });"
                    + "var p = { pc: pc, q: [], pendingIce: [], remoteSet: false, transceiver: null, wantsMic: false,"
                    + "  gain: null, pan: null, analyser: null, audio: null, volume: 0, panValue: 0 };"
                    + "pc.onicecandidate = function(e) { if (e.candidate) p.q.push(['ice', e.candidate.candidate,"
                    + "  e.candidate.sdpMLineIndex | 0]); else p.q.push(['iceDone']); };"
                    + "pc.onconnectionstatechange = function() { p.q.push(['state', pc.connectionState]); };"
                    + "pc.ondatachannel = function(e) { var dc = e.channel; dc.binaryType = 'arraybuffer'; dc.q = [];"
                    + "  dc.onopen = function() { dc.q.push(['open']); };"
                    + "  dc.onmessage = function(ev) { dc.q.push(['message', ev.data]); };"
                    + "  dc.onclose = function() { dc.q.push(['close']); };"
                    + "  if (dc.readyState === 'open') dc.q.push(['open']);"
                    + "  p.q.push(['channel', dc.label, dc]); };"
                    + "pc.ontrack = function(e) { if (p.gain) return;"
                    + "  var stream = e.streams && e.streams[0] ? e.streams[0] : new MediaStream([e.track]);"
                    + "  var el = new Audio(); el.muted = true; el.srcObject = stream; el.play().catch(function() {});"
                    + "  var ctx = r.context(); var src = ctx.createMediaStreamSource(stream);"
                    + "  p.analyser = ctx.createAnalyser(); p.analyser.fftSize = 256; src.connect(p.analyser);"
                    + "  p.gain = ctx.createGain(); p.gain.gain.value = p.volume;"
                    + "  p.pan = ctx.createStereoPanner(); p.pan.pan.value = p.panValue;"
                    + "  src.connect(p.gain); p.gain.connect(p.pan); p.pan.connect(ctx.destination);"
                    + "  p.audio = { el: el, src: src }; p.q.push(['audio']); };"
                    + "return p;")
    private static native JSObject newPeer(String iceJson);

    /*
     * The microphone's transceiver is made when the peer's side of the audio is: before an offer
     * this side makes, or from the one a peer's offer brings (a transceiver added here before that
     * offer wouldn't be matched to it, and nothing would be sent).
     */
    @JSBody(params = "p", script = "p.wantsMic = true;")
    private static native void addMicrophone(JSObject p);

    @JSBody(
            params = "p",
            script = "var r = globalThis.incariRtc;"
                    + "if (p.wantsMic && !p.transceiver) { p.transceiver = p.pc.addTransceiver('audio', { direction: 'sendrecv' });"
                    + "  r.mic.peers.push(p); r.attachMic(p); }"
                    + "p.pc.createOffer().then(function(d) { return p.pc.setLocalDescription(d); })"
                    + ".then(function() { p.q.push(['desc', JSON.stringify(p.pc.localDescription)]); })"
                    + ".catch(function(e) { p.q.push(['error', String(e)]); });")
    private static native void offer(JSObject p);

    @JSBody(
            params = {"p", "json"},
            script =
                    "var d; try { d = JSON.parse(json); } catch (e) { p.q.push(['error', 'bad description']); return; }"
                            + "p.pc.setRemoteDescription(d).then(function() { p.remoteSet = true;"
                            + "  for (var i = 0; i < p.pendingIce.length; i++) p.pc.addIceCandidate(p.pendingIce[i]).catch(function() {});"
                            + "  p.pendingIce = [];"
                            + "  if (d.type !== 'offer') return null;"
                            + "  if (p.wantsMic && !p.transceiver) { var ts = p.pc.getTransceivers();"
                            + "   for (var k = 0; k < ts.length; k++) if (ts[k].receiver.track && ts[k].receiver.track.kind === 'audio') {"
                            + "    p.transceiver = ts[k]; ts[k].direction = 'sendrecv'; break; }"
                            + "   if (p.transceiver) { var r = globalThis.incariRtc; r.mic.peers.push(p); r.attachMic(p); } }"
                            + "  return p.pc.createAnswer().then(function(a) { return p.pc.setLocalDescription(a); })"
                            + "   .then(function() { p.q.push(['desc', JSON.stringify(p.pc.localDescription)]); }); })"
                            + ".catch(function(e) { p.q.push(['error', String(e)]); });")
    private static native void remoteDescription(JSObject p, String json);

    /** One of the peer's candidates; any that come before its description wait for it. */
    @JSBody(
            params = {"p", "candidate", "index"},
            script = "var c = { candidate: candidate, sdpMLineIndex: index };"
                    + "if (p.remoteSet) p.pc.addIceCandidate(c).catch(function() {}); else p.pendingIce.push(c);")
    private static native void iceCandidate(JSObject p, String candidate, int index);

    @JSBody(
            params = "p",
            script = "var r = globalThis.incariRtc; try { p.pc.close(); } catch (e) {}"
                    + "if (p.gain) { try { p.pan.disconnect(); p.gain.disconnect(); p.audio.src.disconnect(); } catch (e) {}"
                    + "  p.audio.el.srcObject = null; }"
                    + "var i = r.mic.peers.indexOf(p); if (i >= 0) r.mic.peers.splice(i, 1);")
    private static native void closePeer(JSObject p);

    @JSBody(
            params = {"p", "volume"},
            script = "p.volume = volume; if (p.gain) p.gain.gain.value = volume;")
    private static native void voiceVolume(JSObject p, float volume);

    @JSBody(
            params = {"p", "pan"},
            script = "p.panValue = pan; if (p.pan) p.pan.pan.value = pan;")
    private static native void voicePan(JSObject p, float pan);

    @JSBody(params = "p", script = "return globalThis.incariRtc.level(p.analyser);")
    private static native double voiceLevel(JSObject p);

    @JSBody(
            params = {"p", "label"},
            script = "var dc = p.pc.createDataChannel(label); dc.binaryType = 'arraybuffer'; dc.q = [];"
                    + "dc.onopen = function() { dc.q.push(['open']); };"
                    + "dc.onmessage = function(e) { dc.q.push(['message', e.data]); };"
                    + "dc.onclose = function() { dc.q.push(['close']); };"
                    + "return dc;")
    private static native JSObject newDataChannel(JSObject p, String label);

    @JSBody(
            params = {"dc", "data"},
            script = "if (dc.readyState === 'open') dc.send(data);")
    private static native void channelSend(JSObject dc, Int8Array data);

    @JSBody(params = "dc", script = "try { dc.close(); } catch (e) {}")
    private static native void channelClose(JSObject dc);

    /*
     * m.gen counts opens and closes: a permission granted after the microphone was closed (or
     * opened again) belongs to nobody, and its stream is stopped at once.
     */
    @JSBody(
            script = "var r = globalThis.incariRtc; var m = r.mic; m.state = 'opening';"
                    + "var g = m.gen = (m.gen | 0) + 1;"
                    + "navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true,"
                    + "  noiseSuppression: true, autoGainControl: true } }).then(function(stream) {"
                    + "  if (m.gen !== g) { var old = stream.getTracks();"
                    + "   for (var k = 0; k < old.length; k++) old[k].stop(); return; }"
                    + "  var ctx = r.context(); var src = ctx.createMediaStreamSource(stream);"
                    + "  m.gain = ctx.createGain(); m.gain.gain.value = m.volume;"
                    + "  m.analyser = ctx.createAnalyser(); m.analyser.fftSize = 256;"
                    + "  var dest = ctx.createMediaStreamDestination();"
                    + "  src.connect(m.gain); m.gain.connect(dest); m.gain.connect(m.analyser);"
                    + "  m.raw = stream; m.track = dest.stream.getAudioTracks()[0];"
                    + "  m.track.enabled = m.transmitting; m.state = 'open';"
                    + "  for (var i = 0; i < m.peers.length; i++) r.attachMic(m.peers[i]);"
                    + "}).catch(function() { if (m.gen === g) m.state = 'unavailable'; });")
    private static native void openMic();

    /** Stops the microphone's device and graph; peers it went to send nothing until it opens again. */
    @JSBody(
            script = "var m = globalThis.incariRtc.mic; m.gen = (m.gen | 0) + 1; m.state = 'closed';"
                    + "if (m.raw) { var ts = m.raw.getTracks(); for (var i = 0; i < ts.length; i++) ts[i].stop(); }"
                    + "if (m.track) m.track.stop();"
                    + "try { if (m.gain) m.gain.disconnect(); } catch (e) {}"
                    + "m.raw = null; m.track = null; m.gain = null; m.analyser = null;"
                    + "for (var k = 0; k < m.peers.length; k++) { var p = m.peers[k];"
                    + "  if (p.transceiver) p.transceiver.sender.replaceTrack(null).catch(function() {}); }")
    private static native void closeMic();

    @JSBody(script = "return globalThis.incariRtc.mic.state;")
    private static native String micState();

    @JSBody(
            params = "on",
            script = "var m = globalThis.incariRtc.mic; m.transmitting = on; if (m.track) m.track.enabled = on;")
    private static native void micTransmitting(boolean on);

    @JSBody(
            params = "volume",
            script = "var m = globalThis.incariRtc.mic; m.volume = volume; if (m.gain) m.gain.gain.value = volume;")
    private static native void micVolume(float volume);

    @JSBody(
            script = "var m = globalThis.incariRtc.mic;"
                    + "return m.transmitting ? globalThis.incariRtc.level(m.analyser) : 0;")
    private static native double micLevel();

    /* Peers and data channels keep their queue in .q. */

    @JSBody(params = "o", script = "return o.q.length;")
    private static native int queued(JSObject o);

    @JSBody(
            params = {"o", "i"},
            script = "return o.q[i][0];")
    private static native String queuedType(JSObject o, int i);

    @JSBody(
            params = {"o", "i", "field"},
            script = "return String(o.q[i][field]);")
    private static native String queuedString(JSObject o, int i, int field);

    @JSBody(
            params = {"o", "i", "field"},
            script = "return o.q[i][field] | 0;")
    private static native int queuedInt(JSObject o, int i, int field);

    @JSBody(
            params = {"o", "i"},
            script = "return o.q[i][1];")
    private static native ArrayBuffer queuedData(JSObject o, int i);

    @JSBody(
            params = {"o", "i", "field"},
            script = "return o.q[i][field];")
    private static native JSObject queuedObject(JSObject o, int i, int field);

    @JSBody(
            params = {"o", "count"},
            script = "o.q.splice(0, count);")
    private static native void dropQueued(JSObject o, int count);
}
