# EaglerXServer notes

How Incari joins EaglerXServer, and what a future Incari server add-on could hook into. Researched
from EaglerXServer 1.1.1's source (2026-09-27). The client side is ADR 0016.

## Joining needs no add-on

The client runs Eagler's V5 handshake itself, as the first stage of its pipeline, and joins stock
EaglerXServer with default settings. EaglerXServer accepts any Minecraft protocol number by default
and, after the handshake, passes one plain packet per binary WebSocket message to the proxy.

How EaglerXServer takes a connection:

1. A WebSocket upgrade fires `EaglercraftWebSocketOpenEvent` (headers, path, channel).
2. A first text frame is a MOTD query. A first binary frame starting `01 02` is Eagler's
   handshake (V2–V5), `01 01` is V1, `02 xx` a legacy handshake for the Rewind API. **Anything
   else, including a vanilla handshake, is closed silently.**
3. After the handshake, EaglerXServer writes the vanilla handshake and Login Start into the proxy
   itself (no online mode, compression always off).
4. Then every binary message is a vanilla packet. Messages starting `0xEE` are Eagler's own (skins,
   voice, cookies); no vanilla packet uses that id.

So the client's Eagler stage runs the V5 exchange, then plays the vanilla login locally: it
swallows the game's handshake, Login Start and Login Acknowledged, answers with a Login Success from
`ALLOW_LOGIN`'s name and UUID, and routes `0xEE` messages to Incari.

Limits: the protocol number must fit 16 bits, no online mode, no compression, and the proxy must
support the game's version (or Via handles it, ADR 0017).

## For a future add-on (`server-plugin`)

Useful only for Incari-only features (plugin channels, letting servers switch modules off).

- **Rewind API** (`IEaglerXRewindService.registerLegacyProtocol`): for a `02 <byte>` first message,
  it can inject Netty handlers and emulate the Eagler handshake with any protocol number. Public API.
- **`EaglercraftWebSocketOpenEvent`**: the raw channel before the first frame. Handler names are
  internal, not API.
- **`EnumPipelineEvent`**: events at each stage (`EAGLER_HANDSHAKE_COMPLETE`, …).

## Constraints

- **License:** EaglerXServer is All Rights Reserved. Never copy or fork it. An add-on may compile
  against its published API jars (compile-only), but ask the author before publishing one.
- **Platform:** Java 17+. Its Velocity support uses Velocity internals by reflection, so check
  each Velocity version.
- **Upstream bugs seen:** `RewindService.registerLegacyProtocol`'s range check is never true;
  `VanillaInitializer` drops the connection on any unexpected login packet.

## Testing

- Desktop: `./gradlew :app-desktop:smokeServer -Pincari.server=ws://<eaglerxserver>` (see `net`'s README).
- Without EaglerXServer: `tools/wsbridge` runs Eagler's handshake in front of any vanilla server.
- Browser: `app-web/browser-checks/README.md` has the BungeeCord + EaglerXServer setup.
