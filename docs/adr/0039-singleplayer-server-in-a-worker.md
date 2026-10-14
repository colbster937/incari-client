# ADR 0039: Singleplayer's server in a Web Worker

- Status: accepted
- Date: 2026-10-07

## Context

ADR 0038's server on the page's thread works, but its ticks and world generation take time from the
client's frames (60 ms frames after joining a new world, the server seconds behind). On the
reference Chromebook that's the difference between playable and not. Web Workers share nothing with
the page: own WebAssembly instance, own heap, messages only.

## Decision

### The same program, in a worker

- **No second module:** the worker loads the game's own (cached) module and starts it with a role:
  `WebMain.main(["--worker", "server"])` runs `GameWorkerMain`, never the client. A second compile
  would double build time and download for nearly all-shared code.
- **`PlatformThreads.startGameWorker`** runs `game-worker.js`, which loads the module as `boot.ts`
  does (with JSPI for Fast). Page and worker talk through a `PlatformChannel` of transferred byte
  messages. The desktop keeps the vanilla integrated server; `?singleplayer=page` keeps ADR 0038's way.
- **The worker's platform is headless** (`WorkerPlatform`): threads, pool, files. Pool slots are 8 ms
  turns of the event loop (`WorkerSlots`).
- **The worker reads the client jar** from OPFS where the page cached it.

### The world's files move with the server

- **Hand-over:** the page sends the world's files and forgets them (`PlatformStorage.handOver`); on
  stop the worker sends them back (`takeOver`). Never in both heaps. (ADR 0041 later leaves region
  files in place.)
- **The worker's writes go to storage through the page** every 250 ms (ADR 0041 later made this direct).
- **A new world's settings are written on the page** before hand-over, so the worker opens every
  world the same way.

### The server

- **`WorkerServer`**: a `MinecraftServer` written for the worker, doing what `IntegratedServer` does
  for one player. Pause and view distances arrive by message. The owner's data goes into
  `level.dat`, so worlds move between browser and desktop unchanged.
- **The client connects as if remote:** the game's pipeline, one packet per message over the channel
  (`ChannelLink`). No compression or encryption.
- **Hooks:** opening (`Minecraft.doWorldLoad`: write data, start the worker, show its progress,
  connect), leaving (`Minecraft.disconnect`: save and stop with "Saving world"), pausing, and the
  world icon (taken on the page, sent to the worker).
- **World Options** asks a `SingleplayerHost` (the integrated server, or the worker's reported
  settings, changed by message). Open to LAN fails cleanly.

## Verified

`singleplayer.steps`, both builds: create, play, World Options and a command, save, ~1,100 chunks
inflate, reopen, download, import, delete. A worker-made world opens in page mode too.

Desktop frames in the 5 s after joining a new world, then after: server on the page 32, 41, 56, 55
fps; in a worker 36, 58, 59, 58 fps.

## Consequences

- **Two copies of the game run;** the worker's heap (registries, data packs, world) adds to the
  page's. Measuring both together is next.
- **The worker starts in a few seconds per world** (later started early, ADR 0041).
- **Logs** carry the worker's own thread names in the page's console.
- **"UUID of added entity already exists"** still appears: a chunk's entities load twice. The game
  keeps the first; saved files hold no duplicates. Why it happens here and not on the desktop is open.
- **The desktop is unchanged.**
