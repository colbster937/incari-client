# online: Incari accounts, client side

## Purpose

The client's side of Incari's optional online service (ADR 0058): one WebSocket to the service,
with back-off and a resumable session, speaking the protocol in
[`docs/online-protocol.md`](../docs/online-protocol.md). The service itself is closed source and
not in this repository; [`tools/onlinedev`](../tools/onlinedev) stands in for it during development.

## Entry points

- `OnlineService`: the connection, sign-up (with `ProofOfWork`), sign-in, `SessionStore`, recovery
  codes, and everything below. Fills in the framework's `Online` for features.
- `WorldPlayers`, `ServerHash`: which players in the world are Incari players, matched by a hash of
  the server's address so the service never stores a plain list of servers.
- `Social`: friends, requests, conversations and blocking (ADR 0059).
- `Releases`: the latest release the service names, for the update notice (ADR 0060).
- `Catalog`, `Loadout`, `Wardrobe`: cosmetics owned, worn and synced (ADR 0061).
- Test fixtures: `FakeService`, an in-process stand-in that answers as the service should.

The screens live in `client`'s `incari.client.online`; accounts aren't a module.

## Invariants

See [`AGENTS.md`](AGENTS.md): no game types, no blocking, game-thread only, and never a password,
token or recovery code in a log.

## How to test

```sh
./gradlew :online:check
./gradlew :tools:onlinedev:run --args="--listen 8090"   # then the page with ?online=ws://localhost:8090/v1/ws
```
