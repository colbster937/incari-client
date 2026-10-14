/**
 * What the browser supports, checked once before anything is downloaded. The loader uses it to
 * offer the builds that can run here (Fast needs JSPI, both need WebAssembly GC), and to explain a
 * failure before the player waits through a download that can't run.
 */
import { supportsJspi } from "./jspi.ts";

export interface BrowserFeatures {
  /** WebAssembly with the GC proposal: needed by both builds of the game. */
  readonly wasmGc: boolean;
  /** WebAssembly JavaScript Promise Integration: needed by the Fast build (ADR 0036). */
  readonly jspi: boolean;
  /**
   * SharedArrayBuffer is usable, which needs COOP/COEP headers on the page. Worker meshing uses
   * it; without it, workers fall back to transferring buffers.
   */
  readonly crossOriginIsolated: boolean;
  /** A WebGL2 context can be created: the graphics floor for every build. */
  readonly webgl2: boolean;
}

/**
 * The smallest module that uses a GC type: the header, then a type section declaring one empty
 * struct (0x5f). Engines without the GC proposal reject the struct type form.
 */
// prettier-ignore
const WASM_GC_PROBE = new Uint8Array([
  0x00, 0x61, 0x73, 0x6d, // magic "\0asm"
  0x01, 0x00, 0x00, 0x00, // version 1
  0x01, 0x03, // type section, 3 bytes
  0x01, // one type
  0x5f, 0x00, // struct with no fields
]);

/** The parts of the global scope detection reads, so tests can supply their own. */
export interface FeatureScope {
  readonly WebAssembly?: Pick<typeof WebAssembly, "validate">;
  readonly crossOriginIsolated?: boolean;
  readonly SharedArrayBuffer?: unknown;
  readonly document?: Pick<Document, "createElement">;
}

export function detectFeatures(scope: FeatureScope = globalThis): BrowserFeatures {
  return {
    wasmGc: supportsWasmGc(scope),
    jspi: supportsWasmGc(scope) && supportsJspi(scope),
    crossOriginIsolated: scope.crossOriginIsolated === true && scope.SharedArrayBuffer !== undefined,
    webgl2: supportsWebgl2(scope),
  };
}

function supportsWasmGc(scope: FeatureScope): boolean {
  try {
    return scope.WebAssembly?.validate(WASM_GC_PROBE) === true;
  } catch {
    // Some engines throw on unknown type forms instead of returning false.
    return false;
  }
}

function supportsWebgl2(scope: FeatureScope): boolean {
  if (scope.document === undefined) {
    return false;
  }
  const canvas = scope.document.createElement("canvas");
  // Released straight away: this only asks whether a context can exist.
  const context = canvas.getContext("webgl2");
  context?.getExtension("WEBGL_lose_context")?.loseContext();
  return context !== null;
}
