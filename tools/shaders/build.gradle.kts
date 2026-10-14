import java.util.Properties

plugins {
    application
}

// Translates the game's shaders to GLSL ES 3.00 for the browser build. Runs on a normal
// JVM at build time, with LWJGL's shaderc and SPIRV-Cross natives, the way the desktop game does
// at run time. Its output holds translated Mojang shaders: build output, never committed.

dependencies {
    // The manifest format the WebGL2 device reads, and through it the game (RenderPipelines).
    implementation(project(":render-webgl2"))
    implementation(project(":client"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

val repoRoot = rootProject.layout.projectDirectory
val minecraftVersion: String = Properties().run {
    repoRoot.file("incari.properties").asFile.reader().use { load(it) }
    getProperty("minecraft.version")
}

/** This machine's LWJGL natives (shaderc and SPIRV-Cross among them), as `incari fetch` got them. */
val nativeJars = run {
    val root = repoRoot.asFile
    providers.fileContents(repoRoot.file("work/cache/versions/$minecraftVersion/natives.txt")).asText.map { text ->
        text.lines().filter { it.isNotBlank() }.map { root.resolve(it.trim()) }
    }.orElse(emptyList())
}

dependencies {
    runtimeOnly(files(nativeJars))
}

application {
    applicationName = "incari-shaders"
    mainClass = "incari.tools.shaders.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=${repoRoot.dir("work/natives/lwjgl").asFile}",
    )
}

tasks.test {
    // Game classes loaded by tests write logs/ into the working directory; keep that in build/.
    val runDir = layout.buildDirectory.dir("test-run").get().asFile
    workingDir = runDir
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=${repoRoot.dir("work/natives/lwjgl").asFile}",
    )
    doFirst { runDir.mkdirs() }
}
