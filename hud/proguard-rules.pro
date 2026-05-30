# kotlinx.serialization keeps reflection-driven serializers happy without
# regenerating proguard configuration each time a wire-format field changes.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.eried.eucplanet.hud.**$$serializer { *; }
-keepclassmembers class com.eried.eucplanet.hud.** {
    *** Companion;
}
-keepclasseswithmembers class com.eried.eucplanet.hud.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp / Okio internals.
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# JmDNS uses java.beans.PropertyChangeSupport which Android doesn't bundle.
-dontwarn java.beans.**
-dontwarn javax.jmdns.**

# Ktor's IntellijIdeaDebugDetector touches the JVM management API that
# Android doesn't ship. The class is dead-code on device so silence the
# missing-class warnings rather than carrying a JVM-only shim.
-dontwarn java.lang.management.**
-dontwarn io.ktor.util.debug.**
