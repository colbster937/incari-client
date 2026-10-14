plugins {
    `java-library`
}

// The Via stack in the client (ADR 0017): ViaVersion and ViaBackwards under a small Incari
// platform, as a stage of the game's Netty pipeline. No game types.

dependencies {
    // The Via jars ship in the build (desktop and browser alike); they're GPLv3 (ADR 0017).
    api(libs.viaversion)
    api(libs.viabackwards)

    // What Via is written against besides the libraries it bundles (relocated Gson, fastutil,
    // SnakeYAML); at run time the game's own copies, the same versions, are used.
    compileOnly(libs.netty.buffer)
    compileOnly(libs.netty.transport)
    compileOnly(libs.netty.codec.base)
    compileOnly(libs.guava)

    testImplementation(libs.netty.buffer)
    testImplementation(libs.netty.transport)
    testImplementation(libs.netty.codec.base)
    testImplementation(libs.guava)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
