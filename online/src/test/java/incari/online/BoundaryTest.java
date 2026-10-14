package incari.online;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The online client holds no game, LWJGL or TeaVM types: the JDK's basics, Gson's tree API for the
 * messages, the platform's network and the framework's views.
 */
@AnalyzeClasses(packages = "incari.online", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyJdkGsonPlatformAndFramework = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java.lang..",
                    "java.util..",
                    "java.io",
                    "java.nio",
                    "java.nio.charset",
                    "org.jspecify..",
                    "com.google.gson..",
                    "incari.platform.api.net",
                    "incari.framework.config",
                    "incari.framework.online",
                    "incari.online..");
}
