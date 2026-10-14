# ADR 0019: Eagler's extras: passwords, cookies, redirects, voice chat and shared worlds

- Status: accepted
- Date: 2026-09-29

## Context

Joining an Eagler server (ADR 0016) left out EaglerXServer's extras: handshake passwords, login
cookies, redirects, WebRTC voice chat, and worlds shared through a relay. Gate: voice chat and
auth-plugin servers work.

Two kinds of auth: **most Eagler servers log players in on the backend** with plugins like AuthMe
(`/login` in chat), which needs only chat and commands. **EaglerXServer's handshake auth** asks for a
password before the player reaches any server; it's in the protocol but uncommon. Neither is a
Mojang account.

EaglerXServer and Eagler's clients are All Rights Reserved: everything here is written from the wire
protocol. Local test doubles (an auth plugin, the relay jar) live under `run/` and are never committed.

## Decision

- **Handshake passwords** (`EaglerPassword`, `EaglerAuth`, `PasswordScreen`): the server names a
  method and salt; the client answers `EAGLER_SHA256`, `AUTHME_SHA256`
  (`hex(sha256(hex(sha256(password)) + salt))`), or plain text only after the player agrees. A
  refusal for a missing password shows the password screen, which joins again (`JoinFailures`).
  Passwords stay in memory for the session, by server URL; never on disk.
- **Cookies** (`EaglerCookies`): a server's cookie (≤ 255 bytes, ≤ a week) is sent with the next
  login to the same URL. The server chooses whether it's saved (`incari/eagler-cookies.txt`).
- **Redirects:** a redirect to a `ws://`/`wss://` address joins it with "Auto"; others are refused.
- **Voice chat** (`EaglerVoice`, `Platform.rtc()`; browser only): the server only signals (who has
  voice on, pairing, offers, answers, ICE). Each peer gets an audio transceiver up front and the mic
  track is swapped in once granted, so the permission prompt never forces renegotiation. Voices play
  through the game's audio context with a gain and stereo pan set each tick (proximity: linear fade
  and direction; global: fixed). Push to talk on B. Voice starts off; choosing a channel shares the
  microphone. Controls are in the pause menu's voice panel (ADR 0045); the HUD lists who's talking.
- **Joining shared worlds** (`incari.net.relay`, `RelayScreen`): "Shared..." on the multiplayer screen
  takes a relay URL and code. `RelayJoin` negotiates a WebRTC data channel through the relay, which
  becomes the game's connection with Eagler's LAN framing (`LanFraming`) and login (`LanLogin`), under
  Via at 1.8, since Eaglercraft hosts are 1.8. Hosting came later (ADR 0062).
- **Capabilities** asked at login: cookies and redirects always; voice where WebRTC exists.

## Alternatives rejected

- **Storing passwords or hashes on disk:** cookies are the servers' own "remember me", and a stored
  hash is as good as the password.
- **An audio element per voice with `PannerNode`:** voices share the game's context and listener,
  with one gain each for mutes and fades.
- **WebRTC on the desktop:** it's the development runtime; voice and shared worlds are checked in the browser.

## Verified

- **Handshake auth** (`smokeAuth`) against EaglerXServer 1.1.1 with a local test plugin: both hashed
  methods accepted, the cookie saved, and the next join logged in by cookie alone. Hashes match
  independent vectors in `EaglerHandshakeTest`. Plain text is unit-tested only.
- **Voice** (`voice-talker.steps` + `voice-listener.steps`, two headless Chromiums with fake mics):
  paired, connected, and the listener hears the talker. Signalling also on the desktop (`smokeVoiceA`
  + `smokeVoiceB`). Fixed on the way: the answering side now adopts the offer's transceiver.
- **Shared worlds** (`relay-join.steps`) against EaglerSPRelay and a stand-in host page: the channel
  opens, the host gets the Eagler login, the client reaches the world.

## Consequences

- **In-game `/login` plugins (the common case) aren't checked yet.** They need only chat and
  commands, but a check with AuthMe on the local 1.8.9 server should run before the gate counts.
- Redirects are unit-tested only; no redirecting server was at hand.
- The desktop has no voice or shared worlds; its screens say so.
- The talking indicator holds 0.6 s after the last loud moment.
