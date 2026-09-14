# Third-Party Notices

Larx is licensed under the GNU General Public License v3.0 (see [`LICENSE`](LICENSE)).
It bundles, links, or depends on the third-party components listed below. Each is
distributed under its own license, all of which are compatible with GPL-3.0 for
the combined work (Larx is distributed as a whole under GPL-3.0; the permissive
and Apache-2.0 components are one-way compatible into GPLv3).

| Component | Version | License | Compatibility |
|---|---|---|---|
| [whisper.cpp](https://github.com/ggml-org/whisper.cpp) (git submodule; on-device transcription) | v1.7.4 | MIT | ✅ GPL-3.0 compatible |
| [MediaPipe Tasks (Text)](https://github.com/google-ai-edge/mediapipe) / TensorFlow Lite (semantic embeddings) | 0.10.x | Apache-2.0 | ✅ (one-way into GPLv3) |
| [SQLCipher for Android](https://github.com/sqlcipher/sqlcipher-android) (encrypted database) | 4.6.1 | BSD-3-Clause (Zetetic) | ✅ GPL-3.0 compatible |
| [Bouncy Castle](https://www.bouncycastle.org/) (`bcprov-jdk18on`, Argon2id) | 1.79 | MIT-style (Bouncy Castle License) | ✅ GPL-3.0 compatible |
| AndroidX, Jetpack Compose, Material 3, Hilt/Dagger, Room, WorkManager, kotlinx-coroutines | — | Apache-2.0 | ✅ (one-way into GPLv3) |

> The whisper `ggml-base.bin` model weights are **not** distributed in this
> repository (see the README for how to obtain them). The model is published by
> the whisper.cpp / OpenAI Whisper projects under their respective terms.

---

## whisper.cpp — MIT License

```
MIT License

Copyright (c) 2023-2024 The ggml authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## SQLCipher for Android — BSD-3-Clause (Zetetic LLC)

```
Copyright (c) 2008-2023, ZETETIC LLC
All rights reserved.

Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are met:
    * Redistributions of source code must retain the above copyright
      notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above copyright
      notice, this list of conditions and the following disclaimer in the
      documentation and/or other materials provided with the distribution.
    * Neither the name of the ZETETIC LLC nor the
      names of its contributors may be used to endorse or promote products
      derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY ZETETIC LLC ''AS IS'' AND ANY
EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
DISCLAIMED. IN NO EVENT SHALL ZETETIC LLC BE LIABLE FOR ANY
DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
(INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

SQLCipher statically links a build of OpenSSL's libcrypto for its cryptographic
primitives; OpenSSL is distributed under the Apache-2.0 license (OpenSSL 3.x).

---

## Bouncy Castle — Bouncy Castle License (MIT-style)

```
Copyright (c) 2000 - 2026 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

Permission is hereby granted, free of charge, to any person obtaining a copy of
this software and associated documentation files (the "Software"), to deal in
the Software without restriction, including without limitation the rights to
use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
of the Software, and to permit persons to whom the Software is furnished to do
so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## MediaPipe / TensorFlow Lite, and AndroidX / Jetpack Compose / Material 3 / Hilt (Dagger) / Room / WorkManager / kotlinx-coroutines — Apache License 2.0

These components are © their respective authors (Google LLC; the Android Open
Source Project; JetBrains s.r.o. for kotlinx-coroutines) and licensed under the
Apache License, Version 2.0. The full text is available at
<https://www.apache.org/licenses/LICENSE-2.0>. Apache-2.0 is one-way compatible
with GPL-3.0, so these are incorporated into the GPL-3.0-licensed whole.
