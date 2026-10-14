plugins {
    application
}

dependencies {
    // Fixes to TeaVM classes; first on the class path so they replace TeaVM's (see its README).
    implementation(project(":tools:teavm-patches"))
    // The workspace, the version pin, and the survey's library rules and JDK hiding.
    implementation(project(":tools:build"))
    implementation(project(":tools:survey"))
    implementation(libs.teavm.tooling)
    implementation(libs.teavm.core)
    implementation(libs.teavm.classlib)
    // The class substitution SPI (ClasslibPolicy).
    implementation(libs.teavm.extension.spi)
    // The JavaScript interop the web platform is written against.
    implementation(libs.teavm.jso)
    implementation(libs.teavm.jso.apis)
    runtimeOnly(libs.teavm.jso.impl)
    // The browser check's DevTools protocol messages.
    implementation(libs.gson)
    // The screenshot-diff harness bridges the browser to its game server in-process.
    implementation(project(":tools:wsbridge"))
    // Turns WebAssembly stack traces back into Java names in development builds.
    runtimeOnly(libs.teavm.wasm.gc.deobfuscator)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "incari-webbuild"
    mainClass = "incari.tools.webbuild.Main"
    // TeaVM's dependency analysis over the game is memory-hungry.
    applicationDefaultJvmArgs = listOf("-Xmx16g")
}
