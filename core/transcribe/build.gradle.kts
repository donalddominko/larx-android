plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

// The native whisper.cpp build is engaged ONLY when the pinned submodule is
// materialised (dev machine: `git submodule update --init --recursive`). When it
// is absent — this authoring machine and the emulator-less CI runner — the module
// still configures and Gate A (JVM unit tests) still runs; the on-device path
// then reports isAvailable()=false and fails honestly. This guard is what keeps
// "wire the native build" from breaking the green Kotlin pipeline without an NDK.
val nativeWhisperPresent = file("src/main/cpp/whisper.cpp/CMakeLists.txt").exists()

android {
    namespace = "london.aipartner.echo.core.transcribe"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Ship the JNI keep rules to any consuming app's R8 pass (protects the
        // FindClass("TranscriptSegment") boundary from release-only obfuscation).
        consumerProguardFiles("consumer-rules.pro")
        if (nativeWhisperPresent) {
            // On-device whisper.cpp native build: arm64-v8a ONLY (Phase 4 decision).
            // Other ABIs intentionally unsupported — isAvailable() reports false and
            // the on-device path fails honestly via TranscriptionEngineUnavailable.
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild {
                cmake {
                    // CRITICAL: build whisper/ggml OPTIMIZED even in the *debug* APK.
                    // By default AGP compiles native code with CMAKE_BUILD_TYPE matching
                    // the variant (debug → Debug → -O0), and ggml is almost entirely SIMD
                    // math — at -O0 whisper-base runs ~10× slower (an 18 s clip took ~4 min
                    // on the A03). Force Release so on-device transcription is usable in
                    // debug too. (We don't need native-level debugging of whisper.)
                    arguments += "-DCMAKE_BUILD_TYPE=Release"
                }
            }
        }
    }
    if (nativeWhisperPresent) {
        // JNI build for the whisper.cpp engine: the CMake script links whisper.cpp
        // (the pinned git submodule) statically into libwhisper_jni.so. Requires
        // the NDK installed on the build machine. See PROGRESS.md Phase 4.
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
        // AGP-preferred NDK for this toolchain; install via sdkmanager "ndk;<ver>".
        ndkVersion = "27.0.12077973"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // Run each instrumented test in its OWN process (AndroidX Test Orchestrator).
    // This module's androidTest suite initialises three heavy native runtimes —
    // whisper.cpp (Gate B), TFLite/XNNPACK (MediaPipe search), and SQLCipher — and
    // on the low-RAM A03 (2 GB) running them all in one shared instrumentation
    // process exhausts memory and the kernel kills the run ("Process crashed").
    // Per-test process isolation keeps native state + memory from accumulating; each
    // test (which passes individually) now also passes in the full suite.
    testOptions {
        execution = "ANDROIDX_TEST_ORCHESTRATOR"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    api(libs.kotlinx.coroutines.core)

    // Entitlements/Feature for the cloud double-gate; data entities + DAO for
    // persisting rev-0 transcripts. (No consent-module dep — egress is read via
    // the EgressConsent fun-interface, bound in :app.)
    implementation(project(":core:billing"))
    implementation(project(":core:data"))

    // Phase 5 — on-device semantic search. MediaPipe Text Embedder runs the bundled
    // Universal Sentence Encoder model (src/main/assets) fully on-device: free,
    // offline, zero egress. The model is committed (~5.8 MB); the AAR's TFLite
    // native libs are the APK-size contributor measured in PROGRESS/Phase 5.
    implementation(libs.mediapipe.tasks.text)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    // Gate B (Phase 4) — on-device native whisper smoke test. Runs only on a
    // physical arm64 device (the .so + ~142 MB ggml-base model are real). The model
    // and reference WAVs live in src/androidTest/assets and are copied to filesDir
    // at runtime (no storage-permission/path issues).
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    // The AI-artifact lifecycle test drives the real encrypted EchoDatabase, so the
    // androidTest classpath needs Room's types (EchoDatabase is `implementation` in main).
    androidTestImplementation(libs.androidx.room.runtime)
    // Per-test process isolation for the heavy native suite (see testOptions above).
    androidTestUtil(libs.androidx.test.orchestrator)
}
