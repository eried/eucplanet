# Keeping iOS in sync with Android

The iPhone app is not a port. It runs the same Kotlin code as the phone app:
everything that can be shared lives in the `:shared` module, and both apps
consume it. A change to shared code reaches iOS the next time the iOS app is
built, with no extra work.

See `docs/IOS_MIGRATION_PLAN.md` for how the code got here and
`docs/IOS_PARITY.md` for what each screen and feature does on iOS today.

## Where code lives

| Source set | Compiled for | What goes there |
|---|---|---|
| `shared/src/commonMain` | Android and iOS | Everything that does not touch a platform API: protocols, decoders, models, settings, view models, Compose screens. |
| `shared/src/androidMain` | Android only | The Android side of an `expect`, or an interface implementation that uses the Android SDK. |
| `shared/src/iosMain` | iOS only | The iOS side of an `expect`, or an interface implementation that uses an Apple framework (CoreBluetooth, CoreLocation, AVFoundation...). |
| `shared/src/androidUnitTest` | JVM | The JUnit tests that moved with the code. They run in `./gradlew test` like before. |
| `shared/src/commonTest` | JVM and iOS simulator | Tests that should also run on iOS (`:shared:iosSimulatorArm64Test`, Mac only). |
| `app/` | Android only | Android-only features (Android Auto, watches, HUD, widgets, Flic), services, `Application`, manifest. |
| `iosApp/` | iOS only | The Xcode project: a small UIKit host that shows the shared Compose UI. |

## The everyday case: changing a shared feature

Edit the file in `shared/src/commonMain`, build and test Android as usual.
Nothing else is needed. The iOS app picks the change up on its next build.

## When iOS needs its own side

Only when the change touches a platform API. Then:

1. Put the platform call behind a small interface (preferred, injected through
   Koin) or an `expect` declaration in commonMain.
2. Write the Android side in `shared/src/androidMain` (or in `app/` when it is
   wired from there).
3. Write the iOS side in `shared/src/iosMain`. If you can't write it now, add a
   stub that does nothing and mark the feature "not yet" in
   `docs/IOS_PARITY.md`. Never leave the iOS build broken.

Seams that need both sides on every change:

| Seam | Common | Android | iOS |
|---|---|---|---|
| Wheel link (BLE) | `ble/WheelLink.kt` | `app/.../ble/BleConnectionManager.kt` | `iosMain/.../ble/IosWheelLink.kt` |
| Log, clock, charsets, CRC, format, local time | `platform/*.kt` | `platform/*.android.kt` | `platform/*.ios.kt` |
| Object graph | `di/SharedKoin.kt` (Koin) | `app/.../di/SharedModule.kt` hands the Koin singletons to Hilt | `MainViewController.kt` reads Koin |

The table grows as packages move; keep it current.

Labels that still come from Android string resources stay in `:app` as
extensions on the shared type, so call sites read the same:
`Units.speedUnit(context, unit)` (`util/UnitsAndroid.kt`) and the widget
registries' `pickerLabel` (`data/model/WidgetSlotLabels.kt`). They move to
Compose resources with the strings.

## Using JVM-style APIs in common code

Shared code keeps the same call sites the Android code used. The facades in
`com.eried.eucplanet.platform` shadow the JVM names, so moving a file usually
means only changing imports:

| Instead of | Import |
|---|---|
| `android.util.Log` | `com.eried.eucplanet.platform.Log` |
| `System.currentTimeMillis()`, `System.nanoTime()`, `System.arraycopy` | `com.eried.eucplanet.platform.System` |
| `Charsets.US_ASCII`, `String(bytes, cs)`, `toByteArray(cs)` | `com.eried.eucplanet.platform.Charsets` (and `String`, `toByteArray`) |
| `"%.1f".format(x)`, `String.format(...)`, `Locale.US` | `com.eried.eucplanet.platform.format`, `com.eried.eucplanet.platform.Locale` |
| `java.util.zip.CRC32` | `com.eried.eucplanet.platform.crc32` |
| `java.util.Calendar`, `SimpleDateFormat` | `com.eried.eucplanet.platform.localTimeParts` |
| `java.util.Random` | `kotlin.random.Random` |
| `BluetoothGattCharacteristic.WRITE_TYPE_*`, `PROPERTY_WRITE*` | `com.eried.eucplanet.ble.GattCharacteristic` |
| `java.io.ByteArrayOutputStream` | `com.eried.eucplanet.platform.ByteArrayOutputStream` |
| `@Volatile` (kotlin.jvm) | `kotlin.concurrent.Volatile` |
| `synchronized(lock)`, `@Synchronized` | `kotlinx.atomicfu.locks.synchronized` with a `SynchronizedObject` |
| `java.util.UUID` | `kotlin.uuid.Uuid` (convert with `toJavaUuid()` at the Android BLE boundary) |
| `org.json` | `kotlinx.serialization` |
| `R.string.x`, `stringResource(R.string.x)` | Compose resources `Res.string.x` |
| `javax.inject` / Hilt | a Koin module |

`String.format` on iOS is a clone of the JVM formatter (`platform/JavaFormat.kt`).
`JavaFormatTest` compares it with `java.lang.String.format` on the JVM, and
`JavaFormatCommonTest` runs 1,950 JVM-printed cases on the iOS simulator.

## The check: Android-only APIs in commonMain

`CommonMainGuardTest` (in `shared/src/androidUnitTest`) runs in
`./gradlew test` on every host, including Windows and the Linux CI where iOS
can't compile. It fails when a file in `shared/src/commonMain`:

- imports `android.*`, `java.*`, `javax.*`, `dagger.*`, `okhttp3.*`,
  `org.json.*`, Google Play services, `androidx.activity/core/hilt/work`,
  `LocalContext`, `AndroidView`, Android resources (`R`,
  `androidx.compose.ui.res`) or `kotlin.jvm.Volatile`;
- calls `System.` (or `System::`), `Charsets.`, `Log.x(`, `.format(`, `@Volatile` or
  `synchronized(` without importing the facade (these compile on the JVM
  without an import, so they would slip through);
- uses a fully qualified `java.`/`javax.`/`android.` name, `R.string...`,
  `Thread` or `@Synchronized`.

The failure message names the file, the line and the fix. On a Mac, the iOS
compile is the final word:

```sh
sh gradlew :shared:compileKotlinIosSimulatorArm64 :shared:iosSimulatorArm64Test
```

## Building iOS

Mac only. `iosApp/iosApp.xcodeproj` runs
`:shared:embedAndSignAppleFrameworkForXcode` as its first build phase, so
opening it in Xcode and pressing Run is enough. From a terminal:

```sh
sh iosApp/run-simulator.sh     # build, install and launch on a simulator
SIMCTL_CHILD_EUC_CONNECT=VIRTUAL:MASTER xcrun simctl launch booted com.eried.eucplanet.ios
                               # relaunch connected to a virtual wheel
sh iosApp/deploy-device.sh     # build, sign and install on a cabled iPhone
```

The device script uses the free Apple ID signing set up in Xcode
(Settings > Accounts). Free installs expire after 7 days; run it again.
