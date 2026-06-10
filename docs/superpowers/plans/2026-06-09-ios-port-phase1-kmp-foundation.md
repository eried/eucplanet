# iOS Port — Phase 1: KMP Foundation & Shared Byte Primitives — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introduce a `:shared` Kotlin Multiplatform module wired into the existing Android app with zero behavior change, and move the first pure-Kotlin byte primitives (`ByteUtils`, a portable `Crc32`) into shared code with tests — proving KMP integrates with the shipping Android build before any iOS work.

**Architecture:** Strangler-fig. `:shared` is additive; `:app` keeps compiling and shipping at every commit. Moved files keep their exact package (`com.eried.eucplanet.util`), so `:app` import statements are unchanged — the class simply now lives in the shared module's `commonMain`. iOS targets are declared only on macOS (host-gated) so Windows builds the Android target cleanly; iOS compilation is verified later on the Mac.

**Tech Stack:** Kotlin 2.0.21, Kotlin Multiplatform plugin, Android Gradle Plugin 8.7.3 (frozen), `kotlin("test")`. No Compose, Koin, or iOS code in this phase. Verification on Windows via `./gradlew :app:assembleDebug` (Android-green) and `./gradlew :shared:testDebugUnitTest` (shared tests). Deployment targets `iosArm64` + `iosSimulatorArm64` are declared but only built on the Mac.

**Verification environment:** Windows dev box (this repo, JDK 17 + Android SDK present, `local.properties` valid). The full baseline `:app:assembleDebug` is already green.

---

## File Structure

| File | Responsibility |
|---|---|
| `settings.gradle.kts` | Register `:shared` in the build (modify) |
| `gradle/libs.versions.toml` | Add the `kotlin-multiplatform` plugin alias (modify) |
| `build.gradle.kts` (root) | Declare the KMP plugin `apply false` (modify) |
| `shared/build.gradle.kts` | KMP module config: androidTarget + host-gated iOS targets, test deps (create) |
| `shared/src/commonMain/kotlin/com/eried/eucplanet/util/ByteUtils.kt` | Shared little/big-endian byte readers (moved from `:app`) |
| `shared/src/commonMain/kotlin/com/eried/eucplanet/util/Crc32.kt` | Pure-Kotlin CRC-32, byte-identical to `java.util.zip.CRC32` (create) |
| `shared/src/commonTest/kotlin/com/eried/eucplanet/util/ByteUtilsTest.kt` | Tests for the byte readers (create) |
| `shared/src/commonTest/kotlin/com/eried/eucplanet/util/Crc32Test.kt` | CRC-32 known-vector tests (create) |
| `app/build.gradle.kts` | Add `implementation(project(":shared"))` (modify) |
| `app/src/main/java/com/eried/eucplanet/util/ByteUtils.kt` | **Deleted** (moved to `:shared`) |

---

## Task 1: Create the `:shared` module and wire it into `:app` (no-op)

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root)
- Create: `shared/build.gradle.kts`
- Create: `shared/src/commonMain/kotlin/com/eried/eucplanet/Placeholder.kt`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Add the KMP plugin alias to the version catalog**

In `gradle/libs.versions.toml`, under `[plugins]`, add (the `kotlin` version `2.0.21` already exists):

```toml
kotlin-multiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
```

- [ ] **Step 2: Declare the plugin `apply false` in the root build**

In `build.gradle.kts` (root), inside the `plugins { ... }` block, add a line alongside the existing `apply false` declarations:

```kotlin
alias(libs.plugins.kotlin.multiplatform) apply false
```

- [ ] **Step 3: Register the module**

In `settings.gradle.kts`, after `include(":app")`, add:

```kotlin
include(":shared")
```

- [ ] **Step 4: Create the shared module build file**

Create `shared/build.gradle.kts`:

```kotlin
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.library)
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iOS targets only build on macOS. On Windows/Linux they are skipped so the
    // Android-green loop stays clean; iOS compilation is verified on the Mac.
    if (HostManager.hostIsMac) {
        iosArm64()
        iosSimulatorArm64()
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "com.eried.eucplanet.shared"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 5: Add a placeholder source so the module compiles**

Create `shared/src/commonMain/kotlin/com/eried/eucplanet/Placeholder.kt`:

```kotlin
package com.eried.eucplanet

