import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

data class RustDaemonAbi(
    val androidAbi: String,
    val rustTarget: String,
    val linkerName: String,
) {
    val envKey: String = rustTarget.replace('-', '_').uppercase()
    val taskSuffix: String = androidAbi.split('-', '_').joinToString("") { part ->
        part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }
}

val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { localProperties.load(it) }
}

val androidMinSdkVersion = 32
val androidNdkVersion = "29.0.14206865"
val rustDaemonAbis = listOf(
    RustDaemonAbi(
        androidAbi = "arm64-v8a",
        rustTarget = "aarch64-linux-android",
        linkerName = "aarch64-linux-android${androidMinSdkVersion}-clang",
    ),
    RustDaemonAbi(
        androidAbi = "armeabi-v7a",
        rustTarget = "armv7-linux-androideabi",
        linkerName = "armv7a-linux-androideabi${androidMinSdkVersion}-clang",
    ),
    RustDaemonAbi(
        androidAbi = "x86_64",
        rustTarget = "x86_64-linux-android",
        linkerName = "x86_64-linux-android${androidMinSdkVersion}-clang",
    ),
)

fun androidHostTag(): String {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    return when {
        osName.contains("linux") -> "linux-x86_64"
        osName.contains("mac") && osArch.contains("aarch64") -> "darwin-aarch64"
        osName.contains("mac") -> "darwin-x86_64"
        osName.contains("windows") -> "windows-x86_64"
        else -> error("Unsupported host OS for Android NDK: $osName/$osArch")
    }
}

fun androidSdkDir(): File {
    localProperties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let { return file(it) }
    listOf("/opt/android-sdk", "/usr/local/lib/android/sdk").map(::file).firstOrNull { it.exists() }?.let { return it }
    error("Android SDK not found. Set sdk.dir, ANDROID_HOME, or ANDROID_SDK_ROOT.")
}

fun ndkToolchainBinDir(): File = file(
    "${androidSdkDir().absolutePath}/ndk/$androidNdkVersion/toolchains/llvm/prebuilt/${androidHostTag()}/bin",
)

android {
    namespace = "io.github.xiaotong6666.tools"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.xiaotong6666.tools"
        minSdk = androidMinSdkVersion
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        androidResources {
            localeFilters += "zh"
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        val keystorePath = localProperties.getProperty("ANDROID_DEBUG_KEYSTORE")
        val keystoreFile = listOfNotNull(
            keystorePath?.takeIf { it.isNotBlank() }?.let(::file),
            file(System.getProperty("user.home") + "/.android/debug.keystore"),
        ).firstOrNull { it.exists() }
        if (keystoreFile != null) {
            register("debugKey") {
                storeFile = keystoreFile
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfigs.findByName("debugKey")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            signingConfigs.findByName("debugKey")?.let { signingConfig = it }
            isMinifyEnabled = false
            isDebuggable = true
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = androidNdkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

val rustDaemonProjectDir = layout.projectDirectory.dir("src/main/cpp/tools-daemon")
val rustDaemonTargetDir = layout.buildDirectory.dir("rust/tools-daemon")
val nativeDaemonAssetsDir = layout.buildDirectory.dir("generated/nativeDaemonAssets")
val copiedDaemonAssetFiles = rustDaemonAbis.map { file("src/main/assets/${it.androidAbi}/tools-daemon") }

val rustupAndroidTargets = tasks.register<Exec>("rustupAndroidTargets") {
    group = "build"
    description = "Installs Rust Android targets used by tools-daemon."
    commandLine(listOf("rustup", "target", "add") + rustDaemonAbis.map { it.rustTarget })
}

val rustDaemonBuildTasks = rustDaemonAbis.map { abi ->
    tasks.register<Exec>("buildToolsDaemon${abi.taskSuffix}") {
        group = "build"
        description = "Builds Rust tools-daemon for ${abi.androidAbi}."
        dependsOn(rustupAndroidTargets)

        val toolchainBinDir = ndkToolchainBinDir()
        val linker = file("${toolchainBinDir.absolutePath}/${abi.linkerName}")
        val strip = file("${toolchainBinDir.absolutePath}/${if (System.getProperty("os.name").lowercase().contains("windows")) "llvm-strip.exe" else "llvm-strip"}")
        val cargoTargetDir = rustDaemonTargetDir.get().asFile
        val outputFile = nativeDaemonAssetsDir.map { it.file("${abi.androidAbi}/tools-daemon") }

        workingDir = rustDaemonProjectDir.asFile
        environment("CARGO_TARGET_DIR", cargoTargetDir.absolutePath)
        environment("CARGO_TARGET_${abi.envKey}_LINKER", linker.absolutePath)
        commandLine("cargo", "build", "--release", "--target", abi.rustTarget, "--locked")

        inputs.files(
            fileTree(rustDaemonProjectDir.asFile) {
                include("Cargo.toml", "Cargo.lock", "rust-toolchain.toml", "src/**")
            },
        )
        outputs.file(outputFile)

        doFirst {
            if (!linker.isFile) {
                throw GradleException("Android NDK linker not found: ${linker.absolutePath}")
            }
            outputFile.get().asFile.parentFile.mkdirs()
        }
        doLast {
            val builtBinary = file("${cargoTargetDir.absolutePath}/${abi.rustTarget}/release/tools-daemon")
            if (!builtBinary.isFile) {
                throw GradleException("Cargo did not produce tools-daemon: ${builtBinary.absolutePath}")
            }
            copy {
                from(builtBinary)
                into(outputFile.get().asFile.parentFile)
                rename { "tools-daemon" }
            }
            outputFile.get().asFile.setReadable(true, true)
            outputFile.get().asFile.setWritable(true, true)
            outputFile.get().asFile.setExecutable(true, true)
            if (strip.isFile) {
                providers.exec {
                    commandLine(strip.absolutePath, "--strip-debug", outputFile.get().asFile.absolutePath)
                }.result.get().assertNormalExitValue()
            }
        }
    }
}

dependencies {
    implementation(project(":uihelper"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigationevent.compose)

    implementation(libs.miuix.ui)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    implementation(libs.miuix.preference)

    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.legacy.support.v4)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.com.google.android.material)
}

tasks.register("copyDaemonAssets") {
    dependsOn(rustDaemonBuildTasks)
    doLast {
        val generatedDir = file("${layout.buildDirectory.asFile.get()}/generated/nativeDaemonAssets")
        if (generatedDir.exists()) {
            generatedDir.copyRecursively(file("src/main/assets"), overwrite = true)
        }
    }
}
tasks.matching { it.name == "mergeDebugAssets" || it.name == "mergeReleaseAssets" }.configureEach {
    dependsOn("copyDaemonAssets")
}

tasks.named<Delete>("clean") {
    delete(rustDaemonProjectDir.dir("target"))
    delete(copiedDaemonAssetFiles)
}
