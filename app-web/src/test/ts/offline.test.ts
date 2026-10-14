import assert from "node:assert/strict";
import { resolveObjectURL } from "node:buffer";
import { afterEach, beforeEach, describe, it } from "node:test";

import {
  installFiles,
  shimSource,
  startWorker,
  type FileScope,
  type FileSetup,
  type StartScope,
} from "../../main/ts/offline.ts";

const BASE = "file:///home/player/";

/** The shim replaces URL's Blob URL functions in its realm; each test gets the originals back. */
const createObjectURL = URL.createObjectURL.bind(URL);
const revokeObjectURL = URL.revokeObjectURL.bind(URL);

afterEach(() => {
  URL.createObjectURL = createObjectURL;
  URL.revokeObjectURL = revokeObjectURL;
});

async function textAt(url: string): Promise<string> {
  const blob = resolveObjectURL(url);
  assert.ok(blob !== undefined, `${url} is a Blob URL`);
  return blob.text();
}

function script(source: string): Blob {
  return new Blob([source], { type: "text/javascript" });
}

function setup(blobs: Record<string, Blob>, paths: string[] = Object.keys(blobs)): FileSetup {
  return { base: BASE, paths, blobs, shim: "/* shim */" };
}

/** A realm whose fetch records what reached the network. */
function scope(): FileScope & { fetched: string[] } {
  const fetched: string[] = [];
  return {
    fetched,
    fetch: (input) => {
      fetched.push(input instanceof Request ? input.url : String(input));
      return Promise.resolve(new Response("from the network"));
    },
  };
}

describe("offline files: fetch", () => {
  it("serves embedded files by relative, absolute and versioned URLs", async () => {
    const realm = scope();
    installFiles(realm, setup({ "build.json": new Blob(["{}"], { type: "application/json" }) }));

    for (const url of ["build.json", `${BASE}build.json`, "build.json?v=0123456789abcdef", "./build.json#x"]) {
      const response = await realm.fetch(url);
      assert.equal(response.status, 200);
      assert.equal(response.headers.get("content-type"), "application/json");
      assert.equal(await response.text(), "{}");
    }
    assert.deepEqual(realm.fetched, []);
  });

  it("leaves every other URL to the real fetch", async () => {
    const realm = scope();
    installFiles(realm, setup({ "build.json": new Blob(["{}"]) }));

    await realm.fetch("assets/objects/ab/abcdef");
    await realm.fetch("https://example.com/build.json");
    await realm.fetch("data:text/plain,hi");

    assert.deepEqual(realm.fetched, [
      "assets/objects/ab/abcdef",
      "https://example.com/build.json",
      "data:text/plain,hi",
    ]);
  });

  it("decodes a file once, when it's first fetched", async () => {
    const realm = scope();
    const decoded: string[] = [];
    installFiles(realm, setup({}, ["client.jar", "missing.bin"]), (path) => {
      decoded.push(path);
      return Promise.resolve(path === "client.jar" ? new Blob(["jar"]) : null);
    });

    const [first, second] = await Promise.all([realm.fetch("client.jar"), realm.fetch("client.jar?v=1")]);
    assert.equal(await first.text(), "jar");
    assert.equal(await second.text(), "jar");
    assert.equal((await realm.fetch("missing.bin")).status, 404);
    assert.deepEqual(decoded, ["client.jar", "missing.bin"]);
  });
});

