import assert from "node:assert/strict";
import { describe, it } from "node:test";

import { detectFeatures, type FeatureScope } from "../../main/ts/features.ts";

/** JSPI's two members, which TypeScript's WebAssembly types don't have yet. */
function jspiOf(api: object): { Suspending: unknown; promising: unknown } {
  const jspi = api as { Suspending: unknown; promising: unknown };
  return { Suspending: jspi.Suspending, promising: jspi.promising };
}

describe("detectFeatures", () => {
  it("finds WebAssembly GC in an engine that has it", () => {
    // Node 24+ ships the GC proposal, so the real probe must validate here.
    assert.equal(detectFeatures({ WebAssembly }).wasmGc, true);
  });

  it("finds JSPI in an engine that has it, and none without WebAssembly GC", () => {
    // Node 26 ships JSPI, as Chrome 137+ does.
    assert.equal(detectFeatures({ WebAssembly }).jspi, true);
    assert.equal(detectFeatures({ WebAssembly: { validate: () => true } }).jspi, false);
    assert.equal(
      detectFeatures({
        WebAssembly: { ...jspiOf(WebAssembly), validate: () => false } as unknown as typeof WebAssembly,
      }).jspi,
      false,
    );
  });

  it("reports no WebAssembly GC when the engine rejects the probe", () => {
    const withoutGc: FeatureScope = { WebAssembly: { validate: () => false } };
    assert.equal(detectFeatures(withoutGc).wasmGc, false);
  });

  it("treats an engine that throws on the probe as unsupported", () => {
    const throwing: FeatureScope = {
      WebAssembly: {
        validate: () => {
          throw new TypeError("unknown type form");
        },
      },
    };
    assert.equal(detectFeatures(throwing).wasmGc, false);
  });

  it("needs both isolation and SharedArrayBuffer for shared memory", () => {
    assert.equal(detectFeatures({ crossOriginIsolated: true, SharedArrayBuffer }).crossOriginIsolated, true);
    assert.equal(detectFeatures({ crossOriginIsolated: false, SharedArrayBuffer }).crossOriginIsolated, false);
    assert.equal(detectFeatures({ crossOriginIsolated: true }).crossOriginIsolated, false);
  });

  it("reports no WebGL2 without a document, or when no context can be made", () => {
    assert.equal(detectFeatures({}).webgl2, false);
    const noContext = {
      createElement: () => ({ getContext: () => null }),
    } as unknown as Pick<Document, "createElement">;
    assert.equal(detectFeatures({ document: noContext }).webgl2, false);
  });
});
