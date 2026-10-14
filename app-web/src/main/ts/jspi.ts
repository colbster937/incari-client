/**
 * The JavaScript side of the Fast build's fibers (WebAssembly JavaScript Promise Integration; TeaVM's
 * patched `Fiber.Jspi` in tools/teavm-patches; ADR 0036). The Fast build has no coroutines:
 * a Java thread that blocks calls `teavmJspi.await`, which hands the engine a promise, and the engine
 * keeps the thread's whole WebAssembly stack until the promise settles. Each thread runs in its own
 * call of the module's `teavm.jspi.run` export through `WebAssembly.promising`, so suspending one
 * returns to whoever started it. The Compatible build imports none of this.
 *
 * The engine never frees a suspended stack, nor anything it reaches: a fiber still waiting when the
 * page goes away would keep the whole program alive in the tab's process through every reload. So
 * `terminate` (on `pagehide`) resumes each waiting fiber with an order to end, which it obeys with a
 * trap that unwinds its stack past every Java handler.
 */

/** What JSPI adds to `WebAssembly`; missing in engines without it. */
interface JspiApi {
  Suspending: new (fn: (...args: never[]) => unknown) => object;
  promising: (fn: (...args: never[]) => unknown) => (...args: never[]) => Promise<unknown>;
}

/** The parts of the global scope the imports read, so tests can supply their own. */
export interface JspiScope {
  readonly WebAssembly?: object;
}

export function supportsJspi(scope: JspiScope = globalThis): boolean {
  const api = scope.WebAssembly as Partial<JspiApi> | undefined;
  return typeof api?.Suspending === "function" && typeof api.promising === "function";
}

export interface JspiImports {
  /** The `teavmJspi` import module. */
  readonly module: Record<string, unknown>;
  /** Gives the imports the instance's exports, which they call to start a fiber. */
  attach(exports: Record<string, unknown>): void;
  /** Promises created and not yet settled or dropped, for tests and leak checks. */
  pending(): number;
  /** Ends every fiber: each waiting one now, any other at its next wait. */
  terminate(): void;
}

/** What a fiber's wait resumes with: carry on, or end (`Fiber.Jspi.suspend`). */
const RESUME = 0;
const END = 1;

/**
 * Builds the `teavmJspi` imports. `fail` gets an error that escapes a fiber: it can't reach the
 * starter's stack, which has moved on by the time the fiber's promise settles.
 */
export function createJspiImports(fail: (error: unknown) => void, scope: JspiScope = globalThis): JspiImports {
  const api = scope.WebAssembly as JspiApi;
  const promises = new Map<number, { promise: Promise<number>; resolve: (value: number) => void }>();
  let nextId = 1;
  let run: ((id: number) => Promise<unknown>) | null = null;
  let ending = false;
  // The fibers' traps on the way out aren't failures.
  const report = (error: unknown): void => {
    if (!ending) {
      fail(error);
    }
  };

  const module: Record<string, unknown> = {
    create(): number {
      const id = nextId++;
      let resolve!: (value: number) => void;
      const promise = new Promise<number>((r) => {
        resolve = r;
      });
      promises.set(id, { promise, resolve });
      return id;
    },
    // Hands the engine the promise: the calling fiber's stack waits for it.
    await: new api.Suspending((id: number): Promise<number> => {
      const entry = promises.get(id);
      if (entry === undefined) {
        throw new Error(`No JSPI promise ${String(id)} to wait for`);
      }
      if (ending) {
        promises.delete(id);
        return Promise.resolve(END);
      }
      return entry.promise;
    }),
    resolve(id: number): void {
      const entry = promises.get(id);
      promises.delete(id);
      entry?.resolve(ending ? END : RESUME);
    },
    // A call that completed before its fiber had to wait.
    drop(id: number): void {
      promises.delete(id);
    },
    run(id: number): void {
      if (run === null) {
        throw new Error("A fiber started before the module's exports were attached");
      }
      // Returns once the fiber ends or first waits; the rest runs as its promises settle.
      run(id).catch(report);
    },
  };

  return {
    module,
    attach(exports) {
      const runExport = exports["teavm.jspi.run"];
      if (typeof runExport === "function") {
        run = api.promising(runExport as (id: number) => unknown) as (id: number) => Promise<unknown>;
      }
    },
    pending: () => promises.size,
    terminate() {
      ending = true;
      for (const entry of promises.values()) {
        entry.resolve(END);
      }
      promises.clear();
    },
  };
}
