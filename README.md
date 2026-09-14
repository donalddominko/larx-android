# Larx — AI Voice Recorder (Android)

A privacy-first, **on-device-AI** voice recorder for Android. Record memos,
meetings and interviews; transcribe them **entirely on the phone** (whisper.cpp);
search and replay with a karaoke-style synced transcript. Nothing leaves the
device — Larx ships with **no network access at all** (see [Privacy](#privacy--no-egress-guarantee)).

Native Kotlin · Jetpack Compose · Material 3. `minSdk 26`, `targetSdk 36` (Android 16).

Licensed under **GPL-3.0** (see [`LICENSE`](LICENSE)); third-party attributions in
[`THIRD-PARTY.md`](THIRD-PARTY.md).

## Privacy / no-egress guarantee

Larx's core promise is that your recordings and transcripts never leave your
phone. This is enforced **structurally**, not by policy — and the code is here
for you to inspect:

- **The `INTERNET` permission is removed from the merged manifest**
  (`<uses-permission android:name="android.permission.INTERNET" tools:node="remove"/>`).
  With no `INTERNET` permission, any socket operation throws at the OS layer.
  Egress is *impossible*, not merely *disabled*.
- **There is network-capable code in the tree, and it is deliberately disarmed.**
  `core/sync/.../WebDavSink.kt` uses `java.net.HttpURLConnection` for a
  **deferred/Pro** cloud-backup feature. In release builds it is dead twice over:
  (1) it is gated off behind `Entitlements` (Pro is off in release), so it is
  never invoked; and (2) even if it were reached, there is no `INTERNET`
  permission, so it cannot open a socket. It's present and visible on purpose —
  inspect it rather than take our word for it. No production HTTP library
  (OkHttp/Retrofit/Ktor) is shipped; those appear only in test code.
- **A transitive telemetry path is neutralized.** The on-device MediaPipe text
  embedder (semantic search) transitively pulls Google's `datatransport`/Firelog
  (CCT) telemetry, which *declares* `INTERNET`. Its transport components are
  stripped from the manifest with `tools:node="remove"`, and — again — without
  the `INTERNET` permission they are inert.
- **A CI gate proves it on every build.** `tools/verify-release-no-egress.sh`
  (run in `.github/workflows/android.yml`) fails the build if the release
  artifact regains `INTERNET` or any known telemetry/transport component. There
  are **no analytics or crash-reporting SDKs** (no Firebase/Crashlytics/Sentry/etc.).

In short: we structurally prevent egress **and** show you the disarmed code and
the gate that keeps it that way.

## Locked capture decision (v1)

A third-party Play app **cannot** record both legs of a normal carrier call (the
OS removed the path across Android 6→9→10; Play banned the Accessibility-API
workaround in May 2022). On-device testing confirmed a Samsung A03 zeroes the mic
for a third-party app during a telephony call. Rather than ship a feature that
silently captures nothing on a large share of devices, Larx ships as a focused
**voice recorder**: room audio (memos/meetings/interviews), always available and
always Play-safe. The UI always renders the **resolved capture capability**
honestly — no copy ever implies call capture. The `CaptureSource` seam is
preserved so a viable future path (VoIP, an OEM intent) can be added as a new
source without churn elsewhere.

## Architecture

Five architectural seams, each owning a module under `core/`, never collapsed:
`CaptureSource`, `ConsentGate`, `Entitlements`, `Transcriber`, `CloudSink`.

```
app/            host (Compose), single build, signing, CI
core/capture    CaptureSource seam + recording foreground service
core/consent    ConsentGate seam
core/billing    Entitlements + Monetization seam
core/transcribe Transcriber seam (on-device whisper.cpp; a Pro cloud path is gated + deferred)
core/sync       CloudSink seam (WebDAV; disarmed — see Privacy)
core/data       encrypted Room data model (SQLCipher)
core/ui         Material 3 theme + shared Compose UI
```

## Building

**1. Clone with submodules.** On-device transcription uses
[whisper.cpp](https://github.com/ggml-org/whisper.cpp) as a git submodule pinned
to **v1.7.4**:

```bash
git clone --recurse-submodules <repo-url>
# or, if already cloned:
git submodule update --init --recursive
```

**2. Provide the whisper model.** The `ggml-base.bin` weights (~142 MB) are **not
committed** to this repository. The build copies them into the install-time
asset pack (`whisper_base_assets/`) and verifies their SHA-256. Obtain the file
(e.g. from the [whisper.cpp models](https://huggingface.co/ggerganov/whisper.cpp)
— `ggml-base.bin`) and either:

- place it at `.tmp/ggml-base.bin` (the default source path), or
- pass its location explicitly: `./gradlew ... -PmodelSrc=/path/to/ggml-base.bin`

(For contributors, distributing it via a GitHub Release asset or Git LFS is
recommended — never commit the binary into the tree.)

**3. Build.** Requires JDK 17 and the Android SDK (`compileSdk 36`).

```bash
# One-time, if the wrapper is not present: gradle wrapper --gradle-version 8.13
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease      # requires keystore/signing.properties (gitignored)
adb install app/build/outputs/apk/debug/app-debug.apk
```

Larx is a **single build** (no product flavors). Capture behaviour should be
validated on **physical devices** (one Pixel/AOSP, one Samsung/Xiaomi) — the
emulator's audio is not a faithful proxy.

## Signing

Release signing reads from `keystore/signing.properties` (gitignored). Copy
`keystore/signing.properties.example` to `keystore/signing.properties`, generate
a keystore, and fill it in. A fresh checkout builds **debug** without any secrets.

## License

GPL-3.0-or-later. See [`LICENSE`](LICENSE) and [`THIRD-PARTY.md`](THIRD-PARTY.md).
