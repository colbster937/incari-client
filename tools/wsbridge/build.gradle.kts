plugins {
    application
}

// A development stand-in for an Eagler proxy (ADR 0009, ADR 0016): lets the client's Eagler transport
// join a vanilla server of any version, with Eagler's handshake and MOTD, one packet per message.
// Never shipped.

dependencies {
    implementation(libs.netty.buffer)
    implementation(libs.netty.transport)
    implementation(libs.netty.codec.base)
    implementation(libs.netty.codec.http)
    implementation(libs.netty.handler)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "incari-wsbridge"
    mainClass = "incari.tools.wsbridge.Main"
}

tasks.named<JavaExec>("run") {
    // `./gradlew :tools:wsbridge:run --args="--listen 8816 --target 127.0.0.1:25565"`
    standardInput = System.`in`
}
