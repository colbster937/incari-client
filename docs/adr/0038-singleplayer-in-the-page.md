# ADR 0038: Singleplayer in the page

- Status: accepted
- Date: 2026-10-07

## Context

Singleplayer was planned for much later. The plan changed: all of vanilla works before the client
builds its own features, singleplayer included. The integrated server was always in the browser
build, but nothing had opened a world. The first try failed to save `level.dat` and then waited
forever for its first chunk.

This ADR is stage one: singleplayer working with the integrated server on the page's one thread.
ADR 0039 moves the server into a worker.

## Decision

### Getting the server to run

- **The game's wait loops end their frame:** world load and save-and-quit pump events from inside a
  loop, so the page never reached the point between frames where the server and pool run.
  `SDLEventHandler.pumpEvents` now ends the frame there. No-op on the desktop.
- **A blocking pool task gives up the pool** (`IdleTimeExecutor.beforeWait`, via `Waits`): world
  generation joins work it queued on the same pool, which never ran with the one runner holding it.
  Another runner takes over. `IdleTimeExecutorTest`.

### Class library fixes (found because singleplayer is the first heavy user)

| What | Bug | Fix |
| --- | --- | --- |
| `GZIPOutputStream.flush()` | Threw on zlib's "no progress" | web-classlib's own |
| `Deflater`, `DeflaterOutputStream` | Threw mid-stream; miscounted input | web-classlib's own over jzlib (`DeflateProbe`) |
| `InflaterInputStream.skip` | Overwrote input not yet inflated | An addition skipping into scratch |
| `ByteBuffer.allocateDirect` | Not zeroed: region headers garbage | teavm-patches |
| Polled `ReferenceQueue` references | Lost their class's methods (Optimize World trapped) | teavm-patches (`WeakQueueProbe`) |
| Netty's local channels | Reflection, two NIO exceptions, interface probing | Declared, added, fixed ids |
| `STBImageResize` | Missing | `ImageResize` in Java |
| Logged crashes | Lost their cause | The console logger walks the chain |

### What a page does instead of a folder or a port

- **Folder buttons download zips** where the platform can't show folders (`FolderDownloads`): Open
  World Folder, Open Backups Folder. Pack folders keep the file picker.
- **Drop a world on the world list to import it** (`WorldImport`): a zip or folder with a
  `level.dat` becomes a new world, never overwriting one. Entries pointing outside are refused.
- **Open to LAN fails cleanly:** `PlatformNetwork.canAcceptConnections` is false, and the game's own
  "couldn't host" path shows. Sharing through a relay came later (ADR 0062).

## Verified

Headless Chrome: create, generate, play, pause, save and quit, reopen, quit again; every chunk in
every region file inflates with the browser's `DecompressionStream`. Backups, Edit World, World
Options and Optimize World work.

## Consequences

- **The server shares the page's thread:** 60 ms frames during world generation, and "Can't keep
  up". ADR 0039 fixes this.
- **Worlds sit in memory** as well as OPFS (until ADR 0041).
- **"UUID of added entity already exists"** a few times a session: entities load twice, the game
  keeps the first, and saves hold no duplicates. Cause open.
- **The loading screen's chunk map** costs half the page's time while a world loads.
