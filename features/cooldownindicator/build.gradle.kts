plugins {
    `java-library`
}

// Cooldown Indicator (ADR 0053): the attack cooldown as a bar, ring or number, in place of the game's indicator.
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
