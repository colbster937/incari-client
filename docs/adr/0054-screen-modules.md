# ADR 0054: The screen modules: Shulker Tooltips, Screenshot Manager, Theme Editor

- Status: accepted; Screenshot Manager and the Theme Editor changed in ADR 0062 (the gallery is the
  client's; Theme is always on, with fonts)
- Date: 2026-10-10

## Context

The third set the player asked for adds screens: **Shulker Tooltips** (contents at a glance), a
**Screenshot Manager** (view, name, sort, download; F2 optionally to the clipboard), and a **Theme
Editor** (presets, colours, opacity, rounding, live on menus and HUD, shared as a code; no extra
fonts at the time, for download size).

## Decision

### Framework additions

- **Events:** `CONTAINER_TOOLTIP` (whether Shift is held; listeners set `preview`, `tinted`) and
  `SCREENSHOT` (the saved file's name, on the game thread).
- **Menu buttons:** `Module.menuButton(...)` shows a module's buttons on the title and pause menus
  while it's on.
- **`Game`:** `openScreenshots()`, `copyScreenshot()`, `applyTheme(ThemeColors)`, `clipboard()`,
  `copy()`. **`ThemeColors`** with `FROST`.
- **`PlatformShell.setClipboardImage(png)`:** the browser's Clipboard API; false on the desktop.

### The theme became the player's

`Theme`'s colours and radii were constants. Now they're static fields every screen reads as it draws,
and `Theme.apply` derives them all from `ThemeColors` (button edges from the text colour, HUD glass
from the panel colour, readable text on the accent). Frost's own values are restored exactly.

### Modules

| Module | What it does |
| --- | --- |
| Shulker Tooltips | A container item's tooltip shows its slots nine across with counts, on the box's dye colour, in place of the first five names; optionally only with Shift |
| Screenshot Manager | A gallery (`ScreenshotsScreen`): thumbnails sorted by date or name; a viewer with arrows; rename, delete (confirmed), download or copy; select several to download as one zip. "Copy to clipboard" for each F2 |
| Theme Editor | Presets (Frost, Midnight, Ember, Mint, Rose, Mono) or custom colours, opacity and rounding, applied next tick; copy and paste theme codes (`incari-theme:…`) |

Thumbnails are made one a frame as they scroll into view (a full PNG decode costs tens of ms on a
Chromebook). The gallery works on the screenshots folder, kept in OPFS.

### Patches

Two: `Screenshot.grab` (the event after saving) and the tooltip image path (`ItemStack`,
`ClientTooltipComponent`, `ItemContainerContents`), answered from one event so they agree.

## Consequences

- Two patches, 13 changed lines.
- `smokeScreenModules` covers the tooltip, F2 events, the gallery and viewer, themes on menus and
  HUD, and Frost restored. `newmodules.steps` covers the page.

## Not yet

- Copying a picture works only in the browser.
- The gallery has no search and no grouping by day.
