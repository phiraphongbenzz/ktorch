plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Public API dumps. binary-compatibility-validator and Kotlin's own abiValidation do not see
// compilations under AGP 9's built-in Kotlin yet, so the dump is taken with `javap -public` from
// each module's release compile jar: the exact class files a consumer compiles against.
val apiModules = listOf("library", "library-no-op", "library-room")
val javap = File(System.getProperty("java.home"), "bin/javap").absolutePath

fun publicApi(jar: File): String {
    val classes = java.util.zip.ZipFile(jar).use { zip ->
        zip.entries().asSequence().map { it.name }
            .filter { it.endsWith(".class") && it.startsWith("com/phiraphongbenzz/ktorch/") }
            // internal.* is Kotlin-internal or cross-module plumbing; R is generated resources.
            .filterNot { "/internal/" in it || it.substringAfterLast('/').matches(Regex("R(\\$.*)?\\.class")) }
            .map { it.removeSuffix(".class").replace('/', '.') }
            .sorted().toList()
    }
    if (classes.isEmpty()) return ""
    val process = ProcessBuilder(listOf(javap, "-public", "-cp", jar.absolutePath) + classes)
        .redirectErrorStream(true).start()
    val out = process.inputStream.bufferedReader().readText()
    check(process.waitFor() == 0) { "javap failed:\n$out" }
    // Keep public classes only, and drop compiler-generated members: `$stable` (Compose) and
    // `access$...` accessors for private top-level declarations.
    val result = StringBuilder()
    var keep = false
    out.lines().filterNot { it.startsWith("Compiled from") || it.isBlank() }.forEach { line ->
        if (!line.startsWith(" ") && line != "}") keep = line.startsWith("public ")
        if (keep && "\$stable" !in line && "access\$" !in line) result.append(line).append('\n')
    }
    return result.toString()
}

fun compileJar(module: String) =
    file("$module/build/intermediates/compile_library_classes_jar/release/bundleLibCompileToJarRelease/classes.jar")

val apiDump by tasks.registering {
    group = "verification"
    description = "Writes <module>/api/<module>.api from the release compile jar."
    dependsOn(apiModules.map { ":$it:bundleLibCompileToJarRelease" })
    doLast {
        apiModules.forEach { module ->
            file("$module/api").mkdirs()
            file("$module/api/$module.api").writeText(publicApi(compileJar(module)))
        }
    }
}

val apiCheck by tasks.registering {
    group = "verification"
    description = "Fails if a public API changed without ./gradlew apiDump."
    dependsOn(apiModules.map { ":$it:bundleLibCompileToJarRelease" })
    doLast {
        apiModules.forEach { module ->
            val expected = file("$module/api/$module.api")
            check(expected.exists()) { "Missing $expected. Run ./gradlew apiDump." }
            check(expected.readText() == publicApi(compileJar(module))) {
                "Public API of :$module changed. Review it, then run ./gradlew apiDump."
            }
        }
    }
}

// The no-op artifact must expose exactly the same API as the real one, otherwise
// `debugImplementation` / `releaseImplementation` call sites stop compiling in release.
val checkApiParity by tasks.registering {
    group = "verification"
    description = "Fails if library and library-no-op public APIs differ."
    dependsOn(apiCheck)
    doLast {
        val real = file("library/api/library.api").readText()
        val noop = file("library-no-op/api/library-no-op.api").readText()
        check(real == noop) {
            "Public API of :library and :library-no-op differ.\n" +
                "diff library/api/library.api library-no-op/api/library-no-op.api"
        }
    }
}

tasks.register("check") {
    group = "verification"
    dependsOn(checkApiParity, ":library:lint", ":library-room:lint", ":library:testDebugUnitTest")
}
