# via: the Via stack in the client

Lets the game join servers of other versions (ADR 0017): ViaVersion for older servers back to
1.8, ViaBackwards for newer ones, as a stage of the connection's pipeline.

## Code map

- `IncariVia`: Incari's Via platform. `configure` (the data folder and the game's protocol),
  `load(serverProtocol)` (starts Via on first use and waits for its data), `supports`,
  `versionName`, `releases` (for the server list's version button), `shutdown`.
- `ViaPipeline.install(channel, serverProtocol, splitter, prepender)`: adds `via-decoder`,
  `via-relay` and `via-encoder` to one connection.
- `ServerVersions` (package-private): tells Via which version each connection's server speaks.
- `ViaSettings`: Via's settings, in memory.

## Invariants

- **No game types**, and nothing of Incari's but this module (`gradle/module-boundaries.txt`).
  The client's adapters (`incari.client.net`) decide which version a server speaks.
- **Via starts off the main thread.** `load` waits for mapping data; the connect screen's
  connector thread calls it. Never call it from the render thread or a Netty event loop.
- **Via writes no files and parses no YAML**: its configs load from `ViaSettings`.
- **Via's data is on the class path**: the jars on the desktop, the asset pack's `classpath/` in
  the browser (`:app-web:assetPack`).
- Via is GPLv3 without Incari's Minecraft exception: see ADR 0002's open issue.

## How to test

```sh
./gradlew :via:check
```

`ViaPipelineTest` loads Via on the JVM and takes a 26.3 login to a 1.8 server and back. End to end,
against vanilla servers behind the development bridge (`tools/wsbridge`):

```sh
./gradlew :app-desktop:smokeServer -Pincari.server=ws://127.0.0.1:8801   # a 1.8.9 server's bridge
```
