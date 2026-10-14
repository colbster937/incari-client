# tools/wsbridge: a development Eagler proxy

Lets the client join a plain Java server of any version over WebSocket, as it joins an Eagler
server (ADR 0009, 0016). It answers Eagler's MOTD query, runs the server side of Eagler's V5
handshake, logs the player in over TCP, then bridges packets (one WebSocket message per packet,
decompressing if the server compresses). The server needs `online-mode=false`. Skin requests are
dropped.

```sh
./gradlew :tools:wsbridge:run --args="--listen 8816 --target 127.0.0.1:25581"
./gradlew :tools:wsbridge:installDist      # build/install/incari-wsbridge/bin/incari-wsbridge
```

`--bind` defaults to `127.0.0.1`; any URL path works. `WebSocketBridge` is the server,
`EaglerConnection` one client's login, `BackendStatus` the MOTD.

## Invariants

- **Development only.** No limits, auth or TLS. Never expose it to the internet or ship it.
- **Only the login is read.** After it, packets are opaque.

## How to test

```sh
./gradlew :tools:wsbridge:check
```
