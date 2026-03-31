# ML Kit - preserve all ML Kit classes
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.mlkit.**

# Keep TextParser and domain logic
-keep class com.coordextractor.domain.** { *; }
-keep class com.coordextractor.data.** { *; }

# Kotlin Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Keep Enum values
-keepclassmembers enum * { public static **[] values(); public static ** valueOf(java.lang.String); }

# General Android rules
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
