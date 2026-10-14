package incari.platform.web;

import incari.platform.api.PlatformRecorder;
import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;

/**
 * Screen recording in the page (ADR 0062), as Eaglercraft's browser builds record: the canvas as a
 * video track ({@code captureStream}), the game's sound from the audio device's master gain (ADR
 * 0013) through a gain of its own, and the microphone if asked, mixed into one stream that a
 * {@code MediaRecorder} encodes (the browser's own encoder, off the page's thread; MP4 where Chrome
 * records it, else WebM). On stop the video downloads.
 *
 * <p>The recorded chunks stay in the tab's memory until then: about 19 MB a minute at the default
 * 2.5 Mbit/s, which the settings screen says.
 */
final class WebRecorder implements PlatformRecorder {

    @Override
    public boolean supported() {
        return available();
    }

    @Override
    public void start(Options options, String fileName) {
        if (state() != State.IDLE) {
            return;
        }
        start(
                WebPlatform.CANVAS_ID,
                options.framesPerSecond(),
                options.videoKilobits(),
                options.gameVolume(),
                options.microphone(),
                options.microphoneVolume(),
                fileName);
    }

    @Override
    public void stop() {
        stopRecording();
    }

    @Override
    public State state() {
        return switch (stateName()) {
            case "starting" -> State.STARTING;
            case "recording" -> State.RECORDING;
            default -> State.IDLE;
        };
    }

    @Override
    public @Nullable String error() {
        return errorText();
    }

    @Override
    public long elapsedMillis() {
        return (long) elapsed();
    }

    @JSBody(
            script = "return typeof MediaRecorder !== 'undefined' && typeof HTMLCanvasElement !== 'undefined'"
                    + " && typeof HTMLCanvasElement.prototype.captureStream === 'function';")
    private static native boolean available();

    @JSBody(
            params = {"canvasId", "fps", "kbps", "gameVolume", "mic", "micVolume", "fileName"},
            script = "var r = globalThis.incariRecorder = {state: 'starting', error: null, chunks: [], started: 0};"
                    + "try {"
                    + " var canvas = document.getElementById(canvasId);"
                    + " var video = canvas.captureStream(fps);"
                    + " var tracks = video.getVideoTracks().slice(); r.video = video;"
                    + " var a = globalThis.incariAudio, dest = null;"
                    + " if (a && a.master) { r.ctx = a.master.context; dest = r.ctx.createMediaStreamDestination();"
                    + "  r.game = r.ctx.createGain(); r.game.gain.value = gameVolume;"
                    + "  a.master.connect(r.game); r.game.connect(dest); r.master = a.master; }"
                    + " function cleanup() {"
                    + "  try { if (r.master && r.game) r.master.disconnect(r.game); } catch (e) {}"
                    + "  if (r.mic) r.mic.getTracks().forEach(function (t) { t.stop(); });"
                    + "  r.video.getTracks().forEach(function (t) { t.stop(); }); }"
                    + " function begin(micStream) {"
                    + "  if (r.state !== 'starting') { if (micStream) micStream.getTracks().forEach(function (t) { t.stop(); });"
                    + "   cleanup(); return; }"
                    + "  if (micStream && dest) { r.mic = micStream; var source = r.ctx.createMediaStreamSource(micStream);"
                    + "   var level = r.ctx.createGain(); level.gain.value = micVolume; source.connect(level); level.connect(dest); }"
                    + "  if (dest) dest.stream.getAudioTracks().forEach(function (t) { tracks.push(t); });"
                    + "  var types = ['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'video/webm;codecs=vp9,opus',"
                    + "   'video/webm;codecs=vp8,opus', 'video/webm'];"
                    + "  var type = ''; for (var i = 0; i < types.length; i++)"
                    + "   if (MediaRecorder.isTypeSupported(types[i])) { type = types[i]; break; }"
                    + "  var ext = type.indexOf('mp4') >= 0 ? 'mp4' : 'webm';"
                    + "  var rec = new MediaRecorder(new MediaStream(tracks), type"
                    + "   ? {mimeType: type, videoBitsPerSecond: kbps * 1000, audioBitsPerSecond: 128000}"
                    + "   : {videoBitsPerSecond: kbps * 1000});"
                    + "  rec.ondataavailable = function (e) { if (e.data && e.data.size > 0) r.chunks.push(e.data); };"
                    + "  rec.onerror = function (e) { r.error = 'The browser stopped the recording'"
                    + "   + (e && e.error && e.error.message ? ': ' + e.error.message : ''); };"
                    + "  rec.onstop = function () { cleanup();"
                    + "   if (r.chunks.length > 0) { var blob = new Blob(r.chunks, {type: type || 'video/webm'});"
                    + "    var url = URL.createObjectURL(blob); var link = document.createElement('a');"
                    + "    link.href = url; link.download = fileName + '.' + ext; document.body.appendChild(link);"
                    + "    link.click(); link.remove(); setTimeout(function () { URL.revokeObjectURL(url); }, 60000); }"
                    + "   r.chunks = []; r.state = 'idle'; };"
                    + "  rec.start(1000); r.rec = rec; r.started = performance.now(); r.state = 'recording'; }"
                    + " if (mic && navigator.mediaDevices && navigator.mediaDevices.getUserMedia)"
                    + "  navigator.mediaDevices.getUserMedia({audio: true}).then(begin, function () {"
                    + "   r.error = 'No microphone (not allowed, or none): recording without it'; begin(null); });"
                    + " else begin(null);"
                    + "} catch (e) { r.state = 'idle'; r.error = 'Couldn\\'t record: ' + (e && e.message ? e.message : e);"
                    + " try { if (r.master && r.game) r.master.disconnect(r.game); } catch (e2) {}"
                    + " if (r.video) r.video.getTracks().forEach(function (t) { t.stop(); }); }")
    private static native void start(
            String canvasId, int fps, int kbps, float gameVolume, boolean mic, float micVolume, String fileName);

    @JSBody(
            script =
                    "var r = globalThis.incariRecorder; if (!r) return;"
                            + " if (r.state === 'starting') { r.state = 'idle'; return; }"
                            + " if (r.state === 'recording' && r.rec && r.rec.state !== 'inactive') { r.state = 'stopping'; r.rec.stop(); }")
    private static native void stopRecording();

    @JSBody(script = "var r = globalThis.incariRecorder; return r ? r.state : 'idle';")
    private static native String stateName();

    @JSBody(script = "var r = globalThis.incariRecorder; return r && r.error ? r.error : null;")
    private static native @Nullable String errorText();

    @JSBody(
            script = "var r = globalThis.incariRecorder;"
                    + " return r && r.state === 'recording' ? performance.now() - r.started : 0;")
    private static native double elapsed();
}
