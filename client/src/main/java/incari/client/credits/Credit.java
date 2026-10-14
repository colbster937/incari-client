package incari.client.credits;

import java.net.URI;
import org.jspecify.annotations.Nullable;

/**
 * One entry on the credits screen: a piece of software in this build, under its own license.
 *
 * @param name what the software is called
 * @param version the version in this build, shown after the name; null when it isn't known here
 * @param authors who holds its copyright
 * @param license the license's short name, as SPDX names it where it can
 * @param licenseText where the license's full text is
 * @param source where its source code is
 */
public record Credit(
        String name, @Nullable String version, String authors, String license, URI licenseText, URI source) {

    /** The name with the version after it, if there is one. */
    public String title() {
        return version == null ? name : name + " " + version;
    }
}
