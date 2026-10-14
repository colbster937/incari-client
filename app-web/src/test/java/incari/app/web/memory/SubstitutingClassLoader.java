package incari.app.web.memory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/**
 * Loads the game's classes as the browser build links them, as far as memory goes: every
 * reference to an LWJGL class that {@code web-classlib} stands in for ({@code MemoryUtil},
 * {@code MemoryStack}, {@code STBImage}...) is renamed to the stand-in, the way TeaVM's
 * substitution does it, and the stand-ins run on this JVM.
 *
 * <p>Only the classes that need it are loaded again here: those that name a stand-in's class, and
 * those that name one of them, and so on. Everything else, the stand-ins included, comes from the
 * parent, so the game's shared data (vertex formats, enums) is the same objects on both sides.
 * The {@code roots} are test classes that drive the game and are loaded here too.
 */
final class SubstitutingClassLoader extends ClassLoader {

    private static final String SUBSTITUTES = "incari/classlib/substitutes/";

    private final Set<String> reloaded;
    private final Remapper remapper;

    SubstitutingClassLoader(ClassLoader parent, Class<?> gameClass, Set<String> roots) {
        super("substituting", parent);
        Map<String, Set<String>> references = references(gameClass);
        for (String root : roots) {
            references.put(root.replace('.', '/'), referencesOf(readParent(root.replace('.', '/'))));
        }
        this.reloaded = closure(references);
        this.reloaded.addAll(roots.stream().map(root -> root.replace('.', '/')).toList());
        this.remapper = new Remapper(Opcodes.ASM9) {
            @Override
            public String map(String internalName) {
                String substitute = substituteFor(internalName);
                return substitute != null ? substitute : internalName;
            }
        };
    }

    /** How many of the game's classes are loaded again, for the test's report. */
    int reloadedCount() {
        return reloaded.size();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            if (!reloaded.contains(name.replace('.', '/'))) {
                return super.loadClass(name, resolve);
            }
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null) {
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(readParent(name.replace('.', '/'))).accept(new ClassRemapper(writer, remapper), 0);
                byte[] bytes = writer.toByteArray();
                loaded = defineClass(name, bytes, 0, bytes.length);
            }
            if (resolve) {
                resolveClass(loaded);
            }
            return loaded;
        }
    }

    /** {@code org/lwjgl/system/MemoryUtil$MemoryAllocator} to its stand-in's name, if web-classlib has one. */
    private @Nullable String substituteFor(String internalName) {
        if (!internalName.startsWith("org/lwjgl/")) {
            return null;
        }
        int slash = internalName.lastIndexOf('/');
        String candidate = SUBSTITUTES + internalName.substring(0, slash + 1) + "T" + internalName.substring(slash + 1);
        return getParent().getResource(candidate + ".class") != null ? candidate : null;
    }

    private byte[] readParent(String internalName) {
        try (InputStream in = getParent().getResourceAsStream(internalName + ".class")) {
            if (in == null) {
                throw new IllegalStateException("No class file for " + internalName);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The classes each class of the game names, read from the jar or directory {@code gameClass} came from. */
    private static Map<String, Set<String>> references(Class<?> gameClass) {
        Map<String, Set<String>> references = new HashMap<>();
        try {
            Path location = Path.of(gameClass
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
            if (Files.isDirectory(location)) {
                try (Stream<Path> files = Files.walk(location)) {
                    for (Path file : (Iterable<Path>) files::iterator) {
                        String relative = location.relativize(file).toString().replace('\\', '/');
                        if (relative.endsWith(".class")) {
                            references.put(
                                    relative.substring(0, relative.length() - 6),
                                    referencesOf(Files.readAllBytes(file)));
                        }
                    }
                }
            } else {
                try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(location))) {
                    for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                        String name = entry.getName();
                        if (name.endsWith(".class") && !name.startsWith("META-INF/")) {
                            references.put(name.substring(0, name.length() - 6), referencesOf(zip.readAllBytes()));
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
        return references;
    }

    /**
     * Every class a class file names, loosely: each UTF-8 constant as a name itself (what class
     * entries point at) and the {@code Lpkg/Name;} names inside it (descriptors and signatures).
     * Names that aren't classes are harmless; they match nothing.
     */
    private static Set<String> referencesOf(byte[] classFile) {
        Set<String> names = new HashSet<>();
        ClassReader reader = new ClassReader(classFile);
        for (int i = 1; i < reader.getItemCount(); i++) {
            int offset = reader.getItem(i);
            // A CONSTANT_Utf8 entry: tag 1, a big-endian length, then the text (ASCII for class names).
            if (offset > 0 && classFile[offset - 1] == 1) {
                int length = ((classFile[offset] & 0xFF) << 8) | (classFile[offset + 1] & 0xFF);
                String text = new String(classFile, offset + 2, length, java.nio.charset.StandardCharsets.ISO_8859_1);
                names.add(text.startsWith("[") ? text.substring(text.lastIndexOf('[') + 1) : text);
                addDescriptorNames(text, names);
            }
        }
        return names;
    }

    /** Names in {@code Lpkg/Name;} form inside a descriptor or signature, loosely: false names are harmless. */
    private static void addDescriptorNames(String text, Set<String> names) {
        int at = text.indexOf('L');
        while (at >= 0) {
            int end = text.indexOf(';', at);
            if (end < 0) {
                return;
            }
            String candidate = text.substring(at + 1, end);
            int generic = candidate.indexOf('<');
            names.add(generic >= 0 ? candidate.substring(0, generic) : candidate);
            at = text.indexOf('L', end);
        }
    }

    /**
     * The classes that reach a stand-in's class through the references, and every class in their
     * packages: a class loaded here can't use package-private members of one from the parent,
     * whose runtime package differs.
     */
    private Set<String> closure(Map<String, Set<String>> references) {
        Map<String, Set<String>> users = new HashMap<>();
        Map<String, Set<String>> packages = new HashMap<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        Set<String> result = new HashSet<>();
        for (Map.Entry<String, Set<String>> entry : references.entrySet()) {
            String name = entry.getKey();
            packages.computeIfAbsent(packageOf(name), p -> new HashSet<>()).add(name);
            for (String reference : entry.getValue()) {
                users.computeIfAbsent(reference, n -> new HashSet<>()).add(name);
                if (substituteFor(reference) != null && result.add(name)) {
                    pending.add(name);
                }
            }
        }
        Set<String> expandedPackages = new HashSet<>();
        while (!pending.isEmpty()) {
            String name = pending.removeFirst();
            for (String user : users.getOrDefault(name, Set.of())) {
                if (result.add(user)) {
                    pending.add(user);
                }
            }
            if (expandedPackages.add(packageOf(name))) {
                for (String mate : packages.getOrDefault(packageOf(name), Set.of())) {
                    if (result.add(mate)) {
                        pending.add(mate);
                    }
                }
            }
        }
        return result;
    }

    private static String packageOf(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }
}
