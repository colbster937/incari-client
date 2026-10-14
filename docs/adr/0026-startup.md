# ADR 0026: Start-up

- Status: accepted
- Date: 2026-10-01

## Context

The plan: pre-build what can be pre-built, cache processed state, stream the compile, defer what the
title screen doesn't need. Done when a warm start hits a time budget (set here, since none had been set
without the Chromebook).

Measured first (desktop, development build): cold start 10.5 s, warm 8.7 s. The warm start's profile
(`-Pbenchmark.profile` now records one):

| What | Time |
| --- | ---: |
| Data fixer (removed by ADR 0025) | 2.1 s + GC |
| Loading screen fade after everything loaded | 2.0 s |
| Block state definitions and models | 1.5 s |
| Bootstrap: block state shape caches | 1.1 s |
| Atlases: decode, stitch, mip | 0.6 s |
| Model discovery lookups (TeaVM scanned maps) | 0.27 s |
| Copying the module through the loader's byte counter | 0.25 s |
| Interface type checks over long tag-range lists | 0.24 s |

The benchmark now splits a warm start: to the first frame, to the end of the first reload, and the fade.

## Decision

- **Models load after the title screen** (`DeferredModels`): the first resource reload skips the
  model manager and entity/block-entity dispatchers; a second reload runs them once the title is up.
  Joining while they load shows "Loading Minecraft" until done. A later reload runs everything
  together, as the game does. Page only (`-Pincari.browserStartup` tries it on the desktop).
- **Vanilla sprites decoded at build time** (`tools/sprites`, `SpriteCache`): 3142 sprites with their
  mip levels, ~1.06 MB in the asset pack, kept in OPFS. The page uses them instead of decoding PNGs;
  pack and animated sprites go the game's way. `SpritesTest` compares every byte.
- **Block state answers once per shape** (`BlockStateSharing`): support answers depend only on the
  shape, which most of the 35,000 states share. A build-time registry snapshot would have to rebuild
  `VoxelShape` identities, for less gain.
- **No copy through the loader:** a module the service worker already has goes straight to the
  browser's compiler instead of through a byte-counting stream.
- **Faster fade** (`LoadingFade`): half a second, not two.
- **TeaVM fixes:** unmodifiable collections ask the wrapped collection (`TCollections`); interface
  type checks binary-search their tag ranges (`WasmGCSupertypeFunctionGenerator`).

Not done: **pre-built baked models**. Models no longer delay the title, and serializing the model
graphs would need a format kept in step with every game version, to save time a join mostly spends
on the network.

## Results

Desktop, 2026-10-01 (before: development build; after: release build, now what's measured):

| | Before | After |
| --- | ---: | ---: |
| Warm start to title | 8.7 s | 2.1 s (first frame 1.1 s, reload done 1.6 s) |
| Cold start to title | 10.5 s | 2.9 s |
| Join the benchmark server | 11.4 s | 5.1 s |

Desktop budgets (3.0 s warm, 4.5 s cold) hold; the Chromebook's (10 s, 20 s) are estimates.

The pre-built sprites first drew blocks blank: TeaVM's absolute `ByteBuffer.put` copied nothing when
the source had no `remaining()`. Fixed in `TByteBuffer`; screenshot diffs 14/14 again.

## Consequences

- The first join after start-up may wait for models. `?models=now` and `?sprites=off` turn the two
  changes off to compare.
- `sprites.bin` is rebuilt with the game; a stale or missing copy costs time, not correctness.
- The Chromebook's time budgets wait for the device.
