# render-webgl2

- Only the game's render API (`com.mojang.renderpearl`), `incari.client.render`, TeaVM JSO and the
  JDK (`BoundaryTest`). JavaScript goes in `WebGl.java` only.
- Code here runs under TeaVM: no reflection, no blocking waits. The hot paths (`WebGl2RenderPass`,
  `GlState`, `Bindings`) don't allocate per draw.
- Pure logic (formats, layouts, the manifest) stays free of JSO calls so it's tested on a JVM.
- Renderer changes need a human reviewer (`.github/CODEOWNERS`).
- A WebGL extension is feature-detected with a fallback, and the fallback is written down (ADR 0012).
- Test: `./gradlew :render-webgl2:check`, then `./gradlew :app-web:browserCheck`. A change to what's
  drawn also needs `./gradlew :app-web:screenshotDiff` (local machine: a display and a GPU) to pass.
