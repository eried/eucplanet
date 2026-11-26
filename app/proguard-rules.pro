# Shrinking and obfuscation are on; Play flags a bundle under 25 % obfuscated.
# Crash traces still resolve: Play deobfuscates with the mapping file the
# upload carries, and every GitHub release attaches
# phone-<version>-mapping.txt for traces from the APK (retrace, or Android
# Studio's Analyze Stack Trace). Line info is kept so they resolve to the line.
-keepattributes SourceFile,LineNumberTable

# Service Mode dumps WheelData by reflecting its field names
# (diagnostics/ServiceOverlay.kt reflectFields); keep them readable.
-keepclassmembernames class com.eried.eucplanet.data.model.WheelData {
    <fields>;
}

# Room, the generated implementation references the database subclass and
# every @Entity by name.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Flic 2 SDK, third-party AAR from jitpack; the SDK invokes our callbacks
# reflectively and ships no consumer rules of its own.
-keep class io.flic.** { *; }
-dontwarn io.flic.**

# Enums resolved from a stored string via valueOf() (AlarmMetric, FlicAction,
# MetricType, ExternalGpsSource). values()/valueOf() must survive shrinking.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Ktor (embedded HUD companion server) references java.lang.management for an
# IntelliJ debugger heuristic that doesn't exist on Android. Telling R8 not to
# warn lets the shrink complete; the code path is never exercised at runtime.
-dontwarn java.lang.management.**
-dontwarn io.ktor.**
-dontwarn kotlinx.coroutines.debug.**
# survivesAsStorageFull (util/StorageFull.kt) recognises a failure handed over
# by a coroutine by the name of the note the coroutine attaches to it.
-keepnames class kotlinx.coroutines.internal.DiagnosticCoroutineContextException

# JmDNS multicast discovery -- the library inspects classes reflectively for
# DNS record types.
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**

# Debug and verbose logging is stripped from release builds. Some of it sat
# on the telemetry path (a string format per frame for a logcat line nobody
# reads on a release install). Log.i / w / e stay: testers' crash traces and
# Service Mode captures lean on them.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}

