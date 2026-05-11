# Add project-specific ProGuard rules here.
# By default, the rules in this file are appended to the default ProGuard rules
# found in the Android SDK.
#
# If you keep dependencies in your build.gradle file, the ProGuard configuration
# is usually provided by the library authors.

# Add any specific rules for your libraries here (e.g., Room, Media3, Coil)
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.RawQuery *;
}
