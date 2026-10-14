# ADR 0057: One HTML file (`./incari build --html`)

- Status: accepted
- Date: 2026-10-11

## Context

The goal: an offline HTML file a player opens from disk, as Eaglercraft's offline download
works. ADR 0028 left it out because `file://` pages can't do what the folder relies on. Measured in
Chromium:

- `fetch` of a neighbouring file fails, and so does `<script src>` unless it's a Blob URL.
- Module workers can't start from a Blob URL; classic ones can.
- A worker can't read the page's Blob URLs, only its own.
- OPFS throws; IndexedDB works (shared with Blob URL workers). No service worker.

The game loads files from the page and from four kinds of worker (storage, mesher, the server worker
and its terrain helpers), each fetching by URL.

## Decision

- **`./incari build --html [--out <file>]`** writes the web folder (without sound files) and
  `:app-web:singleFile` (`SingleFile`) packs it into one file, default `dist/incari.html`.
- **Every file is a `<script type="application/x-incari-file">`** with path, type and base64
  contents (gzipped where it saves a tenth). Service worker, `.htaccess` and `README.txt` stay out.
- **A file shim in every realm** (`offline.ts`): `fetch` of an embedded file answers from a Blob;
  `importScripts` and `new Worker` of embedded scripts start classic workers from the shim's own
  source, which installs the shim, receives the files (shared Blobs), then runs the script
  (`import()` for modules, with relative imports rewritten). Everything else (WebSockets, servers)
  goes to the browser as usual.
- **Lazy decoding:** scripts are decoded at once; everything else on first fetch, so the unpicked
  build and unused languages are never decoded. Workers ask the page for files they lack.
- **Two hooks** where scripts load without `fetch` (`boot.ts` and `game-worker.ts` use
  `incariFiles.url`). The game's Java code is unchanged.
- **The notice travels with the file:** a comment at the top says never to share it; `SingleFile`
  only overwrites its own files; the build refuses an output git would track.
- **`browserCheck -Pbrowser.html=<file>`** runs checks against a file.
- **The folder's client jar is cut to resources only** (`WebFolder.resourcesOnly`): 41 → 12 MB, for
  both outputs.

## Results

87 MB with both builds (languages, panorama, font and packs 32 MB, Compatible 28, Fast 15, jar 10);
~60 MB with `--only fast`. From `file://` in headless Chromium: title at 60 fps in both builds; in
Fast, singleplayer creates, saves, survives a reload from IndexedDB and reopens. Firefox reaches the
title. Not measured: the Chromebook, Firefox singleplayer, Compatible singleplayer.

## Consequences

- Undecoded file text stays in the DOM (15–28 MB for the unpicked build, 24 for languages), and the
  browser parses the whole file before the loader starts.
- The file was silent at first; ADR 0062 added every sound but music, re-encoded smaller.
- Every page opened from disk shares one origin in Chrome, so copies of the file share worlds.
- Licensing is unchanged: it's a build, for the person who made it.
