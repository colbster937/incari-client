package incari.render.webgl2;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The WebGL2 backend talks to the game only through its render API and the Incari hook for it, and
 * to the page only through TeaVM's JavaScript interop. LWJGL appears in two places the render API
 * forces on it: {@code MemoryUtil} for mapped memory the game takes addresses of, and
 * {@code PointerBuffer} in a multi-draw signature it never implements.
 */
@AnalyzeClasses(packages = "incari.render.webgl2", importOptions = ImportOption.DoNotIncludeTests.class)
class BoundaryTest {

    @ArchTest
    static final ArchRule onlyTheRenderApiAndJso = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideInAnyPackage(
                    "java..",
                    "org.jspecify..",
                    "org.teavm.jso..",
                    "com.mojang.renderpearl.api..",
                    "com.mojang.renderpearl.backend.api..",
                    "com.mojang.renderpearl.backend.common..",
                    "com.mojang.renderpearl.frontend",
                    "com.mojang.renderpearl.util..",
                    "net.minecraft.resources..",
                    "net.minecraft.client.renderer",
                    "com.google.gson..",
                    "it.unimi.dsi.fastutil..",
                    "org.joml..",
                    "org.lwjgl",
                    "org.lwjgl.system",
                    "incari.client.render..",
                    "incari.render.webgl2..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule lwjglOnlyWhereTheApiForcesIt = classes()
            .should()
            .onlyDependOnClassesThat()
            .resideOutsideOfPackages("org.lwjgl.opengl..", "org.lwjgl.sdl..", "org.lwjgl.util..", "org.lwjgl.vulkan..")
            .allowEmptyShould(true);
}
