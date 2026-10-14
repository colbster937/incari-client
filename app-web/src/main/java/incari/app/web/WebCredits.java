package incari.app.web;

import incari.client.credits.Credit;
import incari.client.credits.Credits;
import java.net.URI;

/**
 * What the browser build contains that the desktop doesn't, for the credits screen: TeaVM's
 * runtime and class library, ThreeTen Backport, which TeaVM maps {@code java.time} onto, and JZlib,
 * the deflate engine under TeaVM's zip classes. All are compiled into the build's WebAssembly.
 */
final class WebCredits {

    private WebCredits() {}

    static void install() {
        Credits.add(new Credit(
                "TeaVM",
                null,
                "Alexey Andreev and contributors",
                "Apache-2.0",
                Credits.APACHE_2,
                URI.create("https://github.com/konsoletyper/teavm")));
        Credits.add(new Credit(
                "ThreeTen Backport",
                null,
                "Stephen Colebourne and Michael Nascimento Santos",
                "BSD-3-Clause",
                Credits.BSD_3_CLAUSE,
                URI.create("https://github.com/ThreeTen/threetenbp")));
        Credits.add(new Credit(
                "JZlib",
                null,
                "ymnk, JCraft, Inc.",
                "BSD-3-Clause",
                Credits.BSD_3_CLAUSE,
                URI.create("https://github.com/ymnk/jzlib")));
    }
}
