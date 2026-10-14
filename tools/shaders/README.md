# tools/shaders: the game's shaders, translated for WebGL2

Translates every shader stage the game can compile into GLSL ES 3.00 at build time (ADR 0007), so
the browser needs no shader compiler. `./gradlew :app-web:shaders` writes
`app-web/build/web-shaders/shaders.json` (read by `render-webgl2`) and `shaders.txt` (what was
translated and what WebGL2 can't run). Both hold translated Mojang shaders: never committed.

## How it works

1. `GameShaderFiles` reads shader sources and post effects from the client jar.
2. `PipelineCatalog` loads the game's pipeline registry on this JVM and lists every distinct stage.
3. `Includes` expands includes, `SpirvCompiler` compiles with shaderc, `EsslTranslator` turns SPIR-V
   into GLSL ES with SPIRV-Cross, and `EsslRewrites` fills WebGL2's gaps (texel buffers become 2D
   textures; output arrays become separate outputs). Stages that depend on 0-to-1 depth get a
   second variant.
4. `GlslangCheck` compiles and links each pipeline with `glslangValidator`.

## Invariants

- **Nothing here ships.**
- **Vanilla shaders must translate.** Only missing WebGL2 features are allowed, each listed with why.

## How to test

```sh
./gradlew :tools:shaders:check
./gradlew :app-web:shaders          # the real run (needs ./incari setup)
```
