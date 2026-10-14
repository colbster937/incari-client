import java.util.Properties

plugins {
    application
}

// Decodes the vanilla sprites and makes their mip levels at build time, with the game's own code,
// for the browser build's asset pack (ADR 0026). Runs on a normal JVM with LWJGL's natives
// (stb_image decodes the PNGs, as on the desktop). Its output holds Mojang's textures, decoded:
// build output, never committed or published.

dependencies {
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

/** This machine's LWJGL natives (stb among them), as `incari fetch` got them. */
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
    applicationName = "incari-sprites"
    mainClass = "incari.tools.sprites.Main"
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
