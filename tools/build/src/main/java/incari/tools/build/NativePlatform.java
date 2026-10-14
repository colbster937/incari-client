package incari.tools.build;

import java.util.Locale;

/**
 * The operating system and CPU the desktop runtime runs on, named the way Mojang's version JSON
 * classifies native jars ({@code natives-linux}, {@code natives-windows-arm64}, ...).
 */
public record NativePlatform(String os, boolean arm64) {

    public static NativePlatform current() {
        return of(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static NativePlatform of(String osName, String osArch) {
        String name = osName.toLowerCase(Locale.ROOT);
        String os;
        if (name.startsWith("windows")) {
            os = "windows";
        } else if (name.startsWith("mac") || name.startsWith("darwin")) {
            os = "macos";
        } else if (name.startsWith("linux")) {
            os = "linux";
        } else {
            throw new CliException("Minecraft ships no native libraries for " + osName);
        }
        String arch = osArch.toLowerCase(Locale.ROOT);
        return new NativePlatform(os, arch.equals("aarch64") || arch.equals("arm64"));
    }

    public String classifier() {
        return "natives-" + os + (arm64 ? "-arm64" : "");
    }
}
