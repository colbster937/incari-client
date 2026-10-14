# ADR 0010: Browser input: focus, a text input element, an asynchronous clipboard, dropped files

- Status: accepted
- Date: 2026-09-27

## Context

The game reads input as SDL3 reports it (ADR 0005). A page gets DOM events instead, and the browser
keeps control SDL gives a desktop program: it owns shortcuts like Ctrl+W, allows Pointer Lock and
fullscreen only after a gesture, releases the lock on Escape, reads the clipboard only in a paste
event or a promise, and gives dropped files as contents, not paths. And a DOM listener can't call
into the game (a coroutine can't start from a JavaScript callback).

## Decision

- **Every DOM event is queued and drained at the game's poll** (`DomEventQueue`, plain Java). Async
  APIs report back through the same queue. The only call from a listener into Java is the pure
  `DomEventQueue.consumesKey`.
- **Keys are the game's while it has focus** (canvas, its textarea, or the body). All defaults are
  cancelled except developer tools, Ctrl+V (for the paste event) and dead keys (for composition). A
  host page's own controls keep their keys.
- **Browser-reserved shortcuts:** Ctrl+W/T/N, Ctrl+1–9 and Ctrl+Q can't be cancelled outside
  fullscreen. In fullscreen, Keyboard Lock takes them (and Escape pauses; holding it exits). Outside
  fullscreen, a leave-page prompt guards the tab while the mouse is locked.
- **Text through an invisible textarea** while a field wants typing, placed over the field so input
  method candidates appear there. Ordinary typing still comes from key events; only compositions
  and keyless text (virtual keyboards, voice) come from the textarea.
- **Key names from the Keyboard Map API**, re-read on refocus; learned from typing where it's
  missing (Firefox, Safari).
- **Clipboard never waited on:** Ctrl+V's paste event arrives in the same frame as the key. Writes
  use the async Clipboard API (or a copy command over plain http) without waiting.
- **Pointer Lock asks for raw movement** (`unadjustedMovement`), falling back to a plain lock. Leaving
  with Escape gives the game the Escape it missed, so it pauses.
- **Fullscreen is the canvas's**, always borderless; leaving it from the browser updates the game's option.
- **A hidden tab counts as lost focus:** held keys release, pause-on-focus-loss applies.
- **Dropped files are copied into `/incari/dropped/<id>/`** and their paths handed to the game's drop
  handler, so pack screens take them as on the desktop.

## Consequences

- Nothing depends on game types; the game-facing side is unchanged from ADR 0005.
- Headless Chromium can't show raw movement, Keyboard Lock, input method windows or the leave-page
  prompt: they're on the manual checklist.
- Without `unadjustedMovement` (Firefox, Safari, some Chromium), mouse movement keeps system
  acceleration, unlike SDL's relative mode.
