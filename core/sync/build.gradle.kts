plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "london.aipartner.echo.core.sync"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)

    // Entitlements/Feature for the Pro gate on sync (fail-closed alongside egress).
    // Egress is the binding, testable gate in Phase 8; the entitlement check sits
    // beside it. No consent-module dep — egress is read via the SyncEgressConsent
    // fun-interface, bound in :app (mirrors :core:transcribe's EgressConsent).
    implementation(project(":core:billing"))

    // Argon2id KDF for the user-held backup key (Option B, zero-knowledge). Pure-JVM
    // BouncyCastle — no JNI/native lib, so it derives identically in unit tests and
    // on-device, and adds no second ABI to the arm64-only APK.
    implementation(libs.bouncycastle.bcprov)

    // EncryptedSharedPreferences (Keystore-backed) caches the *derived key* + salt +
    // acknowledgement after first setup. The raw passphrase is NEVER persisted.
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real HTTP server for the WebDavSink tests — exercises the actual network code
    // (HttpURLConnection) against a controllable target: byte-count assertions, the
    // egress gate, and failure injection (401/500/mid-upload disconnect). Test-only.
    testImplementation(libs.okhttp.mockwebserver)
}
