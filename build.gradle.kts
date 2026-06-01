import java.util.Properties

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless)
}

val ndkVersion = "30.0.14904198"
val rustDaemonDir = file("app/src/main/cpp/tools-daemon")
val rustDaemonToolchain = rustDaemonDir.resolve("rust-toolchain.toml").takeIf { it.isFile }
    ?.readText()
    ?.lineSequence()
    ?.map { it.trim() }
    ?.firstOrNull { it.startsWith("channel") }
    ?.substringAfter('=')
    ?.trim()
    ?.removeSurrounding("\"")
    ?.takeIf { it.isNotBlank() }
    ?: "stable"

spotless {
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    java {
        target("**/src/*/java/**/*.java")
        targetExclude("**/api/**", "**/build/**")

        palantirJavaFormat()
        importOrder()
        removeUnusedImports()
        formatAnnotations()
    }

    kotlin {
        target("**/src/*/kotlin/**/*.kt", "**/src/*/java/**/*.kt")
        targetExclude("**/api/**", "**/build/**")
        ktlint().editorConfigOverride(
            mapOf(
                "ktlint_standard_backing-property-naming" to "disabled",
                "ktlint_standard_no-wildcard-imports" to "disabled",
                "ktlint_standard_property-naming" to "disabled",
                "ktlint_standard_function-naming" to "disabled",
                "ktlint_standard_max-line-length" to "disabled",
                "ktlint_standard_comment-wrapping" to "disabled",
                "ktlint_standard_package-name" to "disabled",
                "ktlint_standard_filename" to "disabled"
            )
        )
    }

    format("cpp") {
        target("**/src/main/cpp/**/*.c", "**/src/main/cpp/**/*.cpp", "**/src/main/cpp/**/*.h", "**/src/main/cpp/**/*.hpp")
        targetExclude("**/api/**", "**/build/**")

        var sdkDir = ""
        val properties = Properties()
        val localProps = file("local.properties")
        if (localProps.exists()) {
            localProps.inputStream().use { properties.load(it) }
            sdkDir = properties.getProperty("sdk.dir") ?: ""
        }
        if (sdkDir.isBlank()) {
            sdkDir = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: ""
        }
        if (sdkDir.isBlank()) {
            val commonPaths = listOf("/opt/android-sdk", "/usr/local/lib/android/sdk")
            for (path in commonPaths) {
                if (file(path).exists()) {
                    sdkDir = path
                    break
                }
            }
        }

        val osName = System.getProperty("os.name").lowercase()
        val platform = when {
            osName.contains("linux") -> "linux-x86_64"
            osName.contains("mac") -> "darwin-x86_64"
            else -> "windows-x86_64"
        }
        var clangPath = "$sdkDir/ndk/$ndkVersion/toolchains/llvm/prebuilt/$platform/bin/clang-format"
        if (osName.contains("windows")) clangPath += ".exe"

        val clangFile = file(clangPath)
        if (clangFile.exists()) {
            clangFormat("21.0.0").style("file").pathToExe(clangPath)
        } else {
            println("Spotless Warning: Clang-format not found at $clangPath")
            clangFormat().style("file")
        }
    }
}

tasks.register("format") {
    dependsOn("spotlessApply")
    if (rustDaemonDir.resolve("Cargo.toml").isFile) {
        dependsOn("cargoFmt")
    }
    group = "formatting"
    description = "Formats the code using Spotless"
}

tasks.register<Exec>("rustupRustfmt") {
    group = "formatting"
    description = "Installs rustfmt for the active Rust toolchain"
    commandLine("rustup", "component", "add", "--toolchain", rustDaemonToolchain, "rustfmt")
    onlyIf { rustDaemonDir.resolve("Cargo.toml").isFile }
}

tasks.register<Exec>("cargoFmt") {
    group = "formatting"
    description = "Formats Rust code for tools-daemon using cargo fmt"
    dependsOn("rustupRustfmt")
    workingDir = rustDaemonDir
    commandLine("cargo", "+$rustDaemonToolchain", "fmt", "--all")
    onlyIf { rustDaemonDir.resolve("Cargo.toml").isFile }
}
