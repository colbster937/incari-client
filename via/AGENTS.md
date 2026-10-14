# via

- Netty, the Via libraries and Guava only: no game types, no platform code (`BoundaryTest`).
- Compiled into the browser build: no reflection of Incari's own, no blocking on an event loop,
  nothing that writes files. `IncariVia.load` blocks; only the connector thread calls it.
- Keep the stage's handler names (`via-decoder`, `via-relay`, `via-encoder`) and its placement
  after "splitter" and "prepender": compression and the Eagler stage depend on it.
- Upgrading Via: bump `viaversion` and `viabackwards` together in `gradle/libs.versions.toml`,
  then run the desktop version matrix (`:app-desktop:smokeServer` against each test server) and the
  browser checks. Networking changes need a human reviewer (`.github/CODEOWNERS`).
- Test: `./gradlew :via:check`.
