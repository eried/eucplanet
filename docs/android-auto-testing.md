# Testing the Android Auto car screen

Read this before shipping anything that touches `app/src/main/java/com/eried/eucplanet/car/`.

## The rule

**A debug build passing in the emulator proves nothing about the car screen.**

R8 is the only difference between the build that renders perfectly on your desk
and the one testers install, and it is enough to make the car screen refuse to
open. It has already happened once, in the build shipped on 2026-10-08:

```
androidx.car.app.serialization.Bundler$TracedBundlerException:
    Class to deserialize is missing a no args constructor
Caused by: java.lang.NoSuchMethodException:
    androidx.car.app.navigation.model.MapWithContentTemplate.<init> []
```

Every template crosses to the host through the Car App Library's own `Bundler`,
which rebuilds each model by reflection and so needs a no-arg constructor. The
library keeps those itself only for classes it annotated `@KeepFields`.
`MapWithContentTemplate` (car-app 1.4.0) carries only `@RequiresCarApi`, so R8
dropped its constructor. Testers saw "EUC Planet has encountered an unexpected
error" and could not open the app at all. Debug builds never run R8, so the
emulator showed a perfect car screen throughout.

The keep rule in `app/proguard-rules.pro` covers every car-app model, so a
library upgrade cannot reintroduce that exact fault. It cannot protect you from
the next reflection-shaped one. Run the release build.

## Running a release build on a real car host

The AAOS emulator carries Google's real templates host
(`com.google.android.apps.automotive.templates.host`), which does real API level
negotiation and real template serialization. That is enough to catch this class
of bug.

```sh
# 1. Build a release APK the emulator can host (adds x86_64, the automotive
#    host and its CarAppActivity; none of it reaches a shipping build).
./gradlew :app:assembleRelease -PaaosTest

# 2. Start the AAOS emulator (AVD: EucCar_AAOS_34) and install.
adb -s emulator-5554 install -r app/build/outputs/apk/release/phone-release.apk

# 3. Launch the car screen.
adb -s emulator-5554 shell am start \
    -n com.eried.eucplanet/androidx.car.app.activity.CarAppActivity

# 4. Look at it. The screenshot needs the display id.
adb -s emulator-5554 exec-out screencap -p -d 4619827259835644672 > car.png
```

Check `pkgFlags` has no `DEBUGGABLE` before believing the result:

```sh
adb -s emulator-5554 shell dumpsys package com.eried.eucplanet | grep pkgFlags
```

A debuggable build takes `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR`, skips R8 and
gets its car API level forced to the host maximum. All three hide real faults.

### What to look for

- The car screen renders at all. A blank screen is a failure, and the reason is
  usually drawn on the emulator display as a stack trace, not in logcat.
- `grep -i TracedBundlerException` in logcat. Zero is the only acceptable count.
- The negotiated level: `CarApp.H.Ser: ... chosen api level: N`. Our code takes a
  different template branch below 7, and that branch needs its own look.

## What the emulator still cannot tell you

- Whether a POI app splits with Google Maps. AAOS has no Coolwalk.
- Anything about the MotoEye's 800x480 panel.
- Android Auto's own host, which is a different build from the AAOS one.

For those, a physical phone running DHU is the only option.

## DHU, when you need Android Auto itself

`SDK/extras/google/auto/desktop-head-unit.exe`, already installed.

The phone must be new enough. Android 10 is refused outright now ("Phone will no
longer be supported"), and the first-run wizard aborts without ever projecting.
Android 13 works.

```sh
# On the phone: Settings > Apps > Android Auto > the gear icon, scroll to the
# bottom, tap Version ten times, allow development settings. Then the overflow
# menu gains Developer settings (turn on Unknown sources) and Start head unit
# server.
adb -s <serial> forward tcp:5277 tcp:5277
cd "$ANDROID_HOME/extras/google/auto" && ./desktop-head-unit.exe --adb=5277
```

DHU reads commands on stdin and quits the moment stdin closes, so it needs a
live one; `tail -f cmdfile | ./desktop-head-unit.exe --adb=5277` works, and you
append commands to `cmdfile`. `help` lists them, `screenshot <path>` wants a
Windows path, and `tap x y` drives the car screen.

MIUI blocks `adb install` until Developer options has **Install via USB** on.
`pm install` from the device shell hits the same restriction.
