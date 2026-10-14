plugins {
    application
}

dependencies {
    // The workspace layout and version pin: compares against the same decompiled tree
    // `incari setup` produced.
    implementation(project(":tools:build"))

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "incari-copycheck"
    mainClass = "incari.tools.copycheck.Main"
    // The vendor tree has millions of token windows; the index is a sorted long[].
    applicationDefaultJvmArgs = listOf("-Xmx2g")
}
