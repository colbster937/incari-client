# online

- Incari accounts and the online service, client side (ADR 0058; the wire format is
  `docs/online-protocol.md`, which the service implements; change both together).
- No game, LWJGL or TeaVM types (`BoundaryTest`): `:client`'s `incari.client.online` feeds it the
  world and draws its screens.
- Compiled into the browser build: no reflection, no blocking, nothing TeaVM's class library lacks.
  Gson's tree API only.
- `OnlineService` is game-thread only. The platform's network callbacks are queued and run in
  `tick()`; proof of work runs on the background executor it's given and reports back the same way.
- Never log or keep a password; never log a token or a recovery code. Nothing in the game may wait
  on the service: every call returns at once and answers through a callback on a later tick.
- Test: `./gradlew :online:check`. The vectors in `src/test/resources/incari/online/vectors.json`
  are shared with the backend.
