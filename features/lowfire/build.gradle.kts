plugins {
    `java-library`
}

// Low Fire (ADR 0050): the fire overlay drawn lower, so burning hides less of the view.
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
