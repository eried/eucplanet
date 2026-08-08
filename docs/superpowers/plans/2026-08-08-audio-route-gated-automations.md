# Audio-route-gated automations Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the rider restrict the auto play/pause-music automation and the periodic voice announcements so they act only when audio is on an external output (headphones / Bluetooth / wired / USB), not the phone speaker.

**Architecture:** One shared, mostly-pure helper (`AudioOutput`) decides "is an external output active". Two independent, default-off per-feature toggles consult it: `MediaControlSettings.requireExternalOutput` gates `AutomationManager.evaluateMediaControl`, and a new top-level `voiceAnnounceRequireExternal` adds an AND condition to `WheelService.startVoiceLoop`. `EngineSoundEngine` is refactored to reuse the same helper.

**Tech Stack:** Kotlin, Jetpack Compose, Android `AudioManager`/`AudioDeviceInfo`, Hilt, JUnit 4 (the ONLY test lib available - no mockk, Mockito, or Robolectric).

## Global Constraints

- **No em-dashes** anywhere (UI strings, code comments, commit and PR text). Use commas, " - ", or separate sentences (repo rule 2).
- **Colors from the theme:** read via `MaterialTheme.appColors.*`; never hardcode `Color(...)`. Switches use the file's existing `themedSwitchColors()`. Always set explicit on-colors (repo rules 4, 6).
- **Read/write settings through `SettingsRepository`**; every field must round-trip through `SettingsJson.toJson` AND `fromJson` (repo rule 7).
- **Keep `AppSettings` under the 255-arg dex limit:** the media-control field is nested inside the existing `MediaControlSettings` group; the announcement field is one new top-level Boolean (repo rule 8).
- **Localize all user-facing text** to every supported locale, using rider terminology (device = wheel, user = rider). Keep labels short (repo rule 12).
- **Verify builds** by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED`; never mask the exit code (repo rule 14).
- **Both new toggles default OFF** - existing behavior is unchanged until the rider opts in.
- **Route filter = any external output, not the phone speaker.** The qualifying `AudioDeviceInfo` types are exactly: `TYPE_WIRED_HEADPHONES`, `TYPE_WIRED_HEADSET`, `TYPE_BLUETOOTH_A2DP`, `TYPE_USB_HEADSET`, `TYPE_BLE_HEADSET`.
- **Branch:** work on `feature/audio-route-gated-automations` (already created off `next-experimental`; the design doc is already committed there). Do NOT push - the rider tests locally first.

**Testability note (applies to every task):** Android's `AudioManager` and `AudioDeviceInfo` cannot be mocked in a plain JVM unit test here (no mocking lib). Therefore the only route logic that gets a unit test is the pure predicate over a `List<Int>` of device-type ints (the `static final int` `TYPE_*` constants inline at compile time, so referencing them in test code is safe and touches no Android runtime). The thin `AudioManager` wrapper, the `AutomationManager` gate, and the `WheelService` gate are verified by a green build plus on-device testing, matching how these managers are already covered in this repo.

---

### Task 1: Shared `AudioOutput` route helper + reuse in `EngineSoundEngine`

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/audio/AudioOutput.kt`
- Create: `app/src/test/java/com/eried/eucplanet/audio/AudioOutputTest.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/audio/EngineSoundEngine.kt:606-615`

