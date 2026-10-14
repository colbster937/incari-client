# ADR 0061: Cosmetics: the Wardrobe, worn cosmetics, emotes and personal effects

- Status: accepted
- Date: 2026-10-12
- Builds on: ADR 0058 (cosmetics belong to the account), ADR 0046 (modules), ADR 0048 (the look)

## Context

ADR 0058 made cosmetics belong to an Incari account, and its lookups already carry cosmetic ids.
This ADR decides what they are, how they're drawn and picked, and how the service learns what's worn.

Asked for: capes (animated too), hats, wings and back items, name tag colours, trails, emotes, saved
outfits, and personal effects only the player sees; easy and familiar to use; a switch to hide
others' cosmetics.

Constraints: nothing may cost a frame where it isn't seen (4 GB Chromebook); no Mojang art; no
one-of-a-kind cosmetics (they'd let an account be followed between servers); the service is one
WebSocket, no HTTP.

## Decision

### Two kinds

- **Worn cosmetics** (others see them): capes, hats, wings and back items, name tags, trails (one of
  each), and emotes (owned and played). Picked in the **Wardrobe**, not the module menu.
- **Personal effects** are ordinary modules only the player sees: Block Outline, Hit Color, Enchant
  Glint, Hit & Kill Effects.

### Catalog and art

- **37 cosmetics** in `:online`'s `Catalog`: 7 capes (4 animated), 6 hats, 4 back items, 8 name tags,
  4 trails, 8 emotes. Ids `<slot>.<name>` never change. All free for now; the service decides ownership.
- **Art is painted in code** (`CosmeticArt`, `CosmeticModels`): no images, nothing of Mojang's. Moving
  capes repaint 10x a second, only while seen in the last 2 s.
- **The service sends only ids;** unknown ids are skipped, so the catalog can grow on the service first.
- **No uploads, no unique cosmetics.**

### Drawing

| Cosmetic | How |
| --- | --- |
| Hats, back items | One render layer (`CosmeticsLayer`) through the game's model submits, so instancing takes them. Hidden under helmets and elytras; not drawn past 48 blocks or on invisible players |
| Capes | The game's own cape with the cosmetic's texture, so physics, elytra and the Cape option all work. A backpack hides the cape |
| Name tags | Restyle the name unless the server colours it (a team colour means something) |
| Trails | The game's particles behind a moving player: every other tick, ≤ 12 a tick in total, within 32 blocks |
| Emotes | Pose arms and head at the end of `setupAnim`, eased over 4 ticks; legs still walk. Your own emote turns the camera to face you; moving ends it |

`Cosmetics` works out each player's `Worn` once, when their list or emote changes, not per frame.

### Wardrobe

- **Laid out like the module menu:** kinds on the left with counts, a card grid, the player in 3D on
  the right. Hover tries on; click wears; click again (or None) takes off. The preview turns to show
  capes and wings and can be dragged.
- **Outfits:** up to 12 saved per device; **Take Off All**.
- **Under the preview:** "Incari players see these" or "Only you see these" with Sign In, and the
  **Show others' cosmetics** switch (per device).
- **Opens from** the title card's Cosmetics button, Incari Settings, and the module menu.
- **Emote wheel:** hold **G** in a world.

### Service sync

- `:online`'s `Wardrobe` keeps worn, owned, outfits and the switch in `incari/online/wardrobe.json`.
  Everything works without an account (you see your own).
- Changes apply at once; `equip` is sent once clicking stops (≤ 1/s), and after a reconnect.
- On sign-in the account's wardrobe wins, unless it wears nothing and the device does: then the
  device's carries over.
- Protocol: `wardrobe`, `equip`, `emote`, their events, and `not_owned`. A service without them
  answers `bad_request` and the client stays device-only.

### Personal effects

- **Block Outline:** colour, opacity, width, optional fill. High contrast keeps the game's outline.
- **Hit Color:** the hurt tint's colour and strength (repaints the overlay texture's rows).
- **Enchant Glint:** the glint textures repainted in the chosen colour; off restores the game's.
- **Hit & Kill Effects:** one of 10 particle effects on hits, bigger on kills (within 3 s), optional sound.
- Framework: `Game.overrideBlockOutline`, `overrideHitColor`, `overrideGlintColor`, `effectOnTarget`,
  `Effect`, and the `KILL` event.

### Patches

Four: a field on `AvatarRenderState` and the layer in `AvatarRenderer`; emotes' pose in
`PlayerModel.setupAnim`; Block Outline in `LevelRenderer.submitBlockOutline`; Hit Color in `OverlayTexture`.

## Consequences

- No cost for a player who wears and sees none: the layer returns at once.
- Others' cosmetics need both players signed in on the same server address, with a service that
  speaks cosmetics. Singleplayer and LAN show only your own.
- New cosmetics need a client release for their art (until art can be fetched), but no protocol change.
- `smokeCosmetics` and `WardrobeTest` cover it.

## Not yet

- Fetching new art from the service.
- Earning or buying cosmetics (`owned` is ready; selling needs Mojang's commercial guidelines checked first).
- Whole-body emotes, shoulder pets, a hand position module.
- Cosmetics in the tab list.
