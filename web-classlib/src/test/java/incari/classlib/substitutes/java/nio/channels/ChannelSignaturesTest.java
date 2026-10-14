package incari.classlib.substitutes.java.nio.channels;

import static org.assertj.core.api.Assertions.assertThat;

import incari.classlib.substitutes.java.nio.channels.spi.TAbstractInterruptibleChannel;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.GatheringByteChannel;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ScatteringByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The browser build links these substitutes by name, so their public and protected members must
 * be the JDK's once {@code T} names are read as JDK names: the same descriptors, and nothing the
 * JDK lacks. The JDK members left out are listed, each for a type TeaVM doesn't have.
 */
class ChannelSignaturesTest {

    private static final String SUBSTITUTES = "incari.classlib.substitutes.";

    @Test
    void fileChannel() {
        assertMatches(TFileChannel.class, FileChannel.class, Set.of("map(FileChannel$MapMode,long,long,Arena)"));
        assertMatches(TFileChannel.MapMode.class, FileChannel.MapMode.class, Set.of());
    }

    @Test
    void fileLock() {
        assertMatches(TFileLock.class, FileLock.class, Set.of("<init>(AsynchronousFileChannel,long,long,boolean)"));
    }

    @Test
    void channels() {
        assertMatches(
                TChannels.class,
                Channels.class,
                Set.of("newInputStream(AsynchronousByteChannel)", "newOutputStream(AsynchronousByteChannel)"));
    }

    @Test
    void interfacesAndBaseClass() {
        assertMatches(TAbstractInterruptibleChannel.class, AbstractInterruptibleChannel.class, Set.of());
        assertMatches(TInterruptibleChannel.class, InterruptibleChannel.class, Set.of());
        assertMatches(TGatheringByteChannel.class, GatheringByteChannel.class, Set.of());
        assertMatches(TScatteringByteChannel.class, ScatteringByteChannel.class, Set.of());
    }

    private static void assertMatches(Class<?> ours, Class<?> jdk, Set<String> omitted) {
        assertThat(jdkName(ours)).isEqualTo(jdk.getName());
        assertThat(ours.getSuperclass() == null ? null : jdkName(ours.getSuperclass()))
                .isEqualTo(
                        jdk.getSuperclass() == null ? null : jdk.getSuperclass().getName());
        assertThat(names(ours.getInterfaces())).isEqualTo(names(jdk.getInterfaces()));
        Map<String, String> ourMembers = members(ours);
        Map<String, String> jdkMembers = members(jdk);
        for (Map.Entry<String, String> member : ourMembers.entrySet()) {
            assertThat(jdkMembers.get(member.getKey()))
                    .as("the JDK's %s", member.getKey())
                    .isEqualTo(member.getValue());
        }
        Set<String> missing = new TreeSet<>(jdkMembers.keySet());
        missing.removeAll(ourMembers.keySet());
        assertThat(missing).as("JDK members not provided").isEqualTo(new TreeSet<>(omitted));
    }

    /** Each visible member, by name and parameters, to its return type and modifiers. */
    private static Map<String, String> members(Class<?> type) {
        Map<String, String> members = new TreeMap<>();
        for (Method method : type.getDeclaredMethods()) {
            if (visible(method) && !method.isSynthetic() && !method.isBridge()) {
                members.put(
                        describe(method, method.getName()),
                        jdkName(method.getReturnType()) + " " + Modifier.toString(method.getModifiers()));
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (visible(constructor)) {
                members.put(describe(constructor, "<init>"), Modifier.toString(constructor.getModifiers()));
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isPublic(field.getModifiers()) || Modifier.isProtected(field.getModifiers())) {
                members.put(field.getName(), jdkName(field.getType()) + " " + Modifier.toString(field.getModifiers()));
            }
        }
        return members;
    }

    private static boolean visible(Executable member) {
        return Modifier.isPublic(member.getModifiers()) || Modifier.isProtected(member.getModifiers());
    }

    private static String describe(Executable member, String name) {
        return name
                + Arrays.stream(member.getParameterTypes())
                        .map(ChannelSignaturesTest::simpleJdkName)
                        .collect(Collectors.joining(",", "(", ")"));
    }

    private static Set<String> names(Class<?>[] types) {
        return Arrays.stream(types).map(ChannelSignaturesTest::jdkName).collect(Collectors.toSet());
    }

    private static String simpleJdkName(Class<?> type) {
        String name = jdkName(type);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /** The JDK name a substitute stands for, as the browser build's substitution policy reads it. */
    static String jdkName(Class<?> type) {
        if (type.isArray()) {
            return jdkName(type.getComponentType()) + "[]";
        }
        String name = type.getName();
        if (!name.startsWith(SUBSTITUTES)) {
            return name;
        }
        String rest = name.substring(SUBSTITUTES.length());
        int dot = rest.lastIndexOf('.');
        return rest.substring(0, dot + 1) + rest.substring(dot + 2);
    }
}
