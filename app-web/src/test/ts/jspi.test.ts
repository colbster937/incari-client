import assert from "node:assert/strict";
import { setImmediate } from "node:timers/promises";
import { describe, it } from "node:test";

import { createJspiImports, supportsJspi, type JspiScope } from "../../main/ts/jspi.ts";

/**
 * A module shaped like a JSPI build's fibers: its `teavm.jspi.run(id)` export waits on promise `id`
 * through `teavmJspi.await`, then calls `t.done(id)`.
 */
// prettier-ignore
const FIBER_MODULE = new Uint8Array([
  0x00, 0x61, 0x73, 0x6d, 0x01, 0x00, 0x00, 0x00, // "\0asm", version 1
  0x01, 0x05, 0x01, 0x60, 0x01, 0x7f, 0x00, // types: (func (param i32))
  0x02, 0x1c, 0x02, // imports, 28 bytes, two:
  0x09, 0x74, 0x65, 0x61, 0x76, 0x6d, 0x4a, 0x73, 0x70, 0x69, // "teavmJspi"
  0x05, 0x61, 0x77, 0x61, 0x69, 0x74, 0x00, 0x00, // "await", func type 0
  0x01, 0x74, 0x04, 0x64, 0x6f, 0x6e, 0x65, 0x00, 0x00, // "t" "done", func type 0
  0x03, 0x02, 0x01, 0x00, // functions: one of type 0
  0x07, 0x12, 0x01, 0x0e, // exports, 18 bytes, one, a 14-byte name:
  0x74, 0x65, 0x61, 0x76, 0x6d, 0x2e, 0x6a, 0x73, 0x70, 0x69, 0x2e, 0x72, 0x75, 0x6e, // "teavm.jspi.run"
  0x00, 0x02, // function 2
  0x0a, 0x0c, 0x01, 0x0a, 0x00, // code, one body of 10 bytes, no locals:
  0x20, 0x00, 0x10, 0x00, // local.get 0; call await
  0x20, 0x00, 0x10, 0x01, // local.get 0; call done
  0x0b, // end
]);

/** A scope whose JSPI wrappers record their use, for the bookkeeping alone. */
function fakeScope(promising: (fn: (id: number) => unknown) => (id: number) => Promise<unknown>): JspiScope {
  return {
    WebAssembly: {
      Suspending: class {
        readonly fn: (id: number) => unknown;
        constructor(fn: (id: number) => unknown) {
          this.fn = fn;
        }
      },
      promising,
    },
  };
}

describe("supportsJspi", () => {
  it("finds JSPI in an engine that has it", () => {
    // Node 26 ships JSPI, as Chrome 137+ does.
    assert.equal(supportsJspi({ WebAssembly }), true);
  });

  it("reports no JSPI without Suspending and promising", () => {
    assert.equal(supportsJspi({ WebAssembly: {} }), false);
    assert.equal(supportsJspi({}), false);
  });
});

describe("createJspiImports", () => {
  it("keeps a fiber's stack until its promise resolves (real JSPI)", async () => {
    const done: number[] = [];
    const jspi = createJspiImports((error) => {
      throw error;
    });
    const { instance } = await WebAssembly.instantiate(FIBER_MODULE, {
      teavmJspi: jspi.module as WebAssembly.ModuleImports,
      t: { done: (id: number) => done.push(id) },
    });
    jspi.attach(instance.exports);
    const create = jspi.module.create as () => number;
    const run = jspi.module.run as (id: number) => void;
    const resolve = jspi.module.resolve as (id: number) => void;

    const id = create();
    run(id);
    // The fiber waits, and run returned to its caller.
    assert.deepEqual(done, []);
    assert.equal(jspi.pending(), 1);

    resolve(id);
    await setImmediate();
    assert.deepEqual(done, [id]);
    assert.equal(jspi.pending(), 0);
  });

  it("ends every waiting fiber when told to, and reports nothing on the way out", async () => {
    const done: number[] = [];
    const failures: unknown[] = [];
    const jspi = createJspiImports((error) => failures.push(error));
    const { instance } = await WebAssembly.instantiate(FIBER_MODULE, {
      teavmJspi: jspi.module as WebAssembly.ModuleImports,
      t: { done: (id: number) => done.push(id) },
    });
    jspi.attach(instance.exports);
    const create = jspi.module.create as () => number;
    const run = jspi.module.run as (id: number) => void;

    const first = create();
    const second = create();
    run(first);
    run(second);
    assert.equal(jspi.pending(), 2);

    jspi.terminate();
    await setImmediate();
    // Both resumed (a real build's fiber then traps); none is left waiting.
    assert.deepEqual(done.sort(), [first, second].sort());
    assert.equal(jspi.pending(), 0);
    assert.deepEqual(failures, []);

    // A fiber that waits after the end resumes at once.
    const late = create();
    run(late);
    await setImmediate();
    assert.ok(done.includes(late));
  });

  it("forgets a promise dropped by a call that completed before waiting", () => {
    const jspi = createJspiImports(
      () => undefined,
      fakeScope(() => () => Promise.resolve()),
    );
    const create = jspi.module.create as () => number;
    const drop = jspi.module.drop as (id: number) => void;
    const first = create();
    const second = create();
    assert.notEqual(first, second);
    drop(first);
    assert.equal(jspi.pending(), 1);
  });

  it("reports an error that escapes a fiber", async () => {
    const failures: unknown[] = [];
    const jspi = createJspiImports(
      (error) => failures.push(error),
      fakeScope(() => () => Promise.reject(new Error("escaped"))),
    );
    jspi.attach({ "teavm.jspi.run": () => undefined });
    (jspi.module.run as (id: number) => void)(1);
    await setImmediate();
    assert.equal(failures.length, 1);
    assert.match(String(failures[0]), /escaped/);
  });

  it("refuses to start a fiber before the exports are attached", () => {
    const jspi = createJspiImports(
      () => undefined,
      fakeScope(() => () => Promise.resolve()),
    );
    assert.throws(() => {
      (jspi.module.run as (id: number) => void)(1);
    }, /before the module's exports/);
  });
});
