# Add project-specific ProGuard rules here.
# By default, the rules in this file are appended to the default ProGuard rules
# found in the Android SDK.

# 基础混淆属性保留
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod

# Room
-keepclassmembers class * {
    @androidx.room.RawQuery *;
}
-keep class androidx.room.RoomDatabase {
    protected <methods>;
}

# Media3 / ExoPlayer
# 现代 Media3 库自带混淆规则，通常不需要手动添加宽泛的 keep。
-dontwarn androidx.media3.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherLoader {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Coil
-dontwarn coil.**

# 解决 "Missing inline cache for void run()"
# 使用 keepclassmembers 更加精确，仅保留方法签名，避免触发 Overly broad 警告
-keepclassmembers class * implements java.lang.Runnable {
    public void run();
}

# 忽略常见的三方库辅助类警告
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# 根据代码审查 H-03 规定，暂不进行日志信息混淆，但由 R8 在编译 Release 发行版时彻底物理剥离 android.util.Log 的 Log.v/d/i 级别的日志，防止敏感路径等用户隐私信息泄露。
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
