# Echo ProGuard/R8 rules. Populated as modules with reflection (Room, Hilt
# already ship consumer rules) require it.

# Jetpack Security EncryptedFile (Phase 2 encrypted-at-rest audio) pulls in Tink,
# which references compile-only Error Prone annotations not on the runtime
# classpath. They are safe to strip from warnings.
-dontwarn com.google.errorprone.annotations.**
