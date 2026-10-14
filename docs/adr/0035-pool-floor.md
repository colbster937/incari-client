# ADR 0035: A floor for the background pool's share of a late frame

- Status: accepted
- Date: 2026-10-05

## Context

Background work runs in slots between frames (ADR 0015), ending 2 ms before the next frame is due,
with at least 1 ms. When the game's frame already overruns (the lobby on `laptop-floor` runs at 33
fps on a 60 Hz display), every slot is that 1 ms: part of one section's snapshot. A section took
dozens of frames, and terrain after a teleport took minutes on a slow device.

## Decision

While the pool has work, a slot lasts until `max(frame start + interval − 2 ms, now + floor)`, the
floor 4 ms by default (`WebPlatform.setPoolFloor`; `?poolfloor=<ms>`). On-time frames are unchanged;
only already-late frames give the pool more, and only while it has work.

## Results

Desktop, CPU throttled 4x, lobby:

| Floor | FPS | Mesh queue | Sections drawn |
| --- | ---: | ---: | ---: |
| 1 ms (before) | 29.9 | 81 | 119 |
| 4 ms | 29.0 | 48 | 196 |
| 8 ms | 26.4 | 46 | 197 |

Two-thirds more terrain in the same time for 3% of the frame rate; 8 ms cost more and loaded no
more. The fly-through (time to spare each frame) didn't move. On `laptop-floor` the lobby's queue
halved (17.7 → 8.3) for 2% of its frame rate.

## Not done

- **Several sections in progress at once:** starting another task while one waits for a worker made
  no difference; the page's share of each section (snapshot, upload) is the limit.

## Consequences

- A late frame is ~3 ms later while terrain loads (more if a long task starts in its slot).
- The lobby benchmark loads further in the same time, so its tick and render submit rise with the
  terrain drawn.
