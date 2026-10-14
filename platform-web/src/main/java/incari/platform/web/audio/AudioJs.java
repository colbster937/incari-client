package incari.platform.web.audio;

import org.jspecify.annotations.Nullable;
import org.teavm.jso.JSBody;
import org.teavm.jso.JSFunctor;
import org.teavm.jso.JSObject;
import org.teavm.jso.typedarrays.Int8Array;

/**
 * Every JavaScript snippet the Web Audio platform runs, in one place.
 *
 * <p>The graph: each source (a "voice") feeds a 2-channel gain node that up-mixes mono to both
 * sides, a splitter, one gain per ear (the {@link Spatializer}'s gains), and a merger into the
 * device's master gain, then the page's output. With HRTF ("Directional Audio") a mono voice goes
 * through a Web Audio HRTF panner instead of the ear gains' panning. Sounds are buffer sources
 * (one-shot nodes, restarted at an offset to resume) or, for streams, a media element that
 * decodes as it plays.
 *
 * <p>Callbacks here (a node's end, a media element's events, the unlock gesture) never call Java:
 * they set flags on the voice that the sound thread reads, as the input platform's listeners queue
 * events (ADR 0010). The one exception is the decoder's promise, which completes an
 * {@code AsyncCallback}.
 *
 * <p>{@code globalThis.incariAudio} holds the context, the master gain, the playing streams' media
 * elements and counters, for the browser checks and for debugging from the console.
 */
final class AudioJs {

    private AudioJs() {}

    // --- The context, and the unlock prompt ------------------------------------------------------

    /** The page's audio context, or null if the browser has none (or refused to make one). */
    @JSBody(
            script = "var C = window.AudioContext || window.webkitAudioContext; if (!C) return null;"
                    + " var ctx; try { ctx = new C({ latencyHint: 'interactive' }); } catch (e) { return null; }"
                    + " var a = globalThis.incariAudio || (globalThis.incariAudio = {});"
                    + " a.context = ctx; a.buffersStarted = 0; a.streamsStarted = 0; a.voices = 0; a.pending = [];"
                    + " a.streams = [];"
                    + " a.unlockRequested = false; return ctx;")
    static native @Nullable JSObject createContext();

    @JSBody(params = "ctx", script = "return ctx.currentTime;")
    static native double time(JSObject ctx);

    @JSBody(params = "ctx", script = "return ctx.sampleRate;")
    static native int sampleRate(JSObject ctx);

    @JSBody(params = "ctx", script = "return ctx.state === 'closed';")
    static native boolean closed(JSObject ctx);

    /**
     * Whether a sound started now will be heard soon: the context runs, or a gesture has asked it
     * to. Before the first gesture it might not run for minutes, and short sounds are dropped then
     * rather than all heard at once when it does.
     */
    @JSBody(params = "ctx", script = "return ctx.state === 'running' || globalThis.incariAudio.unlockRequested;")
    static native boolean startsSoon(JSObject ctx);

    /**
     * Browsers start an audio context suspended until the page gets a click or a key press. This
     * shows a small prompt until then, resumes the context on the first gesture anywhere on the
     * page, and starts the streams that were waiting for it.
     */
    @JSBody(
            params = "ctx",
            script = "var a = globalThis.incariAudio; var events = ['pointerdown', 'mousedown', 'keydown', 'touchend'];"
                    + " var prompt = null;"
                    + " function startPending() { var p = a.pending; a.pending = [];"
                    + "  for (var i = 0; i < p.length; i++) incariPlayMedia(p[i]); }"
                    + " function done() { if (ctx.state !== 'running') return;"
                    + "  if (prompt) { prompt.remove(); prompt = null; }"
                    + "  for (var i = 0; i < events.length; i++) window.removeEventListener(events[i], gesture, true);"
                    + "  a.unlocked = true; startPending(); }"
                    + " function gesture() { a.unlockRequested = true; ctx.resume().then(done, function() {}); }"
                    + " ctx.addEventListener('statechange', done);"
                    + " if (ctx.state === 'running') { a.unlocked = true; return; }"
                    + " a.unlocked = false;"
                    + " prompt = document.createElement('div'); prompt.id = 'incari-audio-unlock';"
                    + " prompt.textContent = 'Click or press a key to turn on sound';"
                    + " prompt.style.cssText = 'position:fixed;right:12px;bottom:12px;padding:6px 12px;"
                    + "border-radius:14px;background:rgba(0,0,0,0.6);color:#eee;font:13px system-ui,sans-serif;"
                    + "pointer-events:none;z-index:2147483647;user-select:none';"
                    + " document.body.appendChild(prompt);"
                    + " for (var i = 0; i < events.length; i++) window.addEventListener(events[i], gesture, true);")
    static native void installUnlock(JSObject ctx);

