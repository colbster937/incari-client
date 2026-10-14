import net.ltgt.gradle.errorprone.errorprone

plugins {
    `java-library`
}

// Fixes to TeaVM classes, shadowing TeaVM's own on the web build's class path (README.md).
// TeaVM's code (Apache License 2.0), kept in its own style: not reformatted, not linted.
// The tests are Incari code and follow the usual rules.

dependencies {
    compileOnly(libs.teavm.core)
    compileOnly(libs.teavm.classlib)
    compileOnly(libs.teavm.jso)
    compileOnly(libs.teavm.jso.impl)
    // The JSO generator's JavaScript bodies are Rhino trees (TeaVM's relocated copy).
    compileOnly("org.teavm:teavm-relocated-libs-rhino:${libs.versions.teavm.get()}")

    // Class library fixes run on the JVM in the tests, checked against the JDK's own behaviour.
    testImplementation(libs.teavm.classlib)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.named<JavaCompile>("compileJava") {
    options.compilerArgs.removeAll { it.startsWith("-Xlint") || it == "-Werror" }
    options.errorprone.enabled = false
}

configure<com.diffplug.gradle.spotless.SpotlessExtension> {
    java {
        // Third-party code keeps its upstream formatting.
        targetExclude("src/main/**/*.java")
    }
}
