package incari.platform.api;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * platform-api is compiled into every build, the browser one included: it may use only the JDK
 * (the subset TeaVM supports) and JSpecify. No game, library or platform types.
 */
@AnalyzeClasses(packages = "incari.platform.api", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyJdkAndJspecify = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "org.jspecify..", "incari.platform.api..")
            .allowEmptyShould(true);
}
