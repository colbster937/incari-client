# ADR 0029: Edit Profile

- Status: accepted
- Date: 2026-10-01

## Context

Eagler clients open an "Edit Profile" screen at load: a username, a skin from a dropdown beside a
preview, Add Skin and Clear List, and a Capes link. Eagler players know it, and nearly every Eagler
server is offline-mode, so that name is the player's identity. Incari took the name only from the
launch (`?name=`, `--username`), and skins from a bare "Eagler Skin..." screen.

## Decision

- **The name is answered at `Minecraft.getUser`** (one hook, `PlayerName`), so the login, Eagler
  handshake, singleplayer, relay and voice all use it, with the matching offline UUID. Only offline
  launches; a real account keeps its name.
- **3–16 letters, digits or underscores**, as Eagler requires; Done stays off until valid. Kept in
  `incari/profile.properties`.
- **The screen opens at start-up** after the game's first-run screens, unless the entry point names
  the player (`?name=`, `?server=`, tests, `-Pincari.username`). The web suggests `Player` + three
  digits. Reopened from a title-screen link or the skin customisation screen. Locked in a world.
- **Skin list:** the game's nine default skins in both models, then the player's added PNGs
  (`incari/skin/library/`; slim or wide read from the image). Add Skin uses the file picker, Clear
  List removes added ones, the cape has its own screen. "Eagler Skin..." is gone.
- **Your own skin shows everywhere**, singleplayer and vanilla servers included.
- **The preview is vanilla's `PlayerSkinWidget`** (draggable); in the browser it shows "..." until
  models load after the title (ADR 0026).

## Consequences

- Three new hooks plus one retargeted.
- A different name is a different offline UUID, so singleplayer inventories are per name.
- Not done: Eagler's profile Import/Export, and a cape on the preview.
