plugins {
    `java-library`
}

// Fullbright: the world fully lit, as night vision lights it.
// A feature module: it sees the module framework and nothing else.
dependencies {
    api(project(":framework"))

    testImplementation(testFixtures(project(":framework")))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