    /**
     * Defines the page-wide helper the unlock and the voices share to start a media element, once:
     * a stream asked to play while the context is suspended waits for it to run, and one the
     * browser still refuses waits for the next gesture.
     */
    @JSBody(
            script = "if (globalThis.incariPlayMedia) return;"
                    + " function incariPlayMediaImpl(v) { var m = v.media; if (!m || !m.want) return;"
                    + "  var a = globalThis.incariAudio;"
                    + "  if (v.ctx.state !== 'running') { if (a.pending.indexOf(v) < 0) a.pending.push(v); return; }"
                    + "  a.streamsStarted++;"
                    + "  m.el.play().catch(function(e) {"
                    + "   if (e && e.name === 'NotAllowedError') {"
                    + "    function retry() { window.removeEventListener('pointerdown', retry, true);"
                    + "     window.removeEventListener('keydown', retry, true); incariPlayMediaImpl(v); }"
                    + "    window.addEventListener('pointerdown', retry, true); window.addEventListener('keydown', retry, true);"
                    + "   } else if (e && e.name !== 'AbortError') { m.ended = true; } }); }"
                    + " globalThis.incariPlayMedia = incariPlayMediaImpl;")
    static native void defineHelpers();

    // --- The device --------------------------------------------------------------------------

    /** The device's master gain, into the page's output. */
    @JSBody(
            params = {"ctx", "hrtf"},
            script = "var m = ctx.createGain(); m.connect(ctx.destination); globalThis.incariAudio.master = m;"
                    + " return { ctx: ctx, master: m, hrtf: hrtf };")
    static native JSObject createBus(JSObject ctx, boolean hrtf);

    @JSBody(params = "bus", script = "bus.master.disconnect();")
    static native void closeBus(JSObject bus);

    // --- Voices ------------------------------------------------------------------------------

    @JSBody(
            params = "bus",
            script = "var ctx = bus.ctx;"
                    + " var v = { bus: bus, ctx: ctx, input: ctx.createGain(), split: ctx.createChannelSplitter(2),"
                    + "  l: ctx.createGain(), r: ctx.createGain(), merge: ctx.createChannelMerger(2), panner: null,"
                    + "  nodes: [], gen: 0, ended: false, media: null, routed: false };"
                    + " v.input.channelCount = 2; v.input.channelCountMode = 'explicit';"
                    + " v.input.channelInterpretation = 'speakers';"
                    + " v.split.connect(v.l, 0); v.split.connect(v.r, 1);"
                    + " v.l.connect(v.merge, 0, 0); v.r.connect(v.merge, 0, 1); v.merge.connect(bus.master);"
                    + " globalThis.incariAudio.voices++; return v;")
    static native JSObject createVoice(JSObject bus);

    /**
     * Connects the voice's input for {@code stereo} or mono content: straight to the ear gains, or
     * for mono with HRTF on, through an HRTF panner (with no distance model of its own).
     */
    @JSBody(
            params = {"v", "stereo"},
            script = "if (v.routed) { v.input.disconnect(); if (v.panner) { v.panner.disconnect(); v.panner = null; } }"
                    + " if (v.bus.hrtf && !stereo) { v.input.channelCount = 1; var p = v.ctx.createPanner();"
                    + "  p.panningModel = 'HRTF'; p.distanceModel = 'linear'; p.rolloffFactor = 0;"
                    + "  p.positionZ.value = -1; v.input.connect(p); p.connect(v.split); v.panner = p;"
                    + " } else { v.input.channelCount = 2; v.input.connect(v.split); }"
                    + " v.routed = true;")
    static native void route(JSObject v, boolean stereo);

