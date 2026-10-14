plugins {
    `java-library`
}

// The terrain mesher (ADR 0023): turns a flat snapshot of a section and its surroundings into
// vertex data, using tables built from the game's models. No game types and no dependencies, so it
// compiles on its own into the small WebAssembly module the page's mesher workers run, and runs in
// the game itself where there are no workers (the desktop, or a mesh the render thread needs now).
dependencies {
    api(libs.jspecify)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}
