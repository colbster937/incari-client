import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { createInbox, serve, startChild, type WorkerFactory, type WorkerScope } from "../../main/ts/game-worker.ts";

function envelope(kind: number): { kind: number; data: ArrayBuffer } {
  return { kind, data: new ArrayBuffer(kind) };
}

describe("game-worker inbox", () => {
  it("hands queued messages out in order, then waits for the next", async () => {
    const inbox = createInbox();
    inbox.push(envelope(1));
    inbox.push(envelope(2));

    assert.equal(inbox.take()?.kind, 1);
    assert.equal((await inbox.next())?.kind, 2);
    assert.equal(inbox.take(), null);

    const waiting = inbox.next();
    inbox.push(envelope(3));
    assert.equal((await waiting)?.kind, 3);
  });

  it("ends with null, and nothing comes after", async () => {
    const inbox = createInbox();
    inbox.push(envelope(1));
    inbox.push(null);
    inbox.push(envelope(2));

    assert.equal((await inbox.next())?.kind, 1);
    assert.equal(await inbox.next(), null);
  });
});

describe("game-worker serve", () => {
  it("queues the page's messages for the program", async () => {
    const posted: unknown[] = [];
    const scope: WorkerScope = {
      postMessage: (message) => posted.push(message),
      onmessage: null,
      close: () => undefined,
      console,
    };
    serve(scope);

    scope.onmessage?.({ data: envelope(7) });
    const parent = scope.__incariParent;
    assert.ok(parent !== undefined);

    assert.equal((await parent.next())?.kind, 7);
    assert.deepEqual(posted, []);

    scope.onmessage?.({ data: { kind: -1, data: new ArrayBuffer(0) } });
    assert.equal(await parent.next(), null);
  });
});

describe("game-worker endings", () => {
  it("closes a terrain helper when its server ends the channel", () => {
    let closed = false;
    const scope: WorkerScope = {
      postMessage: () => undefined,
      onmessage: null,
      close: () => {
        closed = true;
      },
      console,
    };
    serve(scope);
    scope.onmessage?.({
      data: { start: { runtime: "data:text/javascript,", module: "m.wasm", jspi: false, role: "terrain" } },
    });
    scope.onmessage?.({ data: { kind: -1, data: new ArrayBuffer(0) } });
    assert.equal(closed, true);
  });
});

describe("game-worker startChild", () => {
  it("starts the build in the role, and passes messages both ways until the child ends", async () => {
    const posted: unknown[] = [];
    let made: { url: string; name: string | undefined } | null = null;
    let deliver: ((data: unknown) => void) | null = null;
    const factory: WorkerFactory = (url, options) => {
      made = { url: url.href, name: options.name };
      return {
        postMessage: (message) => posted.push(message),
        listen: (onMessage) => {
          deliver = onMessage;
        },
        terminate: () => undefined,
      };
    };
    const build = { runtime: "r.js", module: "m.wasm", jspi: true, role: "server", query: "?x=1" };
    const channel = startChild({ console }, build, "terrain", factory, new URL("https://h/game-worker.js"));

    assert.deepEqual(made, { url: "https://h/game-worker.js", name: "incari-terrain" });
    assert.deepEqual(posted[0], { start: { ...build, role: "terrain" }, storage: null });

    channel.send(4, new ArrayBuffer(4));
    assert.equal((posted[1] as { kind: number }).kind, 4);

    const toChannel = deliver as ((data: unknown) => void) | null;
    assert.ok(toChannel !== null);
    toChannel(envelope(5));
    assert.equal((await channel.next())?.kind, 5);
    toChannel({ kind: -1, data: new ArrayBuffer(0) });
    assert.equal(await channel.next(), null);
  });
});
