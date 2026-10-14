plugins {
    `java-library`
}

dependencies {
    api(project(":platform-api"))

    // TeaVM's JavaScript interop: the browser APIs this platform is built on.
    compileOnly(libs.teavm.jso)
    compileOnly(libs.teavm.jso.apis)
    // @Async: suspending the game's coroutine until the browser calls back.
    compileOnly(libs.teavm.interop)
    // org.teavm.runtime.fs: the file system interface TeaVM's java.io and java.nio.file read
    // through, which storage/ implements. Only those interfaces are used (BoundaryTest).
    compileOnly(libs.teavm.core)

    testImplementation(libs.teavm.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj)
    testImplementation(libs.archunit)
    testRuntimeOnly(libs.junit.launcher)
}

// The storage worker (src/main/js/storage-worker.js) and the program worker's bootstrap
// (src/main/js/program-worker.js) run from Blob URLs, so the page needs no extra files: their
// sources become string constants the platform hands to the page.
val workerScripts = mapOf(
    "storage-worker.js" to "incari/platform/web/storage/StorageWorkerSource",
    "program-worker.js" to "incari/platform/web/ProgramWorkerSource",
)
val workerSourceDir = layout.buildDirectory.dir("generated/sources/workers/java")
val generateWorkerSources = tasks.register("generateWorkerSources") {
    description = "Turns the workers' JavaScript into Java string constants."
    val scripts = workerScripts.mapKeys { layout.projectDirectory.file("src/main/js/${it.key}") }
    inputs.files(scripts.keys)
    outputs.dir(workerSourceDir)
    // Locals: a task action capturing the build script can't go in the configuration cache.
    val out = workerSourceDir
    val sources = scripts.map { (file, className) -> file.asFile to className }
    doLast {
        for ((script, className) in sources) {
            val text = script.readText()
            val literal = StringBuilder()
            for (line in text.split("\n")) {
                val escaped = StringBuilder()
                for (c in line + "\n") {
                    when {
                        c == '\\' -> escaped.append("\\\\")
                        c == '"' -> escaped.append("\\\"")
                        c == '\n' -> escaped.append("\\n")
                        c.code < 0x20 || c.code > 0x7E -> escaped.append(String.format("\\u%04x", c.code))
                        else -> escaped.append(c)
                    }
                }
                literal.append("            + \"").append(escaped).append("\"\n")
            }
            val packageName = className.substringBeforeLast('/').replace('/', '.')
            val simpleName = className.substringAfterLast('/')
            val file = out.get().file("$className.java").asFile
            file.parentFile.mkdirs()
            file.writeText(
                """
                |package $packageName;
                |
                |/** Generated from src/main/js/${script.name} by :platform-web:generateWorkerSources. */
                |final class $simpleName {
                |
                |    /** The worker's source, run from a Blob URL. */
                |    static final String SCRIPT = ""
                |${literal.toString().trimEnd()};
                |
                |    private $simpleName() {}
                |}
                |""".trimMargin(),
            )
        }
    }
}

sourceSets.main {
    java.srcDir(generateWorkerSources)
}
