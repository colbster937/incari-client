# ADR 0020: The legacy behaviour layer: playing 1.8 servers as 1.8 did

- Status: accepted
- Date: 2026-09-29

## Context

Via translates packets but can't change how the client moves, aims and collides. A 1.8 server
simulates the player by 1.8's rules: sneaking under a slab, a thicker ladder or a taller lily pad
gets corrected (rubber-banding) or flagged by anticheat. And 1.8 PvP means no attack cooldown, sword
blocking, and a swing before each attack.

ViaFabricPlus is the reference, but it's GPL-3.0 without Incari's Minecraft exception, so nothing of
it is used: the behaviour is written fresh from what 1.8 does. Gate: side-by-side recordings with a
real 1.8.9 client.

## Decision

- **Everything keys off the server's version** (`LegacyRules`, from
  `ServerConnections.lastJoinedProtocol`, updated once a tick). Plain static fields, cheap on hot paths.
- **Combat on 1.8** (`LegacyCombat`):
  - no attack cooldown (strength always 1, no indicator);
  - **sword blocking:** swords become usable like shields with the `BLOCK` animation; Via turns the
    use into 1.8's block and the server halves damage. 26.3 already draws the 1.8 pose;
  - the swing sent before attacking or digging;
  - a 0.1 pick margin around entities.
- **Movement and poses** (`LegacyPlayer`, `LegacyMovement`): sneaking keeps a 1.8-high box with eyes
  at 1.54 (1.65 high for 1.9–1.13), for every player so hitboxes match; no swimming or crawling before
  1.13; on 1.8 each axis of motion under 0.005 a tick stops.
- **Block shapes on 1.8** (`LegacyShapes`, one pair of hooks in `BlockStateBase`): ladders 2/16
  thick; lily pads full 1/64 squares; glass panes and bars as 1.8's arms; anvils full height, 12/16
  wide; farmland a full block. Unchanged shapes aren't touched.
- **Fishing rods and bows** need nothing: the server and Via handle them.

## Alternatives rejected

- **Porting ViaFabricPlus:** its licence, and its fixes are mixins against other mappings.
- **Hooks per block class:** more hooks for the same result.
- **A `blocks_attacks` component on swords:** it's the server's, and would show in tooltips and be
  sent back in creative.

## Consequences

- **Verified against a real 1.8.9 server** (`smokeLegacy`): rules follow protocol 47; pick margin
  0.1; full strength after a swing; 1.54 sneak eyes; a server-placed ladder collides at 2/16; and
  blocking halves an arrow as 1.8 does ((1 + d) / 2: 3.0 open, 2.0 blocking). Singleplayer and current
  servers are unchanged.
- **The gate isn't met as written:** no recordings beside a real 1.8.9 client yet. Feel (knockback,
  hit registration, strafing) still needs that session with a PvP player.
- Not changed yet: creative reach, red hurt armour, hurt tilt, the offhand. Each is a small hook.
- Rules are per version group: 1.9–1.13 get the sneak height (and to 1.12 no swim pose), but not
  1.9's own changes.
