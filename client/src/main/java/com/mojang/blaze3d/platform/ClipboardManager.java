package com.mojang.blaze3d.platform;

import incari.client.platform.ClientPlatform;
import net.minecraft.util.StringDecomposer;

/** Incari's replacement for Mojang's clipboard access (patches/classes.txt), over the platform shell. */
public class ClipboardManager {

    /** The clipboard's text, or "" if it holds none or can't be read (the platform logs why). */
    public String getClipboard() {
        String text = ClientPlatform.get().shell().clipboardText();
        // Other programs can put lone surrogates on the clipboard; the game's text code can't take them.
        return text == null ? "" : StringDecomposer.filterBrokenSurrogates(text);
    }

    public void setClipboard(final String text) {
        ClientPlatform.get().shell().setClipboardText(text);
    }
}
