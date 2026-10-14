# ADR 0037: The Unicode font and the languages from the asset index

- Status: accepted; the panorama is no longer fetched (menus show Incari's own photo, `MenuBackdrop`)
- Date: 2026-10-05

## Context

The game reads both the client jar and the assets directory a launcher fills from the asset index.
The page took only sounds from the index (ADR 0013), and it showed:

- **Only English:** the jar has `en_us.json`; the other 142 languages (and the language list,
  `pack.mcmeta`) are in the index.
- **No Unicode font** (`font/unifont*.zip`, 1.8 MB): CJK, most non-Latin scripts and many symbols drew
  as boxes.
- **A grey title panorama:** the jar holds blank stand-ins. (Since replaced by Incari's own photo.)

These are Mojang's files: fetched by `./incari assets` into the ignored cache and copied into the
builder's own folder (ADR 0028), like the sounds. Nothing is committed.

## Decision

- **The build copies them:** `WebFolder` copies `sounds.json`, `pack.mcmeta`, `minecraft/font/` and
  `minecraft/lang/` with the sounds. `--no-sounds` still leaves the whole index out.
- **The language list is fetched at start**, with `sounds.json`, since the pack repository reads it
  once before the first reload.
- **The rest is fetched before the listeners that read it reload** (`RemoteAssets.gate`, through the
  existing reload hook, no new patch): the font and the selected language are fetched on the
  background pool while the language and font managers' reloads wait; others start at once. Files
  already written are skipped, so later reloads fetch only a newly picked language.
- **A failed fetch is logged** and the reload continues with what's there.
- **The service worker keeps them** between loads (content-addressed objects).
- The desktop reads its full assets directory as before.

## Consequences

- Every language can be picked, and text outside the bitmap font draws.
- A first visit downloads ~1.8 MB more for the font (0.6 MB more for a non-English language).
- The folder grows by the languages (85 MB on disk; only one is downloaded).

## Alternatives rejected

- **A file system that fetches on open:** the page's file system never suspends (ADR 0011).
- **Fetching when the language screen closes:** a new patch, a render-thread wait, and it misses a
  language saved from an earlier visit.
- **Incari's own font:** only needed for a distributable build, which none is.
