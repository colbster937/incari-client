# ADR 0017: Via in the client, and choosing a server's version

- Status: accepted
- Date: 2026-09-29

## Context

The game speaks only 26.3's protocol. Players want mostly 1.8 servers (nearly every Eagler server),
then 1.12, then modern ones. ViaVersion and its companions translate packet by packet as a Netty
stage, as ViaFabricPlus does in-client. Goal: Via under TeaVM, version detection with a manual
override, and 1.8, 1.12, 1.20 and 26.3 servers joining and playing.

## Decision

- **A new module, `:via`**: Netty and Via, no game types. ViaVersion and ViaBackwards 5.12.0,
  unmodified, from `repo.viaversion.com`.
- **ViaVersion covers every older server** (1.8–26.2): "a newer client on an older server" is its
  job. **ViaBackwards** only for servers newer than the game, loaded on first need. **No ViaRewind**
  (for old *clients* on new servers). Pre-1.8 would need ViaLegacy; left until needed.
- **The platform** (`IncariVia`): a version provider per connection. Settings live in memory (no
  update checks, 1.8 idle movement packets, no rate limits); no YAML. Via starts on the first join
  that needs it, on the connector thread, never blocking the network thread.
- **The pipeline** (`ViaPipeline`): `via-decoder` after "splitter", `via-encoder` after
  "prepender", so compression lands correctly; the Eagler stage stays below and speaks the server's
  version. `via-relay` defers packets Via releases synchronously inside the game's own write (for
  pre-1.20.2 servers), which otherwise met a pipeline with no outbound protocol.
- **Choosing the version:** a "Version" button on each server's edit screen ("Auto" or a release),
  saved in `servers.dat`. (How "Auto" resolves was replaced by ADR 0018: join as the game's version
  and read the refusal.)
- **The server list** counts servers Via can reach as compatible.
- **In the browser:** Via compiles in like any library; its mappings (0.3 MB compressed) ride in
  the asset pack. Build help: Via's bundled `TypeToken` joins `TypeCaptures`, `PipelineUtil` is
  substituted, SnakeYAML is stripped, `Collections.unmodifiableSortedSet` added (probes for each).
- **Licensing is open:** Via is GPLv3 without Incari's Minecraft exception (ADR 0002). The repo
  holds no Via code, and building locally distributes nothing; the project distributes no build
  with Via until it's settled.

## Alternatives rejected

- **ViaLoader:** pulls in ViaLegacy, ViaBedrock and ViaAprilFools; the part needed is a hundred lines.
- **Via's YAML configs:** a parser and file writes for settings no player edits.
- **Detecting an Eagler server's version from the handshake:** V5 accepts anything; only the backend knows.
- **One Via stage for both directions:** the game's handlers aren't neighbours, and compression is
  placed by the framing names.

## Verified

- **Desktop** (`smokeServer`, behind the bridge): 1.8.9, 1.12.2, 1.20.4 join through Via, chat, move,
  stay 20 s, reconnect; 26.3 without Via. 1.8.9 over plain TCP too. Via starts in ~150 ms.
- **Browser** (`version-join.steps`): the same three versions and stock EaglerXServer with a 1.8.9
  backend: join, chat, 20 s, kick, rejoin. Via starts in ~30 ms.

## Consequences

- A wrongly detected version gets the server's own "outdated client" message; the Version button fixes it.
- The 1.8 behaviour layer (ADR 0020) builds on the known version (`ServerConnections.lastJoinedProtocol`).
- Every packet to an older server costs Via's translation on the network thread.
