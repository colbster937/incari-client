# Glossary

Incari uses Mojang's official names for game concepts (since 26.1 the game ships unobfuscated),
so a name in Incari code means what it means in the game. These are the project's own terms.

| Term | Meaning |
| --- | --- |
| **Drop** | A Minecraft release Incari follows (26.3 now). Each drop means re-pinning `incari.properties`, re-fixing decompile errors and rebasing patches. |
| **Vendor tree** | The decompiled, patched game source in `work/client`. Generated, never tracked. |
| **Vendor code** | Mojang's code, in the vendor tree. Touched minimally, never reformatted. |
| **Incari code** | Everything in this repo's modules. Held to the full quality gates. |
| **Patch** | One commit in `work/client` after the `base` tag, saved as a file in `patches/<subsystem>/`. |
| **Decompile fix** | A patch in `patches/decompile/` that makes the decompiled source compile without changing behavior. |
| **Hook** | The one- or two-line call a patch adds into an Incari module. |
| **Class manifest** | `patches/classes.txt`: whole vendor classes deleted, or replaced by an Incari source file. |
| **Platform** | One implementation of `platform-api`: desktop (LWJGL) or web (browser APIs). |
| **Backend** | A Mojang `GpuBackend`: the graphics API under the renderer. OpenGL on the desktop, WebGL2 in the browser, later WebGPU. |
| **Section** | Mojang's 16×16×16 block unit of a chunk, the unit the renderer meshes. |
| **Region** | A group of sections drawn together (terrain batching, ADR 0022). |
| **Pipeline** | Mojang's `RenderPipeline`: shaders plus fixed render state. |
| **Tier** | A quality tier (Potato, Standard, High) the performance budgets scale by; to be picked from the device, player-adjustable. |
| **Floor device** | The reference 4 GB Chromebook every performance gate is measured on. |
| **Fast build** | The browser build whose threads suspend through JSPI: quicker and smaller, for browsers with JSPI (ADR 0036). |
| **Compatible build** | The browser build whose threads are TeaVM's coroutines: slower, for every browser with WebAssembly GC (ADR 0036). |
| **Eagler server** | A Minecraft server reached over a WebSocket through EaglerXServer, which runs its own login exchange first (ADR 0016). Every `ws://` or `wss://` address is taken to be one. |
| **Eagler stage** | The first stage of a WebSocket connection's pipeline (`EaglerHandshake`): Eagler's login, standing in for the vanilla one, then Eagler's own messages beside the game's packets. |
| **Via** | ViaVersion and ViaBackwards: translation between Minecraft versions, a stage of the pipeline when a server speaks another version than the game (ADR 0017). |
| **Server version** | The Minecraft protocol a server speaks, as the player chose it on the server's edit screen or as the ping found it ("Auto"). |
