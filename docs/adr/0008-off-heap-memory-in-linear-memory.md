# ADR 0008: Off-heap memory in the module's linear memory, and Netty in heap mode

- Status: accepted
- Date: 2026-09-27

## Context

The game keeps vertex data, images and uniforms off the Java heap through LWJGL's `MemoryUtil`,
`MemoryStack` and allocators. The first stand-ins (ADR 0006) used Java arrays, so every write went through a lookup
and every upload was copied (a `byte[]` under WebAssembly GC is invisible to JavaScript).

Netty's buffers didn't start in the page at all: `MethodHandles.lookup()` failed on TeaVM's empty
`java.lang.invoke`, the leak detector used reflection, and TeaVM's generated atomic updaters were
treated as missing. Networking needs them.

The original plan was `ArrayBuffer` + `DataView`, but every vertex element would then be a call into
JavaScript, where TeaVM's `Address` intrinsics compile to single load/store instructions on the same
bytes.

## Decision

- **The game's memory users run unchanged;** `web-classlib`'s stand-ins (`TMemoryUtil`,
  `TMemoryStack`, `TBufferUtils`, `TSTBImage`) sit underneath, over `Heap`. No vendor patch.
- **Memory is the module's linear memory:** each allocation is a TeaVM direct buffer, an address is
  its real offset, reads and writes are single instructions (`LinearMemory`), and views are slices.
  WebGL uploads read them in place, with no copy.
- **Freeing is immediate** (linear memory only grows). `calloc` and `BufferUtils` clear explicitly.
- **TeaVM's internal buffer interfaces** are redeclared identically in `web-classlib` so `Heap` can name them.
- **The same code runs on a JVM for tests:** `Heap.linear()` picks the backend at compile time; the
  JVM backend checks bounds and fills `malloc`'d memory with junk.
- **Byte parity is tested:** `MemoryParityTest` runs the game's memory users on LWJGL and on the
  stand-ins and requires every byte to match; a browser probe compares digests.
- **Netty runs in heap mode:** unpooled, no `Unsafe`, leak detection off (`LibraryDefaults`). A
  method-handle lookup that finds nothing makes Netty fall back; leak detector reflection is declared;
  generated atomic updaters count as present.
- **JOML starts:** `joml.format=false` (TeaVM rejects its `DecimalFormat`), and `org.joml.Runtime` is
  substituted (`Class.forName("java.lang.Float")` fails in TeaVM).

## Consequences

- A vertex element costs one store; a mesh upload costs no copy.
- Linear memory isn't bounds-checked: a stray write corrupts instead of throwing. The JVM backend
  and the parity test catch misuse.
- Linear memory never shrinks; its high-water mark is the page's.
- Netty's adaptive allocator stays unusable; the default is unpooled.
- LWJGL's `PointerBuffer` isn't stood in for: nothing the browser runs uses it.
