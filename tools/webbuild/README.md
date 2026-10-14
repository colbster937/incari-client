# tools/webbuild: the browser build's compiler

Compiles the game for the browser with TeaVM (WebAssembly GC): the game as `:client` built it, the
web platform, `web-classlib`, and the libraries `tools/survey/rules.txt` keeps. Output goes to
`app-web/build/teavm/` (Compatible) or `teavm-fast/` (Fast), never committed or published. It also
holds the dev server, browser checks, screenshot diffs, benchmark and packaging. You run it through
`app-web`'s Gradle tasks.

```sh
./gradlew :app-web:teavm -Pteavm.verbose     # with TeaVM's call chains for link errors
```

## What changes what TeaVM sees

`Main` first checks that `tools/teavm-patches`' classes, not TeaVM's, are loaded. Then:

| Class | Does |
| --- | --- |
| `ClasslibPolicy`, `ClasslibRenamer` | Map `web-classlib`'s `T<Name>` substitutes over JDK classes |
| `AdditionsTransformer` | Add `web-classlib`'s additions to TeaVM's classes, or replace methods |
| `RemovalTransformer`, `Removals` | Remove references to missing symbols the survey files under *delete* or *rewrite* |
| `MonitorElision` | Drop monitors from methods that can't suspend, so they don't become coroutines (ADR 0006) |
| `TypeCaptures` | Keep generic superclasses that `TypeToken`s and Netty handlers read at run time |
| `JspiTransformer` | Route fibers through JSPI in the Fast build (ADR 0036) |
| `JsoIntrinsics`, `MathIntrinsics` | Cheaper JavaScript calls; `Math.floor`/`ceil`/`sqrt` as wasm instructions |
| `ThrowCensus` | Count throws (`-Pteavm.throwCensus`, ADR 0033) |
| `WasmOpt` | Run Binaryen's wasm-opt on the Fast release build (ADR 0043) |

## Tools

| Class | Task | Does |
| --- | --- | --- |
| `Serve` | `serve` | Localhost-only static server with the right `.wasm` type and isolation headers |
| `BrowserCheck`, `DevTools`, `Chromium` | `browserCheck` | Runs the page in headless Chromium; passes on the game loop's heartbeat, fails on a crash. Runs step scripts |
| `DownloadSize`, `DownloadBudget` | `checkDownloadSize` | Sizes (raw, gzip 9, brotli via the `brotli` command) against the budget |
| `ScreenshotDiff`, `ImageDiff` | `screenshotDiff` | Desktop vs browser renders by SSIM (ADR 0012) |
| `Benchmark`, `BenchmarkReport`, `PerfBaseline`, `PerfBudget` | `benchmark` | Scenario runs against a baseline ([`docs/benchmark.md`](../../docs/benchmark.md)) |
| `BuildManifest`, `WebFolder`, `SingleFile`, `SmallSounds` | `buildManifest`, `webFolder`, `singleFile` | The manifest, the self-hosted folder, the one-file build |

## Outputs beside the build

- `problems.txt`: every link error, by missing symbol, with call sites.
- `async.txt`: why methods are coroutines, and hot virtual calls still compiled as suspension points.
- `removed.txt`: every reference removed, by the rule that removed it.

## Invariants

- Removal decisions belong to the survey: change `tools/survey/rules.txt`, never special-case a
  symbol here. Only missing symbols are removed.
- A missing stub or shim stays a link error until `web-classlib` provides it.
- Runs on exactly JDK 25, like the survey.
