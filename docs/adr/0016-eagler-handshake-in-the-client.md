# ADR 0016: The Eagler handshake in the client, and Eagler skins

- Status: accepted
- Date: 2026-09-29

## Context

Every browser-reachable Minecraft server today is an Eagler server: EaglerXServer in front of
ordinary backends, nearly all 1.8. Its first binary message opens Eagler's own login exchange (a
vanilla handshake is closed silently); after it, the proxy logs the player in and carries vanilla
packets one per message (ADR 0009's framing). Protocol V5 accepts any Minecraft protocol number, so
the handshake belongs in the client ([`docs/eaglerxserver.md`](../eaglerxserver.md)).

Eagler servers also answer the server list with a text MOTD query and move skins in their own
messages. Gate: join a stock EaglerXServer 1.8 network with skins both ways. EaglerXServer is All
Rights Reserved: written from the wire protocol only.

## Decision

- **Every `ws://`/`wss://` server speaks Eagler.** `EaglerHandshake` sits at the pipeline's head,
  below framing, and runs V5 when the channel opens. It swallows the game's handshake and Login Start
  (the proxy already logged in), then answers with a Login Success in the connection's protocol
  layout (and swallows Login Acknowledged from 1.20.2, holding backend packets until then). A refusal
  becomes a Login Disconnect, shown as the kick reason.
- **The protocol named is the server's:** with Via above, it's the version Via translates to.
- **Eagler's own messages** (`0xEE`, bundled `0xEE 0xFF`) go to an `EaglerSession`.
- **Server list MOTD:** WebSockets gain text messages. A hook in `ServerStatusPinger.pingServer`
  sends `Accept: motd` (`EaglerPings`) and fills the entry: MOTD, counts, names, and the 64×64 icon.
  An Incari extension field `data.protocol` is read when present; otherwise the server is taken as 1.8.
- **Skins:** the player's own is sent at login (`skin_v2`, `cape_v1`; chosen on Edit Profile since
  ADR 0029). Others' are asked for by UUID the first time they're drawn (`EaglerSkins`, a hook in
  `PlayerInfo`), registered as `incari:eagler/...` textures, released on disconnect. Invalidations and
  forced skins are honoured.
- **Formats** (`EaglerTextures`): skins 64×64 at 3 bytes a pixel (alpha as one bit), capes a 22×17
  region plus 11 pixels. Checked byte for byte against EaglerXServer's converters with a local
  program (nothing of its jar committed).
- **Presets:** Eagler's skins 0 and 1 show as Steve and Alex; the rest (Eagler's art, not shipped) as
  one of the game's default skins. Preset capes (Mojang's) aren't drawn; custom capes are.
- **`tools/wsbridge` speaks Eagler** in front of a vanilla server: MOTD, V5 handshake, login over
  TCP, compression, then bridging.
- **Later:** passwords, voice, cookies, redirects, capabilities.

## Alternatives rejected

- **An EaglerXServer add-on:** needs each server's operator.
- **Eagler only for marked addresses:** no WebSocket Minecraft server isn't Eagler.
- **Answering the login before the game asks:** the game's login listener isn't ready yet.
- **Drawing Eagler's preset skins:** not Incari's art to ship.

## Verified

- **Desktop** against EaglerXServer 1.1.1 on BungeeCord, in front of vanilla 1.8.9: `smokeServer`
  pings, joins through Via, chats, moves, stays 20 s, reconnects. `smokeSkinA` + `smokeSkinB`: each
  sees the other's exact skin, model and cape. Against the bridge: 26.3, 1.20.4, 1.12.2, 1.8.9 pass.
- **Browser:** `server-join.steps` and `version-join.steps` pass.

## Consequences

- Eagler servers show their MOTD, counts and icon; the version reads "Eagler 1.8.x" or what they name.
- `wss://` works as far as the platform's WebSocket does.
- The bridge now closes with a proper close frame (an abrupt drop showed as error 1006).
