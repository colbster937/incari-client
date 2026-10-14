package incari.via;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * via is Via under a small platform: Netty, the Via libraries and the JDK. It never sees game
 * types (the client's adapters decide each server's version) or Incari's platform code.
 */
@AnalyzeClasses(packages = "incari.via", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyNettyAndVia = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage("java..", "org.jspecify..", "io.netty..", "com.viaversion..", "incari.via..")
            .allowEmptyShould(true);
}
