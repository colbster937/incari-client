package incari.client;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Incari's game-side code runs in every build, so it's platform-neutral: it reaches the machine
 * only through platform-api, never through LWJGL, TeaVM or a platform implementation. That covers
 * the incari.client packages and every vendor class Incari replaces (patches/classes.txt), which
 * is the point of replacing them. Vendor code keeps its own LWJGL calls until a patch routes them.
 */
class BoundaryTest {

    private static JavaClasses classes;
    private static Set<String> replacedClasses;

    @BeforeAll
    static void importClasses() throws IOException {
        Path manifest = Path.of(System.getProperty("incari.repoRoot", "../..")).resolve("patches/classes.txt");
        replacedClasses = Files.readAllLines(manifest).stream()
                .map(line -> line.replaceAll("#.*", "").strip())
                .filter(line -> line.startsWith("replace "))
                .map(line -> line.split("\\s+", -1)[1])
                .collect(Collectors.toSet());
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("incari.client", "com.mojang", "net.minecraft");
    }

    @Test
    void incariCodeIsPlatformNeutral() {
        DescribedPredicate<JavaClass> incariCode = DescribedPredicate.describe(
                "Incari code (incari.client and the classes patches/classes.txt replaces)",
                javaClass -> javaClass.getPackageName().startsWith("incari.client")
                        || replacedClasses.contains(outermost(javaClass).getName()));

        noClasses()
                .that(incariCode)
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.lwjgl..", "org.teavm..", "incari.platform.desktop..", "incari.platform.web..")
                .check(classes);
    }

    @Test
    void everyReplacementWasImported() {
        // Guards the rule above: a replacement it can't see isn't checked.
        Set<String> imported = classes.stream().map(JavaClass::getName).collect(Collectors.toSet());
        assertThat(imported).containsAll(replacedClasses);
    }

    private static JavaClass outermost(JavaClass javaClass) {
        JavaClass current = javaClass;
        while (current.getEnclosingClass().isPresent()) {
            current = current.getEnclosingClass().get();
        }
        return current;
    }
}
