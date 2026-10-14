# mesher: the terrain mesher

Turns a section of terrain into vertex data, off the game's thread (ADR 0023). The game copies a
section and its border into a flat `Snapshot`; the mesher builds the block and fluid quads, lighting,
face culling, visibility and the sorted translucent layer from `MeshTables` (built once from the
game's models). The output is byte-identical to the game's own section compiler.

In the browser it runs in Web Workers as `mesher.wasm` (~120 KB). On the desktop, and for meshes
needed at once, it runs in the game.

## Code map

- `SectionMesher`: meshes a `Snapshot` into a `MeshResult`.
- `MeshTables` and its `Builder`: the tables, as the game's side fills them.
- `MeshJobs`: a worker's side of the conversation (tables, meshes, re-sorts).
- `GameMath`: the game's position hash, random and table trigonometry, which meshes depend on.
- `VertexFormats`: the game's 28-byte block vertex and Incari's 16-byte compact one.

## Invariants

- **Depends on nothing** but the JDK's basics and JSpecify (`BoundaryTest`).
- **Exact**: the desktop's `smokeMesher` compares every section with the game's compiler.
- **Allocation-free once warm** on the hot paths.

## How to test

```sh
./gradlew :mesher:check
./gradlew :app-desktop:smokeMesher   # a display: the game meshes varied worlds both ways
```
