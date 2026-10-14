# net

- Only `platform-api`, Netty and the JDK. Never game types (`BoundaryTest`): the client's adapters
  (`incari.client.net`) hand it what it needs.
- Compiled into the browser build: no reflection, no blocking waits, no JDK APIs TeaVM lacks
  (that's why `WebSocketUrl` parses by hand instead of using `java.net.URI`).
- Keep the game's pipeline handler names ("splitter", "prepender", "decoder", "encoder"...)
  working: the game, Via (`:via`) and the Eagler stage place handlers relative to them.
- Eagler (`incari.net.eagler`): written from the wire protocol. Never copy EaglerXServer's code
  (it's "All Rights Reserved"); reading it to learn the protocol is fine. A format change must
  still match its converters byte for byte (ADR 0016 says how that was checked).
- Networking changes need a human reviewer (`.github/CODEOWNERS`).
- Test: `./gradlew :net:check`; end to end, `./gradlew :app-desktop:smokeServer` (README).
