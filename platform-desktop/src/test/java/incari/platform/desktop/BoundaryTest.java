package incari.platform.desktop;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The desktop platform talks to the machine through LWJGL and to the game only through
 * platform-api. It never sees game types: those stay behind the interfaces.
 */
@AnalyzeClasses(packages = "incari.platform.desktop", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyPlatformApiAndLwjgl = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..", "org.jspecify..", "org.lwjgl..", "incari.platform.api..", "incari.platform.desktop..")
            .allowEmptyShould(true);
}
