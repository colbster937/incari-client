package incari.client.tier;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.TextureFilteringMethod;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.server.level.ParticleStatus;
import org.jspecify.annotations.Nullable;

/**
 * Video Settings' Potato preset, below Fast, and the game's default in Incari: what a 4 GB
 * Chromebook needs for its frame rate. Each setting at the cheapest the game allows, except where
 * that costs more than it saves: simulation distance's floor is 5, and the render distance is 4,
 * where 2 leaves too little world to play in.
 */
public final class PotatoGraphics {

    /**
     * The preset's name. The game shows a preset's translation key, and a key with no translation
     * shows as itself; Incari has no language files yet.
     */
    public static final String KEY = "Potato";

    private PotatoGraphics() {}

    /** {@code GraphicsPreset.apply}: sets each option, and the open settings screen's widget for it. */
    public static void apply(Options options, @Nullable OptionsSubScreen screen) {
        set(screen, options.biomeBlendRadius(), 0);
        set(screen, options.renderDistance(), 4);
        set(screen, options.prioritizeChunkUpdates(), PrioritizeChunkUpdates.NONE);
        set(screen, options.simulationDistance(), 5);
        set(screen, options.ambientOcclusion(), false);
        set(screen, options.cloudStatus(), CloudStatus.OFF);
        set(screen, options.particles(), ParticleStatus.MINIMAL);
        // No mip levels: a third less atlas memory, and at 4 chunks little is far enough to shimmer.
        set(screen, options.mipmapLevels(), 0);
        set(screen, options.entityShadows(), false);
        set(screen, options.entityDistanceScaling(), 0.5);
        // The menu blur is a full-screen pass per frame behind every menu.
        set(screen, options.menuBackgroundBlurriness(), 0);
        set(screen, options.cloudRange(), 2);
        set(screen, options.cutoutLeaves(), false);
        set(screen, options.improvedTransparency(), false);
        set(screen, options.weatherRadius(), 3);
        set(screen, options.maxAnisotropyBit(), 1);
        set(screen, options.textureFiltering(), TextureFilteringMethod.NONE);
    }

    private static <T> void set(@Nullable OptionsSubScreen screen, OptionInstance<T> option, T value) {
        if (!option.get().equals(value)) {
            option.set(value);
            if (screen != null) {
                screen.resetOption(option);
            }
        }
    }
}
