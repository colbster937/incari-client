# ADR 0044: Defaults and fixes before M4

- Status: accepted
- Date: 2026-10-09

## Context

Six things players met before any of the client's own features: picking a server's version meant
clicking a cycle button through some forty releases; a first start used Fancy graphics at render
distance 16 on a Chromebook; the tab list showed no heads on offline-mode servers; every join showed
the "chat messages can't be verified" toast and every message a not-secure mark; `/login` and a
login plugin's password dialog showed the password as typed; and a respawn sometimes paused the game
as if the player had pressed Escape.

## Decision

- **Version list.** The edit-server screen's version button opens `VersionSelectScreen`: every
  version in one scrolling list, newest first under "Auto", with a search box ("1.8" leaves the
  1.8 releases). A double-click, Enter or Done picks; the button shows the pick. No patch changed:
  ADR 0017's hook adds whatever widget `ServerVersions.button` returns.
- **Potato graphics, the default.** A Potato preset before Fast (`GraphicsPreset.POTATO`, its
  values in `PotatoGraphics`): render distance 4, simulation distance 5 (the game's floor), biome
  blend off, no smooth lighting, clouds, entity shadows, mip levels or menu blur, minimal particles,
  entity distance 50%, the smallest cloud and weather ranges. New installs start on it, and the game
  applies a saved preset at every start as it always has; a saved Fancy stays Fancy. The benchmarks
  and screenshot diffs pin their own options and don't change.
- **Heads in the tab list** whatever the server's mode: the game drew them only on online-mode
  servers. On Eagler servers they are the players' Eagler skins (ADR 0016).
- **No secure-chat warnings.** Offline-mode servers sign no messages, so the join toast and the
  per-message not-secure and modified marks are off.
- **Passwords as stars** (`HiddenPasswords`): in chat, the arguments of a login plugin's commands
  (`/login`, `/l`, `/register`, `/changepassword` and their aliases, with or without a namespace);
  in a server dialog, a text box whose label names a password. Only the drawing changes, through the
  box's formatter; the text sent is what was typed. A selection or click inside the stars lands by
  the hidden characters' widths, a small error in exchange for no change to `EditBox`'s measuring.
- **No Escape on respawn.** The page turned the end of Pointer Lock while the game wanted the mouse
  into an Escape (the browser keeps the player's Escape from the page). A death screen releases the
  mouse and a respawn a tick later takes it back, before the page reports the release, so the
  release looked like the player's and paused the game. The event queue now remembers that it asked
  for the release (`DomEventQueue.exitAsked`); that release makes no Escape, and if the game wants
  the mouse again by then it asks for the lock again (a lock the page let go of comes back without a
  click; a click takes it back if not).

## Consequences

- Four new patches (budget raised to 124).
- Login commands are still kept in the game's chat history as typed (`command_history.txt`), as
  in vanilla.
