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

# Hilt
-keep class dagger.hilt.** { *; }

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

# Android Auto. Every template crosses to the host through the Car App
# Library's own Bundler, which rebuilds each model by reflection and needs a
# no-arg constructor to do it. The library keeps those itself only for classes
# it annotated @KeepFields, and MapWithContentTemplate (car-app 1.4.0) carries
# only @RequiresCarApi, so R8 drops its constructor and the car screen dies at
# serialization with "Class to deserialize is missing a no args constructor".
# Release-only: debug builds never run R8, which is why the emulator always
# passed while every tester saw "EUC Planet has encountered an unexpected
# error". Keep the constructor on every car-app model, not just the one that
# bit us, so a library upgrade cannot reintroduce this.
-keepclassmembers class androidx.car.app.** {
    <init>();
}

# The Bundler also writes each model's class and field names into the bundle
# and the host rebuilds them by those names, so with obfuscation on the
# car-app classes keep their names too (they still shrink).
-keepnames class androidx.car.app.** { *; }
