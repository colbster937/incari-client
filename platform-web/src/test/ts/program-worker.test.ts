import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { runInThisContext } from "node:vm";

// The program worker's bootstrap as the page runs it: its source, run against a stand-in for the
// worker's global scope whose TeaVM loader "loads" a program written here, driven through the
// messages the page sends.
const source = readFileSync(new URL("../../main/js/program-worker.js", import.meta.url), "utf8");

type Message = Record<string, unknown>;

/** A stand-in program: answers each message with its bytes reversed; an empty one gets no answer. */
function reversingProgram() {
  let memory = new Int8Array(16);
  let reply = new Int8Array(0);
  return {
    input(length: number): Int8Array {
      if (memory.length < length) {
        memory = new Int8Array(length);
      }
      return memory.subarray(0, length);
    },
    handle(length: number): number {
      if (length === 0) {
        return -1;
      }
      reply = memory.slice(0, length).reverse();
      return length;
    },
    output(length: number): Int8Array {
      return reply.subarray(0, length);
    },
  };
}

class Worker {
  readonly posted: Message[] = [];
  private onmessage: ((event: { data: Message }) => void) | null = null;
  private readonly waiting: { match: (message: Message) => boolean; resolve: (message: Message) => void }[] = [];
  readonly imported: string[] = [];

  constructor(program: ReturnType<typeof reversingProgram> | null) {
    const listen = (handler: ((event: { data: Message }) => void) | null) => {
      this.onmessage = handler;
    };
    const scope: Record<string, unknown> = {
      postMessage: (message: Message) => {
        this.posted.push(message);
        const index = this.waiting.findIndex((wait) => wait.match(message));
        if (index >= 0) {
          const [wait] = this.waiting.splice(index, 1);
          wait?.resolve(message);
        }
      },
      set onmessage(handler: ((event: { data: Message }) => void) | null) {
        listen(handler);
      },
      importScripts: (url: string) => {
        this.imported.push(url);
        scope.TeaVM = {
          wasmGC: {
            load: (module: string) => {
              this.imported.push(module);
              return Promise.resolve({
                exports: {
                  main: () => {
                    if (program) {
                      scope.__incariProgram = program;
                    }
                  },
                },
              });
            },
          },
        };
      },
    };
    const start = runInThisContext(`(function(globalThis) {\n${source}\n})`) as (scope: unknown) => void;
    start(scope);
  }

  send(message: Message): void {
    this.onmessage?.({ data: message });
  }

  waitFor(match: (message: Message) => boolean): Promise<Message> {
    const already = this.posted.find(match);
    return already ? Promise.resolve(already) : new Promise((resolve) => this.waiting.push({ match, resolve }));
  }
}

function job(id: number, bytes: number[], shared = false): Message {
  const buffer = shared ? new SharedArrayBuffer(64) : new ArrayBuffer(64);
  new Int8Array(buffer, 8, bytes.length).set(bytes);
  return { id, buffer, offset: 8, length: bytes.length };
}

describe("the program worker", () => {
  it("loads the program, then answers each message with the program's reply", async () => {
    const worker = new Worker(reversingProgram());
    // A message before the program has started waits for it.
    worker.send(job(1, [1, 2, 3]));
    worker.send({ kind: "start", runtime: "https://page/mesher.wasm-runtime.js", module: "https://page/mesher.wasm" });
    await worker.waitFor((message) => message.kind === "ready");
    assert.deepEqual(worker.imported, ["https://page/mesher.wasm-runtime.js", "https://page/mesher.wasm"]);

    const first = await worker.waitFor((message) => message.id === 1);
    assert.deepEqual([...new Int8Array(first.reply as ArrayBuffer)], [3, 2, 1]);

    worker.send(job(2, [9, 8], true));
    const second = await worker.waitFor((message) => message.id === 2);
    assert.deepEqual([...new Int8Array(second.reply as ArrayBuffer)], [8, 9]);
  });

  it("answers a message the program doesn't reply to with nothing, and one with no id not at all", async () => {
    const worker = new Worker(reversingProgram());
    worker.send({ kind: "start", runtime: "r.js", module: "m.wasm" });
    await worker.waitFor((message) => message.kind === "ready");
    worker.send(job(-1, [5]));
    worker.send(job(3, []));
    const answer = await worker.waitFor((message) => message.id === 3);
    assert.equal((answer.reply as ArrayBuffer).byteLength, 0);
    assert.equal(
      worker.posted.some((message) => message.id === -1),
      false,
    );
  });

  it("says so when the program doesn't start", async () => {
    const worker = new Worker(null);
    worker.send({ kind: "start", runtime: "r.js", module: "m.wasm" });
    const failed = await worker.waitFor((message) => message.kind === "failed");
    assert.match(String(failed.error), /didn't install its handlers/);
  });
});
