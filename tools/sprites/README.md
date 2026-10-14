# tools/sprites: vanilla sprites, decoded at build time

Decodes every vanilla sprite and makes its mip levels at build time, with the game's own code
(ADR 0026). `./gradlew :app-web:sprites` writes `app-web/build/web-sprites/sprites.bin` for the
asset pack. In the page, `client`'s `SpriteCache` uses these instead of decoding PNGs. The file
holds Mojang's textures: build output, never committed or published.

## How it works

1. `Main` boots the game on this JVM and opens the vanilla pack as the game does.
2. `Sprites` asks each atlas for its sprite sources. Plain, non-animated sprites are decoded with
   the game's decoder and mipmapped by the game's `MipmapGenerator` (blocks atlas up to level 4).
3. The file: little-endian magic, version, count, then each sprite's name, size and RGBA levels.

Generated sprites (armor trims, sheets) and animated ones are left to the page, as before.

## Invariants

- **Nothing here ships.** It runs on the build machine with LWJGL's natives.
- **Byte-identical to the game.** `SpritesTest` compares every level through `SpriteCache`; the
  screenshot diffs compare whole frames.
- **The format belongs to `SpriteCache`.** Change both together and raise the version.
- **Fewer than 1000 sprites fails the build**, so a moved sprite layout can't ship a useless file.