internal const val SHARED_MODULE_PLACEHOLDER = "shared"
```

- [ ] **Step 6: Make `:app` depend on `:shared`**

In `app/build.gradle.kts`, inside `dependencies { ... }`, add (near the top of the block, after the `hud-protocol` line):

```kotlin
implementation(project(":shared"))
```

- [ ] **Step 7: Verify the shared module configures and compiles (Android target)**

Run: `./gradlew :shared:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (First run downloads the Kotlin Multiplatform Android components.)

- [ ] **Step 8: Verify the Android app still builds green with the dependency wired in**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. The APK is unchanged in behavior (no shared code is used yet).

- [ ] **Step 9: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml shared/ app/build.gradle.kts
git commit -m "build: add :shared KMP module wired into :app (no-op)"
```

---

## Task 2: Move `ByteUtils` into `commonMain` (fix the one non-portable line)

`ByteUtils` is pure Kotlin except `toHexString()`, which uses the JVM-only `String.format`. Replace it with a manual hex conversion so it compiles in `commonMain`. The package stays `com.eried.eucplanet.util`, so no `:app` imports change.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eried/eucplanet/util/ByteUtils.kt`
- Create: `shared/src/commonTest/kotlin/com/eried/eucplanet/util/ByteUtilsTest.kt`
- Delete: `app/src/main/java/com/eried/eucplanet/util/ByteUtils.kt`

- [ ] **Step 1: Write the shared `ByteUtils` test first**

Create `shared/src/commonTest/kotlin/com/eried/eucplanet/util/ByteUtilsTest.kt`:

```kotlin
package com.eried.eucplanet.util

import com.eried.eucplanet.util.ByteUtils.toHexString
import kotlin.test.Test
import kotlin.test.assertEquals

class ByteUtilsTest {
    @Test fun uint16LE() {
        assertEquals(0x3412, ByteUtils.getUint16LE(byteArrayOf(0x12, 0x34), 0))
    }

    @Test fun int16BE_negative() {
        // 0xFFFE big-endian = -2
        assertEquals(-2, ByteUtils.getInt16BE(byteArrayOf(0xFF.toByte(), 0xFE.toByte()), 0))
    }

    @Test fun wordSwappedUint32_veteran() {
        // bytes b0 b1 b2 b3 -> (b2<<24)|(b3<<16)|(b0<<8)|b1
        val v = ByteUtils.getWordSwappedUint32(byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D), 0)
        assertEquals(0x0C0D_0A0BL, v)
    }

    @Test fun hexStringIsLowercaseSpaceSeparated() {
        assertEquals("00 0f a0 ff", byteArrayOf(0x00, 0x0F, 0xA0.toByte(), 0xFF.toByte()).toHexString())
    }

    @Test fun parseTemperatureOffset() {
        assertEquals(0f, ByteUtils.parseTemperature(176.toByte()))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails (class not yet in shared)**

Run: `./gradlew :shared:testDebugUnitTest --console=plain`
Expected: FAIL — compilation error, `ByteUtils` unresolved in `:shared`.

- [ ] **Step 3: Create the shared `ByteUtils` with the portable hex fix**

Create `shared/src/commonMain/kotlin/com/eried/eucplanet/util/ByteUtils.kt` with the exact contents of the current `app` copy, except replace the `toHexString()` body. Full file:

```kotlin
package com.eried.eucplanet.util

object ByteUtils {

