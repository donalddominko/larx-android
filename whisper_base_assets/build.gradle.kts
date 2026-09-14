plugins {
    // No version: the asset-pack plugin is already on the classpath via AGP.
    id("com.android.asset-pack")
}

// Install-time Play Asset Delivery pack that ships the on-device whisper `base`
// weights (ggml-base.bin, ~142 MB) WITH the app install — present at first launch,
// NO runtime download, NO INTERNET permission. This is what makes on-device
// transcription work offline-from-first-launch (the store "works in airplane mode"
// promise) and is the fix for the model-missing transcription failure.
//
// The 142 MB binary is NOT committed to git (see .gitignore). `provisionModelAsset`
// copies it in from `.tmp/ggml-base.bin` (or -PmodelSrc=/path) and fails loudly with
// instructions if absent — so a release build can never silently ship without the model.
assetPack {
    packName.set("whisper_base_assets")
    dynamicDelivery {
        deliveryType.set("install-time")
    }
}

// Config-cache safe: capture only serializable locals (String/File) — no references to
// the Project, script functions, or the `logger` inside the execution action.
val expectedSha = "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"
val srcFile = file(
    (project.findProperty("modelSrc") as String?)
        ?: rootProject.layout.projectDirectory.file(".tmp/ggml-base.bin").asFile.absolutePath,
)
val destFile = layout.projectDirectory.file("src/main/assets/ggml-base.bin").asFile

val provisionModelAsset by tasks.registering {
    description = "Copies + SHA-verifies ggml-base.bin into the asset pack before packaging."
    // Local copies so the doLast action captures serializable values, not the build
    // script object (configuration-cache requirement).
    val sha = expectedSha
    val src = srcFile
    val dest = destFile
    inputs.property("expectedSha", sha)
    outputs.file(dest)
    doLast {
        fun sha256(f: java.io.File): String {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(1 shl 20)
                while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
        if (dest.isFile && sha256(dest) == sha) return@doLast
        if (!src.isFile) {
            throw GradleException(
                "whisper_base_assets: model source not found at ${src.absolutePath}.\n" +
                    "  The ~142 MB ggml-base.bin is not in git. Provide it before a release build:\n" +
                    "    cp /path/to/ggml-base.bin .tmp/ggml-base.bin   (SHA-256 $sha)\n" +
                    "  or pass -PmodelSrc=/abs/path/to/ggml-base.bin",
            )
        }
        val actual = sha256(src)
        if (actual != sha) {
            throw GradleException(
                "whisper_base_assets: model at ${src.absolutePath} failed integrity check " +
                    "(sha=$actual, expected=$sha)",
            )
        }
        dest.parentFile.mkdirs()
        src.copyTo(dest, overwrite = true)
    }
}

// Ensure the asset is in place before ANY packaging task runs.
tasks.matching {
    it.name.startsWith("merge") || it.name.contains("AssetPack") ||
        it.name.startsWith("assemble") || it.name.startsWith("bundle")
}.configureEach { dependsOn(provisionModelAsset) }
