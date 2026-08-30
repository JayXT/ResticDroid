plugins {
    alias(libs.plugins.android.library)
}

kotlin {
    explicitApi()
}

android {
    namespace = "io.github.resticdroid.restic"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            keepDebugSymbols += "**/librestic.so"
            useLegacyPackaging = true
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val androidSdkDir: String = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .orElse(
        providers.fileContents(rootProject.layout.projectDirectory.file("local.properties"))
            .asText.map { text ->
                text.lineSequence()
                    .firstOrNull { it.trimStart().startsWith("sdk.dir=") }
                    ?.substringAfter('=')?.trim().orEmpty()
            }
    )
    .getOrElse("")

val buildResticBinaries = tasks.register<Exec>("buildResticBinaries") {
    group = "restic"
    description = "Cross-compiles restic from third_party/restic into src/main/jniLibs."

    val script = rootProject.layout.projectDirectory.file("tools/build-restic.sh")
    val source = rootProject.layout.projectDirectory.dir("third_party/restic")
    val output = layout.projectDirectory.dir("src/main/jniLibs")
    val version = layout.projectDirectory.file("src/main/res/raw/restic_version.txt")

    inputs.dir(source).withPropertyName("resticSource").ignoreEmptyDirectories()
    outputs.dir(output).withPropertyName("jniLibs")
    // Declared so that losing it alone marks the task out of date. Otherwise
    // Gradle sees the binaries, calls the task up to date, never runs the
    // script that writes this, and R.raw.restic_version resolves to nothing.
    outputs.file(version).withPropertyName("resticVersion")

    commandLine("sh", script.asFile.absolutePath)
    environment("ANDROID_HOME", androidSdkDir)

    onlyIf { source.file("go.mod").asFile.exists() }
}

// Unconditional, not "only when a binary is missing": the binaries are build
// output, and output that is never rechecked is output that silently disagrees
// with its source - a bumped submodule would otherwise ship the old restic.
//
// The cost of asking every time is nil - build-restic.sh keeps a stamp of the
// commit it built and returns immediately when it matches, which also means CI
// and the F-Droid buildserver, which run the script themselves before Gradle,
// do not pay for a second compile.
tasks.named("preBuild") { dependsOn(buildResticBinaries) }
