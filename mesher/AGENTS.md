# mesher

- Only `java.lang`, `java.util` and `org.jspecify.*`: no game types, no TeaVM, no other module.
  It's compiled on its own into the page's worker module (`mesher.wasm`), and it must stay small.
  `BoundaryTest` fails otherwise.
- Worker-side code: no game objects, nothing but the flat formats defined here (`MeshTables`,
  `Snapshot`, `MeshResult`, `MeshJobs`' messages). A change to a format changes both sides:
  `client`'s `incari.client.render.mesher` fills and reads them.
- The mesher writes exactly what the game's section compiler writes, vertex for vertex. A change
  to what it writes must still pass `./gradlew :app-desktop:smokeMesher` (a display and the game's
  assets: every section meshed both ways and compared, the debug world's every block state
  included).
- Hot paths (`SectionMesher`, `FluidMesher`, `VertexFormats`, `QuadSorter`) don't allocate once
  their scratch space has grown.
- Test: `./gradlew :mesher:check`, then `smokeMesher` for anything that changes a mesh.
