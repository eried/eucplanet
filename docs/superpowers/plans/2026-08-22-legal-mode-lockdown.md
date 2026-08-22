# Legal Mode Lockdown Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a manufacturer-code lock that pins EUC Planet to a fixed, stripped screen and stops every surface in the app from lifting the wheel's legal speed limits.

**Architecture:** Lockdown state lives in its own DataStore file, outside `AppSettings`, so arming can never touch or be undone by the rider's settings. A Hilt singleton `LegalLockdownController` owns it. Three gates enforce it: an allowlist in `FlicManager.executeAction()` (the single dispatch table every remote surface funnels through), hard refusals in `WheelRepository`, and a re-apply on wheel connect. A separate `LegalLockdownScreen` replaces the whole nav graph while armed.

**Tech Stack:** Kotlin, Jetpack Compose, Hilt, DataStore Preferences, JUnit 4 + Robolectric-free plain unit tests, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-22-legal-mode-lockdown-design.md`

## Global Constraints

Copied from `CLAUDE.md` and `CONVENTIONS.md`. Every task's requirements implicitly include these.

- **No em-dashes anywhere.** UI strings, code comments, commit and PR text. Use commas, " - ", or separate sentences.
- **Modern toast only.** `LocalSnackbar` in Compose, `AppNotifier.post()` in background code. Never `Toast.makeText`.
- **Colors come from the theme.** `MaterialTheme.appColors.*` only. Never hardcode `Color(...)`, never `MaterialTheme.colorScheme.*` in feature UI.
- **Read settings through `SettingsRepository`** (`get()` / `settings` Flow), never `SettingsStore` directly. Lockdown state is the documented exception: it has its own store and its own controller, and never passes through `AppSettings`.
- **Keep `AppSettings` under the 255-arg limit.** This feature adds no `AppSettings` fields at all.
- **Editor and confirmation dialogs set `dismissOnClickOutside = false`.**
- **Localize all user-facing text.** Every string in `app/src/main/res/values/strings.xml`, translated to all 22 locales under `app/src/main/res/values-*/strings.xml`. Rider terminology: wheel, not bike or car. Rider, not driver.
- **Verify builds** by grepping output for `BUILD SUCCESSFUL` / `BUILD FAILED`. Never pipe gradle to `tail` and mask the exit code.
- **Branch:** `feature/legal-mode-lockdown`, off `next-version`. **Do not push to GitHub.**
- Build command: `./gradlew :app:assembleDebug`
- Unit test command: `./gradlew :app:testPhoneDebugUnitTest` (confirm the exact variant task name with `./gradlew :app:tasks --all | grep -i unittest` on first use)

---

### Task 1: Lockdown state store, controller and backup exclusion

The state and the only writer of it. Nothing else in this plan works without it.

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/data/store/LegalLockdownStore.kt`
- Create: `app/src/main/java/com/eried/eucplanet/data/repository/LegalLockdownController.kt`
- Create: `app/src/main/res/xml/backup_rules.xml`
- Create: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/java/com/eried/eucplanet/di/AppModule.kt`
- Modify: `app/src/main/AndroidManifest.xml:77`
- Test: `app/src/test/java/com/eried/eucplanet/data/LegalLockdownCodeTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `LegalLockdownStore(context: Context)` with `val state: Flow<LockdownState>`, `suspend fun get(): LockdownState`, `suspend fun set(armed: Boolean, codeHash: String)`
  - `data class LockdownState(val armed: Boolean = false, val codeHash: String = "")`
  - `object LegalLockdownCode { fun hash(pin: String): String; fun isValidPin(pin: String): Boolean; fun matches(pin: String, hash: String): Boolean }`
  - `LegalLockdownController` (`@Singleton`) with `val armed: StateFlow<Boolean>`, `val configured: StateFlow<Boolean>`, `suspend fun arm(pin: String): Boolean`, `suspend fun tryDisarm(pin: String): Boolean`, `fun isArmed(): Boolean`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/eried/eucplanet/data/LegalLockdownCodeTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.LegalLockdownCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownCodeTest {

    @Test
    fun `hash is stable and not the plain pin`() {
        val h = LegalLockdownCode.hash("4821")
        assertEquals(h, LegalLockdownCode.hash("4821"))
        assertFalse(h.contains("4821"))
        assertEquals(32, h.length)
    }

    @Test
    fun `different pins hash differently`() {
        assertFalse(LegalLockdownCode.hash("4821") == LegalLockdownCode.hash("4822"))
    }

    @Test
    fun `matches accepts the right pin and rejects the wrong one`() {
        val h = LegalLockdownCode.hash("135790")
        assertTrue(LegalLockdownCode.matches("135790", h))
        assertFalse(LegalLockdownCode.matches("135791", h))
    }

    @Test
    fun `matches never accepts anything against a blank hash`() {
        assertFalse(LegalLockdownCode.matches("1234", ""))
        assertFalse(LegalLockdownCode.matches("", ""))
    }

    @Test
    fun `pin must be 4 to 8 digits`() {
        assertFalse(LegalLockdownCode.isValidPin("123"))
        assertTrue(LegalLockdownCode.isValidPin("1234"))
        assertTrue(LegalLockdownCode.isValidPin("12345678"))
        assertFalse(LegalLockdownCode.isValidPin("123456789"))
        assertFalse(LegalLockdownCode.isValidPin("12a4"))
        assertFalse(LegalLockdownCode.isValidPin(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownCodeTest*"`
Expected: FAIL, unresolved reference `LegalLockdownCode`.

- [ ] **Step 3: Write the store**

`app/src/main/java/com/eried/eucplanet/data/store/LegalLockdownStore.kt`:

```kotlin
package com.eried.eucplanet.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** Armed flag plus the MD5 of the manufacturer code. */
data class LockdownState(
    val armed: Boolean = false,
    val codeHash: String = ""
)

/**
 * Legal Mode Lockdown state, deliberately NOT an [com.eried.eucplanet.data.model.AppSettings]
 * field and deliberately not in `eucplanet_settings`.
 *
 * Everything in AppSettings flows through SettingsJson and SyncManager, and the
 * restore path overwrites the current values from the payload. A lockdown flag
 * living there would be cleared by restoring any older backup, which is a
 * one-tap bypass of the whole feature. Its own store makes that impossible by
 * construction instead of by remembering to strip a field in two places.
 *
 * The second reason is the promise the feature makes to the rider: arming and
 * disarming must not change a single setting. With the state out here, the arm
 * and disarm paths never call SettingsRepository.update() at all, so the promise
 * is structural rather than a thing we have to keep testing for.
 *
 * Excluded from Android auto-backup and device transfer in backup_rules.xml and
 * data_extraction_rules.xml, so the lock does not ride a cloud backup onto a new
 * phone and a reinstall is genuinely clean.
 */
class LegalLockdownStore(private val context: Context) {

    private val dataStore: DataStore<Preferences> get() = context.lockdownDataStore

    val state: Flow<LockdownState> = dataStore.data.map { prefs ->
        LockdownState(
            armed = prefs[KEY_ARMED] ?: false,
            codeHash = prefs[KEY_HASH].orEmpty()
        )
    }

    suspend fun get(): LockdownState = state.first()

    suspend fun set(armed: Boolean, codeHash: String) {
        dataStore.edit { prefs ->
            prefs[KEY_ARMED] = armed
            prefs[KEY_HASH] = codeHash
        }
    }

    private companion object {
        val KEY_ARMED = booleanPreferencesKey("armed")
        val KEY_HASH = stringPreferencesKey("codeHash")
    }
}

private val Context.lockdownDataStore: DataStore<Preferences>
        by preferencesDataStore(name = "legal_lockdown")
```

- [ ] **Step 4: Write the code helper and the controller**

`app/src/main/java/com/eried/eucplanet/data/repository/LegalLockdownController.kt`:

```kotlin
package com.eried.eucplanet.data.repository

import com.eried.eucplanet.data.store.LegalLockdownStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manufacturer code rules. MD5 is deliberate: the secret is a 4 to 8 digit PIN
 * checked on a phone that may be handling telemetry at 250 ms ticks, and the
 * threat model is a rider, not an offline cracker. What makes guessing
 * impractical is the cooldown on the unlock dialog, not the hash.
 */
object LegalLockdownCode {

    private val PIN = Regex("^[0-9]{4,8}$")

    fun isValidPin(pin: String): Boolean = PIN.matches(pin)

    fun hash(pin: String): String =
        MessageDigest.getInstance("MD5")
            .digest(pin.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Blank stored hash never matches, so an unconfigured lock cannot be opened. */
    fun matches(pin: String, storedHash: String): Boolean =
        storedHash.isNotEmpty() && hash(pin) == storedHash
}

/**
 * The single owner of Legal Mode Lockdown state. Every other part of the app
 * only reads [armed].
 *
 * Side effects of arming (finalising the trip, stopping the recorder, forcing
 * legal mode on) do NOT live here - they would make this a hub that depends on
 * half the app and cannot be constructed in a test. The caller in the settings
 * layer runs them, then calls [arm].
 */
@Singleton
class LegalLockdownController @Inject constructor(
    private val store: LegalLockdownStore,
    private val scope: CoroutineScope
) {
    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _configured = MutableStateFlow(false)
    val configured: StateFlow<Boolean> = _configured.asStateFlow()

    init {
        scope.launch {
            store.state.collect { s ->
                _armed.value = s.armed
                _configured.value = s.codeHash.isNotEmpty()
            }
        }
    }

    /**
     * Hot read for non-suspending callers on the telemetry path (the gates in
     * FlicManager, WheelRepository and WheelService). The StateFlow is seeded
     * from disk at process start, so this never blocks.
     */
    fun isArmed(): Boolean = _armed.value

    /** Returns false and changes nothing when [pin] is not 4 to 8 digits. */
    suspend fun arm(pin: String): Boolean {
        if (!LegalLockdownCode.isValidPin(pin)) return false
        store.set(armed = true, codeHash = LegalLockdownCode.hash(pin))
        return true
    }

    /** Returns true and unlocks only on a matching code. */
    suspend fun tryDisarm(pin: String): Boolean {
        val current = store.get()
        if (!LegalLockdownCode.matches(pin, current.codeHash)) return false
        // The hash is kept so re-arming later can reuse the same code if the
        // rider wants, and clearing it would let a blank hash unlock nothing.
        store.set(armed = false, codeHash = current.codeHash)
        return true
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownCodeTest*"`
Expected: PASS, 5 tests.

- [ ] **Step 6: Wire Hilt**

In `app/src/main/java/com/eried/eucplanet/di/AppModule.kt`, next to `provideSettingsStore`:

```kotlin
    @Provides
    @Singleton
    fun provideLegalLockdownStore(@ApplicationContext context: Context): LegalLockdownStore =
        LegalLockdownStore(context)
```

Add the import `com.eried.eucplanet.data.store.LegalLockdownStore`. `LegalLockdownController` is `@Inject constructor` so it needs no provider, but confirm an application-scoped `CoroutineScope` is already provided in this module. If it is not, add:

```kotlin
    @Provides
    @Singleton
    fun provideAppScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)
```

Search first: `grep -n "CoroutineScope" app/src/main/java/com/eried/eucplanet/di/AppModule.kt`. Reuse the existing one rather than adding a second application scope.

- [ ] **Step 7: Exclude the store from Android backup**

`app/src/main/res/xml/backup_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- Legal Mode Lockdown must not travel to another phone or come back on a
     restore. Everything else keeps the platform default of being backed up. -->
<full-backup-content>
    <exclude domain="file" path="datastore/legal_lockdown.preferences_pb" />
</full-backup-content>
```

`app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="datastore/legal_lockdown.preferences_pb" />
    </cloud-backup>
    <device-transfer>
        <exclude domain="file" path="datastore/legal_lockdown.preferences_pb" />
    </device-transfer>
</data-extraction-rules>
```

In `app/src/main/AndroidManifest.xml`, on the `<application>` tag beside `android:allowBackup="true"`:

```xml
        android:fullBackupContent="@xml/backup_rules"
        android:dataExtractionRules="@xml/data_extraction_rules"
```

- [ ] **Step 8: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add app/src/main/java/com/eried/eucplanet/data/store/LegalLockdownStore.kt \
        app/src/main/java/com/eried/eucplanet/data/repository/LegalLockdownController.kt \
        app/src/main/res/xml/backup_rules.xml \
        app/src/main/res/xml/data_extraction_rules.xml \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/com/eried/eucplanet/di/AppModule.kt \
        app/src/test/java/com/eried/eucplanet/data/LegalLockdownCodeTest.kt
git commit -m "feat(lockdown): manufacturer code state in its own store"
```

---

### Task 2: Gate every path that can lift the legal limits

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/lockdown/LockdownPromptBus.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/flic/FlicManager.kt` (`executeAction`, around line 320-362)
- Modify: `app/src/main/java/com/eried/eucplanet/data/repository/WheelRepository.kt` (`toggleSafetySpeed` 1662, `enableSafetySpeed` 1690, `disableSafetySpeed` 1697)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt` (`updateSafetyTiltback`, `updateSafetyAlarm`)
- Test: `app/src/test/java/com/eried/eucplanet/data/LegalLockdownGateTest.kt`

**Interfaces:**
- Consumes: `LegalLockdownController.isArmed()` from Task 1.
- Produces:
  - `object LockdownPromptBus { val showUnlock: StateFlow<Boolean>; fun request(); fun consume() }`
  - `object LockdownGate { val ALLOWED_ACTIONS: Set<String>; fun isAllowed(key: String): Boolean; fun raisesUnlockPrompt(key: String): Boolean }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/eried/eucplanet/data/LegalLockdownGateTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.flic.LockdownGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownGateTest {

    @Test
    fun `only horn and light are allowed while armed`() {
        assertEquals(setOf("HORN", "LIGHT_TOGGLE"), LockdownGate.ALLOWED_ACTIONS)
        assertTrue(LockdownGate.isAllowed("HORN"))
        assertTrue(LockdownGate.isAllowed("LIGHT_TOGGLE"))
    }

    @Test
    fun `every legal mode action is blocked`() {
        assertFalse(LockdownGate.isAllowed("SAFETY_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("SAFETY_ON"))
        assertFalse(LockdownGate.isAllowed("SAFETY_OFF"))
    }

    @Test
    fun `legal mode actions raise the unlock prompt, others are silent`() {
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_TOGGLE"))
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_ON"))
        assertTrue(LockdownGate.raisesUnlockPrompt("SAFETY_OFF"))
        assertFalse(LockdownGate.raisesUnlockPrompt("LOCK_TOGGLE"))
        assertFalse(LockdownGate.raisesUnlockPrompt("RECORD_TOGGLE"))
    }

    @Test
    fun `lock, record, voice and custom ble are blocked`() {
        assertFalse(LockdownGate.isAllowed("LOCK_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("RECORD_TOGGLE"))
        assertFalse(LockdownGate.isAllowed("VOICE_ANNOUNCE"))
        assertFalse(LockdownGate.isAllowed("B:0000-1111"))
        assertFalse(LockdownGate.isAllowed("OPEN_SETTINGS"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownGateTest*"`
Expected: FAIL, unresolved reference `LockdownGate`.

- [ ] **Step 3: Add the gate object and the prompt bus**

At the bottom of `app/src/main/java/com/eried/eucplanet/flic/FlicManager.kt`, outside the class:

```kotlin
/**
 * What may still fire while Legal Mode Lockdown is armed.
 *
 * An allowlist, not a blocklist, on purpose: a new action added to
 * ActionCatalog later is blocked by default rather than silently becoming a
 * hole in the lock.
 */
object LockdownGate {

    val ALLOWED_ACTIONS: Set<String> = setOf("HORN", "LIGHT_TOGGLE")

    private val LEGAL_MODE_ACTIONS = setOf("SAFETY_TOGGLE", "SAFETY_ON", "SAFETY_OFF")

    fun isAllowed(key: String): Boolean = key in ALLOWED_ACTIONS

    /** A blocked legal-mode press shows the rider the way out instead of doing nothing. */
    fun raisesUnlockPrompt(key: String): Boolean = key in LEGAL_MODE_ACTIONS
}
```

`app/src/main/java/com/eried/eucplanet/ui/lockdown/LockdownPromptBus.kt`:

```kotlin
package com.eried.eucplanet.ui.lockdown

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A blocked legal-mode press can come from a Flic, a volume key, the watch or
 * the HUD, none of which have a Compose scope. They post here and the locked
 * screen opens its unlock dialog. Same shape as DashboardDialogBus.
 */
object LockdownPromptBus {

    private val _showUnlock = MutableStateFlow(false)
    val showUnlock: StateFlow<Boolean> = _showUnlock.asStateFlow()

    fun request() { _showUnlock.value = true }

    fun consume() { _showUnlock.value = false }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownGateTest*"`
Expected: PASS, 4 tests.

- [ ] **Step 5: Apply the gate in FlicManager**

Inject the controller into `FlicManager`'s constructor:

```kotlin
    private val legalLockdown: com.eried.eucplanet.data.repository.LegalLockdownController,
```

In `executeAction`, immediately after the existing `enabledReader` precondition check and before the `when (key)` block:

```kotlin
        // Legal Mode Lockdown. Every remote surface (Flic, volume keys, watch,
        // HUD) and the dashboard tiles funnel through this function, so one
        // allowlist here covers all of them.
        if (legalLockdown.isArmed() && !LockdownGate.isAllowed(key)) {
            if (LockdownGate.raisesUnlockPrompt(key)) {
                com.eried.eucplanet.ui.lockdown.LockdownPromptBus.request()
            }
            Log.i(TAG, "action $key blocked: legal mode lockdown armed")
            return
        }
```

In the same file, the `"LIGHT_TOGGLE"` branch currently reads:

```kotlin
            "LIGHT_TOGGLE" -> {
                automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
```

Change it to skip the suspension while armed. `notifyManualLightChange()` suspends auto-lights for the whole session, and a temporary mode must not leave that behind:

```kotlin
            "LIGHT_TOGGLE" -> {
                // While locked down the light button must not suspend
                // auto-lights: the mode is temporary and the rider is never
                // told the automation was overridden.
                if (!legalLockdown.isArmed()) automationManager.notifyManualLightChange()
                wheelRepository.toggleLight()
            }
```

- [ ] **Step 6: Apply the gate in WheelRepository**

Inject `LegalLockdownController` into `WheelRepository`. If that creates a Hilt cycle (the controller depends only on its store, so it should not), break it by injecting `dagger.Lazy<LegalLockdownController>`.

`toggleSafetySpeed()` at line 1662, first line of the body:

```kotlin
        // Backstop for any caller that does not go through FlicManager. Turning
        // legal mode ON is always fine, turning it OFF while locked down is not.
        if (legalLockdown.isArmed() && _safetySpeedActive.value) return
```

`disableSafetySpeed()` at line 1697, first line of the body:

```kotlin
        if (legalLockdown.isArmed()) return
```

Leave `enableSafetySpeed()` untouched.

- [ ] **Step 7: Block raising the legal limits from settings**

In `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt`, at the top of `updateSafetyTiltback` and `updateSafetyAlarm`:

```kotlin
        // Otherwise setting the legal tiltback to 60 km/h is the bypass.
        if (legalLockdown.isArmed()) return
```

Inject `LegalLockdownController` into the view model.

- [ ] **Step 8: Build, test and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.
Run: `./gradlew :app:testPhoneDebugUnitTest` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): gate every path that can lift the legal limits"
```

---

### Task 3: Force the limits onto the wheel, including on reconnect

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/repository/WheelRepository.kt` (the connect-time safety sync near lines 1854 and 2154)
- Test: `app/src/test/java/com/eried/eucplanet/data/LegalLockdownReapplyTest.kt`

**Interfaces:**
- Consumes: `LegalLockdownController.isArmed()`, `WheelRepository.enableSafetySpeed()`.
- Produces: `object LockdownReapply { fun shouldReapply(armed: Boolean, wheelReportsLegalOn: Boolean): Boolean }`

- [ ] **Step 1: Write the failing test**

`app/src/test/java/com/eried/eucplanet/data/LegalLockdownReapplyTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.repository.LockdownReapply
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalLockdownReapplyTest {

    @Test
    fun `armed and wheel came back without legal mode means reapply`() {
        assertTrue(LockdownReapply.shouldReapply(armed = true, wheelReportsLegalOn = false))
    }

    @Test
    fun `armed and wheel already legal means leave it alone`() {
        assertFalse(LockdownReapply.shouldReapply(armed = true, wheelReportsLegalOn = true))
    }

    @Test
    fun `not armed never reapplies`() {
        assertFalse(LockdownReapply.shouldReapply(armed = false, wheelReportsLegalOn = false))
        assertFalse(LockdownReapply.shouldReapply(armed = false, wheelReportsLegalOn = true))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownReapplyTest*"`
Expected: FAIL, unresolved reference `LockdownReapply`.

- [ ] **Step 3: Add the rule and call it**

In `WheelRepository.kt`, outside the class:

```kotlin
/**
 * Whether a freshly connected wheel needs the legal limits pushed back onto it.
 *
 * Split out from the connect path so the rule is testable without a BLE stack.
 * A wheel that was power-cycled, or one connected for the first time after the
 * lock was armed with no wheel present, comes back reporting legal mode off.
 */
object LockdownReapply {
    fun shouldReapply(armed: Boolean, wheelReportsLegalOn: Boolean): Boolean =
        armed && !wheelReportsLegalOn
}
```

At the two places where the repository syncs the wheel's reported legal state into `_safetySpeedActive` (near lines 1854 and 2154, `_safetySpeedActive.value = isLegalOn` and `_safetySpeedActive.value = confirmedSafety`), add right after each assignment:

```kotlin
            if (LockdownReapply.shouldReapply(legalLockdown.isArmed(), _safetySpeedActive.value)) {
                enableSafetySpeed()
            }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testPhoneDebugUnitTest --tests "*LegalLockdownReapplyTest*"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): push the legal limits back on every wheel connect"
```

---

### Task 4: Suppress the subsystems the mode turns off

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/repository/TripRepository.kt` (`startRecording` 640)
- Modify: `app/src/main/java/com/eried/eucplanet/nav/NavigationEngine.kt` (`start` 268)
- Modify: `app/src/main/java/com/eried/eucplanet/service/WheelService.kt` (`pushWidget` 976, `renderWidget` 1116, `applyPhoneHud` 998, `pushPhoneHud` 1044, `buildNotificationActions` 889)
- Modify: `app/src/main/java/com/eried/eucplanet/service/VoiceService.kt` (`announceStatus` 493, `announceTrigger` 500, `announceEvent` 610)
- Modify: `app/src/main/java/com/eried/eucplanet/service/AutomationManager.kt` (`evaluate` 117)
- Modify: `app/src/main/java/com/eried/eucplanet/diagnostics/DiagnosticsLogger.kt` (`enable` 53)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/dashboard/DashboardViewModel.kt` (`onLightToggle` 768)

**Interfaces:**
- Consumes: `LegalLockdownController.isArmed()`.
- Produces: nothing new. Every change is an early return guarded by `isArmed()`.

The split in `VoiceService` matters and is not arbitrary. `AlarmEngine` speaks through `voiceService.speak(...)`, and nothing else does. Every announcement that is app chatter rather than an alarm goes through `announceEvent`, `announceTrigger` or `announceStatus`. Gating those three and leaving `speak()` alone is exactly "alarms still fire, the app stops talking about legal mode and recording".

- [ ] **Step 1: TripRepository**

Inject `LegalLockdownController`. At the top of `startRecording()`:

```kotlin
        // Lockdown stops the recorders. Auto-record reaches this same function,
        // so gating here covers the policy too.
        if (legalLockdown.isArmed()) return
```

- [ ] **Step 2: NavigationEngine**

Inject `LegalLockdownController`. At the top of `start(route, mode)`:

```kotlin
        if (legalLockdown.isArmed()) return
```

- [ ] **Step 3: WheelService**

Inject `LegalLockdownController` with `@Inject lateinit var`, matching how `phoneHudWindow` is injected at line 157. Add an early return at the top of each of these:

```kotlin
    private fun pushWidget(data: WheelData) {
        if (legalLockdown.isArmed()) return
        ...
    }

    private fun renderWidget(data: WheelData?) {
        if (legalLockdown.isArmed()) return
        ...
    }

    private fun pushPhoneHud(rawData: WheelData) {
        if (legalLockdown.isArmed()) return
        ...
    }
```

`applyPhoneHud()` decides whether the overlay window is shown. While armed it must take the hide path, so instead of returning early:

```kotlin
    private fun applyPhoneHud() {
        if (legalLockdown.isArmed()) {
            phoneHudWindow.hide()
            return
        }
        ...
    }
```

And at line 889, `buildNotificationActions(nav.active, data).forEach { builder.addAction(it) }` becomes:

```kotlin
        if (!legalLockdown.isArmed()) {
            buildNotificationActions(nav.active, data).forEach { builder.addAction(it) }
        }
```

- [ ] **Step 4: VoiceService**

Inject `LegalLockdownController`. At the top of `announceStatus`, `announceTrigger` and `announceEvent`:

```kotlin
        // Lockdown silences app chatter. AlarmEngine speaks through speak()
        // instead, so the rider's alarms still fire.
        if (legalLockdown.isArmed()) return
```

Leave `speak()`, `speakInternal()` and `speakWelcomeNow()` untouched.

- [ ] **Step 5: AutomationManager**

Inject `LegalLockdownController`. In `evaluate(settings)`, change the media-control and proximity-lock lines:

```kotlin
        val armed = legalLockdown.isArmed()
        val mc = settings.mediaControl
        if (!armed && (mc.pauseEnabled || mc.resumeEnabled)) evaluateMediaControl(settings)
        if (!armed && settings.proximityLock.lockEnabled) evaluateProximityLock(settings)
        else proximityLock.reset()
```

Leave `evaluateLights` and `evaluateVolume` exactly as they are. Auto lights and auto volume keep running.

Also guard `notifyManualLightChange()` itself, so the lockdown LIGHT button can never suspend auto-lights no matter which caller reaches it:

```kotlin
    fun notifyManualLightChange() {
        if (legalLockdown.isArmed()) return
        ...
    }
```

- [ ] **Step 6: DiagnosticsLogger**

Inject `LegalLockdownController`. At the top of `enable()`:

```kotlin
        if (legalLockdown.isArmed()) return
```

Then in `MainActivity`, where the volume-key path opens the service overlay (near line 636, `ServiceOverlayState`), wrap the open in `if (!legalLockdown.isArmed())`.

- [ ] **Step 7: DashboardViewModel**

`onLightToggle()` at line 768 already calls `automationManager.notifyManualLightChange()`, which Step 5 now guards internally. No change needed here, but confirm by reading it.

- [ ] **Step 8: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): stop the recorders, navigation, overlay, widgets and chatter"
```

---

### Task 5: The locked screen

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/lockdown/LegalLockdownScreen.kt`
- Create: `app/src/main/java/com/eried/eucplanet/ui/lockdown/LegalLockdownViewModel.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/MainActivity.kt` (composition root near line 620, rotation block near line 609)
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `LegalLockdownController.armed`, `LockdownPromptBus`, `internal fun SpeedGauge` from `DashboardScreen.kt`.
- Produces: `@Composable fun LegalLockdownScreen(onNavigateToScan: () -> Unit)`.

- [ ] **Step 1: Add the strings**

In `app/src/main/res/values/strings.xml`:

```xml
    <string name="lockdown_title">Legal Mode Lockdown</string>
    <string name="lockdown_action_speed_limit">Speed limit</string>
    <string name="lockdown_action_vehicle">Vehicle</string>
    <string name="lockdown_no_wheel">No wheel connected. Use the Bluetooth icon to connect.</string>
    <string name="lockdown_vehicle_name">Bluetooth name</string>
    <string name="lockdown_vehicle_brand">Maker</string>
    <string name="lockdown_vehicle_model">Model</string>
    <string name="lockdown_vehicle_max_speed">Max speed limit</string>
    <string name="lockdown_vehicle_alarm">Alarm</string>
    <string name="lockdown_vehicle_odometer">Odometer</string>
    <string name="lockdown_unlock_prompt">Unlock with manufacturer code</string>
    <string name="lockdown_unlock">Unlock</string>
    <string name="lockdown_wrong_code">Incorrect code</string>
```

- [ ] **Step 2: Write the view model**

`app/src/main/java/com/eried/eucplanet/ui/lockdown/LegalLockdownViewModel.kt`. It exposes only what the six pills, the gauge and the two dialogs need. Nothing configurable, nothing from `dashboardMetricOrder`:

```kotlin
package com.eried.eucplanet.ui.lockdown

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eried.eucplanet.data.repository.LegalLockdownController
import com.eried.eucplanet.data.repository.WheelRepository
import com.eried.eucplanet.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LegalLockdownViewModel @Inject constructor(
    private val wheelRepository: WheelRepository,
    private val settingsRepository: SettingsRepository,
    private val lockdown: LegalLockdownController
) : ViewModel() {

    val wheelData = wheelRepository.wheelData
    val connectionState = wheelRepository.connectionState
    val connectedDeviceName = wheelRepository.connectedDeviceName
    val connectedBrand = wheelRepository.connectedBrand
    val modelName = wheelRepository.modelName

    /** True on a correct code. The screen shows the error and starts the
     *  cooldown on false. */
    suspend fun tryUnlock(pin: String): Boolean = lockdown.tryDisarm(pin)
}
```

Check the exact names and types of `connectedBrand` / `modelName` in `WheelRepository` before writing this, and mirror the `DashboardViewModel` lines 105 onward for how speed unit, distance unit and the legal tiltback / alarm flows are exposed. Add the same flows here for `safetyTiltbackSpeed`, `safetyAlarmSpeed`, `speedUnit`, `distanceUnit` and `tempUnit`.

- [ ] **Step 3: Write the screen**

`app/src/main/java/com/eried/eucplanet/ui/lockdown/LegalLockdownScreen.kt`. Structure, top to bottom in a `Column` filling the screen:

1. A top row with only a Bluetooth `IconButton` calling `onNavigateToScan`, tinted from `MaterialTheme.appColors`. Nothing else.
2. `SpeedGauge(...)` from `DashboardScreen.kt`, reusing the same parameters the dashboard passes for the non-compact portrait case, with the legal tiltback as the limit.
3. A metrics grid, hard-coded, 2 columns x 3 rows, in this order: `BATTERY, TEMPERATURE, VOLTAGE, CURRENT, LOAD, TRIP`. Render each with a plain `Card` + label + value. Do **not** reuse `LiveMetricTile`, which carries tap handling, sparklines and stat menus. The pills must be inert: no `clickable`, no `combinedClickable`, no ripple.
4. An action grid, hard-coded, 2 columns x 2 rows: HORN, LIGHT, SPEED LIMIT, VEHICLE. HORN and LIGHT call `viewModel` methods that route through the wheel repository, and are disabled when not connected. SPEED LIMIT and VEHICLE open the dialogs from Task 6.
5. A static version `Text`, no `clickable`.

Wire the prompt bus so a blocked remote press opens the unlock dialog:

```kotlin
    val promptUnlock by LockdownPromptBus.showUnlock.collectAsState()
    LaunchedEffect(promptUnlock) {
        if (promptUnlock) {
            showUnlockDialog = true
            LockdownPromptBus.consume()
        }
    }
```

Consume back:

```kotlin
    androidx.activity.compose.BackHandler(enabled = true) { /* locked, back does nothing */ }
```

- [ ] **Step 4: Swap it in at the composition root**

In `MainActivity.kt`, replace the `Box { NavGraph(...) ... }` block with a branch on the armed state. Rendering instead of the nav graph, rather than changing `startDestination`, is what makes the other routes genuinely unreachable rather than merely not-the-start:

```kotlin
                    val lockdownArmed by legalLockdown.armed.collectAsState()
                    if (lockdownArmed) {
                        LegalLockdownScreen(
                            onNavigateToScan = { showScanSheet = true }
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize()) {
                            NavGraph(navController = navController)
                            // ... existing NavigationOverlay and service overlay
                        }
                    }
```

The Bluetooth icon needs a way to reach scanning without the nav graph. Simplest correct option: keep a single-route `NavHost` for the locked mode with only `legal_lockdown` and `scan` in it, and make `ScanScreen`'s back pop to the locked screen. Verify `ScanScreen` does not offer any route into settings; if it does, hide that control while armed.

Inject `LegalLockdownController` into `MainActivity` with `@Inject lateinit var legalLockdown: LegalLockdownController`.

- [ ] **Step 5: Force portrait**

In the rotation `LaunchedEffect` near line 609, add `legalLockdown.armed` to the key list and short-circuit:

```kotlin
                        if (legalLockdown.isArmed()) {
                            this@MainActivity.requestedOrientation =
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            return@LaunchedEffect
                        }
```

No `rotate*` setting is read or written on this path.

- [ ] **Step 6: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): the fixed locked dashboard"
```

---

### Task 6: The unlock and vehicle dialogs

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/lockdown/LegalLockdownScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `LegalLockdownViewModel.tryUnlock`, the speed-unit and odometer flows.
- Produces: two private composables in the same file, `UnlockDialog` and `VehicleDialog`.

- [ ] **Step 1: The unlock dialog**

Opened by the SPEED LIMIT button and by `LockdownPromptBus`. Shows the two real values, then the code field. `DialogProperties(dismissOnClickOutside = false)` per rule 11. A `TextField` with `KeyboardType.NumberPassword` and `PasswordVisualTransformation`.

Cooldown, as local state in the dialog:

```kotlin
    var error by remember { mutableStateOf(false) }
    var cooldownUntil by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cooldownUntil) {
        while (System.currentTimeMillis() < cooldownUntil) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(250)
        }
        now = System.currentTimeMillis()
    }
    val cooling = now < cooldownUntil
```

The Unlock button is `enabled = !cooling && pin.isNotEmpty()`. On a wrong code set `error = true` and `cooldownUntil = System.currentTimeMillis() + 3_000L`. On a correct code the controller disarms, `armed` flips, and `MainActivity` swaps back to the nav graph on its own. Do not navigate manually.

- [ ] **Step 2: The vehicle dialog**

Connected: rows for Bluetooth name, maker, model, max speed limit (the legal tiltback, in the rider's unit), alarm, odometer. Not connected: the single line `lockdown_no_wheel`. Same `dismissOnClickOutside = false`.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): unlock and vehicle dialogs"
```

---

### Task 7: Arming from settings

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt` (after the legal-mode-speed section at line 6725, and the `corpusSpeed` search list at line 543)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Interfaces:**
- Consumes: `LegalLockdownController.arm`, `TripRepository.stopRecording`, `WheelRepository.enableSafetySpeed`.
- Produces: `SettingsViewModel.armLockdown(pin: String): Boolean`.

- [ ] **Step 1: Add the strings**

The warning is one string with newlines so translators see it as one block:

```xml
    <string name="lockdown_setting_label">Legal Mode Lockdown</string>
    <string name="lockdown_setting_desc">Lock the app to a simple screen. Needs a manufacturer code to undo.</string>
    <string name="lockdown_set_code">Set the manufacturer code</string>
    <string name="lockdown_confirm_code">Repeat the code</string>
    <string name="lockdown_code_hint">4 to 8 digits</string>
    <string name="lockdown_code_mismatch">The two codes do not match</string>
    <string name="lockdown_code_invalid">The code must be 4 to 8 digits</string>
    <string name="lockdown_arm">Turn on</string>
    <string name="lockdown_trip_saved">Trip saved. Recording stopped.</string>
    <string name="lockdown_warning">This switches the app to a simple, locked screen. While it is on:\n
\n
- Legal mode speed limits are forced on and cannot be turned off, from the screen, a Flic button, the volume keys, the watch or the HUD.\n
- The legal tiltback and alarm speeds cannot be changed.\n
- Trip recording stops, and auto record will not start new trips.\n
- Navigation stops and routes cannot be started.\n
- The floating overlay is hidden.\n
- Home screen widgets stop updating.\n
- Service mode and diagnostics recording stop.\n
- The dashboard is replaced by a fixed screen: speedometer, six metrics, four buttons.\n
- The screen will not rotate.\n
- Settings cannot be opened.\n
- Trip history, the battery monitor, the studio and metric details cannot be opened.\n
- Tapping a metric does nothing.\n
- Voice announcements, periodic reports and accel splits are silent. Your alarms still work.\n
- The wheel cannot be locked or unlocked, and proximity auto-lock will not run.\n
- Auto lights and auto volume keep working. Media control stops.\n
- The notification loses its buttons.\n
- The version number will not open About.\n
- This mode is not included in settings backups.\n
\n
Your settings are not changed. Everything comes back exactly as it is now when you unlock.\n
\n
The only way out is the manufacturer code, from the Speed limit button. If you lose that code, the only way to recover the app is to uninstall and reinstall it.</string>
```

- [ ] **Step 2: Add the view-model method**

In `SettingsViewModel`, injecting `LegalLockdownController`, `TripRepository` and `WheelRepository`:

```kotlin
    /**
     * Arms the lock. Order matters: the rider's in-progress trip is finalised
     * and saved BEFORE the recorder gate goes up, otherwise the partial ride is
     * stranded. Returns false on an invalid code, having changed nothing.
     */
    suspend fun armLockdown(pin: String): Boolean {
        if (!LegalLockdownCode.isValidPin(pin)) return false
        if (tripRepository.recording.value) tripRepository.stopRecording()
        wheelRepository.enableSafetySpeed()
        return legalLockdown.arm(pin)
    }
```

- [ ] **Step 3: Add the switch and the arming dialog**

In `SettingsScreen.kt`, immediately after the legal-mode-speed `Row` that ends near line 6749:

```kotlin
        SectionHeader(stringResource(R.string.lockdown_title))
        SwitchSettingWithDesc(
            label = stringResource(R.string.lockdown_setting_label),
            description = stringResource(R.string.lockdown_setting_desc),
            checked = lockdownArmed,
            onCheckedChange = { on -> if (on) showLockdownDialog = true }
        )
```

The switch never turns itself off: while armed the settings screen is unreachable, and the only way back is the code. `onCheckedChange` therefore ignores `false`.

The dialog holds two `TextField`s (`NumberPassword`), the scrollable warning text, and a confirm button enabled only when both codes match and are valid. On confirm call `armLockdown(pin)`, then show `lockdown_trip_saved` through `LocalSnackbar` if a trip was recording. `MainActivity` swaps to the locked screen when `armed` flips, so do not navigate manually.

Add `stringResource(R.string.lockdown_setting_label)` and `stringResource(R.string.lockdown_title)` to the `corpusSpeed` list at line 543 so settings search finds the row.

- [ ] **Step 4: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`.

```bash
git add -A
git commit -m "feat(lockdown): arm from wheel parameters, with the full warning"
```

---

### Task 8: The settings-untouched guarantee, as a test

The feature's central promise. It gets its own test rather than being an assumption.

**Files:**
- Test: `app/src/test/java/com/eried/eucplanet/data/LegalLockdownSettingsUntouchedTest.kt`

**Interfaces:**
- Consumes: `SettingsJson.toJson`, `AppSettings`.
- Produces: nothing.

- [ ] **Step 1: Write the test**

Because lockdown state is in its own store, the guarantee reduces to a structural claim: no lockdown field exists anywhere in `AppSettings` or its JSON. That is exactly what to assert, and it will fail loudly if someone later moves the flag into settings.

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LegalLockdownSettingsUntouchedTest {

    @Test
    fun `lockdown state never appears in the settings payload`() {
        val json = SettingsJson.toJson(AppSettings()).toString().lowercase()
        assertFalse(json.contains("lockdown"))
        assertFalse(json.contains("codehash"))
    }

    @Test
    fun `settings round trip is unaffected`() {
        val original = AppSettings()
        val restored = SettingsJson.fromJson(SettingsJson.toJson(original))
        assertEquals(original, restored)
    }
}
```

- [ ] **Step 2: Run the full unit suite**

Run: `./gradlew :app:testPhoneDebugUnitTest` and grep for `BUILD SUCCESSFUL`.
Expected: all tests pass, including the pre-existing suite.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "test(lockdown): the settings-untouched guarantee"
```

---

### Task 9: Translate the new strings to all 22 locales

**Files:**
- Modify: all 22 of `app/src/main/res/values-{b+es+419,cs,da,de,es,fi,fr,hu,it,ja,ko,nl,no,pl,pt-rBR,ro,ru,sv,tr,uk,zh,zh-rTW}/strings.xml`

- [ ] **Step 1: List what needs translating**

Run: `git diff next-version -- app/src/main/res/values/strings.xml | grep '^+' | grep 'name='` and translate every listed key.

- [ ] **Step 2: Translate**

Rider terminology in every language: the device is a wheel, not a bike or a car, and the person is a rider, not a driver. Keep button labels short enough to fit: `lockdown_action_speed_limit`, `lockdown_action_vehicle`, `lockdown_unlock` and `lockdown_arm` all sit on buttons. No em-dashes.

- [ ] **Step 3: Build and commit**

Run: `./gradlew :app:assembleDebug` and grep for `BUILD SUCCESSFUL`. A missing closing tag in any locale fails the resource merge, so the build is the check.

```bash
git add app/src/main/res/values-*/strings.xml
git commit -m "i18n(lockdown): translate the new strings"
```

---

### Task 10: Verify on the emulator

The AVD `legalmode` (Pixel 7, API 36.1) already exists.

- [ ] **Step 1: Install**

```bash
./gradlew :app:installPhoneDebug
```

- [ ] **Step 2: Walk the spec**

Each of these is a pass or fail, recorded:

1. Settings, Wheel parameters. The Legal Mode Lockdown row is under Legal mode speed. Turning it on shows the full warning and asks for the code twice.
2. A mismatched code is refused. A 3-digit code is refused.
3. Arm with a valid code. The app lands on the locked screen.
4. The locked screen shows: Bluetooth icon only in the chrome, the gauge, six pills in 2 x 3, four buttons in 2 x 2, static version text.
5. Tapping each metric pill does nothing, with no ripple.
6. Rotating the device leaves the screen in portrait.
7. Back does nothing.
8. SPEED LIMIT opens the dialog with the real limit and alarm values.
9. A wrong code shows "Incorrect code" and disables Unlock for 3 seconds.
10. VEHICLE with no wheel shows the connect line.
11. Force-stop the app and relaunch. It comes back locked.
12. Volume-down hold (bound to `SAFETY_TOGGLE` by default) opens the unlock dialog rather than toggling legal mode.
13. Enter the correct code. The normal dashboard returns with the rider's own metric order, action order and rotation behaviour intact.
14. With the switch off, toggle Legal Mode from the dashboard tile and confirm it behaves exactly as before.

- [ ] **Step 3: Record the results**

Append a short results section to the spec file, then commit.

```bash
git add -A
git commit -m "docs(lockdown): emulator verification results"
```

---

## Self-Review

**Spec coverage.** Every spec section maps to a task: storage and backup exclusion to Task 1, the three gates to Tasks 2 and 3, the runtime overlay table to Task 4, the screen to Task 5, the dialogs to Task 6, arming and the warning text to Task 7, the guarantee to Task 8, rule 12 to Task 9, and the spec's test plan to Task 10.

**Naming consistency.** `LegalLockdownController.isArmed()` is used in every gate, `armed` (the StateFlow) only in Compose. `LockdownGate.isAllowed` / `raisesUnlockPrompt` are used exactly as defined in Task 2. `LockdownReapply.shouldReapply` matches Task 3.

**Known soft spot.** Task 5 Step 4 leaves the scan route mechanism to the implementer's judgement, because how `ScanScreen` is best reached without the full nav graph depends on what that screen pulls in. That is a real decision to make while reading the file, not a placeholder: the requirement is fixed, which is that the Bluetooth icon reaches scanning and no route to settings is reachable from there.
