# ADR 0013: Sound over Web Audio, with the browser's decoder and the desktop's panning

- Status: accepted
- Date: 2026-09-28

## Context

The game's sound engine already sits on `PlatformAudio` (ADR 0005), with OpenAL on the desktop. The
browser had a silent stand-in. The game expects OpenAL's model: a listener, mono sources placed in
the world with a distance model, gain, pitch, looping, one-shot buffers and queued streams, 255
sources.

The game decodes Ogg Vorbis itself (JOrbis). On the page that would run on the only thread and cost
frames for every new sound and every second of music. Browsers decode Ogg natively, but
`decodeAudioData` decodes whole files only (70 MB of floats for a 3-minute track). And a page can't
play sound until the player clicks or presses a key.

## Decision

- **Web Audio implements `PlatformAudio`**: one `AudioContext`; each source is a small graph with a
  gain per ear. **Panning comes from `Spatializer`**, plain Java that runs OpenAL Soft's own model,
  not Web Audio's `PannerNode`, so each ear hears what it would on the desktop.
- **The desktop's model, measured:** `:platform-desktop:openAlReference` records OpenAL Soft's mix to
  a WAV at known positions. For stereo without HRTF, each ear gets `0.5 ± 0.5·right + 0.0957·front`
  of the attenuated gain (ahead 0.596 per ear, behind 0.404). `SpatializerTest` holds the browser
  within 0.003.
- **The browser decodes, off the page's thread.** `PlatformAudio.decoder()` (optional) is
  `decodeAudioData`. `PlatformSoundLoading` hooks the game's sound buffer library; three loader
  threads decode and cache as the game would. The desktop has no decoder and keeps the game's path;
  a file the browser can't decode falls back to it too.
- **Streams play compressed:** music and records go to a media element feeding the same graph
  (pitch as `playbackRate`), so a track costs its file, never its decoded samples. The game's
  buffer-queue path stays for anything else.
- **OpenAL semantics over one-shot nodes:** a `PlaybackClock` per source handles pause, resume,
  position and looping. 255 sources, as OpenAL Soft.
- **Gains change only when needed**, smoothed over 5 ms while playing. Re-panning every frame allocates nothing.
- **Directional Audio (HRTF)** uses the browser's HRTF panner, which differs from OpenAL Soft's data set.
- **Autoplay unlock:** a small "Click or press a key to turn on sound" prompt until the first
  gesture. Until then one-shot sounds are dropped (so title clicks don't pile up) and streams wait.
  JavaScript callbacks only set flags; only the decoder's promise calls Java.
- **Sounds are fetched when first played:** the page writes the sound list and an empty placeholder
  per sound; on first play `RemoteSounds` fetches the real file. Resource pack sounds are read normally.
- `javax.sound.sampled.AudioFormat` (description only) is in `web-classlib`.

## Deviations from the desktop

- No output limiter: loud stacks clip instead of being limited.
- No output device choice.
- Sound starts after the first click or key press, as on every web page.
- Decoded sounds take about twice the desktop's memory (32-bit floats at output rate).
- A sound's first play waits for its download.

## Alternatives rejected

- **`PannerNode`:** equal-power panning ignores front and back and makes listener-relative sounds
  (UI, music) 1.5 dB louder than the desktop.
- **JOrbis in the page:** on the only thread; frames drop on every new sound.
- **WebCodecs with an Ogg demuxer:** a demuxer to maintain and patchy Vorbis support; the media
  element does it natively.
- **A worker for decoding:** `decodeAudioData` is already off-thread.
- **Downloading every sound at start:** 360 MB, mostly never played.

## Consequences

- Positional sound, UI, music and records work in the browser. `audio.steps` checks the unlock and
  streaming; `audio-positional.steps` matches the desktop's panning and fade within 1%.
- What needs ears (HRTF, jukeboxes, long sessions) is on the manual checklist.
- Where Safari refuses an Ogg file, the game's decoder takes over for that sound.
