# ADR 0033: Throws off the page's main thread: the packet handoff, and Via's dropped packets

- Status: accepted
- Date: 2026-10-03

## Context

`PacketUtils.ensureRunningOnSameThread` was 2.5% of combat on `laptop-floor`, mostly entity movement
packets, whose handoff cost ~100x their decoding.

The game decodes each packet on the network thread and calls its handler there; nearly every client
handler queues the packet for the game's thread and throws a shared exception that
`Connection.channelRead0` catches. Cheap on the JVM. In the page (`PacketHandoffProbe`):

| ns per packet | Main thread | Any other thread |
| --- | ---: | ---: |
| Calls | 5–15 | 5–10 |
| Queueing | 20–30 | 20–30 |
| Throw and catch | 65–100 | 640–860 |

The network thread is another thread, so every packet paid the dear throw.

## Decision

**`PacketHandoff`** (two hooks in `Connection.channelRead0`): the first time a packet type's handler
throws to hand off (per listener type), the pair is noted. From then on the connection queues such
packets on the game's packet processor itself, without calling the handler on the network thread.
The game's thread handles them as before.

That's exactly the game's effect where the handler's first statement is an unconditional handoff.
`PacketHandoffTest` reads the game's listeners and confirms it for 136 of 138 handlers in 26.3; the
other two (custom payload, transfer) are never noted. Handlers that don't hand off never throw, so
are never noted. Client listeners only. Page only; `?handoff=game` restores the game's way.

### The other throws: a census

`-Pteavm.throwCensus` (development builds) counts every `throw` by site and thread
(`ThrowCensus`, `ThrowCensusProbe`). On a 26.3 server: ~13 a second, mostly loading (missing-file
checks, reflection) and the section dispatcher finding no free buffer (fixed in ADR 0034). Not worth patches.

**Through Via:** 135–205 a second, nearly all Via dropping packets the other version doesn't use. Each
dropped packet threw in Via, then 3–4 more times in Netty's `MessageToMessage` codecs that
`ViaPipeline` extended.

**Via's stage without Netty's codecs:** `ViaPipeline`'s decoder and encoder are now plain handlers
that do the codecs' job and catch Via's drop themselves. A dropped packet now throws twice, both in
Via. `ViaPipelineTest` checks every buffer is released.

## Results

`laptop-floor`, release build, vs `?handoff=game`:

- Normal runs: within noise, since combat and the fly-through already hold 60 fps.
- With the profiler on (page 97–98% busy, like a slower device): combat median 40.7–44.4 vs
  37.6–39.1 fps, every run with the change above every run without.

Via census, same sessions before and after:

| Throws off the main thread | Before | After |
| --- | ---: | ---: |
| 1.8.9 server, 2 minutes | 16,080 | 6,161 |
| 1.12.2 server, 90 s | 18,484 | 5,172 |

On the stand-in that's a few tenths of a millisecond a second, below what can be measured; it
counts where the CPU is the limit.

## Consequences

- One vendor patch (`Connection.channelRead0`).
- A future handler that does something before handing off fails `PacketHandoffTest` until listed.
- Via's own two throws per dropped packet stay; avoiding them means replacing Via's classes.
- `-Pteavm.throwCensus` adds a call before every throw: development only.
