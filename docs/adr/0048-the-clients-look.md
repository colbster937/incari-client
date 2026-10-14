# ADR 0048: The client's look, Frost, in Potato and Normal presets

- Status: accepted
- Date: 2026-10-10

## Context

ADRs 0046 and 0047 left a seam for the restyle: Incari screens draw through `incari.client.ui`, game
screens can be edited or replaced, and a `GuiSkin` can redraw any widget or texture. The maintainer
picked **Frost** from mockups: translucent white glass over a cold dimmed backdrop, white text, an
ice-blue accent (`#A9DCFF`), Red Hat Display, compact sizes. Most of the game's GUI is replaced;
health, hunger and experience stay.

It must cost almost nothing on the reference Chromebook, so it comes in two presets.

## Decision

### Presets

`GuiPreset` in `incari/look.txt`, switched live from the module menu's sidebar. Same layout, colours
and font in both:

- **Potato** (default): solid darker panels, no blur, no animation (hover gets an accent edge).
- **Normal:** menu blur (even when the game's blur option is 0), eased hover glows, a light sweep on
  buttons, title snow.

The HUD is never blurred or animated: its cost is every frame's. It gets tinted glass.

### Shapes without a shader

`RoundRects` draws rounded fills, outlines and soft shadows in screen pixels from small
anti-aliased nine-slice textures made once per size: one pipeline (`GUI_TEXTURED`), batched, no
custom shader. `Icons` draws line icons the same way.

### The skin

`FrostSkin` redraws by sprite and texture name: buttons, sliders, fields, checkboxes, tabs,
scrollers, tooltips, toasts, slots, hotbar, and every backdrop. `ScreenPanels` draws glass panels
under edited or Incari-laid-out screens.

### Fonts

Red Hat Display (text) and Michroma (wordmark), both OFL, in **Incari's built-in pack**
(`IncariPack`, always on top). The browser has no FreeType, so **every** TrueType provider loads
through `SmoothFont` over a new `PlatformFonts`: FreeType on the desktop, a 2D canvas in the
browser (`CanvasFonts`: glyphs drawn white, alpha read back; coverage from the `cmap`, `FontCmap`).
Glyphs are drawn at the GUI scale so they stay sharp, and again on a scale change. Resource packs'
TrueType fonts now work in the browser too.

### Screens

- **Title** (`TitleLook`, two hooks; `TitleScreen` stays the class): wordmark and icon buttons on the
  left, the player on a glass card on the right, language and accessibility top right, version and
  copyright at the foot. Realms, the splash and Friends are left out. (The backdrop is now the
  menus' photo, `MenuBackdrop`.)
- **Pause** (`PauseLook`): one centred card. Voice and Eagler server icons keep working.
- **Module menu** (`ModuleListScreen`): categories with counts on the left, the preset and profile
  below, a search field over module cards with switches; the gear opens settings.
- **Containers** (`ContainerSkin`): inventory, chests, shulker boxes, hoppers, dispensers, crafting
  table and furnaces become dark glass with a rounded well per slot. Others keep their texture,
  cooled to slate, so what the game draws on it stays put.
- **HUD** (`HudLook`): tab list, sidebar, chat and chat field as rounded tinted glass; modules' HUD
  panels rounded.

## Limits

- Older Incari screens (voice, Edit Profile, version list) use the game's widgets, which the skin draws.
- Uncommon containers keep their texture's shapes.
- Normal's eased effects reset when a screen is recreated.

## Consequences

- Patches: blur radius, `SmoothFont` load and rescale, Incari's pack, title (two), tab list,
  scoreboard, chat and its field, container labels (two).
- `smokeLook` screenshots the main screens and HUD in both presets and fails if the skin was dropped.
