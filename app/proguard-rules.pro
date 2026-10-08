# Shrinking is on (dead-code removal, the size win), obfuscation is off.
# Testers send us raw Service Mode diagnostic dumps and crash traces; keeping
# class/method names readable means those stay useful without a mapping file.
-dontobfuscate

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
