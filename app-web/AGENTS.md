# app-web

- Never commit or publish build output (`.wasm`, `.js` bundles, HTML with the game in it), and
  never link to a hosted build.
- The page's TypeScript (`src/main/ts`) runs in the browser with its types stripped
  (`:app-web:pageScripts`, Node's `stripTypeScriptTypes`), with no bundler: erasable syntax only,
  relative imports end in `.ts`, and anything testable goes in a module without side effects
  (`loader.ts`, `cache-policy.ts`) rather than `boot.ts` or `sw.ts`.
- TypeScript: no `any`; every eslint-disable comment says why; only erasable syntax (no enums,
  namespaces or parameter properties).
- The entry point sets library properties before anything reads them (`LibraryDefaults`: Netty in
  heap mode, JOML); keep new ones there.
- Test: `npm run check`, and `./gradlew :app-web:test` (`MemoryParityTest`: the game's vertex and
  image bytes over web-classlib's memory against LWJGL's; needs `./incari setup`).
- `@Async` methods complete with a value that says they failed and throw in Java, never through
  `AsyncCallback.error`, which crashes the page (`platform-web/AGENTS.md`, `probes/FetchFailureProbe`).
