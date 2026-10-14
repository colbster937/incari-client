# An experimental WebGPU backend

Research from 2026-10-08. Not a decision: the spike below would end in an ADR either way.

## Status: shelved (2026-10-09)

The floor device has little for WebGPU to win. A frame already makes few draws, a WebGPU call from
Java costs about what a WebGL2 call does, and WebGL2 would have to stay in full beside it. A High
tier would serve players who already reach 60 FPS, and the client's features come first.

Revisit when both hold:

1. The reference Chromebook's `chrome://gpu` shows WebGPU on for its GPU class.
2. A floor-device profile shows a cost only WebGPU can remove (such as CPU occlusion culling), or
   M4 and M5 are far enough along that a High tier is next.

If the spike runs, its gate must show a win, not parity.

## In short

- **Little to win on the floor device.** After ADRs 0022, 0024 and 0030 a frame makes ~18 draws
  (fly-through) to ~31 (lobby). The lobby's 15 ms of render submit on `laptop-floor` is the game
  building models, text and particles, not API calls.
- **Uncertain reach.** ChromeOS has had WebGPU since Chrome 113, but which Chromebook GPUs get it is
  unpublished. On Linux, Chrome enables it only on Vulkan (off by default outside an allowlist).
- **Real wins elsewhere:** compute shaders, storage and indirect buffers, native base vertex and
  instance, timestamp queries. These open GPU culling and a High tier on stronger hardware.
- **Moderate cost:** renderpearl's Vulkan backend (~5,100 lines) maps almost one to one onto
  WebGPU, and the build already produces SPIR-V.
- **Java calls are cheap enough:** ~0.17 µs per call on `laptop-floor`, so the backend can stay in
  Java with no JavaScript replay layer.

## How renderpearl maps onto WebGPU

A WebGPU backend would be a third implementation of the backend interfaces under the game's
validating `FrontendGpuDevice`, modelled on the Vulkan backend.

| renderpearl call | WebGPU | Gap |
| --- | --- | --- |
| Buffers and mapped writes | `createBuffer`, `queue.writeBuffer` | No persistent mapping on the main thread; keep the copy in linear memory, as WebGL2 does |
| Textures, views, samplers | `createTexture`, `createView`, `createSampler` | ~1:1 |
| `compilePipeline` | `createRenderPipelineAsync` | State is baked in, as in Vulkan |
| Render passes | `beginRenderPass` | Same shape |
| `setUniform` (Vulkan push descriptors) | Bind groups, cached by contents | No push descriptors; uncached changes allocate |
| `pushConstants` | `setImmediates` (Chrome 149+) | Fallback: a uniform ring with dynamic offsets |
| Base vertex/instance draws | Native | WebGL2's rebasing goes away |
| `multiDrawIndexed` | A loop of `drawIndexed`, or render bundles | Multi-draw indirect is experimental only |
| Timestamps | `timestamp-query` | — |

**Shaders** are the cheap part: add SPIR-V → WGSL (Tint or Naga) beside `shaders.json`. Care is
needed for combined samplers (split in WGSL), texel buffers (become storage buffers),
dynamically indexed outputs (ADR 0012's rewrite carries over), `gl_DrawID` (use `instance_index`),
and the GLSL shader rewrites (move them before SPIR-V so both backends share them).

## Measured (2026-10-09)

**Reach.** On Linux, no flags gives no adapter; `--enable-features=Vulkan` gives a real one on both
the RX 9060 XT and the UHD 620 (Gen9.5). The UHD 620 has `timestamp-query`,
`indirect-first-instance`, immediates and `shader-f16`, but not multi-draw indirect. Headless
Chromium also needs `--enable-unsafe-webgpu` (`-Pbrowser.gpu=webgpu` sets both).

**Call cost** (`app-web/probes/WebGpuCallProbe`: 2000 draws a frame, µs per draw, release builds):

| Route | Desktop Compatible | Desktop Fast | `laptop-floor` Compatible | `laptop-floor` Fast |
| --- | ---: | ---: | ---: | ---: |
| JSO, a call per command | 0.14 | 0.23 | 0.49 | 0.58 |
| One JavaScript loop | 0.06 | 0.07 | 0.16 | 0.16 |
| Replay from linear memory | 0.08 | 0.09 | 0.19 | 0.21 |
| Render bundle | 0.01 | 0.01 | 0.03 | 0.03 |
| WebGL2 via JSO | 0.13 | 0.22 | 0.33 | 0.52 |

- A JSO crossing costs ~0.17 µs. At our draw counts that's tens of µs a frame.
- A replay layer saves ~0.3 µs a draw: not worth a second language in the renderer.
- WebGPU calls cost about what WebGL2 calls do, so CPU wins must come from fewer calls (bundles,
  storage buffers, compute).
- Render bundles replay 15–20x cheaper than encoding: the terrain path.
- Fast's JSO calls cost 1.1–1.7x more (each call out of a JSPI stack pays a toll).
  `JsoIntrinsics` now passes `int`s as i31, cutting calls out by about a third.

The probe times CPU only, with a single-triangle pipeline.

## Risks

- **Reach:** the floor GPU class may get no WebGPU, so WebGL2 stays in full and renderer review doubles.
- **Bind group churn** can make WebGPU frames dearer than WebGL's.
- **Pipeline creation** stalls first use unless made ahead (219 pipelines).
- **Memory:** WebGPU adds GPU-process allocations on a 4 GB device.
- **Compatible build:** async WebGPU calls need coroutines there.

## Plan

A new module, `render-webgpu`, chosen with `?gpu=webgpu`, never the default. Each phase starts
only after the previous gate.

| Phase | Effort | Work | Gate |
| --- | --- | --- | --- |
| W0 Spike | ~2 weeks | Terrain on WebGPU in a probe page; CPU per frame vs WebGL2 on `laptop-igpu` and a Gen12 Chromebook | A clear CPU win on target GPUs |
| W1 Shaders | ~1 week | SPIR-V → WGSL; shared rewrites before SPIR-V | All 219 pipelines validate |
| W2 Backend | 4–6 weeks | `render-webgpu`, title screen then world | `screenshotDiff` SSIM ≥ 0.996 |
| W3 Parity | ~2 weeks | Cached bind groups, terrain bundles, benchmark on both | No figure worse than WebGL2 |
| W4 High tier | open | Compute culling, storage-buffer instancing, longer render distance | — |

A failed W0 ends the work with an ADR saying why.

## Open questions

- Does ChromeOS enable WebGPU on Jasper Lake (Celeron N4500, Gen11), the reference Chromebook's class?
- When does compatibility mode (GLES 3.1) reach ChromeOS?
- What do the remaining calls out (~1,500 a frame on the desktop) cost on `laptop-floor`?
- Can the shader rewrites move before SPIR-V without changing `screenshotDiff` results?

## Sources

- [WebGPU is now supported in major browsers](https://web.dev/blog/webgpu-supported-major-browsers) (web.dev, Nov 2025)
- What's new in WebGPU: [Chrome 144](https://developer.chrome.com/blog/new-in-webgpu-144), [145](https://developer.chrome.com/blog/new-in-webgpu-145), [146](https://developer.chrome.com/blog/new-in-webgpu-146), [149–150](https://developer.chrome.com/blog/new-in-webgpu-149-150)
- [web3dsurvey: multi-draw indirect on ChromeOS](https://web3dsurvey.com/webgpu/features/chromium-experimental-multi-draw-indirect/platform/Chromium%20OS)
- [Tint's SPIR-V reader](https://dawn.googlesource.com/dawn/+/HEAD/docs/tint/spirv-reader-overview.md)
