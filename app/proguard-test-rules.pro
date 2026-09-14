# R8 rules for the INSTRUMENTED TEST APK ONLY (built when -PreleaseTest runs androidTest against
# the minified release variant). These keep the test/Hilt/JUnit infrastructure that the minified
# test APK needs at runtime. NOT shipped in the app.
-dontwarn com.google.auto.value.AutoValue

# The custom instrumentation runner + Hilt test application (referenced by name at startup).
-keep class london.aipartner.echo.HiltTestRunner { *; }
-keep class dagger.hilt.android.testing.** { *; }
-keep class dagger.hilt.** { *; }
-keep class * extends android.app.Application { *; }
-keep class * extends androidx.test.runner.MonitoringInstrumentation { *; }
-keep class androidx.test.** { *; }
-dontwarn androidx.test.**

# JUnit + the test classes/methods (found by name via the class-filter runner arg).
-keep class org.junit.** { *; }
-dontwarn org.junit.**
-keepclasseswithmembers @org.junit.runner.RunWith class * { *; }
-keepclassmembers class * { @org.junit.Test <methods>; @org.junit.Before <methods>; @org.junit.After <methods>; }
-keep class london.aipartner.echo.SemanticEmbedReleaseSmokeTest { *; }
