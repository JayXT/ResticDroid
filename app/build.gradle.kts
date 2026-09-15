import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val appId: String = providers.gradleProperty("resticdroid.applicationId").get()

android {
    namespace = "io.github.resticdroid"
    compileSdk = 37
    // Pinned: left unset, AGP downloads its own default mid-build.
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = appId
        minSdk = 26
        targetSdk = 37
        // Literals, not computed: F-Droid finds these with a regular expression,
        // and a derived value would be invisible to it. They are the only copy -
        // a second one elsewhere in this file would drift, and :app:checkVersion
        // would happily pass while the APK carried the old number.
        versionCode = 300
        versionName = "0.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    androidResources {
        localeFilters += listOf("en")
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = false
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val file = rootProject.file("keystore.properties")
                if (file.exists()) file.inputStream().use { load(it) }
            }
            fun value(key: String, env: String) =
                props.getProperty(key) ?: System.getenv(env)

            val store = value("storeFile", "RESTICDROID_KEYSTORE")
            val password = value("storePassword", "RESTICDROID_KEYSTORE_PASSWORD")
            if (store != null && password != null) {
                storeFile = file(store)
                storePassword = password
                keyAlias = value("keyAlias", "RESTICDROID_KEY_ALIAS")
                keyPassword = value("keyPassword", "RESTICDROID_KEY_PASSWORD") ?: password

                // AGP leaves v3 off unless asked. v2 alone verifies fine, but
                // v3 is what carries a signing-certificate lineage, and that
                // lineage is the only mechanism by which this key could ever
                // be rotated. It costs nothing to have and cannot be added to
                // installs that already exist.
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // findByName, not getByName: F-Droid deletes the signingConfigs
            // block before building, and a hard reference to what it removed
            // fails the build outright rather than producing the unsigned APK
            // it is asking for.
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/librestic.so"
        }
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        warningsAsErrors = false
        abortOnError = true
        disable += setOf("GradleDependency", "ObsoleteLintCustomCheck")
    }
}

val abiVersionOffsets = mapOf("armeabi-v7a" to 1, "arm64-v8a" to 2, "x86" to 3, "x86_64" to 4)
val baseVersionCode = android.defaultConfig.versionCode!!
val baseVersionName = android.defaultConfig.versionName!!

androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val abi = output.filters.find { it.filterType.name == "ABI" }?.identifier
            val offset = abiVersionOffsets[abi] ?: 0
            output.versionCode.set(baseVersionCode * 10 + offset)
        }
    }
}

dependencies {
    implementation(project(":restic-android"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.appcompat)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

val forbiddenDependencyGroups = listOf(
    "com.google.android.gms",
    "com.google.android.play",
    "com.google.firebase",
    "com.google.mlkit",
    "com.crashlytics",
    "io.sentry",
    "com.appsflyer",
    "com.adjust",
    "com.amplitude",
    "com.mixpanel",
    "com.segment",
    "com.facebook.android",
    "com.google.android.datatransport",
    "androidx.tracing.perfetto",
    "com.huawei",
)

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val ids: Provider<List<String>> =
            variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map { root ->
                val seen = linkedSetOf<String>()
                fun visit(component: org.gradle.api.artifacts.result.ResolvedComponentResult) {
                    if (!seen.add(component.id.displayName)) return
                    component.dependencies
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
                        .forEach { visit(it.selected) }
                }
                visit(root)
                seen.sorted()
            }

        tasks.register("auditDependencies") {
            group = "verification"
            description = "Fails if the release classpath contains a tracker or a proprietary service SDK."

            val forbidden = forbiddenDependencyGroups
            val report = layout.buildDirectory.file("reports/dependencies/release-runtime.txt")
            inputs.property("modules", ids)
            outputs.file(report)

            doLast {
                val resolved = ids.get()
                val offenders = resolved.filter { id ->
                    forbidden.any { id.startsWith("$it:") || id.startsWith("$it.") }
                }

                report.get().asFile.apply {
                    parentFile.mkdirs()
                    writeText(resolved.joinToString("\n", postfix = "\n"))
                }

                logger.lifecycle("Release runtime classpath: ${resolved.size} modules")
                if (offenders.isNotEmpty()) {
                    throw GradleException(
                        buildString {
                            appendLine("Forbidden dependencies on the release classpath:")
                            offenders.forEach { appendLine("  - $it") }
                            appendLine()
                            appendLine("ResticDroid ships with no trackers and no dependency on a")
                            appendLine("proprietary service. Either remove the dependency that pulled")
                            appendLine("this in, exclude it, or - if it is genuinely harmless - amend")
                            appendLine("forbiddenDependencyGroups in app/build.gradle.kts and say why.")
                        }
                    )
                }
                logger.lifecycle("auditDependencies: no trackers, no proprietary service SDKs.")
            }
        }
    }
}

tasks.register("checkVersion") {
    group = "verification"
    description = "Asserts that versionCode and versionName have not drifted apart."

    val name = baseVersionName
    val code = baseVersionCode
    doLast {
        // A prerelease suffix is part of the name a user reads, not of the
        // number Android compares, so it is stripped before the arithmetic.
        val parts = name.substringBefore('-').split(".")
        require(parts.size == 3 && parts.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            "versionName '$name' is not major.minor.patch with an optional -suffix"
        }
        val expected = parts[0].toInt() * 10000 + parts[1].toInt() * 100 + parts[2].toInt()
        if (expected != code) {
            throw GradleException(
                "versionName '$name' implies versionCode $expected, but versionCode is $code. " +
                    "Both literals in app/build.gradle.kts must be updated together."
            )
        }
        logger.lifecycle("checkVersion: $name / $code")
    }
}

tasks.named("check") { dependsOn("auditDependencies", "checkVersion") }
