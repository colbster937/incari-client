# ADR 0022: Terrain in a few multi-draws a frame

- Status: accepted
- Date: 2026-09-29

## Context

Every WebGL call crosses from WebAssembly into JavaScript and is validated; on a Chromebook that
overhead, not the GPU, limits drawing. Terrain is most draws: each section's layer cost about eight
WebGL calls (uniforms, re-pointing attributes because WebGL2 has no base vertex, the index buffer,
the draw).

The 26.x renderer already keeps sections in shared heaps (`UberGpuBuffer`), culls with the occlusion
graph, groups by layer and heap, and on capable devices submits each group as one indirect draw with
per-instance section data. WebGL2 lacks indirect draws, base vertex and base instance, but has
`WEBGL_multi_draw` (many ranges in one call, told apart by `gl_DrawID`).

## Decision

- **Claim indirect multi-draws and base instance where `WEBGL_multi_draw` exists**, so the game takes
  its indirect terrain path. `?multidraw=off` turns it off.
- **Run indirect draws from the game's copy of the command buffer** (every buffer has one, ADR 0007).
- **Base vertex goes into the indices:** the backend keeps a 32-bit quad index pattern
  (`QuadIndices`) where quad `v / 4` already counts from vertex `v`, so a section draws from an
  offset with no base vertex. A heap is one `multiDrawElementsWEBGL`; sections at a vertex not a
  multiple of four take one extra call per remainder. At most four calls per heap's layer.
- **Base instance becomes `gl_DrawID`:** terrain vertex shaders are rewritten to read per-section
  data (16 bytes) from row `gl_DrawID` of a uniform block (`DrawIdRewrite`), filled per batch from a
  ring (`DrawRows`).
- **Translucent indices are rebased and kept** (`RebasedIndices`): each sorted section's indices are
  copied with the base vertex added, and kept until the game re-sorts it (tracked per 4 KiB page).
  Copying them every frame had cost more than it saved.

## Results

Desktop, render distance 8, three alternating runs each:

| | Fly-through off | on | Combat off | on |
| --- | ---: | ---: | ---: | ---: |
| Terrain draw calls | 553 | 9 | 513 | 9 |
| All draw calls | 629 | 85 | 591 | 87 |
| Render submit (ms) | 1.2 | 1.1 | 1.6 | 1.1 |

Terrain calls fell ~60x, past the tenfold target. The desktop holds 60 fps either way; render submit
falls a third in combat. On a Chromebook, where calls cost more, that's what matters.

Found on the way: a TeaVM heap bug (whole-page allocation after an exactly full heap) fixed in
`tools/teavm-patches`; and new typed-array views per multi-draw caused 290–370 ms GCs until views
were kept.

## Consequences

- The quad index buffer costs 24 bytes per quad of the fullest heap (a fifth of the vertices).
- Per-section uniform rebinding and attribute re-pointing are gone.
- Re-sorted translucent sections are copied again when they change (a handful a frame in water).
- Without `WEBGL_multi_draw`, terrain draws as before.
- This relies on how the game draws terrain (indirect only for terrain, one instance each, the quad
  pattern). Pipelines that break it skip their draws and say so; a changed pattern falls back to a
  draw per section.
