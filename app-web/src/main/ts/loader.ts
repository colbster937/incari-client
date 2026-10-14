/**
 * The loader's decisions, kept apart from the page so they can be tested: which build to run, what
 * the build manifest says, and how far a download has got. `boot.ts` does the page's side.
 */
import type { BrowserFeatures } from "./features.ts";

/** One file of the build, as `build.json` lists it. */
export interface BuildFile {
  readonly path: string;
  readonly size: number;
  /** Hex SHA-256 of the file: its version, so a cache can keep it until it changes. */
  readonly sha256: string;
}

/** A build of the game: TeaVM's loader script and the WebAssembly GC module it loads. */
export interface WasmTarget {
  readonly runtime: string;
  readonly module: string;
  /** The development build's stack deobfuscator and its debug information, if it has them. */
  readonly deobfuscator?: string;
  readonly debugInfo?: string;
}

/** A program the page's workers run beside the game: its own module and loader. */
export interface ProgramTarget {
  readonly runtime: string;
  readonly module: string;
}

/**
 * The two builds of the game, both WebAssembly GC (ADR 0036). What a player sees them called, and
 * what they are:
 *
 * - **Fast**: suspends Java's threads through JSPI (JavaScript Promise Integration), so its code runs
 *   at full speed. It needs a browser with JSPI.
 * - **Compatible**: emulates the threads with TeaVM's coroutines, which slows all of its code and
 *   doubles its size, but runs wherever WebAssembly GC does (Safari, older Chrome and Firefox).
 */
export type BuildKind = "fast" | "compatible";

/**
 * `build.json`, which `:app-web:buildManifest` writes beside the builds: what there is to load, and
 * each file's size and hash. A page served without one (an experiment's build) gets
 * {@link DEFAULT_MANIFEST}.
 */
export interface BuildManifest {
  readonly format: 2;
  /** A hash over every file's: changes whenever any file does. */
  readonly id: string;
  /** The game version the build is of. */
  readonly game: string;
  readonly release: boolean;
  readonly fast?: WasmTarget;
  readonly compatible?: WasmTarget;
  /** The asset pack (build-time data the game mounts read-only), if the build has one. */
  readonly pack?: string;
  /** Programs for the page's workers, by name (`mesher`). */
  readonly programs?: Readonly<Record<string, ProgramTarget>>;
  readonly files: readonly BuildFile[];
}

/**
 * What an experiment's build (no `build.json`) has: TeaVM's development output as `incari.wasm`. It
 * may be either kind; the page gives every module the Fast build's imports where the browser has
 * JSPI (boot.ts), so a Fast experiment runs as this entry too.
 */
export const DEFAULT_MANIFEST: BuildManifest = {
  format: 2,
  id: "development",
  game: "unknown",
  release: false,
  compatible: {
    runtime: "incari.wasm-runtime.js",
    module: "incari.wasm",
    deobfuscator: "incari.wasm-deobfuscator.wasm",
    debugInfo: "incari.wasm.teadbg",
  },
  files: [],
};

/** Checks that `value` is a manifest this loader understands; throws saying what's wrong if not. */
export function parseManifest(value: unknown): BuildManifest {
  if (typeof value !== "object" || value === null) {
    throw new Error("build.json isn't an object");
  }
  const manifest = value as Partial<BuildManifest>;
  if (manifest.format !== 2) {
    throw new Error(`build.json has format ${String(manifest.format)}; this page reads format 2. Reload the page.`);
  }
  if (typeof manifest.id !== "string" || !Array.isArray(manifest.files)) {
    throw new Error("build.json has no id or file list");
  }
  if (manifest.fast === undefined && manifest.compatible === undefined) {
    throw new Error("build.json lists no build to run");
  }
  return manifest as BuildManifest;
}

/** The build to run, or why it can't run. */
export type Choice =
  { readonly kind: BuildKind; readonly target: WasmTarget } | { readonly kind: "unsupported"; readonly reason: string };

/** Why neither build can run without WebGL 2, the graphics floor for both; null if it's there. */
export function missingWebgl2(features: BrowserFeatures): string | null {
  return features.webgl2
    ? null
    : "This browser can't run WebGL 2, which the game needs. Check that hardware acceleration is on.";
}

