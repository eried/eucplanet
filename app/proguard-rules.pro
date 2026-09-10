# Shrinking is on (dead-code removal, the size win), obfuscation is off.
# Testers send us raw Service Mode diagnostic dumps and crash traces; keeping
# class/method names readable means those stay useful without a mapping file.
-dontobfuscate

# No Room or Hilt rules here: room-runtime keeps the RoomDatabase subclass it
# loads by name and hilt-android keeps its entry points, both through the
# consumer rules inside their AARs, so a copy here only drifts.

# Flic 2 SDK: its consumer rules file is empty, and it calls our callbacks reflectively.
-keep class io.flic.** { *; }
-dontwarn io.flic.**

# Enums restored from a stored string (AlarmMetric, FlicAction, MetricType,
# ExternalGpsSource): values()/valueOf() must survive shrinking.
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

# JmDNS: a plain jar with no consumer rules that looks up DNS record classes reflectively.
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
