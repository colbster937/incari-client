# platform-api

- Only `java.*` and `org.jspecify.*` imports. No game types, no LWJGL, no TeaVM: this module is
  compiled into every build. `BoundaryTest` fails otherwise.
- Only JDK APIs TeaVM's class library has. When unsure, check `docs/port-backlog.md`.
- Every public type and method gets Javadoc that states its threading and ownership rules.
- Changing an interface changes both platforms: update `platform-desktop` and `platform-web` in
  the same change.
- Test: `./gradlew :platform-api:check`.