    /**
     * Sets the ear gains: at once before the voice plays, otherwise smoothly (a 5 ms time
     * constant), so a moving source doesn't click.
     */
    @JSBody(
            params = {"v", "left", "right", "immediate"},
            script = "var t = v.ctx.currentTime; var ps = [v.l.gain, v.r.gain]; var xs = [left, right];"
                    + " for (var i = 0; i < 2; i++) { if (immediate) { ps[i].cancelScheduledValues(0); ps[i].value = xs[i]; }"
                    + "  else ps[i].setTargetAtTime(xs[i], t, 0.005); }")
    static native void setGains(JSObject v, double left, double right, boolean immediate);

    @JSBody(
            params = {"v", "x", "y", "z"},
            script =
                    "var p = v.panner; if (p) { p.positionX.value = x; p.positionY.value = y; p.positionZ.value = z; }")
    static native void setPannerPosition(JSObject v, double x, double y, double z);

    /** Starts {@code buffer} at context time {@code when} (0: now), {@code offset} seconds in. */
    @JSBody(
            params = {"v", "buffer", "when", "offset", "rate", "loop"},
            script = "var n = v.ctx.createBufferSource(); n.buffer = buffer; n.loop = loop;"
                    + " n.playbackRate.value = rate; n.connect(v.input); var gen = v.gen;"
                    + " n.onended = function() { var i = v.nodes.indexOf(n); if (i >= 0) v.nodes.splice(i, 1);"
                    + "  if (gen === v.gen && v.nodes.length === 0) v.ended = true; };"
                    + " v.ended = false; v.nodes.push(n); n.start(when, offset);"
                    + " globalThis.incariAudio.buffersStarted++;")
    static native void startBuffer(JSObject v, JSObject buffer, double when, double offset, double rate, boolean loop);

    /** Stops every buffer the voice started; they don't count as having ended. */
    @JSBody(
            params = "v",
            script = "v.gen++; var ns = v.nodes; v.nodes = []; v.ended = false;"
                    + " for (var i = 0; i < ns.length; i++) { ns[i].onended = null;"
                    + "  try { ns[i].stop(); } catch (e) {} ns[i].disconnect(); }")
    static native void stopBuffers(JSObject v);

    /** Whether the buffers the voice last started have all played to their end. */
    @JSBody(params = "v", script = "return v.ended;")
    static native boolean buffersEnded(JSObject v);

    @JSBody(
            params = {"v", "rate"},
            script = "for (var i = 0; i < v.nodes.length; i++) v.nodes[i].playbackRate.value = rate;"
                    + " if (v.media) v.media.el.playbackRate = rate;")
    static native void setRate(JSObject v, double rate);

    @JSBody(
            params = {"v", "loop"},
            script = "for (var i = 0; i < v.nodes.length; i++) v.nodes[i].loop = loop;"
                    + " if (v.media) v.media.el.loop = loop;")
    static native void setLoop(JSObject v, boolean loop);

    /**
     * Attaches a whole Ogg Vorbis file as a media element the browser decodes as it plays, rather
     * than decoding a whole track of music into memory up front.
     */
    @JSBody(
            params = {"v", "bytes", "loop", "rate"},
            script =
                    "var url = URL.createObjectURL(new Blob([bytes], { type: 'audio/ogg' }));"
                            + " var el = new Audio(); el.preload = 'auto'; el.loop = loop; el.preservesPitch = false;"
                            + " el.src = url; el.playbackRate = rate;"
                            + " var node = v.ctx.createMediaElementSource(el); node.connect(v.input);"
                            + " var m = { el: el, node: node, url: url, ended: false, want: false }; v.media = m;"
                            + " globalThis.incariAudio.streams.push(el);"
                            + " el.addEventListener('ended', function() { m.ended = true; });"
                            + " el.addEventListener('error', function() { m.ended = true;"
                            + "  console.warn('[Incari] A sound stream failed: ' + (el.error ? el.error.message || el.error.code : '')); });")
    static native void attachMedia(JSObject v, Int8Array bytes, boolean loop, double rate);