    fun getUint16LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8)
    }

    fun getInt16LE(data: ByteArray, offset: Int): Int {
        val value = getUint16LE(data, offset)
        return if (value >= 0x8000) value - 0x10000 else value
    }

    fun getUint32LE(data: ByteArray, offset: Int): Long {
        return (data[offset].toLong() and 0xFF) or
                ((data[offset + 1].toLong() and 0xFF) shl 8) or
                ((data[offset + 2].toLong() and 0xFF) shl 16) or
                ((data[offset + 3].toLong() and 0xFF) shl 24)
    }

    /** Signed 32-bit LE read used by InMotion V1 fast-info pitch / speed / current. */
    fun getInt32LE(data: ByteArray, offset: Int): Int {
        return (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)
    }

    fun getUint16BE(data: ByteArray, offset: Int): Int {
        return ((data[offset].toInt() and 0xFF) shl 8) or
                (data[offset + 1].toInt() and 0xFF)
    }

    fun getInt16BE(data: ByteArray, offset: Int): Int {
        val value = getUint16BE(data, offset)
        return if (value >= 0x8000) value - 0x10000 else value
    }

    fun getUint32BE(data: ByteArray, offset: Int): Long {
        return ((data[offset].toLong() and 0xFF) shl 24) or
                ((data[offset + 1].toLong() and 0xFF) shl 16) or
                ((data[offset + 2].toLong() and 0xFF) shl 8) or
                (data[offset + 3].toLong() and 0xFF)
    }

    /**
     * Veteran's distance fields are two big-endian u16s with the high word
     * stored second, a serializer quirk specific to the LeaperKim firmware.
     * Given bytes b0 b1 b2 b3 at the offset, the value is
     *   (b2 << 24) | (b3 << 16) | (b0 << 8) | b1.
     * Spec: docs/protocols/veteran.md section 4 ("Word-swapped 32-bit fields").
     */
    fun getWordSwappedUint32(data: ByteArray, offset: Int): Long {
        return ((data[offset + 2].toLong() and 0xFF) shl 24) or
                ((data[offset + 3].toLong() and 0xFF) shl 16) or
                ((data[offset].toLong() and 0xFF) shl 8) or
                (data[offset + 1].toLong() and 0xFF)
    }

    fun putUint16LE(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte()
        )
    }

    private const val HEX = "0123456789abcdef"

    fun ByteArray.toHexString(): String =
        joinToString(" ") {
            val v = it.toInt() and 0xFF
            "${HEX[v ushr 4]}${HEX[v and 0xF]}"
        }

    fun parseTemperature(raw: Byte): Float {
        return (raw.toInt() and 0xFF) - 176f
    }
}
```

- [ ] **Step 4: Delete the `:app` copy**

```bash
git rm app/src/main/java/com/eried/eucplanet/util/ByteUtils.kt
```

- [ ] **Step 5: Run the shared tests — expect PASS**

Run: `./gradlew :shared:testDebugUnitTest --console=plain`
Expected: `BUILD SUCCESSFUL`, 5 tests pass.

- [ ] **Step 6: Run the Android app build — expect still green (imports unchanged because package is identical)**

Run: `./gradlew :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`. (If any `:app` file fails to resolve `ByteUtils`, it means a stale duplicate — confirm the `:app` copy was deleted; do NOT change `:app` import lines.)

- [ ] **Step 7: Commit**

```bash
git add shared/ ; git commit -m "refactor: move ByteUtils to :shared commonMain (portable hex)"
```

---

## Task 3: Add a portable `Crc32` (byte-identical to `java.util.zip.CRC32`)

Veteran frame validation uses `java.util.zip.CRC32` (JVM-only). Add a pure-Kotlin CRC-32 producing identical values, so the Veteran parser can move to `commonMain` in Phase 2. Validate against the standard check vector before touching any parser.

**Files:**
- Create: `shared/src/commonMain/kotlin/com/eried/eucplanet/util/Crc32.kt`
- Create: `shared/src/commonTest/kotlin/com/eried/eucplanet/util/Crc32Test.kt`

- [ ] **Step 1: Write the CRC-32 known-vector test first**

Create `shared/src/commonTest/kotlin/com/eried/eucplanet/util/Crc32Test.kt`:

```kotlin
package com.eried.eucplanet.util

import kotlin.test.Test
import kotlin.test.assertEquals

class Crc32Test {
    @Test fun standardCheckVector() {
        // CRC-32/ISO-HDLC check value for ASCII "123456789" is 0xCBF43926.
        val data = "123456789".encodeToByteArray()
        assertEquals(0xCBF43926L, Crc32.compute(data))
    }

    @Test fun emptyIsZero() {
        assertEquals(0L, Crc32.compute(ByteArray(0)))
    }

