plugins {
    application
}

dependencies {
    implementation(libs.gson)
    // Not called directly: the decompile step runs Vineflower in a child JVM (its own heap,
    // and a crash can't take the CLI down). It sits on the runtime classpath so the CLI can
    // find the jar.
    runtimeOnly(libs.vineflower)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "incari-build"
    mainClass = "incari.tools.build.Main"
}
