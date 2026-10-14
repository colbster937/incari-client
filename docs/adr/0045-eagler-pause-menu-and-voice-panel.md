# ADR 0045: An Eagler server's pause menu, voice chat in a panel, the player on the title screen

- Status: accepted
- Date: 2026-10-09

## Context

EaglerXServer customizes its clients' pause menu (`pause_menu.yml`): a "server info" button (a
store, rules, a web page), a Discord button, and small icons beside the menu's buttons and title.
It sends this only to clients that ask for the `PAUSE_MENU` capability at login, which Incari
didn't, so servers' buttons were missing. Voice chat (ADR 0019) was a "Voice Chat..." button in the
pause menu that opened a settings screen; Eagler's 1.8 client puts the channel switch in the pause
menu's top right corner, one click away.

## Decision

- **The pause menu message.** Incari asks for `PAUSE_MENU` (capability bit 4) and reads V5 message
  `0x18` (`EaglerPauseMenu` in `net`): the two buttons, their modes and addresses, and the icons
  (16-bit images up to 64×64, each named by a key such as `icon_discord_L`). `ServerPauseMenu`
  keeps it for the connection, uploads each image once, and releases them when the connection
  ends.
- **Buttons.** Below the row of icon buttons: the server info button and the Discord button, side
  by side when both fit in half the width, else one a row. They open their address in a new tab;
  only `http` and `https` addresses open. A server info button meant for an embedded web view of
  an address opens that address in a tab instead. One that shows markup the server downloads to a
  web view isn't shown: the page has no web view, and the markup can't be opened in a tab without
  running the server's HTML on the client's own origin.
- **No Server Links button** when the server sent buttons of its own: they take its place.
- **Icons** are drawn 16×16 beside the text of the buttons they name. The server's own buttons
  centre an icon and their text as one group, as Eagler's client does; on the vanilla buttons (back
  to game, advancements, statistics, options, disconnect) and the "Game Menu" title they're drawn
  over the widgets beside the centred text, so no vanilla button changes. The background and
  watermark images aren't drawn.
- **Voice panel.** `VoicePanel` in the pause menu's top right on servers with voice: the channel
  as one segmented control (Off, Nearby, Global), whether the microphone is live or the player is
  talking, everyone connected with who is talking lit and a mute toggle each, and a link to the
  full settings (`VoiceScreen`: volumes, proximity range). Its width leaves the menu's buttons
  clear down to a GUI 432 pixels wide. The pause menu's "Voice Chat..." button is gone.

## Consequences

- Three patches in `PauseScreen`: the button hook replaces the voice button's, one more after the
  menu's widgets adds the panel and the icons, and one leaves out Server Links.
- Embedded web views (server info markup, and the web view message API) wait for a sandboxed
  iframe over the canvas.

## The player on the title screen

The title screen's "Edit Profile (name)" link in the top left corner (ADR 0029) gives way to the
player beside the main buttons, on the right (`TitleProfile`): their model in 3D with their skin
(the game's skin preview, as Edit Profile shows it), turning toward the pointer, their name over
their head on a faint plate like a name tag, and an "Edit Profile" button below. A click on the
model opens Edit Profile too. The model waits for the entity models, which load just after the
title screen first shows (ADR 0026). A screen too narrow to fit it keeps the corner link. The
patch is ADR 0029's title screen hook, which now adds Incari's widgets itself.
