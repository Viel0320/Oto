# Add project-specific ProGuard rules here.
# By default, the rules in this file are appended to the default ProGuard rules
# found in the Android SDK.

# Core Attribute Retention (Preserve metadata needed by generated adapters and framework reflection)
# These attributes keep annotations, generic signatures, and enclosing method data available after shrinking.
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Room
-keepclassmembers class * {
    @androidx.room.RawQuery *;
}
-keep class androidx.room.RoomDatabase {
    protected <methods>;
}

# Media3 / ExoPlayer
# Media3 Consumer Rules (Avoid broad manual keep or dontwarn rules for Media3 internals)
# Media3 ships consumer rules, so release builds should surface missing optional classes instead of hiding whole-package warnings.

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherLoader {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Coil Consumer Rules (Avoid broad manual keep or dontwarn rules for image loading internals)
# Coil ships consumer rules, so this app rule file should not mask package-wide image loading warnings.

# Runnable Inline Cache Workaround (Keep only run method signatures for affected generated paths)
# This narrow rule avoids broader class retention while preventing missing inline-cache warnings.
-keepclassmembers class * implements java.lang.Runnable {
    public void run();
}

# Optional Annotation Warnings (Suppress compile-only helper annotations that are absent at runtime)
# These packages are used by dependencies for static analysis and are not required in the APK.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# Release Log Stripping (Remove verbose, debug, and info logs from release artifacts)
# Warning and error diagnostics remain available for release triage and must route through SecureLog to scrub paths, URLs, and exception text.
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
