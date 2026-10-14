# tools/shaders

- Build-time only; never referenced by code that ships. May use LWJGL and the game freely.
- Keep the manifest format in `render-webgl2`'s `shaders` package, not here: both sides must agree.
- A shader WebGL2 can't run is recorded as unsupported with a reason, never silently dropped.
- Test: `./gradlew :tools:shaders:check`; run for real with `./gradlew :app-web:shaders`.
