// @ts-check
// A worker running a program of its own beside the game (ADR 0023): the mesher's WebAssembly
// module, which TeaVM compiles apart from the game. It runs as a classic worker from a Blob URL
// (platform-web's WebWorkerPool), so the page needs no extra file; the page's first message says
// where the program's loader and module are.
//
// The program's Java side installs `__incariProgram` on the worker's scope: `input(n)` gives a view
// of n bytes of its memory to copy a message into, `handle(n)` takes the message and answers with
// the length of its reply (or -1 for none), and `output(n)` views the reply.
//
// Messages from the page:
//   {kind: "start", runtime, module}          -> {kind: "ready"} or {kind: "failed", error}
//   {id, buffer, offset, length}              -> {id, reply: ArrayBuffer} or {id, error}
// A message's bytes are in `buffer` (an ArrayBuffer handed over, or the page's SharedArrayBuffer)
// from `offset`; id -1 wants no answer. Messages that come before the program has started wait for it.

"use strict";

/**
 * What the program installs.
 * @typedef {{
 *   input(length: number): Int8Array,
 *   handle(length: number): number,
 *   output(length: number): Int8Array}} Program
 */

/**
 * A message for the program.
 * @typedef {{id: number, buffer: ArrayBufferLike, offset: number, length: number}} Job
 */

/**
 * TeaVM's WebAssembly GC loader, which the program's runtime script defines.
 * @typedef {{wasmGC: {load(url: string): Promise<{exports: {main(args: string[]): void}}>}}} TeaVm
 */

/**
 * The parts of the worker's global scope this script uses.
 * @typedef {{
 *   postMessage(message: unknown, transfer?: Transferable[]): void,
 *   onmessage: ((event: {data: Job | {kind: "start", runtime: string, module: string}}) => void) | null,
 *   importScripts(url: string): void,
 *   TeaVM?: TeaVm,
 *   __incariProgram?: Program}} Scope
 */

/**
 * Starts serving the page's messages on `scope`.
 * @param {Scope} scope
 */
function serve(scope) {
  /** @type {Program | null} */
  let program = null;
  /** @type {Job[]} */
  const waiting = [];

  /**
   * @param {Program} running
   * @param {Job} job
   */
  function handle(running, job) {
    /** @type {Int8Array<ArrayBuffer>} */
    let reply;
    try {
      running.input(job.length).set(new Int8Array(job.buffer, job.offset, job.length));
      const length = running.handle(job.length);
      if (job.id < 0) {
        return;
      }
      if (length < 0) {
        scope.postMessage({ id: job.id, reply: new ArrayBuffer(0) });
        return;
      }
      // A copy of the reply in an ArrayBuffer of its own: the program's memory is reused.
      reply = running.output(length).slice();
    } catch (error) {
      scope.postMessage({ id: job.id, error: describe(error) });
      return;
    }
    scope.postMessage({ id: job.id, reply: reply.buffer }, [reply.buffer]);
  }

  /**
   * @param {string} runtime
   * @param {string} module
   */
  async function start(runtime, module) {
    try {
      scope.importScripts(runtime);
      const teavm = scope.TeaVM;
      if (!teavm) {
        throw new Error(`${runtime} didn't define TeaVM's loader`);
      }
      const loaded = await teavm.wasmGC.load(module);
      loaded.exports.main([]);
      const installed = scope.__incariProgram;
      if (!installed) {
        throw new Error("The program didn't install its handlers");
      }
      program = installed;
      scope.postMessage({ kind: "ready" });
      for (const job of waiting.splice(0)) {
        handle(installed, job);
      }
    } catch (error) {
      scope.postMessage({ kind: "failed", error: describe(error) });
    }
  }

  scope.onmessage = (event) => {
    const message = event.data;
    if ("kind" in message) {
      void start(message.runtime, message.module);
    } else if (program === null) {
      waiting.push(message);
    } else {
      handle(program, message);
    }
  };
}

/**
 * @param {unknown} error
 * @returns {string}
 */
function describe(error) {
  return error instanceof Error ? (error.stack ?? error.message) : String(error);
}

serve(/** @type {Scope} */ (/** @type {unknown} */ (globalThis)));
