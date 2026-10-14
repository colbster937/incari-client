package com.mojang.blaze3d.platform;

import incari.client.platform.ClientPlatform;

/**
 * Incari's replacement for Mojang's native message boxes (patches/classes.txt): the dialogs shown
 * when something fails before or outside the game's own UI.
 */
public class MessageBox {

    private static final String TITLE = "Minecraft";

    public static void error(final String message) {
        ClientPlatform.get().shell().showErrorDialog(TITLE, message, false);
    }

    /** Shows the error with Yes/No buttons; true if the player chose to continue. */
    public static boolean errorWithContinue(final String message) {
        return ClientPlatform.get().shell().showErrorDialog(TITLE, message, true);
    }
}
