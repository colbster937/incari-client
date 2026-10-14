plugins {
    application
}

dependencies {
    // Reuses the build tool's workspace layout and version pin, so the survey always reads
    // the same jars `incari fetch` downloaded.
    implementation(project(":tools:build"))
    implementation(libs.teavm.tooling)
    implementation(libs.teavm.core)
    implementation(libs.teavm.classlib)
    implementation(libs.asm)
    implementation(libs.gson)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    applicationName = "incari-survey"
    mainClass = "incari.tools.survey.Main"
    // TeaVM's dependency analysis over the whole client plus its libraries is memory-hungry.
    // Override with INCARI_SURVEY_OPTS="-Xmx..." on smaller machines.
    applicationDefaultJvmArgs = listOf("-Xmx12g", "-Dincari.teavm.version=${libs.versions.teavm.get()}")
}

tasks.test {
    // SurveyRulesTest parses the real rules.txt from the module directory.
    workingDir = projectDir
}