/** Why neither build can run without WebAssembly GC; null if it's there. */
export function missingWasmGc(features: BrowserFeatures): string | null {
  return features.wasmGc
    ? null
    : "This browser is too old to run the game. Use Chrome or Edge 119, Firefox 120, Safari 18.2 or newer.";
}

/** One build on the loading screen's choice: whether it can run here, and why not. */
export interface BuildOption {
  readonly kind: BuildKind;
  readonly available: boolean;
  /** Why it can't run: the browser lacks what it needs, or the build doesn't include it. */
  readonly reason?: string;
}

/**
 * The builds to offer, Fast first: it's the one to run wherever it works (far quicker, and a smaller
 * download). Compatible is for browsers without JSPI.
 */
export function buildOptions(features: BrowserFeatures, manifest: BuildManifest): readonly BuildOption[] {
  const fast: BuildOption =
    manifest.fast === undefined
      ? { kind: "fast", available: false, reason: "This copy of the game doesn't include Fast." }
      : features.jspi
        ? { kind: "fast", available: true }
        : {
            kind: "fast",
            available: false,
            reason: "Fast needs Chrome or Edge 137, Firefox 153 or newer.",
          };
  const compatible: BuildOption =
    manifest.compatible === undefined
      ? { kind: "compatible", available: false, reason: "This copy of the game doesn't include Compatible." }
      : { kind: "compatible", available: true };
  return [fast, compatible];
}

/** `?build=fast` or `?build=compatible`: a build asked for in the address, skipping the choice. */
export function requestedBuild(value: string | null): BuildKind | null {
  return value === "fast" || value === "compatible" ? value : null;
}

/**
 * The option the choice starts on: the player's last pick if it can still run, else Fast if it can,
 * else Compatible.
 */
export function initialBuild(options: readonly BuildOption[], remembered: string | null): BuildKind {
  const available = options.filter((option) => option.available).map((option) => option.kind);
  const last = requestedBuild(remembered);
  if (last !== null && available.includes(last)) {
    return last;
  }
  return available.includes("fast") ? "fast" : "compatible";
}

/** The manifest's entry for a picked build, or why it can't run. */
export function buildFor(kind: BuildKind, options: readonly BuildOption[], manifest: BuildManifest): Choice {
  const option = options.find((o) => o.kind === kind);
  const target = kind === "fast" ? manifest.fast : manifest.compatible;
  if (option?.available !== true || target === undefined) {
    return { kind: "unsupported", reason: option?.reason ?? `No ${kind} build to run.` };
  }
  return { kind, target };
}

/** What the player calls a build. */
export function buildName(kind: BuildKind): string {
  return kind === "fast" ? "Fast" : "Compatible";
}

/** A file's URL with its hash as the version, so a cache keeps it exactly until it changes. */
export function versioned(manifest: BuildManifest, path: string): string {
  const file = manifest.files.find((f) => f.path === path);
  return file === undefined ? path : `${path}?v=${file.sha256.slice(0, 16)}`;
}

/** The size the manifest gives a file, or 0 if it doesn't list it. */
export function sizeOf(manifest: BuildManifest, path: string): number {
  return manifest.files.find((f) => f.path === path)?.size ?? 0;
}

/**
 * A response whose body reports each chunk as it's read, for a progress bar. Headers and status are
 * kept, so `WebAssembly.compileStreaming` still accepts it (it needs `application/wasm`) and still
 * compiles while the bytes arrive.
 */
export function countingResponse(response: Response, onBytes: (received: number) => void): Response {
  const body = response.body;
  if (body === null) {
    return response;
  }
  let received = 0;
  const counted = body.pipeThrough(
    new TransformStream<Uint8Array, Uint8Array>({
      transform(chunk, controller) {
        received += chunk.byteLength;
        onBytes(received);
        controller.enqueue(chunk);
      },
    }),
  );
  return new Response(counted, { status: response.status, statusText: response.statusText, headers: response.headers });
}

/** A byte count for people: "12.3 MB". */
export function formatBytes(bytes: number): string {
  if (bytes < 1000) {
    return `${String(bytes)} B`;
  }
  const units = ["kB", "MB", "GB"];
  let value = bytes / 1000;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }
  return `${value.toFixed(value < 10 ? 1 : 0)} ${units[unit] ?? "GB"}`;
}
