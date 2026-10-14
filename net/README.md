# net: the transport under the game's pipeline

How the game's Netty pipeline reaches a server (ADR 0009): TCP on the desktop, or WebSocket, the
only way out of a browser. The game's own protocol code runs unchanged on top. Every WebSocket
server is treated as an Eagler server, so Eagler's client side lives here too (ADR 0016, 0019).
Version translation is `:via`'s job.

## Code map

| Where | What |
| --- | --- |
| `NetConnectionChannel` | A Netty channel over a platform connection |
| `WebSocketUrl`, `WebSocketAddress` | `ws://`/`wss://` addresses, parsed as a browser reads them |
| `MessageFraming` | One packet per WebSocket message, replacing length-prefix framing under the same handler names |
| `eagler.EaglerHandshake` | The Eagler stage: protocol V5, then answers the game's login in the server's place, then passes packets |
| `eagler.EaglerLogin`, `EaglerMessages`, `EaglerTextures` | Login contents; Eagler's own messages; skin and cape formats |
| `eagler.EaglerQuery` | The MOTD query the server list uses |
| `eagler.EaglerPassword` | Auth plugins' password methods |
| `relay.*` | Joining a world shared through an Eagler relay, over a WebRTC data channel |

## Invariants

- **No game types**, only `platform-api` and Netty (`BoundaryTest`). `client`'s `incari.client.net`
  is the game-facing half.
- **The game's handler names survive**, so everything added relative to "splitter" and
  "prepender" works on both transports.
- **Bytes reach the pipeline only on its event loop.**
- **The Eagler stage speaks the server's version.** It sits below framing and Via.
- **Nothing of EaglerXServer's code** (it's All Rights Reserved). The client side is written from
  the wire protocol.

## How to test

```sh
./gradlew :net:check
```

End to end against a local vanilla server behind `tools/wsbridge` (a stand-in Eagler proxy):

```sh
java -jar server.jar nogui        # server-port=25581, online-mode=false, player opped
./gradlew :tools:wsbridge:run --args="--listen 8816 --target 127.0.0.1:25581"
./gradlew :app-desktop:smokeServer -Pincari.server=ws://127.0.0.1:8816
```

The report (`run/smoke-server/incari-smoke/report.txt`) covers ping, join, chat, the Nether and
back, and reconnecting. Against a real EaglerXServer, use its address; `smokeSkinA` with
`smokeSkinB` checks skins both ways (setup in ADR 0016). In the browser: `server-join.steps`.
