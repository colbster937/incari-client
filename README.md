# Incari Client

Incari is a browser client for Minecraft: Java Edition. It compiles the latest release of the game to
WebAssembly with TeaVM and runs it on a WebGL2 backend under Mojang's own renderer. On top of that
sits a PvP, QoL and visuals client.

This repository contains **no Minecraft code or assets**. The build tool downloads the official game
from Mojang, decompiles it on your machine and applies Incari's patches. You need to own Minecraft:
Java Edition to play.

## What it does

- **Joins any server from 1.8 up.** Eaglercraft servers (EaglerXServer) work over WebSocket, and
  vanilla servers work through a bridge. ViaVersion and ViaBackwards run inside the client to handle
  version differences. On 1.8 servers, combat, movement and block shapes behave like 1.8.
- **Eaglercraft features:** skins and capes, password-protected servers, voice chat, and worlds
  shared through relays.
- **Singleplayer in the browser.** The game's server runs in a Web Worker, and worlds are saved in
  the browser's storage.
- **Fast on weak hardware.** Terrain is meshed in workers and drawn in a few batched calls, memory
  use is trimmed, and the Potato preset is the default. There are two builds: Fast (JSPI) and
  Compatible.
- **Over 25 QoL and PvP modules.**
- **Optional Incari accounts** for friends, messages and cosmetics, with a badge next to other
  Incari players' names.

## Don't share builds

This repository publishes no builds, and nobody has permission to distribute one. Every build
contains Minecraft's code and files, so build it yourself and keep it for your own use.

## Buy Minecraft

Please buy the game. Running Mojang's game without a licence is piracy, and Incari doesn't change
that. Nothing in this project can check that you own it, but you should.

## Getting started

You need JDK 25 or newer, git, Node 24 or newer, and network access to Mojang's download servers and
Maven Central. Compiling the browser build needs about 20 GB of free memory.

```sh
./incari setup                    # download 26.3, decompile it, apply the patches into work/client
npm ci                            # the TypeScript tooling

./incari assets                   # once: the game's sounds and languages (~480 MB)
./gradlew :app-desktop:run        # play on the desktop runtime, for debugging

./gradlew :app-web:teavm          # compile the browser build (about ten minutes)
./gradlew :app-web:serve          # serve it on http://localhost:8080/

./incari build                    # both release builds as one folder for your own web server
```

Then read [`ARCHITECTURE.md`](ARCHITECTURE.md) for how the pieces fit,
[`CONTRIBUTING.md`](CONTRIBUTING.md) for the patch workflow and the checks, and
[`tools/build/README.md`](tools/build/README.md) for every `./incari` command.

## Layout

| Path | What it is |
| --- | --- |
| `incari`, `incari.cmd`, `incari.properties` | The build CLI's launcher, the pinned Minecraft version, and Incari's version and defaults |
| `patches/` | Incari's changes to the game, as patch files plus a class manifest |
| `client/` | Builds the game from `work/client`, plus the Incari code its patches call |
| `platform-api/`, `platform-desktop/`, `platform-web/` | The platform layer the game runs on: window, input, audio, files, threads, networking |
| `render-webgl2/` | The WebGL2 graphics backend |
| `net/`, `via/` | WebSocket transport and Eagler's protocol; ViaVersion inside the client |
| `mesher/` | The terrain mesher that runs in Web Workers |
| `framework/`, `features/` | The module framework, and one module per feature |
| `online/` | Incari accounts, chat and cosmetics, client side |
| `web-classlib/` | JDK classes TeaVM lacks, for the browser build |
| `app-desktop/`, `app-web/` | The desktop dev runtime and the browser build (loader page, benchmark, browser checks) |
| `tools/` | The build CLI, TeaVM compile and fixes, shader and sprite translation, copy check, dev servers, CI guards |
| `docs/` | Decision records (`docs/adr/`), protocol, benchmark and self-hosting guides |
| `work/` | Generated and git-ignored: downloads, decompiled source, the patched tree |

Every module has a `README.md` (what it's for, how to test it) and an `AGENTS.md` (its rules).

## Contributing

Contributions are welcome. Start with [`CONTRIBUTING.md`](CONTRIBUTING.md). Commits must be signed
off (DCO), and a few rules exist to keep the project legally safe, so CI enforces them. File bugs with
the issue form and put ideas in Discussions. Report security problems privately, as described in
[`SECURITY.md`](SECURITY.md). Everyone follows the [code of conduct](CODE_OF_CONDUCT.md).

AI coding agents: start with [`AGENTS.md`](AGENTS.md).

## License

Incari's own code is licensed under the GNU GPL version 3 or later, with an additional permission
for combining it with Minecraft (see [`LICENSE`](LICENSE) and
[`LICENSE-EXCEPTION.txt`](LICENSE-EXCEPTION.txt)). Third-party fonts, code and libraries keep their
own licences ([`NOTICE.md`](NOTICE.md)). Minecraft is a trademark of Mojang Studios. Incari is not
affiliated with or endorsed by Mojang or Microsoft.

## Regarding AI usage in this project:

Considering (as I write this) it's October 2026, it is absolutely ridiculous that I need to go over this. Some of you have a real problem with emerging technologies,
simply because:
1. Certain fear-mongering individuals on social media tell you that AI is bad, it will kill us all in the next 10 years, etc
2. Your dream GPU is too expensive because of the RAMpocolypse
3. AI can do tasks way faster and better than you can

Given the fact that many of you are yet to emerge from even middle school while having this opinion, you're in a real dangerous spot mentally. This technology
is not going away, nor will it slow down. It will keep speeding up, and will continue to contribute immensely in many fields, including scientific ones as was seen with the recent OpenAI solutions to 300+ of the biggest problems in mathematics.
You seriously need to get with the program, and realize that this technology will change your life, for good or for bad. That choice is entirely in your hands.

Either you learn how to use AI to be competitive, massively speed up your tasks, and do things you couldn't before, or reject it, fall behind, go to some shitty college, and struggle to get a job because you didn't pick up AI when everyone else was.
Your choice.

Now that being said, AI was used extensively in the development of this project.

And it should matter to you, as an end user, because it allowed for incredible performance optimizations and client features that would not have been possible for me to feasibly create on a realistic timescale otherwise.
