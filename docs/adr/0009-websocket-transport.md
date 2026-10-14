# ADR 0009: A WebSocket transport under the game's Connection, one packet per message

- Status: accepted
- Date: 2026-09-27

## Context

A page can't open TCP sockets; every browser client uses WebSocket. Eagler servers speak WebSocket
with one packet per binary message, no length prefix, after an Eagler handshake. The game's
connections already run on `PlatformSocketChannel` (ADR 0005). Missing: a WebSocket on each platform,
a way to name a WebSocket server, and message framing. Via and the Eagler handshake must fit later.

## Decision

- **The platform opens WebSockets:** `PlatformNetwork.openWebSocket(url, listener)` returns the same
  `NetConnection` as TCP, with message semantics. Desktop: the JDK's `java.net.http.WebSocket`.
  Browser: the page's `WebSocket`, whose callbacks only queue events, delivered on the game's thread
  at each poll (a JavaScript callback can't start a coroutine).
- **A new module, `:net`**, owns the transport (`NetConnectionChannel`, `WebSocketUrl`,
  `WebSocketAddress`, `MessageFraming`). Netty and `platform-api` only, no game types.
- **Players type a URL:** `ws://` or `wss://`. `ServerAddress` is replaced whole to hold either a TCP
  host and port or a `WebSocketUrl`. Two hooks: the resolver passes WebSocket addresses through (no
  DNS or SRV; the block list still applies), and `Connection.connect` hands them to
  `WebSocketConnections`, which builds the game's own pipeline with message framing. Connect screen,
  server ping and reconnects work unchanged.
- **One packet per message:** `MessageFraming` replaces "splitter" and "prepender" under the same
  names, so compression and encryption slot in as over TCP. Oversized or empty messages fail.
- **Later stages slot in by name:** the Eagler handshake before "splitter"; Via between compression
  and the codec, as ViaFabricPlus does.
- **A development bridge** (`tools/wsbridge`) stands in for an Eagler proxy in front of a vanilla
  server. Never shipped.
- **The browser build keeps Netty's pipeline**, minus the NIO transport. Event loops are local ones
  on coroutine threads; `web-classlib` adds IP-literal socket addresses, two NIO exceptions, and binds
  Netty's logger to SLF4J (Log4j's lookup made every exception constructor a coroutine).
- **The Eagler handshake belongs in the client**, not a server plugin
  ([`docs/eaglerxserver.md`](../eaglerxserver.md)).

## Alternatives rejected

- **TCP's byte stream inside messages:** no Eagler server speaks it.
- **Hooking `ConnectScreen`:** would miss the server list ping, and `Connection.connect` loses a URL.
- **`java.net.URI`:** not a browser's rules. `WebSocketUrl` accepts exactly what a page's `WebSocket` does.
- **A separate channel class:** the address already decides which call opens the connection.

## Verified

- **Desktop** (`smokeServer`): ping, join, chat, the Nether and back, a transfer, disconnect and
  reconnect, over `ws://` through the bridge and over TCP.
- **Browser** (`server-join.steps`): join, chat, the Nether and back, a transfer to a `ws://`
  address, a kick and rejoin. Getting there took about ten TeaVM and build fixes, most with a probe
  in `app-web/probes/`: coroutine type inference, class initialization order, interrupted `wait()`,
  `ArrayDeque`'s iterator, `getSuppressed`, Netty handlers' captured generic types, and `Module`.

## Consequences

- Online-mode encryption can't pass a proxy that doesn't decrypt (each message would be encrypted
  alone). Eagler servers run offline mode, so this matches the target.
- The legacy server ping (only when a modern one fails) can't reach a WebSocket server.
- A transfer packet may name a `ws://` URL as its host, so servers can move browser players.
- The bandwidth chart counts packets without TCP's length prefix.
- Network events reach the game once per frame, which bounds reading in a throttled hidden tab
  (ADR 0006's quarter-second fallback keeps the connection alive).
