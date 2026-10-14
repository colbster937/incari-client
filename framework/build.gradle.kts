plugins {
    `java-library`
    // Fakes of the game's side (storage, a canvas, keys) that feature modules test against.
    `java-test-fixtures`
}

// The module framework (ADR 0046): the Module base class, the event bus, the typed settings
// schema, keybinds, HUD elements, profiles and their config. No game types: features/* see the game
// only through what this module defines, and :client's incari.client.modules fills it in.
dependencies {
    api(libs.jspecify)

    // Profiles are JSON through Gson's tree API (no reflection). The game ships Gson, so at run time
    // its copy is on the class path; this is compile-only, like :net's Netty.
    compileOnly(libs.gson)

    testFixturesApi(libs.jspecify)
    // A module system in a test reads profiles with Gson: the fixtures bring it, as the game does.
    testFixturesRuntimeOnly(libs.gson)
    testImplementation(libs.gson)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