describe("offline files: scripts", () => {
  it("points a module's relative imports at Blob URLs of the imported files", async () => {
    const realm = scope();
    const files = installFiles(
      realm,
      setup({
        "boot.js": script('import { a } from "./loader.js";\nimport "./jspi.js";\nconst b = import("./other.js");'),
        "loader.js": script('import { c } from "./jspi.js";\nexport const a = c;'),
        "jspi.js": script("export const c = 1;"),
      }),
    );

    const boot = await textAt(await files.moduleUrl("boot.js"));
    const imported = [...boot.matchAll(/"(blob:[^"]+)"/g)].map((match) => match[1] ?? "");
    assert.equal(imported.length, 2);
    assert.match(await textAt(imported[0] ?? ""), /^import \{ c \} from "blob:/);
    assert.equal(await textAt(imported[1] ?? ""), "export const c = 1;");
    // Not an embedded file: left as it was.
    assert.match(boot, /import\("\.\/other\.js"\)/);
    // One Blob URL per module, however many import it.
    assert.equal(await files.moduleUrl("jspi.js"), imported[1]);
  });

  it("gives an embedded script's Blob URL for loading, and any other URL back", () => {
    const realm = scope();
    const files = installFiles(realm, setup({ "incari.wasm-runtime.js": script("var TeaVM = {};") }));

    assert.match(files.url(`${BASE}incari.wasm-runtime.js?v=1`), /^blob:/);
    assert.equal(files.url("https://example.com/x.js"), "https://example.com/x.js");
    assert.equal(realm.incariFiles, files);
  });

  it("loads embedded scripts through importScripts from Blob URLs", () => {
    const loaded: string[] = [];
    const realm: FileScope = {
      ...scope(),
      importScripts: (...urls) => {
        loaded.push(...urls);
      },
    };
    installFiles(realm, setup({ "mesher.wasm-runtime.js": script("") }));

    realm.importScripts?.(`${BASE}mesher.wasm-runtime.js?v=2`, "https://example.com/other.js");

    assert.equal(loaded.length, 2);
    assert.match(loaded[0] ?? "", /^blob:/);
    assert.equal(loaded[1], "https://example.com/other.js");
  });
});

/** The shim's `Worker`, which a realm with workers always gets. */
function workerClass(realm: FileScope): NonNullable<FileScope["Worker"]> {
  const Worker = realm.Worker;
  assert.ok(Worker !== undefined);
  return Worker;
}

/** A worker as the shim starts it: what it was made with and the messages posted to it. */
interface MadeWorker {
  url: string;
  options: WorkerOptions | undefined;
  posted: { message: unknown; transfer: Transferable[] | undefined }[];
}

describe("offline files: workers", () => {
  let made: MadeWorker[];
  let realm: FileScope & { fetched: string[] };

  afterEach(() => {
    // Open ports keep Node running.
    for (const worker of made) {
      (worker.posted[0]?.message as { incariFiles?: FileSetup } | undefined)?.incariFiles?.port?.close();
    }
  });

  beforeEach(() => {
    made = [];
    realm = scope();
    realm.Worker = function (url: string | URL, options?: WorkerOptions) {
      const worker: MadeWorker = { url: String(url), options, posted: [] };
      made.push(worker);
      return {
        postMessage: (message: unknown, transfer?: Transferable[]) => worker.posted.push({ message, transfer }),
      } as unknown as Worker;
    } as unknown as NonNullable<FileScope["Worker"]>;
  });

  function setupPosted(worker: MadeWorker | undefined): FileSetup {
    const message = worker?.posted[0]?.message as { incariFiles?: FileSetup } | undefined;
    assert.ok(message?.incariFiles !== undefined, "the shim's files are the first message");
    return message.incariFiles;
  }

  it("starts an embedded module worker as a classic one that runs the shim, then the module", () => {
    installFiles(realm, setup({ "game-worker.js": script("export {};"), "client.jar": new Blob(["jar"]) }));
    new (workerClass(realm))(new URL("game-worker.js", BASE), { type: "module", name: "incari-server" });

    const worker = made[0];
    assert.match(worker?.url ?? "", /^blob:/);
    assert.deepEqual(worker?.options, { name: "incari-server" });
    const files = setupPosted(worker);
    assert.deepEqual(files.script === undefined ? null : { path: files.script.path, module: files.script.module }, {
      path: "game-worker.js",
      module: true,
    });
    assert.deepEqual(Object.keys(files.blobs).sort(), ["client.jar", "game-worker.js"]);
    assert.equal(files.base, BASE);
  });

  it("starts a worker from a script Blob this realm made with the shim, as a classic worker", async () => {
    installFiles(realm, setup({}));
    const source = script("onmessage = () => {};");
    const url = URL.createObjectURL(source);

    new (workerClass(realm))(url, { name: "incari-storage" });

    const files = setupPosted(made[0]);
    assert.equal(files.script?.path, null);
    assert.equal(files.script.module, false);
    assert.equal(await files.script.blob.text(), "onmessage = () => {};");
  });

  it("leaves any other worker alone", () => {
    installFiles(realm, setup({}));

    new (workerClass(realm))("https://example.com/worker.js", { type: "module" });

    assert.deepEqual(made[0], { url: "https://example.com/worker.js", options: { type: "module" }, posted: [] });
  });

  it("answers a worker's request for a file it doesn't have yet", async () => {
    installFiles(realm, setup({}, ["incari-fast.wasm", "program.js"]), (path) =>
      Promise.resolve(path === "incari-fast.wasm" ? new Blob(["module"]) : script("")),
    );
    await realm.fetch("program.js");

    new (workerClass(realm))("program.js");
    const port = setupPosted(made[0]).port;
    assert.ok(port !== undefined);
    const reply = new Promise<{ id: number; blob: Blob | null }>((resolve) => {
      port.onmessage = (event: MessageEvent<{ id: number; blob: Blob | null }>) => {
        resolve(event.data);
      };
    });
    port.postMessage({ id: 7, path: "incari-fast.wasm" });

    const answer = await reply;
    assert.equal(answer.id, 7);
    assert.equal(await answer.blob?.text(), "module");
  });
});

/** A worker's global scope, enough for startWorker. */
class FakeWorkerScope extends EventTarget {
  readonly imported: string[] = [];
  readonly heard: unknown[] = [];
  fetch: FileScope["fetch"] = () => Promise.resolve(new Response(null));
  importScripts = (...urls: string[]): void => {
    this.imported.push(...urls);
    // The real script listens once it runs.
    this.addEventListener("message", (event) => {
      this.heard.push((event as MessageEvent).data);
    });
  };
  setTimeout = (handler: () => void, timeout: number): unknown => setTimeout(handler, timeout);
}

function init(files: Partial<FileSetup>): MessageEvent {
  return new MessageEvent("message", {
    data: { incariFiles: { base: BASE, paths: [], blobs: {}, shim: "", ...files } },
  });
}

describe("offline files: a worker's start", () => {
  it("runs the real script, which then hears the page's messages", async () => {
    const worker = new FakeWorkerScope();
    startWorker(worker as unknown as StartScope, installFiles);

    worker.dispatchEvent(init({ script: { path: null, blob: script(""), module: false } }));
    // A message is a task of its own: the start's promise has settled before the next one comes.
    // (A module's import takes longer; messages before it are held, which the browser checks see.)
    await new Promise((resolve) => setTimeout(resolve, 0));
    worker.dispatchEvent(new MessageEvent("message", { data: "first" }));
    worker.dispatchEvent(new MessageEvent("message", { data: "second" }));

    assert.equal(worker.imported.length, 1);
    assert.match(worker.imported[0] ?? "", /^blob:/);
    assert.deepEqual(worker.heard, ["first", "second"]);
  });

  it("works from its own source alone, as a worker gets it", async () => {
    const worker = new FakeWorkerScope();
    // eslint-disable-next-line @typescript-eslint/no-implied-eval -- the worker's shim is source text by design
    (new Function("self", shimSource()) as (self: unknown) => void)(worker);

    worker.dispatchEvent(
      init({
        paths: ["runtime.js"],
        blobs: { "runtime.js": script("") },
        script: { path: null, blob: script(""), module: false },
      }),
    );
    await new Promise((resolve) => setTimeout(resolve, 0));

    assert.equal(worker.imported.length, 1);
    assert.equal(typeof (worker as unknown as FileScope).incariFiles?.url, "function");
    const response = await worker.fetch("runtime.js");
    assert.equal(response.headers.get("content-type"), "text/javascript");
  });
});
