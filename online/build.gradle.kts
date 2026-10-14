plugins {
    `java-library`
    // An in-process stand-in for the service, for the tests and for tools/onlinedev.
    `java-test-fixtures`
}

// Incari accounts and the online service, client side (ADR 0058): the one WebSocket to the service
// and its back-off, the protocol's messages, the session store, sign-up's proof of work, server
// hashes, presence and the lookup cache. No game types, so it's tested on the JVM against a fake
// service; :client's incari.client.online draws the screens and feeds it the world.
dependencies {
    api(project(":platform-api"))
    api(project(":framework"))

    // Messages are JSON through Gson's tree API (no reflection). The game ships Gson, so at run time
    // its copy is on the class path; this is compile-only, as in :framework.
    compileOnly(libs.gson)

    testFixturesImplementation(libs.gson)
    testImplementation(libs.gson)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