**Interfaces:**
- Produces:
  - `object AudioOutput`
  - `AudioOutput.EXTERNAL_OUTPUT_TYPES: Set<Int>` - the qualifying `AudioDeviceInfo` type ints
  - `fun AudioOutput.hasExternalType(types: List<Int>): Boolean` - pure, unit-tested
  - `fun AudioOutput.isExternalActive(audioManager: AudioManager): Boolean` - Android wrapper used by Tasks 2 and 3

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/audio/AudioOutputTest.kt`:

```kotlin
package com.eried.eucplanet.audio

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputTest {

    @Test
    fun bluetoothA2dpCountsAsExternal() {
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)))
    }

    @Test
    fun bleAndWiredAndUsbCountAsExternal() {
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_WIRED_HEADSET)))
        assertTrue(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_USB_HEADSET)))
    }

    @Test
    fun phoneSpeakerOnlyIsNotExternal() {
        assertFalse(AudioOutput.hasExternalType(listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
    }

    @Test
    fun emptyDeviceListIsNotExternal() {
        assertFalse(AudioOutput.hasExternalType(emptyList()))
    }

    @Test
    fun mixedListWithOneExternalCounts() {
        assertTrue(
            AudioOutput.hasExternalType(
                listOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            )
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.AudioOutputTest"`
Expected: FAIL - `AudioOutput` unresolved / does not compile.

- [ ] **Step 3: Create the helper**

Create `app/src/main/java/com/eried/eucplanet/audio/AudioOutput.kt`:

```kotlin
package com.eried.eucplanet.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Single source of truth for "is audio currently going to an external output
 * device" (Bluetooth A2DP / BLE, wired headphones or headset, or USB) rather
 * than the phone's built-in speaker. Used to gate speed-driven automations so
 * they only act when the rider is actually listening on headphones / Bluetooth.
 *
 * Point-in-time poll of the current output devices - both callers already run
 * on a periodic tick, so no route-change listener is needed.
 */
object AudioOutput {

    /** Output device types that count as "external" (not the phone speaker). */
    val EXTERNAL_OUTPUT_TYPES: Set<Int> = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
    )

    /** Pure decision: does any device type in [types] count as external? */
    fun hasExternalType(types: List<Int>): Boolean = types.any { it in EXTERNAL_OUTPUT_TYPES }

    /** True when a current output device is external (see [EXTERNAL_OUTPUT_TYPES]). */
    fun isExternalActive(audioManager: AudioManager): Boolean {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return false
        return hasExternalType(devices.map { it.type })
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.AudioOutputTest"`
Expected: PASS (5 tests). Confirm with `BUILD SUCCESSFUL`.

- [ ] **Step 5: Refactor `EngineSoundEngine` to reuse the helper**

In `app/src/main/java/com/eried/eucplanet/audio/EngineSoundEngine.kt`, replace the whole `isHeadphonesActive()` body (currently lines 606-615):

```kotlin
    private fun isHeadphonesActive(): Boolean = AudioOutput.isExternalActive(audioManager)
```

(No import needed - `AudioOutput` is in the same `com.eried.eucplanet.audio` package. `audioManager` is an existing field in this class.)

- [ ] **Step 6: Build to verify the refactor compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. `EngineSoundEngine` behavior is unchanged (same device-type set).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/AudioOutput.kt \
        app/src/test/java/com/eried/eucplanet/audio/AudioOutputTest.kt \
        app/src/main/java/com/eried/eucplanet/audio/EngineSoundEngine.kt
git commit -m "feat(audio): shared AudioOutput.isExternalActive helper; EngineSoundEngine reuses it"
```

---

### Task 2: Media-control route gate (setting + serialization + evaluation)

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt:971-978` (add field to `MediaControlSettings`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt:123-128` (write) and `:400-407` (read)
- Create: `app/src/test/java/com/eried/eucplanet/data/SettingsJsonMediaControlTest.kt`
- Modify: `app/src/main/java/com/eried/eucplanet/service/AutomationManager.kt:254-261` (gate)

**Interfaces:**
- Consumes: `AudioOutput.isExternalActive(audioManager)` (Task 1).
- Produces: `MediaControlSettings.requireExternalOutput: Boolean` (default `false`).

- [ ] **Step 1: Write the failing serialization test**

The reflection drift-guard (`SettingsJsonDriftGuardTest`) only covers TOP-LEVEL simple fields; nested-group fields like `mediaControl.*` need their own round-trip test. Create `app/src/test/java/com/eried/eucplanet/data/SettingsJsonMediaControlTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.model.AppSettings
import com.eried.eucplanet.data.store.SettingsJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsJsonMediaControlTest {

    @Test
    fun requireExternalOutputSurvivesRoundTrip() {
        val settings = AppSettings().copy(
            mediaControl = AppSettings().mediaControl.copy(requireExternalOutput = true)
        )
        val roundTripped =
            SettingsJson.fromJson(JSONObject(SettingsJson.toJson(settings).toString()))
        assertTrue(roundTripped.mediaControl.requireExternalOutput)
    }

    @Test
    fun requireExternalOutputDefaultsFalseOnEmptyJson() {
        val roundTripped = SettingsJson.fromJson(JSONObject("{}"))
        assertEquals(false, roundTripped.mediaControl.requireExternalOutput)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonMediaControlTest"`
Expected: FAIL - `requireExternalOutput` unresolved (does not compile yet).

- [ ] **Step 3: Add the field to `MediaControlSettings`**

In `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt`, add to `MediaControlSettings` (after `resumeAboveKmh`, currently line 977):

```kotlin
data class MediaControlSettings(
    val pauseEnabled: Boolean = false,
    // Pause when speed is at or below this (km/h).
    val pauseBelowKmh: Int = 6,
    val resumeEnabled: Boolean = false,
    // Resume (only what this feature paused) when speed is at or above this (km/h).
    val resumeAboveKmh: Int = 10,
    // When true, pause/resume only act while audio is on an external output
    // (headphones / Bluetooth / wired / USB), never the phone speaker.
    val requireExternalOutput: Boolean = false,
)
```

- [ ] **Step 4: Wire serialization in `SettingsJson`**

In `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt`, add to the `mediaControl` write block (after `resumeAboveKmh`, line 127):

```kotlin
            put("requireExternalOutput", s.mediaControl.requireExternalOutput)
```

and to the read block (after `resumeAboveKmh`, line 405):

```kotlin
                requireExternalOutput = m.optBoolean(
                    "requireExternalOutput", base.mediaControl.requireExternalOutput
                ),
```

- [ ] **Step 5: Run the serialization test to verify it passes**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonMediaControlTest"`
Expected: PASS (2 tests).

- [ ] **Step 6: Add the route gate to `evaluateMediaControl`**

In `app/src/main/java/com/eried/eucplanet/service/AutomationManager.kt`, add an import near the top (after line 11):

```kotlin
import com.eried.eucplanet.audio.AudioOutput
```

Then in `evaluateMediaControl` (line 254), immediately after the existing connection guard block that ends at line 261 (`}`), before `val mc = settings.mediaControl` on line 262, insert:

```kotlin
        // Only act while audio is on an external output, if the rider asked for
        // that. On the phone speaker the feature is fully inert (like a
        // disconnected wheel): reset the hold timers and do nothing. mediaAutoPaused
        // is left as-is, so reconnecting the route and speeding up still resumes
        // whatever this feature paused.
        if (settings.mediaControl.requireExternalOutput &&
            !AudioOutput.isExternalActive(audioManager)) {
            mediaPauseCandidateSinceMs = 0L
            mediaResumeCandidateSinceMs = 0L
            return
        }
```

(`audioManager` is an existing field on `AutomationManager`, line 52.)

- [ ] **Step 7: Build to verify it compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt \
        app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt \
        app/src/test/java/com/eried/eucplanet/data/SettingsJsonMediaControlTest.kt \
        app/src/main/java/com/eried/eucplanet/service/AutomationManager.kt
git commit -m "feat(automation): gate media pause/resume on external audio output"
```

---

### Task 3: Announcements route gate (setting + serialization + evaluation)

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt:63` (add top-level field near `voiceAnnounceWhen`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt:88` (write) and near `:350-357` (read)
- Modify: `app/src/main/java/com/eried/eucplanet/service/WheelService.kt` (add `audioManager`, extend the gate at line 586)

**Interfaces:**
- Consumes: `AudioOutput.isExternalActive(audioManager)` (Task 1).
- Produces: top-level `AppSettings.voiceAnnounceRequireExternal: Boolean` (default `false`).

- [ ] **Step 1: Verify the drift-guard covers the new top-level field first (it should FAIL after Step 2, before Step 3)**

The reflection guard `SettingsJsonDriftGuardTest.everySimpleFieldSurvivesRoundTrip` auto-mutates every top-level Boolean and asserts it round-trips. Adding the field WITHOUT wiring `SettingsJson` will make it fail - that is the failing test for this task (no new test file needed for serialization).

Run now to confirm it currently passes (baseline):
Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonDriftGuardTest"`
Expected: PASS.

- [ ] **Step 2: Add the top-level field**

In `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt`, immediately after `voiceAnnounceWhen` (line 63), add:

```kotlin
    // Extra AND condition on the periodic report: when true, only speak while
    // audio is on an external output (headphones / Bluetooth / wired / USB), not
    // the phone speaker. Independent of voiceAnnounceWhen. Off by default.
    val voiceAnnounceRequireExternal: Boolean = false,
```

- [ ] **Step 3: Confirm the drift-guard now FAILS (field not yet serialized)**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonDriftGuardTest"`
Expected: FAIL - message names `voiceAnnounceRequireExternal: wrote true, read back false`.

- [ ] **Step 4: Wire serialization in `SettingsJson`**

In `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt`, add to the write section (right after line 88, `put("voiceAnnounceWhen", s.voiceAnnounceWhen)`):

```kotlin
        put("voiceAnnounceRequireExternal", s.voiceAnnounceRequireExternal)
```

and in the read section (`fromJson`), right after the `voiceAnnounceWhen = ...` assignment that ends at line 357, add:

```kotlin
        voiceAnnounceRequireExternal = j.optBoolean(
            "voiceAnnounceRequireExternal", base.voiceAnnounceRequireExternal
        ),
```

- [ ] **Step 5: Confirm the drift-guard passes again**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.data.SettingsJsonDriftGuardTest"`
Expected: PASS.

- [ ] **Step 6: Add an `AudioManager` to `WheelService` and extend the voice gate**

In `app/src/main/java/com/eried/eucplanet/service/WheelService.kt`:

(a) Add imports (with the other `android.*` imports near the top):

```kotlin
import android.content.Context
import android.media.AudioManager
import com.eried.eucplanet.audio.AudioOutput
```

(`android.content.Context` may already be imported - if so, do not duplicate it.)

(b) Add a lazy field inside the `WheelService` class (near the other private fields, e.g. just above `startVoiceLoop`):

```kotlin
    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
```

(c) In `startVoiceLoop` (line 586), replace the existing gate line:

```kotlin
                    if (!allowed) continue
```

with:

```kotlin
                    if (!allowed) continue
                    // Extra opt-in condition: only speak while audio is on an
                    // external output (headphones / Bluetooth / wired / USB).
                    if (settings.voiceAnnounceRequireExternal &&
                        !AudioOutput.isExternalActive(audioManager)) continue
```

- [ ] **Step 7: Build to verify it compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt \
        app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt \
        app/src/main/java/com/eried/eucplanet/service/WheelService.kt
git commit -m "feat(voice): gate periodic announcements on external audio output"
```

---

### Task 4: UI toggles + localized strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (after `media_control_resume_above`, ~line 1232)
- Modify: all 18 `app/src/main/res/values-*/strings.xml` (via the script below)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt` (two setters)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/AutomationsContent.kt:292-293` (media-control toggle)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt:6361` (announcements toggle)

**Interfaces:**
- Consumes: `MediaControlSettings.requireExternalOutput` (Task 2), `AppSettings.voiceAnnounceRequireExternal` (Task 3).
- Produces: `SettingsViewModel.updateMediaRequireExternalOutput(Boolean)`, `SettingsViewModel.updateVoiceAnnounceRequireExternal(Boolean)`.

- [ ] **Step 1: Add the base English strings**

In `app/src/main/res/values/strings.xml`, after `media_control_resume_above` (line 1232), add:

```xml
    <string name="automation_require_external">Only on headphones or Bluetooth</string>
    <string name="automation_require_external_caption">Skips the phone speaker. Wired and USB also count.</string>
```

- [ ] **Step 2: Localize to all 18 locales**

Run this script from the repo root. File IO is UTF-8 (proper diacritics land in the files); only the final `print` is ASCII to avoid a cp1252 stdout crash on Windows. Idempotent - skips a locale that already has the keys:

```bash
python - <<'PY'
# -*- coding: utf-8 -*-
import io, os, re
label = {
 "b+es+419":"Solo con auriculares o Bluetooth","da":"Kun med hovedtelefoner eller Bluetooth",
 "de":"Nur über Kopfhörer oder Bluetooth","es":"Solo con auriculares o Bluetooth",
 "fr":"Seulement sur casque ou Bluetooth","it":"Solo con cuffie o Bluetooth",
 "ja":"ヘッドフォンまたはBluetoothのみ",
 "ko":"헤드폰 또는 블루투스에서만",
 "nl":"Alleen bij koptelefoon of Bluetooth","no":"Bare med hodetelefoner eller Bluetooth",
 "pl":"Tylko przez słuchawki lub Bluetooth","pt-rBR":"Apenas em fones ou Bluetooth",
 "ru":"Только через наушники или Bluetooth",
 "sv":"Endast med hörlurar eller Bluetooth",
 "tr":"Yalnızca kulaklık veya Bluetooth'ta",
 "uk":"Лише через навушники або Bluetooth",
 "zh-rTW":"僅限耳機或藍牙","zh":"仅限耳机或蓝牙",
}
caption = {
 "b+es+419":"Omite el altavoz del teléfono. Con cable y USB también cuentan.",
 "da":"Springer telefonens højtaler over. Kablet og USB tæller også.",
 "de":"Überspringt den Telefonlautsprecher. Kabel und USB zählen auch.",
 "es":"Omite el altavoz del teléfono. Con cable y USB también cuentan.",
 "fr":"Ignore le haut-parleur du téléphone. Filaire et USB comptent aussi.",
 "it":"Salta l'altoparlante del telefono. Anche cavo e USB contano.",
 "ja":"スマホのスピーカーを除外。有線やUSBも対象です。",
 "ko":"휴대폰 스피커는 제외. 유선과 USB도 포함됩니다.",
 "nl":"Slaat de telefoonspeaker over. Bedraad en USB tellen ook.",
 "no":"Hopper over telefonhøyttaleren. Kablet og USB teller også.",
 "pl":"Pomija głośnik telefonu. Przewodowe i USB też się liczą.",
 "pt-rBR":"Ignora o alto-falante do telefone. Com fio e USB também valem.",
 "ru":"Пропускает динамик телефона. Проводные и USB тоже подходят.",
 "sv":"Hoppar över telefonens högtalare. Kabel och USB räknas också.",
 "tr":"Telefon hoparlörünü atlar. Kablolu ve USB de sayılır.",
 "uk":"Пропускає динамік телефона. Дротові та USB теж рахуються.",
 "zh-rTW":"略過手機喇叭。有線與USB也算。",
 "zh":"跳过手机扬声器。有线和USB也算。",
}
base="app/src/main/res"; ok=0
for loc in label:
    fp=os.path.join(base,"values-"+loc,"strings.xml")
    s=io.open(fp,encoding="utf-8").read()
    if 'name="automation_require_external"' in s:
        continue
    block=('    <string name="automation_require_external">%s</string>\n'
           '    <string name="automation_require_external_caption">%s</string>'
           % (label[loc].replace("&","&amp;"), caption[loc].replace("&","&amp;")))
    m=re.search(r'(^.*name="media_control_resume_above".*$)', s, re.M)
    if not m:
        # fall back to inserting before the closing tag
        m2=re.search(r'</resources>', s)
        s=s[:m2.start()]+block+"\n"+s[m2.start():]
    else:
        s=s[:m.end()]+"\n"+block+s[m.end():]
    io.open(fp,"w",encoding="utf-8").write(s); ok+=1
print("inserted into", ok, "locales")
PY
```

The heredoc carries the real UTF-8 characters (the `# -*- coding: utf-8 -*-` line makes Python read them correctly), and file writes are UTF-8, so the diacritics/CJK/Cyrillic land intact. Only the closing `print` is ASCII, which is what avoids the Windows cp1252 stdout crash. This mirrors the locale-insertion scripts already used successfully earlier in this project.

- [ ] **Step 3: Verify no locale is missing the keys and no duplicates exist**

```bash
# Every strings.xml (base + 18 locales) must have exactly one of each key.
for f in app/src/main/res/values*/strings.xml; do
  c=$(grep -c 'name="automation_require_external"' "$f")
  [ "$c" -gt 1 ] && echo "DUP in $f ($c)"
done
echo "--- locales missing the key (should print nothing) ---"
grep -L 'automation_require_external"' app/src/main/res/values-*/strings.xml
echo "done"
```

Expected: no `DUP` lines, and no locale listed as missing.

- [ ] **Step 4: Add the two ViewModel setters**

In `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt`, after `updateMediaResumeAbove` (line 588) add:

```kotlin
    fun updateMediaRequireExternalOutput(v: Boolean) =
        update { copy(mediaControl = mediaControl.copy(requireExternalOutput = v)) }
```

and after `updateVoiceAnnounceWhen` (line 397) add:

```kotlin
    fun updateVoiceAnnounceRequireExternal(v: Boolean) =
        update { copy(voiceAnnounceRequireExternal = v) }
```

- [ ] **Step 5: Add the media-control toggle row + caption**

In `app/src/main/java/com/eried/eucplanet/ui/settings/AutomationsContent.kt`, inside the media-control `BringIntoViewSection`, just before its closing `}` (line 293, the `// end Media control BringIntoViewSection` line), insert. It is wrapped in `if (settings.mediaControl.pauseEnabled)` so it only appears when the feature is actually on, matching how the pause/resume sub-controls hide:

```kotlin
        // External-output gate: only pause/resume when on headphones / Bluetooth.
        // Shown only once the feature is enabled (pause on), like the other sub-rows.
        if (settings.mediaControl.pauseEnabled) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.automation_require_external),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f))
                Switch(checked = settings.mediaControl.requireExternalOutput,
                    onCheckedChange = { viewModel.updateMediaRequireExternalOutput(it) },
                    colors = themedSwitchColors(),)
            }
            Text(stringResource(R.string.automation_require_external_caption),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
```

(The caption style matches the existing muted-caption pattern already used in this file.)

- [ ] **Step 6: Add the announcements toggle**

In `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt`, inside the `if (settings.voiceEnabled) {` block, immediately after the `AnnounceWhenSelector(...)` call closes (line 6361), insert:

```kotlin
            SwitchSettingWithDesc(
                label = stringResource(R.string.automation_require_external),
                description = stringResource(R.string.automation_require_external_caption),
                checked = settings.voiceAnnounceRequireExternal,
                onCheckedChange = { viewModel.updateVoiceAnnounceRequireExternal(it) },
            )
```

(`SwitchSettingWithDesc` is defined in this same file at line 7100, so it is in scope.)

- [ ] **Step 7: Build to verify everything compiles**

Run: `./gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 8: Run the full unit-test suite for the touched areas**

Run: `./gradlew.bat :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.*" --tests "com.eried.eucplanet.data.SettingsJson*"`
Expected: PASS, `BUILD SUCCESSFUL`.

- [ ] **Step 9: Commit**

```bash
git add app/src/main/res/values/strings.xml app/src/main/res/values-*/strings.xml \
        app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt \
        app/src/main/java/com/eried/eucplanet/ui/settings/AutomationsContent.kt \
        app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt
git commit -m "feat(ui): 'only on headphones or Bluetooth' toggles for media control and announcements (localized)"
```

---

## Manual verification (on device, after all tasks)

Install `phone-debug.apk` to the Pixel and check:

1. **Media control:** enable "Pause when I slow down" + "Resume when I speed up" and turn on "Only on headphones or Bluetooth". On the phone speaker, riding slow then fast does NOT pause/resume media. Connect Bluetooth audio: pause/resume now works. Turn the toggle off: it works on the speaker again.
2. **Announcements:** enable periodic reports (Announce = When riding) and turn on "Only on headphones or Bluetooth". On the phone speaker, no periodic report is spoken while riding. With Bluetooth audio connected, the report speaks. Off = speaks regardless of route.
3. Both toggles persist across an app restart (settings round-trip).

## Notes for the executor

- Line numbers are from the current `feature/audio-route-gated-automations` HEAD; if a prior task shifted them, match on the quoted anchor text instead of the raw number.
- Do NOT push. The rider tests locally first (standing constraint).
- After Task 4, the feature is complete; hand back for on-device testing before any branch promotion.
