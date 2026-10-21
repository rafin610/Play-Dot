# Default ProGuard rules — minification is disabled for Phase 2 dev builds.
# Retrofit/kotlinx-serialization may need rules if minification is enabled later:
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class * { kotlinx.serialization.KSerializer serializer(...); }
