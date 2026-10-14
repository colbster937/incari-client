import java.util.Properties

plugins {
    `java-library`
}

dependencies {
    api(project(":platform-api"))

    // Compile against the same LWJGL version the game ships (libs.versions.toml). At run time
    // the game's own copies are on the classpath, so these are compile-only.
    compileOnly(platform(libs.lwjgl.bom))
    compileOnly(libs.lwjgl)
    compileOnly(libs.lwjgl.sdl)
    compileOnly(libs.lwjgl.openal)
    compileOnly(libs.lwjgl.stb)
    compileOnly(libs.lwjgl.freetype)

    // A WebSocket server for the WebSocket connection's tests.
    testImplementation(libs.netty.transport)
    testImplementation(libs.netty.codec.http)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

// `./gradlew :platform-desktop:openAlReference` (ADR 0013): what the desktop's sound sounds
// like, measured. OpenAlReferenceTest plays a tone through this platform's OpenAL device at known
// positions with OpenAL Soft writing to a WAV file instead of a sound card, and checks each
// channel's level against the gains platform-web's Web Audio model reproduces. Needs the game's
// LWJGL and this machine's natives (`./incari setup`); no sound card. Not part of `check`: OpenAL
// Soft reads the configuration that selects the file only from the environment it starts in.
val repoRoot = rootProject.layout.projectDirectory
val lwjglRuntime = run {
    val version = Properties().apply {
        repoRoot.file("incari.properties").asFile.reader().use { load(it) }
    }.getProperty("minecraft.version")
    val root = repoRoot.asFile
    fun listed(name: String) = providers.fileContents(repoRoot.file("work/cache/versions/$version/$name")).asText
        .map { text -> text.lines().map { it.trim() }.filter { it.isNotEmpty() } }.orElse(emptyList())
    listed("classpath.txt").zip(listed("natives.txt")) { jars, natives -> jars + natives }
        .map { paths ->
            paths.filter { it.contains("/org/lwjgl/lwjgl/") || it.contains("/org/lwjgl/lwjgl-openal/") }
                .map { root.resolve(it) }
        }
}

tasks.register<Test>("openAlReference") {
    group = "verification"
    description = "Measures the desktop's OpenAL panning and attenuation through OpenAL Soft's wave backend."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath + files(lwjglRuntime)
    filter { includeTestsMatching("*OpenAlReferenceTest") }
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val directory = layout.buildDirectory.dir("openal-reference").get().asFile
    val config = directory.resolve("alsoft.conf")
    val wave = directory.resolve("output.wav")
    environment("ALSOFT_CONF", config.path)
    environment("INCARI_OPENAL_WAVE", wave.path)
    jvmArgs(
        "--enable-native-access=ALL-UNNAMED",
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + repoRoot.dir("work/natives/lwjgl").asFile.path,
    )
    // Stereo 48 kHz, as a desktop's usual output; the device is opened as the game opens it.
    doFirst {
        directory.mkdirs()
        config.writeText("[general]\ndrivers=wave\nchannels=stereo\nfrequency=48000\n[wave]\nfile=${wave.path}\n")
    }
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed") }
}
