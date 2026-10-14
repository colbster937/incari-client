# platform-web

- Only `platform-api`, `org.teavm.jso.*`, `org.teavm.interop`, TeaVM's file system interface
  `org.teavm.runtime.fs` (which `storage/` implements) and the JDK. Never game types
  (`BoundaryTest`).
- Code here runs under TeaVM: no reflection, no JDK APIs TeaVM lacks, no blocking waits.
- DOM listeners and promise callbacks never call Java (TeaVM coroutines can't start there): they
  push onto the canvas's event array, drained at poll. Only pure static functions, like
  `DomEventQueue.consumesKey`, may be called from a listener. See ADR 0010.
- An `@Async` method reports failure by completing with a value that says so, and the Java side
  throws: never `AsyncCallback.error`. TeaVM's WebAssembly GC runtime also rethrows that error out
  of the JavaScript callback after the caller has caught it, and the page crashes
  (`app-web/probes/FetchFailureProbe`).
- `@JSBody` scripts: no immediately invoked function expressions; TeaVM's printer drops their
  parentheses and the module fails to load. Declare the function, then call it.
- Audio (ADR 0013): positions are panned by `Spatializer`, which matches the desktop's OpenAL Soft
  as measured by `:platform-desktop:openAlReference`; don't swap in Web Audio's panner. Per-frame
  source updates allocate nothing and send gains only when they change.
- The storage worker (`src/main/js/storage-worker.js`) is plain JavaScript checked through its
  JSDoc types: keep it `@ts-check` clean, and keep the page's side (`BrowserStore`) and it speaking
  the same messages. File calls never suspend; nothing waits on the worker except startup.
- The game's background pool runs in the time the game's frames leave over (`IdleTimeExecutor`): work handed to it must never be waited on from the render thread for longer than a frame.
  The frame monitor (`Dom.installFrameMonitor`) counts frames over 50 ms and flags those in which the
  render thread waited; keep `endFrame` calling `Dom.frameEnded` just before it yields.
- Test: `./gradlew :platform-web:check`, `npm run check` for the worker, and for input, audio or
  storage changes the browser check scripts in `app-web/browser-checks/`.
