# Shrinking is on (dead-code removal — the size win), obfuscation is off.
# Testers send us raw Service Mode diagnostic dumps and crash traces; keeping
# class/method names readable means those stay useful without a mapping file.
-dontobfuscate

# Room — the generated implementation references the database subclass and
# every @Entity by name.
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *

# Hilt
-keep class dagger.hilt.** { *; }

# Flic 2 SDK — third-party AAR from jitpack; the SDK invokes our callbacks
# reflectively and ships no consumer rules of its own.
-keep class io.flic.** { *; }
-dontwarn io.flic.**

# Enums resolved from a stored string via valueOf() (AlarmMetric, FlicAction,
# MetricType, ExternalGpsSource). values()/valueOf() must survive shrinking.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
