# ProGuard rules for OpenAutoLink
# Keep kotlinx.serialization
# Covers all @Serializable classes including ControlMessage, etc.
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

# Protobuf-lite — keep generated message classes from being stripped
-keep class com.openautolink.app.proto.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }

# JNI — native code accesses these classes by field/method name via GetFieldID/GetMethodID.
# R8 must not rename or strip them.
-keep class com.openautolink.app.transport.aasdk.AasdkSdrConfig { *; }
-keep class com.openautolink.app.transport.aasdk.AasdkTransportPipe { *; }
-keep class com.openautolink.app.transport.aasdk.AasdkSessionCallback { *; }
-keep class * implements com.openautolink.app.transport.aasdk.AasdkSessionCallback { *; }
-keep class com.openautolink.app.transport.aasdk.AasdkNative { *; }

# DiagnosticLog — native code calls nativeLog() via FindClass + CallStaticVoidMethod
-keep class com.openautolink.app.diagnostics.DiagnosticLog { *; }

# CarPlay Recon — hidden investigation screen reached via long-press.
# R8 strips it because the entry point is a Compose lambda not visible to static analysis.
-keep class com.openautolink.app.ui.diagnostics.carplay.** { *; }

# Conscrypt SSL provider — suppress warnings for optional platform classes
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl
