plugins {
    `java-library`
}

// No dependencies but the JDK subset TeaVM supports, and JSpecify for nullness in signatures:
// both platforms and the game compile against this module (gradle/module-boundaries.txt).
dependencies {
    api(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
