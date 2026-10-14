plugins {
    `java-library`
}

// The WebGL2 graphics backend: Mojang's GpuDevice API over WebGL2, for the browser build.
// Only the shaders package runs on a normal JVM too (tools/shaders writes the manifest it reads).

dependencies {
    // The game's render API (com.mojang.renderpearl) and the Incari hooks its patches call.
    api(project(":client"))

    // TeaVM's JavaScript interop: WebGL2 and the typed arrays that carry data to it.
    compileOnly(libs.teavm.jso)
    compileOnly(libs.teavm.jso.apis)
    compileOnly(libs.teavm.interop)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    // Game classes loaded by tests write logs/ into the working directory; keep that in build/.
    val runDir = layout.buildDirectory.dir("test-run").get().asFile
    workingDir = runDir
    doFirst { runDir.mkdirs() }
}
