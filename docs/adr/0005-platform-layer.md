# ADR 0005: The platform layer: whole-class replacements over Platform interfaces

- Status: accepted
- Date: 2026-09-26

## Context

26.3 talks to the machine directly: SDL3 for windowing and input, OpenAL for sound, Netty's NIO
and epoll transports for servers, the JVM's thread pools. The browser has none of these. This ADR puts
a platform layer under the game so the same code can run on the desktop (for development) and in
the browser, and proves it on the desktop first: vanilla must stay fully playable.

The SDL and OpenAL calls sit in about twenty classes (`com.mojang.blaze3d.platform`,
`com.mojang.blaze3d.audio`, `Blaze3D`) whose job is exactly this glue, plus a handful of calls in
game classes (`RenderSystem`, `Minecraft`, `MouseHandler`, `InputConstants`, `InputQuirks`).

## Decision

- **`platform-api` defines the platform** as plain Java interfaces with no game or library types:
  `Platform` (lifecycle, clock, event polling) with `PlatformWindow`/`PlatformDisplays`,
  `PlatformInput`, `PlatformShell` (clipboard, links, message boxes), `PlatformAudio`,
  `PlatformNetwork` (a byte stream), `PlatformStorage` and `PlatformThreads`. One platform is
  installed per process (`Platforms`).
- **Input uses SDL3's conventions as data**: scancodes are USB HID usage ids, keycodes follow SDL's
  rule, modifier bits are `SDL_Keymod` values, key actions press/release/repeat. That's what the
  game's key handling already reads, so the desktop passes events through untouched and the
  browser maps `KeyboardEvent.code` onto the same ids.
- **Glue classes are replaced whole** (`patches/classes.txt`) by Incari sources in
  `client/src/main/java` that keep the game-facing API and delegate to the platform. A replacement
  is written fresh (copycheck holds it to that) and carries the full quality gates; a patch that
  deleted the same SDL code would republish it as `-` lines. Classes that are mostly game logic
  (`VideoMode`, `InputConstants`, `RenderSystem`...) get small hook patches instead.
- **Policy moves with the OS calls.** Desktop windowing rules (borderless-window fullscreen on
  Windows, exclusive-mode fallback, mouse confinement in exclusive fullscreen) live in
  `platform-desktop`; the game's own rules (sizes, GUI scale, the fullscreen request) stay in the
  replacement `Window`.
- **Graphics keeps Mojang's backend API.** `PreferredGraphicsApi.getBackendsToTry` asks
  `GraphicsBackends`, which the entry point fills: Mojang's OpenGL and Vulkan backends on the
  desktop, WebGL2 in the browser. The backend creates the native surface through the
  platform (`NativeSurfaceFactory`).
- **Networking keeps Netty's pipeline.** Client connections use `PlatformSocketChannel`, a Netty
  channel over `PlatformNetwork`, so framing, compression and packets run unchanged over a socket
  on the desktop and a WebSocket in the browser. "Open to LAN" still listens with Netty NIO.
- **Threads**: the platform sizes the background pool; 0 means run inline, which the browser uses
  until Web Workers (ADR 0015, 0023).
- **The desktop runtime is verified by a scripted session** (`./gradlew :app-desktop:smokeTest`,
  and `smokeHost` + `smokeJoin` for two clients), driven by real SDL events.

## Deviations from vanilla

- macOS: the "Close Window" menu item isn't hidden (the desktop runtime doesn't drive the
  Objective-C menu); Command+W asks the game to close, which saves as usual.
- Audio: a device that opens but lacks a required OpenAL extension is skipped in favour of the
  next device rather than failing sound; sources are capped at the device's mono-source count.
- The smoke test turns off authentication on its own LAN world so an offline second client can
  join; vanilla always authenticates LAN guests.

## Alternatives rejected

- **Patching every SDL call site**: about 200 edits, each republishing Mojang lines in patch files,
  and conflicts on every drop that touches these classes.
- **A platform API in game types** (taking `Window`, `VideoMode`): would tie `platform-desktop` and
  `platform-web` to the vendor tree and break the module boundary.
- **Replacing Netty's pipeline with Incari's own protocol stack**: far more code, and the Via
  integration (ADR 0017) expects Netty.

## Consequences

- A new drop that changes a replaced class isn't a patch conflict; `./incari setup` still copies
  the replacement. Each drop needs a check that the replacements' API still matches (the compile
  catches most of it) and a smoke-test run.
- `platform-web` implements the same interfaces (ADRs 0006–0015).
