# ADR 0014: The loader page, the build manifest, the service worker and the asset pack

- Status: accepted; the JavaScript fallback and the manifest's `wasm`/`js` entries are superseded by
  ADR 0036 (Fast and Compatible builds, manifest format 2)
- Date: 2026-09-28

## Context

The page was a stub: it fetched the whole module (29 MB gzip) every visit, showed nothing useful
while doing so, and gave no reason when a browser couldn't run it.

What a player's browser downloads falls into three groups (ADR 0001): the **build** (the module and
what's prepared at build time; contains Mojang code, so it's self-hosted, never published by the
project), **Mojang's files** (client jar, asset index, sounds), and **the page**.

## Decision

- **The page is a loader** (`index.html`, `boot.ts`): a loading screen with a progress bar and the
  current step. It checks the browser (`features.ts`), reads the manifest, picks a build, registers
  the service worker, downloads with byte counts, and starts the game, which reports its own steps
  through `window.incariLoader`. Failures stop with a sentence the player can act on.
- **TypeScript compiled by stripping types** (Node's `stripTypeScriptTypes`, `:app-web:pageScripts`):
  erasable syntax only, no bundler.
- **`build.json`** lists the game version, release flag, targets, the asset pack, and every file's
  size and SHA-256. Files are requested as `name?v=<hash>`.
- **Streaming compile with progress:** the module compiles while it downloads.
- **A service worker** (`sw.ts`, `cache-policy.ts`) caches versioned build files and sounds: a
  returning player downloads only what changed. The page and its starting files come from the network
  with a cached fallback, so play works offline. The client jar passes through (the storage worker
  keeps it). `?sw=off` unregisters it.
- **The asset pack** (`incari.pack`): build-time data for the game (shaders, later sprites and Via's
  mappings), mounted read-only at `/incari-pack` like the jar. Never published.
- **Release builds:** `-Pteavm.release` (TeaVM ADVANCED, obfuscated, no debug data). Strict null
  checks stay on: the game catches `NullPointerException`, and without them a null dereference traps.
- **The dev server serves the page by name**, so editing it needs no recompile.

## Consequences

- A returning player downloads only changes. On the desktop a dev build starts in ~0.7 s from a local
  server; Chromebook loads wait for the device.
- Browsers without module workers run uncached, with a warning.
- The JavaScript fallback built and ran (60 fps standing, 36–44 fps in fresh terrain), but was later
  dropped for the Fast and Compatible builds (ADR 0036).
- `serve`, `browserCheck`, `screenshotDiff` and `checkDownloadSize` build the page and pack too.
