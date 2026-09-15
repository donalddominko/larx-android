import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// versionCode is DERIVED from the git commit count — monotonic and impossible to forget. A re-used
// versionCode is rejected by Play AND won't update installed devices (an internal tester keeps the old
// build, old icon/splash and all — this exact trap caused a stale-splash cycle). Deriving it removes
// the human step entirely. `providers.exec` keeps it configuration-cache compatible; a `maxOf(3, …)`
// floor means even a git-less build (source archive) emits a code strictly greater than the last
// MANUAL upload (2), so Play never rejects a degraded build for a stale code. versionName stays manual.
val gitCommitCount: Int = runCatching {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)
val derivedVersionCode: Int = maxOf(3, gitCommitCount)

android {
    namespace = "london.aipartner.echo"
    compileSdk = 36

    testOptions {
        // JVM unit tests exercise production code that calls android.util.Log (e.g. SemanticBackfill).
        // Return default values from unmocked android.jar stubs (Log.* → no-op) instead of throwing
        // "Method not mocked", so pure-logic tests don't need Robolectric just to tolerate a log line.
        unitTests.isReturnDefaultValues = true
    }

    defaultConfig {
        applicationId = "london.aipartner.echo"
        minSdk = 26
        targetSdk = 36
        // AUTO — git-commit-count derived (see the top-of-file note). Monotonic, can't be forgotten.
        // versionName remains a manual human label — bump it when meaningful.
        versionCode = derivedVersionCode
        versionName = "0.1.4"
        testInstrumentationRunner = "london.aipartner.echo.HiltTestRunner"

        // v1 is arm64-v8a ONLY (the Phase 4 native decision). whisper.cpp is built
        // arm64-only, so the app cannot meaningfully run on other ABIs anyway; without
        // this filter, AAR native libs (MediaPipe TFLite, SQLCipher) would ship all 4
        // ABIs and ~4× their on-device weight. Restricting here keeps the bundled
        // MediaPipe embedder's footprint to the arm64 lib only. Revisit only if a
        // non-arm64 target is ever pursued (it would also need an arm-other whisper).
        ndk { abiFilters += "arm64-v8a" }
    }

    // Keystore details are read from keystore/signing.properties (gitignored).
    // Release stays unsigned-by-config until that file exists, so a fresh
    // checkout still builds debug without secrets.
    val signingProps = rootProject.file("keystore/signing.properties")
    signingConfigs {
        if (signingProps.exists()) {
            val props = Properties().apply { load(signingProps.inputStream()) }
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingProps.exists()) signingConfig = signingConfigs.getByName("release")
            testProguardFiles("proguard-test-rules.pro")
        }
    }

    // Instrumented tests normally run against DEBUG (unminified) — which structurally HIDES any
    // R8-only failure (obfuscation breaking JNI FindClass or MediaPipe protobuf reflection). Run
    // with `-PreleaseTest` to build the androidTest suite against the MINIFIED release variant so a
    // targeted smoke exercises the real R8 output. Default stays debug so the device-suite gate
    // (`connectedDebugAndroidTest`) is unaffected. Requires keystore/signing.properties.
    testBuildType = if (project.hasProperty("releaseTest")) "release" else "debug"

    // Single build, no flavors. Echo is a voice/memo recorder; the privileged-
    // capture path that justified the old `play`/`direct` split was removed with
    // call recording. A Google-free `foss` flavor for F-Droid can be reintroduced
    // at Phase 9 (monetization) if/when there's AdMob/Billing code to exclude.
    // Install-time asset pack shipping the whisper `base` weights WITH the app
    // (present at first launch, no runtime download, no INTERNET). The on-device
    // transcriber loads by file path, so the model is copied out of this pack to
    // filesDir on first run — see InstallTimeAssetModelProvisioner.
    assetPacks += listOf(":whisper_base_assets")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // BouncyCastle (Argon2id, via :core:sync) and jspecify both ship a versioned
    // OSGI manifest at the same path; neither is needed at runtime. Exclude to
    // resolve the merge collision.
    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":core:capture"))
    implementation(project(":core:consent"))
    implementation(project(":core:billing"))
    implementation(project(":core:transcribe"))
    implementation(project(":core:sync"))
    implementation(project(":core:data"))
    implementation(project(":core:ui"))

    implementation(libs.androidx.core.ktx)
    // Phase 8: the WebDAV destination store keeps its credentials in EncryptedSharedPreferences.
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.window.size)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // WorkManager — transcription is decoupled from the record FGS into a background worker
    // (Phase 7) so a long transcription can never block a new recording's start/stop.
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // MemoryPressureTest drives the REAL shipped TranscriptionWorker via TestListenableWorkerBuilder
    // (no WorkManager init needed under HiltTestApplication), so it profiles the worker path itself.
    androidTestImplementation(libs.androidx.work.testing)
    // DeleteEverywhereTest builds the real encrypted EchoDatabase to assert the
    // delete-everywhere cascade — needs Room's RoomDatabase supertype on classpath.
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
