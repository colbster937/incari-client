package incari.client.eagler;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;

/** An Eagler login that ended in a redirect or a request for a password (ADR 0019). */
public final class EaglerJoinFailures {

    private EaglerJoinFailures() {}

    /**
     * Follows a redirect, or asks for the password, left by the login that just failed. Returns
     * false if it left neither. Render thread.
     */
    public static boolean handle(Minecraft minecraft, Screen parent, ServerData server, ServerAddress address) {
        String redirect = ServerRedirects.takePending();
        if (redirect != null) {
            EaglerAuth.takePending();
            // Starting a connection replaces the screen being set up: after its init.
            minecraft.execute(() -> ServerRedirects.join(minecraft, parent, redirect));
            return true;
        }
        EaglerAuth.Request request = EaglerAuth.takePending();
        if (request != null) {
            minecraft.execute(() -> minecraft.gui.setScreen(new PasswordScreen(parent, server, address, request)));
            return true;
        }
        return false;
    }
}