    @JSBody(params = "v", script = "if (v.media) { v.media.want = true; incariPlayMedia(v); }")
    static native void playMedia(JSObject v);

    @JSBody(
            params = {"v", "rewind"},
            script = "var m = v.media; if (!m) return; m.want = false; m.el.pause();"
                    + " var p = globalThis.incariAudio.pending; var i = p.indexOf(v); if (i >= 0) p.splice(i, 1);"
                    + " if (rewind) { m.el.currentTime = 0; m.ended = false; }")
    static native void pauseMedia(JSObject v, boolean rewind);

    @JSBody(params = "v", script = "return !!(v.media && v.media.ended);")
    static native boolean mediaEnded(JSObject v);

    /** Stops everything and takes the voice out of the graph for good. */
    @JSBody(
            params = "v",
            script = "v.gen++; var ns = v.nodes; v.nodes = [];"
                    + " for (var i = 0; i < ns.length; i++) { ns[i].onended = null; try { ns[i].stop(); } catch (e) {}"
                    + "  ns[i].disconnect(); }"
                    + " var a = globalThis.incariAudio; var m = v.media;"
                    + " if (m) { m.want = false; m.el.pause(); m.el.removeAttribute('src'); m.el.load();"
                    + "  URL.revokeObjectURL(m.url); m.node.disconnect(); v.media = null;"
                    + "  var i = a.pending.indexOf(v); if (i >= 0) a.pending.splice(i, 1);"
                    + "  var j = a.streams.indexOf(m.el); if (j >= 0) a.streams.splice(j, 1); }"
                    + " v.input.disconnect(); if (v.panner) v.panner.disconnect(); v.split.disconnect();"
                    + " v.l.disconnect(); v.r.disconnect(); v.merge.disconnect(); a.voices--;")
    static native void closeVoice(JSObject v);

    // --- Buffers -----------------------------------------------------------------------------

    /** Interleaved little-endian PCM (8-bit unsigned or 16-bit signed) into a Web Audio buffer. */
    @JSBody(
            params = {"ctx", "bytes", "rate", "channels", "bits"},
            script = "var frames = Math.floor(bytes.length / (channels * bits / 8));"
                    + " var b = ctx.createBuffer(channels, Math.max(frames, 1), rate);"
                    + " var d = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);"
                    + " for (var c = 0; c < channels; c++) { var out = b.getChannelData(c);"
                    + "  for (var i = 0; i < frames; i++) { var k = i * channels + c;"
                    + "   out[i] = bits === 16 ? d.getInt16(k * 2, true) / 32768 : (d.getUint8(k) - 128) / 128; } }"
                    + " return b;")
    static native JSObject pcmBuffer(JSObject ctx, Int8Array bytes, int rate, int channels, int bits);

    @JSBody(params = "buffer", script = "return buffer.sampleRate;")
    static native int bufferRate(JSObject buffer);

    @JSBody(params = "buffer", script = "return buffer.numberOfChannels;")
    static native int bufferChannels(JSObject buffer);

    @JSBody(params = "buffer", script = "return buffer.length;")
    static native int bufferFrames(JSObject buffer);

    /** A decoded file, or a message saying why it couldn't be. */
    @JSFunctor
    interface Decoded extends JSObject {
        void decoded(JSObject buffer);
    }

    @JSFunctor
    interface Failed extends JSObject {
        void failed(String message);
    }

    /**
     * Decodes a whole file with the browser's decoder, which runs off the page's thread (Chromium
     * and Firefox decode on a thread of their own) and resamples to the context's rate.
     */
    @JSBody(
            params = {"ctx", "bytes", "done", "failed"},
            script = "ctx.decodeAudioData(bytes.buffer).then(function(b) { done(b); },"
                    + " function(e) { failed(String(e && e.message ? e.message : e)); });")
    static native void decode(JSObject ctx, Int8Array bytes, Decoded done, Failed failed);
}
