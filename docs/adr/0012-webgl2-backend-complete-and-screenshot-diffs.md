# ADR 0012: Every pipeline in WebGL2, and screenshot diffs against the desktop

- Status: accepted
- Date: 2026-09-28

## Context

After ADR 0007, 33 of 216 pipelines couldn't run: the clouds read a texel buffer (WebGL2 has none),
and the improved-transparency passes index an array of fragment outputs (GLSL ES 3.00 forbids it).
And nothing checked that the browser draws what the desktop draws.

## Decision

### Every pipeline runs

`tools/shaders` lists every pipeline the game can make (219 in 26.3, post-effect passes included)
and what each needs; all translate and link.

- **Texel buffers become 2D textures**, 2048 texels wide (element `i` at `(i & 2047, i >> 11)`). The
  build rewrites `texelFetch` (`EsslRewrites.texelBuffers`); the device re-uploads the texture only
  when the buffer changed (`TexelBufferTexture`).
- **Fragment output arrays become separate outputs**: the shader writes a private array, and a new
  `main` copies each element out (`EsslRewrites.fragmentOutputArrays`).
- **Depth runs 0 to 1 where the browser has EXT_clip_control**, so the game's reversed-Z works as on
  the desktop; the build translates a second variant of stages that read the depth define. Without
  the extension, the game's own −1-to-1 path draws the same images with less far-plane precision.
- **32-bit float targets fall back to 16-bit** where the browser can't blend 32-bit floats.
- **Wireframe** uses WEBGL_polygon_mode where present, else is skipped with one warning.
- **Texture uploads read linear memory in place.**

Atlases, mipmaps, anisotropy, framebuffers, post effects and uniform buffers needed no change.

### Screenshot diffs (`./gradlew :app-web:screenshotDiff`)

- **Two games, one script.** Each gets its own vanilla server with a fresh world from one seed. The
  desktop game (Mojang's OpenGL backend, the reference) is driven over a loopback line protocol
  (`SceneAgent`); the browser build over DevTools. Both run `scenes.txt`: RCON commands build each
  scene and place the camera.
- **Deterministic.** Worlds are frozen and stepped tick by tick from game time 40, so weather,
  clouds and animations line up. Built scenes float above y 150. Same options (1280×720, GUI scale 2,
  FOV 70, render distance 8, HUD hidden). A shot waits for three alike frames 1.5 s apart.
- **Compared by SSIM** (RGB mean, 11-pixel Gaussian window, `ImageDiff`), plus the share of pixels
  over 32/255 apart and a heat map.
- **Threshold SSIM ≥ 0.90** for every shot. Correct renders on two drivers score ≥ 0.996; broken ones
  (unlit terrain, a missing pass, a crash) scored 0.13–0.85.
- **The page must stay clean:** any WebGL error, incomplete framebuffer, shader failure or skipped
  pipeline fails the run.
- **Nothing rendered is committed:** shots are pictures of Mojang's assets.

## Results

2026-09-28, RX 9060 XT: desktop on Mesa radeonsi, browser on ANGLE's OpenGL over the same driver.
14 shots (terrain, translucency with and without improved transparency, entities, particles, sky,
GUI, distant terrain, post effects, weather, Nether, End): **SSIM 0.9967–1.0000**, ≤ 0.17% of pixels
changed.

## Consequences

- **Every vanilla pipeline draws in the browser** with no WebGL errors. The gaps cost little: a
  texture upload when clouds rebuild, a few moves per fragment in improved-transparency passes.
- **The diff found bugs nothing else had:** the End crashed twice (an empty immutable map divided by
  zero; the dragon's phases lacked reflection metadata); two item pipelines never reached the
  manifest; uniform ranges were too small for Mesa's layout under ANGLE.
- **The GPU matters.** Headless Chromium defaults to SwiftShader; the harness uses the real GPU via
  ANGLE (`--gpu gl`). SwiftShader (`-Pscreenshots.gpu=swiftshader`, all CI has) renders correctly
  but scores lower and isn't the gate.
- **Needs a display and GPU**; a full run takes about ten minutes.
- **For later:** the manifest grew 29.6 → 43.5 kB gzip (the 0-to-1 variants ~10.6 kB could load
  separately). To measure on a Chromebook: cloud re-uploads, the improved-transparency targets, and
  whether its GPU has EXT_clip_control.
- Read-backs (screenshots) still block on `readPixels`; async is for when something calls them per frame.
- Resource packs still can't change shaders in the browser (ADR 0007).