    @Test fun respectsOffsetAndLength() {
        // CRC over the middle "123456789" of a padded buffer must match the vector.
        val padded = byteArrayOf(0x00, 0x00) + "123456789".encodeToByteArray() + byteArrayOf(0x00)
        assertEquals(0xCBF43926L, Crc32.compute(padded, offset = 2, length = 9))
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :shared:testDebugUnitTest --console=plain --tests "com.eried.eucplanet.util.Crc32Test"`
Expected: FAIL — `Crc32` unresolved.

- [ ] **Step 3: Implement the portable CRC-32**

Create `shared/src/commonMain/kotlin/com/eried/eucplanet/util/Crc32.kt`:

```kotlin
package com.eried.eucplanet.util

/**
 * Pure-Kotlin CRC-32 (IEEE 802.3 / zlib polynomial 0xEDB88320), byte-identical
 * to java.util.zip.CRC32 and CommonCrypto-free, so frame validation (e.g. the
 * Veteran long-frame trailer) runs unchanged on Android and iOS.
 */
object Crc32 {
    private val TABLE: IntArray = IntArray(256) { n ->
        var c = n
        repeat(8) {
            c = if (c and 1 != 0) 0xEDB88320.toInt() xor (c ushr 1) else c ushr 1
        }
        c
    }

    fun compute(data: ByteArray, offset: Int = 0, length: Int = data.size): Long {
        var crc = 0.inv() // 0xFFFFFFFF
        for (i in offset until offset + length) {
            crc = TABLE[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        }
        return crc.inv().toLong() and 0xFFFFFFFFL
    }
}
```

- [ ] **Step 4: Run the test — expect PASS**

Run: `./gradlew :shared:testDebugUnitTest --console=plain --tests "com.eried.eucplanet.util.Crc32Test"`
Expected: `BUILD SUCCESSFUL`, 3 tests pass.

- [ ] **Step 5: Run the full shared test suite + Android build to confirm nothing regressed**

Run: `./gradlew :shared:testDebugUnitTest :app:assembleDebug --console=plain`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add shared/ ; git commit -m "feat(shared): portable Crc32 matching java.util.zip.CRC32"
```

---

## Task 4 (CHECKPOINT — runs on the Mac once Xcode is installed): verify the shared primitives compile and test on iOS

This task is **blocked until Xcode is installed on the Mac mini** and the repo is cloned/synced there. It proves the host-gated iOS targets and `commonTest` actually build and pass under Kotlin/Native — the first real cross-platform signal.

**Files:** none (verification only).

- [ ] **Step 1: Sync this branch to the Mac**

Sync to the Mac **without pushing to GitHub** (project directive — the branch stays private until it's hardware-tested): use the local `tar`-over-SSH transfer already used to populate `~/eucplanet` (re-run it to refresh), or a `git bundle` scp'd over. Do **NOT** `git push` this branch.

- [ ] **Step 2: Confirm iOS targets are now present (macOS host)**

Run on the Mac: `./gradlew :shared:tasks --console=plain | grep -i iosSimulatorArm64Test`
Expected: the `iosSimulatorArm64Test` task is listed (it is absent on Windows by design).

- [ ] **Step 3: Compile the shared module for the iOS simulator**

Run on the Mac: `./gradlew :shared:compileKotlinIosSimulatorArm64 --console=plain`
Expected: `BUILD SUCCESSFUL` (first run downloads the Kotlin/Native iOS toolchain).

- [ ] **Step 4: Run the shared tests on the iOS simulator**

Run on the Mac: `./gradlew :shared:iosSimulatorArm64Test --console=plain`
Expected: `BUILD SUCCESSFUL` — the same `ByteUtilsTest` and `Crc32Test` pass on Kotlin/Native, confirming the primitives are truly portable.

- [ ] **Step 5: Commit (no code change; this is a verification gate)**

No commit needed. If iOS compilation surfaces an issue (e.g., an unexpected JVM API), fix it in `commonMain` and re-run Step 4 before proceeding to Phase 2.

---

## Phase 2 preview (next plan, not in scope here)

With the foundation proven, Phase 2 moves the first **wheel brand** end-to-end into `commonMain`: add the `Logger` expect/actual (android = `android.util.Log`), a common growable byte buffer (replacing `java.io.ByteArrayOutputStream`), and a String-based BLE-UUID strategy (replacing `java.util.UUID`); then relocate the **Veteran** family (`VeteranParser`/`VeteranCommands`/`VeteranModel`), swap its CRC32 for `Crc32`, and run the existing `VeteranHornTest`/`VeteranLightTest`/`VeteranOryxTest` in `commonTest`. Remaining brands (Begode, KingSong, InMotion V1/V2, Ninebot) then replicate the identical pattern, one commit each, Android-green throughout.

---

## Self-Review

- **Spec coverage:** Implements spec §9 build-sequence steps 1 (create `:shared`, wire to `:app`, no-op) and the leading edge of step 2 (move pure primitives), plus the iOS-target proof from step 0's toolchain half. Compose/dual-Compose (spec §4 caveat) is deliberately excluded — no Compose plugin in this phase.
- **Placeholders:** none — every step has exact files, full code, exact commands, and expected output.
- **Type consistency:** `Crc32.compute(data, offset, length)` signature is used identically in `Crc32Test` and referenced in the Phase 2 preview. `ByteUtils.toHexString()` remains an extension on `ByteArray` as in the original. Package `com.eried.eucplanet.util` is preserved for moved files so `:app` imports are stable.
