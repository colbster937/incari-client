# tools/sprites

- Build-time only; never referenced by code that ships. May use LWJGL and the game freely.
- The file format is `client`'s `incari.client.render.SpriteCache`: both sides must agree, and its
  version goes up with any change.
- Make each image with the game's own code (its decoder, `MipmapGenerator`), never a copy of it:
  the page uses what's here instead of what the game would have made.
- Test: `./gradlew :tools:sprites:check`; run for real with `./gradlew :app-web:sprites`.
