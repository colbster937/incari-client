plugins {
    application
}

// A development stand-in for Incari's online service (ADR 0058): :online's in-process fake behind a
// real WebSocket, so the client's account screens can be tried without the real service.
// Keeps everything in memory, checks nothing that matters, and must never face the internet. Never
// shipped.

dependencies {
    implementation(testFixtures(project(":online")))
    implementation(libs.netty.buffer)
    implementation(libs.netty.transport)
    implementation(libs.netty.codec.base)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.gson)
}

application {
    applicationName = "incari-onlinedev"
    mainClass = "incari.tools.onlinedev.Main"
}

tasks.named<JavaExec>("run") {
    // `./gradlew :tools:onlinedev:run --args="--listen 8090"`, then the page with
    // ?online=ws://localhost:8090/v1/ws, or the desktop with -Pincari.online.url=ws://localhost:8090/v1/ws.
    standardInput = System.`in`
}
