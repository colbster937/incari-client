package incari.platform.api;

/**
 * What the platform needs to know about the application: names for the window manager, the
 * audio mixer and crash dialogs.
 *
 * @param name the product name, for example "Minecraft"
 * @param version the game version, for example "26.3"
 * @param identifier a reverse-DNS application id, for example "com.mojang.minecraft"
 * @param creator who made it, for the platform's "about" metadata
 * @param url the product's web page
 */
public record AppInfo(String name, String version, String identifier, String creator, String url) {}
