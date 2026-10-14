package incari.framework;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The framework is what features see of the game, so it holds no game, platform or TeaVM types:
 * only the JDK's basics, JSpecify, and Gson's tree API for the config.
 */
@AnalyzeClasses(packages = "incari.framework", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyJdkAndJspecify = classes()
            .that()
            .resideOutsideOfPackage("incari.framework.config..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java.lang..", "java.util..", "java.io", "org.jspecify..", "incari.framework..");

    @ArchTest
    static final ArchRule configAddsGson = classes()
            .that()
            .resideInAPackage("incari.framework.config..")
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java.lang..",
                    "java.util..",
                    "java.io",
                    "org.jspecify..",
                    "com.google.gson..",
                    "incari.framework..");
}
