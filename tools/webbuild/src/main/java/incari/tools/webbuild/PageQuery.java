package incari.tools.webbuild;

import java.util.regex.Pattern;

/** The page options the tools open the page with. */
final class PageQuery {

    /** The system property Gradle sets to the build a check or the benchmark runs: fast or compatible. */
    static final String PROPERTY = "incari.build";

    private static final Pattern BUILD = Pattern.compile("(^|&)build=");

    private PageQuery() {}

    /**
     * {@code query} with {@code build=<build>} added unless it names a build already or {@code build}
     * is empty. Where both builds can run, the page waits for the player to pick Fast or Compatible
     * (boot.ts, ADR 0036); a check or the benchmark must not wait, and runs the build it compiled.
     * An experiment's build has one module and no choice, so Gradle gives no build for it.
     */
    static String withBuild(String query, String build) {
        if (build.isEmpty() || BUILD.matcher(query).find()) {
            return query;
        }
        return query.isEmpty() ? "build=" + build : query + "&build=" + build;
    }

    /** {@link #withBuild} with the build Gradle names in {@link #PROPERTY}. */
    static String withBuild(String query) {
        return withBuild(query, System.getProperty(PROPERTY, ""));
    }
}
