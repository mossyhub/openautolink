# ProGuard rules for OpenAutoLink
# Keep kotlinx.serialization
# Covers all @Serializable classes including UpdateManifest, ControlMessage, etc.
# @SerialName values are baked into generated serializer code — no reflection needed.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.openautolink.app.**$$serializer { *; }
-keepclassmembers class com.openautolink.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.openautolink.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
