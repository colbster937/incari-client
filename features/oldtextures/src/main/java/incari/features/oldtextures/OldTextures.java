package incari.features.oldtextures;

import incari.framework.Category;
import incari.framework.Module;

/**
 * Old Textures: the game's own Programmer Art pack, the textures from before 1.14. The module is
 * the pack's switch ({@link Module#followPack}): turning it on turns the pack on in the Resource
 * Packs screen, and turning the pack on or off there turns the module with it. Each change reloads
 * the game's resources, as the screen's would.
 */
public final class OldTextures extends Module {

    /** The game's id for Programmer Art. */
    static final String PROGRAMMER_ART = "programmer_art";

    /** Old Textures, on only if Programmer Art is. */
    public OldTextures() {
        super(
                "oldtextures",
                "Old Textures",
                "The textures from before 1.14 (the game's Programmer Art).",
                Category.VISUAL);
        followPack(PROGRAMMER_ART);
    }
}
