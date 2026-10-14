plugins {
    `java-library`
}

// The game's network transport under its Netty pipeline (ADR 0009): the Netty channel over the
// platform's connections, WebSocket addresses, and message framing. No game types.

dependencies {
    api(project(":platform-api"))

    // Compile against the Netty version the game ships (libs.versions.toml). At run time the game's
    // own copies are on the classpath, so these are compile-only.
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec.base)

    testImplementation(libs.netty.buffer)
    testImplementation(libs.netty.transport)
    testImplementation(libs.netty.codec.base)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
