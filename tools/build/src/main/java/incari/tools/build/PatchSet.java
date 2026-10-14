package incari.tools.build;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The patch files under {@code patches/}, laid out as {@code <subsystem>/<NNNN>-<slug>.patch}.
 *
 * <p>The number is the patch's position in one global order (patches build on each other
 * across subsystems); the directory groups them for reading and review. A patch's commit
 * subject is {@code "<subsystem>: <summary>"}, which is how {@code rebuild} knows where
 * each regenerated file belongs.
 */
public final class PatchSet {

    /** {@code "render: Route section compile to the mesher"} */
    static final Pattern SUBJECT = Pattern.compile("([a-z0-9][a-z0-9-]*): (\\S.*)");

    private static final Pattern FILE_NAME = Pattern.compile("(\\d{4})-[a-z0-9-]+\\.patch");
    private static final int MAX_SLUG = 60;

    private PatchSet() {}

    public record PatchFile(int number, String subsystem, Path path) {}

    /** Every patch file in application order. Rejects stray names and duplicate numbers. */
    public static List<PatchFile> list(Path patchesDir) throws IOException {
        if (!Files.isDirectory(patchesDir)) {
            return List.of();
        }
        List<PatchFile> patches = new ArrayList<>();
        try (Stream<Path> files = Files.walk(patchesDir)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".patch")).toList()) {
                Path relative = patchesDir.relativize(file);
                Matcher name = FILE_NAME.matcher(file.getFileName().toString());
                if (relative.getNameCount() != 2 || !name.matches()) {
                    throw new CliException("Unexpected patch file " + relative
                            + " (expected <subsystem>/<NNNN>-<slug>.patch; run ./incari rebuild to regenerate names)");
                }
                patches.add(new PatchFile(
                        Integer.parseInt(name.group(1)), relative.getName(0).toString(), file));
            }
        }
        patches.sort(Comparator.comparingInt(PatchFile::number));
        Map<Integer, Path> byNumber = new HashMap<>();
        for (PatchFile patch : patches) {
            Path clash = byNumber.put(patch.number(), patch.path());
            if (clash != null) {
                throw new CliException(
                        "Two patches share number " + patch.number() + ": " + clash + " and " + patch.path());
            }
        }
        return List.copyOf(patches);
    }

    /** The repo-relative file a commit with this subject is written to, at position {@code number}. */
    static String fileFor(int number, String subject) {
        Matcher matcher = SUBJECT.matcher(subject);
        if (!matcher.matches()) {
            throw new CliException("Commit subject \"" + subject + "\" must look like \"<subsystem>: <summary>\""
                    + " (lowercase subsystem, e.g. \"render: Route section compile to the mesher\")");
        }
        return matcher.group(1) + "/" + String.format(Locale.ROOT, "%04d", number) + "-" + slug(matcher.group(2))
                + ".patch";
    }

    static String slug(String summary) {
        String slug =
                summary.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (slug.length() > MAX_SLUG) {
            slug = slug.substring(0, MAX_SLUG).replaceAll("-[^-]*$", "");
        }
        return slug.isEmpty() ? "patch" : slug;
    }
}
