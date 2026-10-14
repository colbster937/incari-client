# ADR 0024: Entities, particles and text

- Status: accepted
- Date: 2026-09-30

## Context

A crowded lobby is where browser clients fall apart. The plan: entity occlusion culling,
instanced entity models, batched text, GUI and signs, and pooled, capped particles, gated on a
crowded-lobby benchmark.

The new `lobby` scenario: 80 armored mannequins with names, 24 hologram armor stands, 12 text
displays, 30 dropped items, signs, a sidebar, a boss bar and three particle fountains, from an
orbiting camera. Before this work its profile was mostly particles (~7 of 16 busy seconds), then text
(1.4 s), entity models (1.3 s) and entity ticks.

The 26.x renderer already batches draws (one staged buffer, one range per render type; the GUI merges
elements). What's left is CPU work per vertex, glyph and particle, and entities drawn nobody can see.

## Decision

- **Entity occlusion culling** (`EntityOcclusion`): skip an entity in the frustum if none of the
  sections its box touches was reached by the terrain occlusion graph. Never culled: players,
  glowing entities, visible names, text displays, leashed entities, the camera's entity. A few array
  lookups, no allocation. `?entityculling=off`.
- **Instanced entity models** (`ModelBatches`, page only, `?instancing=off`):
  - A model's cubes are baked once into a vertex buffer (`ModelMesh`).
  - Per instance the CPU runs `setupAnim` and walks the part tree with the game's own transforms:
    one matrix per part (hidden parts collapse to zero).
  - One instanced draw per render type and model; rows in a 1024-row uniform block.
  - A copy of the entity pipeline whose vertex shader the backend rewrites to apply the part
    transform (`EntityInstanceRewrite`).
  - Only solid-phase models with the entity shader; translucent, outlined, crumbling or remapped
    (armor trims) models go the game's way. `ModelMeshTest` checks the arithmetic.
- **Text, GUI and signs:** the game's batching stays. `VertexMath` overrides `BufferBuilder`'s pose
  transforms with the same arithmetic minus the per-vertex vector allocation: bit-identical.
- **Particles:**
  - **Caps per tier** (`ParticleBudget`): 2048 / 8192 / 16384 (Potato / Standard / High), with the
    game's admission curve scaled (all admitted until three quarters full). `?particles=`.
  - **No per-particle allocation:** the camera rotation is reused, turned corners are kept while the
    rotation holds, and light is read once per block per frame (`ParticleScratch`, `ParticleQuads`,
    `ParticleLight`). Bit-identical.

## Results

Desktop:

| | Before | After |
| --- | ---: | ---: |
| Lobby 1% low | 40.2 fps | 38.4–44.6 fps |
| Lobby p99 frame | 22.9 ms | 21.9 ms |
| Lobby extract / render submit | 2.0 / 3.4 ms | 1.9 / 2.4 ms |
| Combat extract + render submit | 2.1 ms | 1.4 ms |
| Lobby particle CPU (41 s profile) | 5.07 s | 4.18 s |

The desktop holds 60 fps either way; the floor device is where this should show.

- Instancing takes armor (and mobs, armor stands in play). The mannequins' bodies stay the game's:
  skins are translucent with a texture each (taken later by ADR 0027's opaque skins).
- Culling saves nothing in the lobby (everything is in sight); it's for ordinary play.

## Consequences

- An entity hidden behind terrain costs nothing to extract, animate or draw.
- New or changed models need nothing: meshes are baked from the game's parts on first draw.
- If a game version changes the entity shader's `Position` or `Normal` inputs, the instanced copy
  fails to compile and those models fall back to the game's way: slower, never missing.
- Particle tiers change what's seen only past three quarters of a group's cap.
