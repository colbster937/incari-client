# ADR 0007: The WebGL2 backend, under Mojang's frontend, with shaders translated at build time

- Status: accepted; the unsupported pipelines and the depth range are superseded by
  [ADR 0012](0012-webgl2-backend-complete-and-screenshot-diffs.md)
- Date: 2026-09-27

## Context

26.3 draws through `com.mojang.renderpearl`: a validating frontend (`FrontendGpuDevice`) over a
backend (`GpuDeviceBackend`: OpenGL or Vulkan). Pipelines compile at run time with shaderc and
SPIRV-Cross, native libraries a page doesn't have; compiling them to WebAssembly would add megabytes
for work with the same answer every time. WebGL2 also differs in shaders (GLSL ES 3.00) and device
features (no mapping, base vertex, multi-draw, per-target blend).

## Decision

- **The backend sits under Mojang's frontend:** `render-webgl2` implements `GpuDeviceBackend`, and
  `WebGl2Backend` wraps it in `FrontendGpuDevice`, so the game's validation stays in front. The
  device reports what WebGL2 lacks, so the game takes its plain paths.
- **Shaders are translated at build time:** `tools/shaders` loads the game's pipeline registry on the
  build JVM and compiles every distinct stage to GLSL ES 3.00 with shaderc and SPIRV-Cross, set up as
  the game does. Varyings are renamed by location; blocks and samplers keep the game's names. The
  result and its reflection go in `shaders.json` beside the page (never in the TeaVM output, never
  committed).
- **glslang checks every program**; `?shaders=eager` links them all in the browser at start-up.
- **One hook** (`PipelineCompilingBackend`) lets the backend compile its own pipelines from the
  manifest, so the frontend never creates shaderc. A resource pack that changes a vanilla shader gets
  the vanilla translation, with a warning.
- **Programs link on first use** (each link costs milliseconds on a Chromebook).
- **The game's copy of a buffer is the truth;** writes are uploaded through linear memory.
- **Gaps filled in the device:** attributes re-pointed for a base vertex, one shared vertex array,
  the first target's blend for all, texture views as mip ranges, no timestamps.
- At first, 33 of 216 pipelines (texel buffers, indexed fragment outputs) drew nothing; ADR 0012 fixed them.

## Consequences

- Most of the render API runs unchanged, and the frontend validates every call before the backend.
- Resource packs can't change shaders in the browser.
- `tools/shaders` runs game code at build time and fails loudly if a version reaches beyond the
  minimal platform it installs.
