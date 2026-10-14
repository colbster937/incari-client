package incari.platform.web;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The browser platform talks to the page through TeaVM's JavaScript interop and to the game
 * only through platform-api. Of TeaVM's runtime it uses only the file system interface its
 * {@code java.io} reads through ({@code org.teavm.runtime.fs}), not TeaVM's own file systems.
 */
@AnalyzeClasses(packages = "incari.platform.web", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyPlatformApiAndJso = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "org.jspecify..",
                    "org.teavm.jso..",
                    "org.teavm.interop..",
                    "org.teavm.runtime.fs",
                    "incari.platform.api..",
                    "incari.platform.web..")
            .allowEmptyShould(true);
}
