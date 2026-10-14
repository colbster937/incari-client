# platform-desktop

- Only `platform-api`, LWJGL and the JDK. Never game types (`BoundaryTest`).
- Never copy code from `work/client` (Mojang's SDL/OpenAL classes). Implement from LWJGL's API
  and the `platform-api` Javadoc. `./incari copycheck` must pass.
- Test changes here on a machine with a GPU and audio; the game can't run without them.
- Test: `./gradlew :platform-desktop:check`.
