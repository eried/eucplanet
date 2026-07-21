# Custom AVAS Engine Sound Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a rider pick their own audio files for the virtual engine (AVAS) sound: one file looped and pitch-shifted, or several files mapped to idle/rev/startup/decel/shutdown sections.

**Architecture:** Add a reserved `"CUSTOM"` engine key. A synthetic `EngineProfile` is built at runtime from two new `AppSettings` fields (a pitch toggle + a JSON slot->URI map) and fed to the existing `CompositionEnginePlayer`, whose `SectionPlayer` gains a `content://` URI datasource path. When only the main clip is set, idle is pitch-shifted by speed; otherwise sections crossfade. UI is a new editor under the existing engine picker with SAF file pickers and per-slot status.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3), AndroidX Media3 (ExoPlayer), Hilt, org.json, JUnit4.

## Global Constraints

- minSdk is >= 24; `PlaybackParams` / media3 already in use. No new dependencies.
- Keep `AppSettings` under the 255-arg JVM/dex limit: this feature adds exactly **2** top-level fields. Do not add more.
- Read/write settings only through `SettingsRepository` / `SettingsViewModel.update {}`; never touch `SettingsStore`.
- UI colors come from `MaterialTheme.appColors.*` or `MaterialTheme.colorScheme.*` already used in `SettingsScreen.kt`; never hardcode `Color(0x...)`.
- All user-facing text lives in `res/values/strings.xml`. Device = "wheel", user = "rider". No em-dashes anywhere (use commas, " - ", or separate sentences).
- Preview/test must play the real configured sound (never an invented demo).
- Verify builds by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED`; never mask the exit code.
- Build/test commands (run from repo root `d:\Downloads\eucplanet-avas`):
  - Unit test one class: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.CustomEngineSoundsTest"`
  - Compile main only (fast UI check): `./gradlew :app:compileDebugKotlin`
  - Full debug build: `./gradlew :app:assembleDebug`

---

### Task 1: EngineProfile carries a URI section + a pitch flag

Adds the two fields the synthetic custom profile needs. Pure data change; guarded by a drift test.

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/audio/EngineProfile.kt` (SampleSection ~9-16; EngineProfile ctor ~113; companion ~120)
- Test: `app/src/test/java/com/eried/eucplanet/audio/EngineProfileCustomTest.kt` (create)

**Interfaces:**
- Produces: `SampleSection(rawAsset, startMs, endMs, uri: String? = null)` with `SampleSection.isCustom: Boolean`; `EngineProfile.pitchModulated: Boolean = false`; `EngineProfile.CUSTOM_KEY = "CUSTOM"`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/audio/EngineProfileCustomTest.kt`:

```kotlin
package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineProfileCustomTest {
    @Test fun `raw section is not custom, uri section is`() {
        assertFalse(SampleSection("engine_v8_cobra", 0, 100).isCustom)
        assertTrue(SampleSection("", 0, 0, uri = "content://x/1").isCustom)
    }

    @Test fun `profiles default to non-pitch-modulated`() {
        assertFalse(EngineProfile.byKey("V8_MUSCLE").pitchModulated)
    }

    @Test fun `custom key constant is stable`() {
        assertEquals("CUSTOM", EngineProfile.CUSTOM_KEY)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.EngineProfileCustomTest"`
Expected: FAIL to compile (`isCustom` / `uri` / `pitchModulated` / `CUSTOM_KEY` unresolved).

- [ ] **Step 3: Add `uri` to SampleSection**

In `EngineProfile.kt`, change the `SampleSection` data class (lines ~9-16) to:

```kotlin
data class SampleSection(
    /** res/raw filename without extension. Empty when [uri] is set. */
    val rawAsset: String,
    val startMs: Int,
    val endMs: Int,
    /**
     * When non-null, the section is loaded from this content:// / file:// URI
     * (a rider's custom file) instead of a res/raw resource. The whole file is
     * used; [startMs]/[endMs] are ignored.
     */
    val uri: String? = null,
) {
    val durationMs: Int get() = (endMs - startMs).coerceAtLeast(0)
    /** True when this section plays a rider-supplied URI, not a baked asset. */
    val isCustom: Boolean get() = uri != null
}
```

- [ ] **Step 4: Add `pitchModulated` and `CUSTOM_KEY`**

In the `EngineProfile` constructor, immediately after the `preview: Boolean = false,` field (line ~113), add:

```kotlin
    /**
     * When true, a single looped section is pitch-shifted by speed (the custom
     * single-file mode). Built-in profiles leave this false and use the
     * idle/rev crossfade instead.
     */
    val pitchModulated: Boolean = false,
```

In the `companion object` (after `val PROFILES` / before `byKey`), add:

```kotlin
        /** Reserved key for the rider's custom engine (built at runtime, not in PROFILES). */
        const val CUSTOM_KEY = "CUSTOM"
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.EngineProfileCustomTest"`
Expected: PASS (grep `BUILD SUCCESSFUL`).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/EngineProfile.kt app/src/test/java/com/eried/eucplanet/audio/EngineProfileCustomTest.kt
git commit -m "feat(engine): SampleSection URI source + profile pitch flag for custom sounds"
```

---

### Task 2: Custom sounds model - slots JSON, status, profile builder

The pure logic for the feature, fully unit-testable and isolated in one file.

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/audio/CustomEngineSounds.kt`
- Test: `app/src/test/java/com/eried/eucplanet/audio/CustomEngineSoundsTest.kt`

**Interfaces:**
- Consumes: `SampleSection(uri=)`, `EngineProfile(pitchModulated=)`, `EngineProfile.CUSTOM_KEY` (Task 1).
- Produces:
  - `object CustomSlot { IDLE, REV, STARTUP, DECEL, SHUTDOWN; ALL; OPTIONAL }`
  - `enum SlotStatus { OK, REQUIRED, MISSING, EMPTY_OPTIONAL }`
  - `object CustomEngineSounds`:
    - `parseSlots(json: String): Map<String,String>`
    - `encodeSlots(slots: Map<String,String>): String`
    - `statusFor(slot: String, uri: String?, canOpen: (String) -> Boolean): SlotStatus`
    - `buildProfile(slots: Map<String,String>, modulatePitch: Boolean): EngineProfile`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/audio/CustomEngineSoundsTest.kt`:

```kotlin
package com.eried.eucplanet.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomEngineSoundsTest {

    @Test fun `slots round-trip through json`() {
        val slots = mapOf(CustomSlot.IDLE to "content://a", CustomSlot.REV to "content://b")
        val json = CustomEngineSounds.encodeSlots(slots)
        assertEquals(slots, CustomEngineSounds.parseSlots(json))
    }

    @Test fun `parse tolerates blank and malformed json`() {
        assertTrue(CustomEngineSounds.parseSlots("").isEmpty())
        assertTrue(CustomEngineSounds.parseSlots("not json").isEmpty())
    }

    @Test fun `status is required when main missing, empty-optional for others`() {
        val canOpen = { _: String -> true }
        assertEquals(SlotStatus.REQUIRED, CustomEngineSounds.statusFor(CustomSlot.IDLE, null, canOpen))
        assertEquals(SlotStatus.EMPTY_OPTIONAL, CustomEngineSounds.statusFor(CustomSlot.REV, "", canOpen))
    }

    @Test fun `status reflects whether the uri opens`() {
        assertEquals(SlotStatus.OK, CustomEngineSounds.statusFor(CustomSlot.IDLE, "content://a") { true })
        assertEquals(SlotStatus.MISSING, CustomEngineSounds.statusFor(CustomSlot.IDLE, "content://a") { false })
    }

    @Test fun `single-file mode builds an idle-only pitched profile`() {
        val slots = mapOf(CustomSlot.IDLE to "content://a", CustomSlot.REV to "content://b")
        val p = CustomEngineSounds.buildProfile(slots, modulatePitch = true)
        assertEquals(EngineProfile.CUSTOM_KEY, p.key)
        assertTrue(p.pitchModulated)
        assertEquals(setOf(CustomSlot.IDLE), p.sampleSections!!.keys)
        assertEquals("content://a", p.sampleSections!![CustomSlot.IDLE]!!.uri)
    }

    @Test fun `multi mode keeps every provided slot and is not pitched`() {
        val slots = mapOf(
            CustomSlot.IDLE to "content://a",
            CustomSlot.REV to "content://b",
            CustomSlot.SHUTDOWN to "content://c",
        )
        val p = CustomEngineSounds.buildProfile(slots, modulatePitch = false)
        assertTrue(!p.pitchModulated)
        assertEquals(setOf(CustomSlot.IDLE, CustomSlot.REV, CustomSlot.SHUTDOWN), p.sampleSections!!.keys)
    }

    @Test fun `no main file yields an empty (silent) section map`() {
        val p = CustomEngineSounds.buildProfile(emptyMap(), modulatePitch = true)
        assertTrue(p.sampleSections!!.isEmpty())
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.CustomEngineSoundsTest"`
Expected: FAIL to compile (unresolved `CustomSlot` / `CustomEngineSounds` / `SlotStatus`).

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/eried/eucplanet/audio/CustomEngineSounds.kt`:

```kotlin
package com.eried.eucplanet.audio

import org.json.JSONObject

/** Slot keys for a custom engine. These mirror CompositionEnginePlayer section names. */
object CustomSlot {
    const val IDLE = "idle_loop"       // required main / default clip
    const val REV = "rev_loop"         // optional high-speed loop
    const val STARTUP = "startup"      // optional one-shot on engine start
    const val DECEL = "decel"          // optional one-shot pop / backfire on throttle close
    const val SHUTDOWN = "shutdown"    // optional one-shot on engine stop

    /** Editor order. IDLE first (required), then the optional one-shots/loops. */
    val ALL = listOf(IDLE, REV, STARTUP, DECEL, SHUTDOWN)
    val OPTIONAL = listOf(REV, STARTUP, DECEL, SHUTDOWN)
}

/** UI status for a single slot's file reference. */
enum class SlotStatus { OK, REQUIRED, MISSING, EMPTY_OPTIONAL }

/**
 * Pure helpers for the rider's custom engine: (de)serialize the slot->URI map,
 * compute per-slot status, and build the synthetic [EngineProfile] the audio
 * engine plays. No Android or IO dependencies, so it is fully unit-tested.
 */
object CustomEngineSounds {

    fun parseSlots(json: String): Map<String, String> {
        if (json.isBlank()) return emptyMap()
        return try {
            val obj = JSONObject(json)
            buildMap {
                for (key in obj.keys()) {
                    val v = obj.optString(key, "")
                    if (v.isNotBlank()) put(key, v)
                }
            }
        } catch (_: Throwable) {
            emptyMap()
        }
    }

    fun encodeSlots(slots: Map<String, String>): String {
        val obj = JSONObject()
        for ((k, v) in slots) if (v.isNotBlank()) obj.put(k, v)
        return obj.toString()
    }

    /** [canOpen] answers whether a URI still resolves (injected so this stays pure). */
    fun statusFor(slot: String, uri: String?, canOpen: (String) -> Boolean): SlotStatus {
        if (uri.isNullOrBlank()) {
            return if (slot == CustomSlot.IDLE) SlotStatus.REQUIRED else SlotStatus.EMPTY_OPTIONAL
        }
        return if (canOpen(uri)) SlotStatus.OK else SlotStatus.MISSING
    }

    /**
     * Build the synthetic custom profile. In single-file mode only the main
     * (idle) clip is used, pitch-shifted by speed. In multi mode every provided
     * slot becomes a URI-backed section. An empty map yields an empty (silent)
     * section set, which the composition player renders as no sound.
     */
    fun buildProfile(slots: Map<String, String>, modulatePitch: Boolean): EngineProfile {
        val effective = if (modulatePitch) slots.filterKeys { it == CustomSlot.IDLE } else slots
        val sections = buildMap {
            for ((slot, uri) in effective) {
                if (uri.isNotBlank()) put(slot, SampleSection(rawAsset = "", startMs = 0, endMs = 0, uri = uri))
            }
        }
        return EngineProfile(
            key = EngineProfile.CUSTOM_KEY,
            displayName = "Custom",
            kind = EngineProfile.Kind.ICE,
            gearless = true,
            idleRpm = 700,
            maxRpm = 6000,
            sampleSections = sections,
            pitchModulated = modulatePitch,
            supportsMuffler = false,
            supportsBrakeWhine = false,
            supportsPops = false,
        )
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.audio.CustomEngineSoundsTest"`
Expected: PASS (`BUILD SUCCESSFUL`).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/CustomEngineSounds.kt app/src/test/java/com/eried/eucplanet/audio/CustomEngineSoundsTest.kt
git commit -m "feat(engine): custom-sound slots model, status, and profile builder"
```

---

### Task 3: Persist the two new settings fields

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt:695` (after `engineHeadphonesOnly`)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt:228` (serialize) and `:492` (deserialize)
- Test: `app/src/test/java/com/eried/eucplanet/data/store/SettingsJsonCustomEngineTest.kt` (create)

**Interfaces:**
- Produces: `AppSettings.engineCustomModulatePitch: Boolean = true`, `AppSettings.engineCustomSounds: String = ""`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/eried/eucplanet/data/store/SettingsJsonCustomEngineTest.kt`:

```kotlin
package com.eried.eucplanet.data.store

import com.eried.eucplanet.data.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsJsonCustomEngineTest {
    @Test fun `custom engine fields survive a json round-trip`() {
        val s = AppSettings(
            engineType = "CUSTOM",
            engineCustomModulatePitch = false,
            engineCustomSounds = """{"idle_loop":"content://a"}""",
        )
        val restored = SettingsJson.fromJson(SettingsJson.toJson(s), AppSettings())
        assertEquals("CUSTOM", restored.engineType)
        assertEquals(false, restored.engineCustomModulatePitch)
        assertEquals("""{"idle_loop":"content://a"}""", restored.engineCustomSounds)
    }

    @Test fun `defaults apply when fields absent`() {
        val restored = SettingsJson.fromJson("{}", AppSettings())
        assertEquals(true, restored.engineCustomModulatePitch)
        assertEquals("", restored.engineCustomSounds)
    }
}
```

Note: confirm the exact `SettingsJson` entry points by reading the top of `SettingsJson.kt`; if the functions are named differently than `toJson`/`fromJson`, adjust the two calls above to match (they wrap the `put(...)` block at line 217 and the `AppSettings(...)` block at line 481).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.store.SettingsJsonCustomEngineTest"`
Expected: FAIL to compile (`engineCustomModulatePitch` unresolved).

- [ ] **Step 3: Add the fields to AppSettings**

In `AppSettings.kt`, immediately after `val engineHeadphonesOnly: Boolean = false,` (line 695) add:

```kotlin
    /** Custom engine: when true, the single main clip is looped and pitch-shifted by speed. */
    val engineCustomModulatePitch: Boolean = true,
    /** Custom engine: JSON object mapping slot keys (idle_loop, rev_loop, startup, decel, shutdown) to content URI strings. Empty = nothing picked. */
    val engineCustomSounds: String = "",
```

- [ ] **Step 4: Serialize both fields**

In `SettingsJson.kt`, after `put("engineHeadphonesOnly", s.engineHeadphonesOnly)` (line 228) add:

```kotlin
        put("engineCustomModulatePitch", s.engineCustomModulatePitch)
        put("engineCustomSounds", s.engineCustomSounds)
```

- [ ] **Step 5: Deserialize both fields**

In `SettingsJson.kt`, after `engineHeadphonesOnly = j.optBoolean("engineHeadphonesOnly", base.engineHeadphonesOnly),` (line 492) add:

```kotlin
        engineCustomModulatePitch = j.optBoolean("engineCustomModulatePitch", base.engineCustomModulatePitch),
        engineCustomSounds = j.optString("engineCustomSounds", base.engineCustomSounds),
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests "com.eried.eucplanet.data.store.SettingsJsonCustomEngineTest"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/data/model/AppSettings.kt app/src/main/java/com/eried/eucplanet/data/store/SettingsJson.kt app/src/test/java/com/eried/eucplanet/data/store/SettingsJsonCustomEngineTest.kt
git commit -m "feat(settings): persist custom engine pitch toggle and slot map"
```

---

### Task 4: Play custom URIs in the composition player

Teach `SectionPlayer` to load a `content://` URI (whole file, no clipping) and to signal completion for one-shots (URI clips have unknown length, so the fixed-duration cleanup timer would truncate them).

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt` (`SectionPlayer.prepare` ~198-229; `fireOneShot` ~149-159; imports ~3-19)

**Interfaces:**
- Consumes: `SampleSection.uri` / `SampleSection.isCustom` (Task 1).
- Produces: no new public surface; behavior change only.

- [ ] **Step 1: Add the media3 default datasource import**

At the top of `CompositionEnginePlayer.kt`, with the other `androidx.media3.datasource` imports (line ~14), add:

```kotlin
import androidx.media3.datasource.DefaultDataSource
```

- [ ] **Step 2: Branch `prepare()` on custom vs raw**

Replace the body of `SectionPlayer.prepare()` (lines ~198-229) with:

```kotlin
    fun prepare(): Boolean {
        val mediaSource = if (section.isCustom) {
            val factory = DataSource.Factory { DefaultDataSource.Factory(context).createDataSource() }
            ProgressiveMediaSource.Factory(factory)
                .createMediaSource(MediaItem.fromUri(Uri.parse(section.uri)))
        } else {
            val resId = context.resources.getIdentifier(section.rawAsset, "raw", context.packageName)
            if (resId == 0) {
                Log.w("SectionPlayer", "raw/${section.rawAsset} not found")
                return false
            }
            val uri = RawResourceDataSource.buildRawResourceUri(resId)
            val factory = DataSource.Factory { RawResourceDataSource(context) }
            ProgressiveMediaSource.Factory(factory).createMediaSource(MediaItem.fromUri(uri))
        }
        return try {
            postToMain {
                if (released) return@postToMain
                // Raw sections clip to [startMs..endMs]; custom URIs play the whole file.
                val source = if (section.isCustom) mediaSource
                    else ClippingMediaSource(mediaSource, section.startMs * 1000L, section.endMs * 1000L)
                player.setMediaSource(source)
                player.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                if (!looping) {
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) onEnded?.invoke()
                        }
                    })
                }
                player.volume = 0f
                player.prepare()
            }
            true
        } catch (e: Throwable) {
            Log.e("SectionPlayer", "prepare failed for ${if (section.isCustom) section.uri else section.rawAsset}", e)
            false
        }
    }
```

- [ ] **Step 3: Add an `onEnded` callback field + setter to SectionPlayer**

Do NOT add a constructor parameter (the cleanup closure needs the instance to
exist first). Inside the `SectionPlayer` class body, near the other `@Volatile`
fields (line ~191), add:

```kotlin
    @Volatile private var onEnded: (() -> Unit)? = null
    fun setOnEnded(cb: () -> Unit) { onEnded = cb }
```

The non-looping `Player.Listener` added in `prepare()` (Step 2) already calls
`onEnded?.invoke()` on `Player.STATE_ENDED`, reading this field.

- [ ] **Step 4: Use completion for custom one-shots**

Replace `fireOneShot` (lines ~149-159) with:

```kotlin
    private fun fireOneShot(section: SampleSection, gain: Float) {
        val sp = SectionPlayer(context, section, looping = false)
        val cleanup = Runnable {
            synchronized(oneShots) { oneShots.remove(sp) }
            sp.release()
        }
        // Custom URIs have unknown length: release on natural end (STATE_ENDED),
        // with a 15s backstop if a decode error means it never fires. Raw one-shots
        // keep the duration timer (their length is known from startMs/endMs).
        if (section.isCustom) sp.setOnEnded { mainHandler.post(cleanup) }
        if (!sp.prepare()) { sp.release(); return }
        sp.setVolume(gain.coerceIn(0f, 1f))
        sp.play()
        synchronized(oneShots) { oneShots.add(sp) }
        mainHandler.postDelayed(cleanup, if (section.isCustom) 15_000L else section.durationMs + 200L)
    }
```

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. (No unit test: ExoPlayer needs a device. Manual verification happens in Task 10 on the emulator.)

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt
git commit -m "feat(engine): play custom content URIs in the composition player"
```

---

### Task 5: Pitch-shift the single custom clip by speed

When a custom profile is `pitchModulated` and has no `rev_loop`, drive the idle section's playback speed from RPM (the single-file behavior), like `SampledEnginePlayer`.

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt` (`update` ~119-143)

**Interfaces:**
- Consumes: `EngineProfile.pitchModulated` (Task 1). Uses existing `SectionPlayer.setSpeed`.

- [ ] **Step 1: Track last speed**

In `CompositionEnginePlayer`, next to the other `@Volatile` state (line ~52), confirm `lastSpeed` exists (it does). No change.

- [ ] **Step 2: Add the pitch branch to `update()`**

At the end of `update()` (after the idle/rev volume block, before the closing brace at line ~143), add:

```kotlin
        // Single-file custom mode: no rev loop, so convey speed by pitching idle.
        // Built-in sampled profiles set pitchModulated=false and skip this.
        if (profile?.pitchModulated == true && rev == null) {
            val curved = lastRpmNorm.let { it * (0.4f + 0.6f * it) }
            val targetSpeed = 0.6f + 1.2f * curved   // 0.6x..1.8x, matches SampledEnginePlayer
            if (kotlin.math.abs(targetSpeed - lastSpeed) > 0.01f) {
                idle?.setSpeed(targetSpeed)
                lastSpeed = targetSpeed
            }
        }
```

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/CompositionEnginePlayer.kt
git commit -m "feat(engine): pitch-shift single custom clip by speed"
```

---

### Task 6: Route the CUSTOM key to a synthetic profile

`applySettings` and `previewProfile` currently call `EngineProfile.byKey`, which cannot represent the custom engine. Build and cache the custom profile from settings, resolve `"CUSTOM"` to it everywhere, and stay silent when no main file is set.

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/audio/EngineSoundEngine.kt` (`applySettings` ~102-105; `previewProfile` ~200-205; add field + helper)

**Interfaces:**
- Consumes: `CustomEngineSounds.parseSlots`, `CustomEngineSounds.buildProfile` (Task 2); `AppSettings.engineCustomSounds`, `engineCustomModulatePitch` (Task 3).

- [ ] **Step 1: Add a cache field + resolver**

In `EngineSoundEngine`, near the other private mutable state (around line 96), add:

```kotlin
    /** Last-built synthetic custom profile, refreshed on every applySettings. */
    private var customProfile: EngineProfile =
        com.eried.eucplanet.audio.CustomEngineSounds.buildProfile(emptyMap(), modulatePitch = true)

    private fun resolveProfile(key: String): EngineProfile =
        if (key == EngineProfile.CUSTOM_KEY) customProfile else EngineProfile.byKey(key)
```

- [ ] **Step 2: Build the custom profile in applySettings**

In `applySettings` (lines 102-105), replace:

```kotlin
        profile = EngineProfile.byKey(s.engineType)
```

with:

```kotlin
        customProfile = com.eried.eucplanet.audio.CustomEngineSounds.buildProfile(
            slots = com.eried.eucplanet.audio.CustomEngineSounds.parseSlots(s.engineCustomSounds),
            modulatePitch = s.engineCustomModulatePitch,
        )
        profile = resolveProfile(s.engineType)
```

- [ ] **Step 3: Resolve custom in previewProfile**

In `previewProfile` (line ~205), replace:

```kotlin
        profile = EngineProfile.byKey(key)
```

with:

```kotlin
        profile = resolveProfile(key)
```

- [ ] **Step 4: Verify the empty-custom case is silent**

Read `start()` (lines 485-508). Confirm that a custom profile with an **empty** `sampleSections` map routes into the `profile.sampleSections != null` branch and that `CompositionEnginePlayer.start` renders nothing when `idle_loop` is absent (it does: `sections["idle_loop"]?.let { ... }`). No code change; this step is a read-and-confirm. If `sampleSections` were ever null for custom, `start()` would fall through to the synth path, which must not happen. `buildProfile` always returns a non-null (possibly empty) map, so the branch is guaranteed.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/audio/EngineSoundEngine.kt
git commit -m "feat(engine): resolve CUSTOM key to a runtime-built profile"
```

---

### Task 7: ViewModel setters for the custom fields

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt` (after `updateEngineHeadphonesOnly` line 394)

**Interfaces:**
- Consumes: `CustomEngineSounds.parseSlots` / `encodeSlots` (Task 2); `update {}` helper.
- Produces: `updateEngineCustomModulatePitch(Boolean)`, `updateEngineCustomSlot(slot: String, uri: String?)`.

- [ ] **Step 1: Add the setters**

After line 394 (`fun updateEngineHeadphonesOnly...`) add:

```kotlin
    fun updateEngineCustomModulatePitch(v: Boolean) = update { copy(engineCustomModulatePitch = v) }

    /** Set or clear (uri == null/blank) the file for one custom slot. */
    fun updateEngineCustomSlot(slot: String, uri: String?) = update {
        val slots = com.eried.eucplanet.audio.CustomEngineSounds.parseSlots(engineCustomSounds).toMutableMap()
        if (uri.isNullOrBlank()) slots.remove(slot) else slots[slot] = uri
        copy(engineCustomSounds = com.eried.eucplanet.audio.CustomEngineSounds.encodeSlots(slots))
    }
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/settings/SettingsViewModel.kt
git commit -m "feat(settings): view-model setters for custom engine sounds"
```

---

### Task 8: Localized strings

**Files:**
- Modify: `app/src/main/res/values/strings.xml` (near the other `engine_*` strings, ~844-846)

**Interfaces:**
- Produces: string resources consumed by Tasks 9-10.

- [ ] **Step 1: Add the strings**

Add near the existing `engine_source_sampled` / `engine_source_synth` entries:

```xml
    <string name="engine_source_custom">custom</string>
    <string name="engine_preset_custom">Custom</string>
    <string name="engine_custom_pick">Pick file</string>
    <string name="engine_custom_clear">Clear</string>
    <string name="engine_custom_modulate_pitch">Single file, modulate pitch with speed</string>
    <string name="engine_custom_modulate_pitch_hint">One clip, looped. Its pitch rises as you speed up.</string>
    <string name="engine_custom_section_header">Section sounds</string>
    <string name="engine_custom_slot_idle">Main sound</string>
    <string name="engine_custom_slot_idle_multi">Main sound (idle)</string>
    <string name="engine_custom_slot_rev">Rev (high speed)</string>
    <string name="engine_custom_slot_startup">Startup</string>
    <string name="engine_custom_slot_decel">Decel / pop</string>
    <string name="engine_custom_slot_shutdown">Shutdown</string>
    <string name="engine_custom_no_file">No file</string>
    <string name="engine_custom_no_file_optional">No file, optional</string>
    <string name="engine_custom_status_ok">Loaded</string>
    <string name="engine_custom_status_required">Required, pick a sound file</string>
    <string name="engine_custom_status_missing">File not found, re-pick</string>
    <string name="engine_custom_required_tag">required</string>
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL` (R regenerates).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/res/values/strings.xml
git commit -m "feat(settings): strings for the custom engine editor (en)"
```

Note: other-locale `strings.xml` files should get the same keys; leaving English fallbacks in place is acceptable for this branch per existing project practice, but list them for the translator.

---

### Task 9: Add "Custom" to the engine picker

Surface a `Custom` entry at the end of the dropdown, and resolve the current profile / source label / icon for the `CUSTOM` key (which is not in `PROFILES`).

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt` (`EngineTypePicker` 8655-8790; the `currentProfile` resolve in `EngineSoundSection` ~8528)

**Interfaces:**
- Consumes: `EngineProfile.CUSTOM_KEY`; `updateEngineType`; string `engine_preset_custom` / `engine_source_custom`.

- [ ] **Step 1: Make `displayFor` and source label handle CUSTOM**

In `EngineTypePicker`, update `displayFor` (lines 8664-8669) so `"CUSTOM"` resolves via the string table (it already will, since `engine_preset_custom` exists). No code change needed, but confirm `sourceLabel` (lines 8681-8684) falls back correctly: replace lines 8680-8684 with:

```kotlin
    val currentProfile = profiles.firstOrNull { it.key == currentKey }
    val isCustomKey = currentKey == com.eried.eucplanet.audio.EngineProfile.CUSTOM_KEY
    val sourceLabel = when {
        isCustomKey -> stringResource(R.string.engine_source_custom)
        currentProfile?.sampleAssetBase != null -> stringResource(R.string.engine_source_sampled)
        else -> stringResource(R.string.engine_source_synth)
    }
```

- [ ] **Step 2: Show the folder icon for the CUSTOM value**

In the `OutlinedTextField` `leadingIcon` (lines 8707-8728), change the `isSampled` computation and icon so custom shows a folder. Replace line 8707:

```kotlin
            val isSampled = currentProfile?.sampleAssetBase != null || isCustomKey
```

and inside the `if (isSampled) { Icon(painter = painterResource(R.drawable.ic_vital_signs) ...) }` block, prefer a folder glyph for custom. Simplest, keep the existing sampled icon for custom too (visually "not synth"); no further change required. (If a distinct folder icon is desired, use `Icons.Filled.FolderOpen` gated on `isCustomKey`.)

- [ ] **Step 3: Append the Custom item to the menu**

After the `profiles.forEach { p -> ... }` block closes inside `ExposedDropdownMenu` (just before the menu's closing brace, around line 8785), add a dedicated Custom entry:

```kotlin
                DropdownMenuItem(
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.engine_preset_custom),
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.engine_source_custom),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        onSelect(com.eried.eucplanet.audio.EngineProfile.CUSTOM_KEY)
                        expanded = false
                    }
                )
```

Add the import `import androidx.compose.material.icons.filled.FolderOpen` at the top of the file if not present.

- [ ] **Step 4: Resolve `currentProfile` for CUSTOM in EngineSoundSection**

Replace lines 8528-8530 with:

```kotlin
        val currentProfile = remember(settings.engineType, settings.engineCustomSounds, settings.engineCustomModulatePitch) {
            if (settings.engineType == com.eried.eucplanet.audio.EngineProfile.CUSTOM_KEY)
                com.eried.eucplanet.audio.CustomEngineSounds.buildProfile(
                    com.eried.eucplanet.audio.CustomEngineSounds.parseSlots(settings.engineCustomSounds),
                    settings.engineCustomModulatePitch
                )
            else com.eried.eucplanet.audio.EngineProfile.byKey(settings.engineType)
        }
```

This makes the muffler/gearbox/pops rows (which check `currentProfile.supports*`) hide for custom, matching other sampled engines.

- [ ] **Step 5: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): add Custom entry to the engine picker"
```

---

### Task 10: Custom editor UI (file pickers + status) and wire-in

The editor composable: main file row + pitch checkbox + section rows (shown when pitch is off), each with a SAF picker, filename, clear, and status chip.

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt` (add `CustomEngineEditor` composable; call it in `EngineSoundSection` after the `EngineTypePicker(...)` block, ~8523)

**Interfaces:**
- Consumes: `CustomSlot`, `SlotStatus`, `CustomEngineSounds` (Task 2); `updateEngineCustomModulatePitch`, `updateEngineCustomSlot` (Task 7); strings (Task 8).

- [ ] **Step 1: Call the editor from EngineSoundSection**

Immediately after the `EngineTypePicker(...)` call (after line 8523) add:

```kotlin
        if (settings.engineType == com.eried.eucplanet.audio.EngineProfile.CUSTOM_KEY) {
            CustomEngineEditor(settings = settings, viewModel = viewModel, previewEnabled = parked)
        }
```

- [ ] **Step 2: Add the editor composable**

Add this composable near `EngineTypePicker` in `SettingsScreen.kt`:

```kotlin
@Composable
private fun CustomEngineEditor(
    settings: com.eried.eucplanet.data.model.AppSettings,
    viewModel: SettingsViewModel,
    previewEnabled: Boolean,
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val slots = remember(settings.engineCustomSounds) {
        com.eried.eucplanet.audio.CustomEngineSounds.parseSlots(settings.engineCustomSounds)
    }
    val modulate = settings.engineCustomModulatePitch

    // Which slot a launched picker should fill. Set right before launch().
    var pendingSlot by remember { mutableStateOf(com.eried.eucplanet.audio.CustomSlot.IDLE) }
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            try {
                ctx.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) { /* some providers don't grant persistable access */ }
            viewModel.updateEngineCustomSlot(pendingSlot, uri.toString())
        }
    }
    fun launchPick(slot: String) { pendingSlot = slot; picker.launch(arrayOf("audio/*")) }

    // Probe each URI once per change to decide OK vs MISSING.
    fun canOpen(uri: String): Boolean = try {
        ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use { true } ?: false
    } catch (_: Throwable) { false }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.appColors.menuBackground)
            .padding(vertical = 4.dp)
    ) {
        // Main sound (idle)
        CustomSlotRow(
            label = stringResource(
                if (modulate) R.string.engine_custom_slot_idle else R.string.engine_custom_slot_idle_multi
            ),
            required = true,
            uri = slots[com.eried.eucplanet.audio.CustomSlot.IDLE],
            status = com.eried.eucplanet.audio.CustomEngineSounds.statusFor(
                com.eried.eucplanet.audio.CustomSlot.IDLE, slots[com.eried.eucplanet.audio.CustomSlot.IDLE], ::canOpen
            ),
            onPick = { launchPick(com.eried.eucplanet.audio.CustomSlot.IDLE) },
            onClear = { viewModel.updateEngineCustomSlot(com.eried.eucplanet.audio.CustomSlot.IDLE, null) },
        )

        // Pitch toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { viewModel.updateEngineCustomModulatePitch(!modulate) }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Top
        ) {
            Checkbox(checked = modulate, onCheckedChange = { viewModel.updateEngineCustomModulatePitch(it) })
            Spacer(Modifier.width(8.dp))
            Column {
                Text(stringResource(R.string.engine_custom_modulate_pitch), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.engine_custom_modulate_pitch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Section slots (only in multi mode)
        if (!modulate) {
            Text(
                stringResource(R.string.engine_custom_section_header),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 2.dp)
            )
            val optional = listOf(
                com.eried.eucplanet.audio.CustomSlot.REV to R.string.engine_custom_slot_rev,
                com.eried.eucplanet.audio.CustomSlot.STARTUP to R.string.engine_custom_slot_startup,
                com.eried.eucplanet.audio.CustomSlot.DECEL to R.string.engine_custom_slot_decel,
                com.eried.eucplanet.audio.CustomSlot.SHUTDOWN to R.string.engine_custom_slot_shutdown,
            )
            optional.forEach { (slot, labelRes) ->
                CustomSlotRow(
                    label = stringResource(labelRes),
                    required = false,
                    uri = slots[slot],
                    status = com.eried.eucplanet.audio.CustomEngineSounds.statusFor(slot, slots[slot], ::canOpen),
                    onPick = { launchPick(slot) },
                    onClear = { viewModel.updateEngineCustomSlot(slot, null) },
                )
            }
        }

        // Preview real config
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayButton(
                onClick = { viewModel.previewEngine(com.eried.eucplanet.audio.EngineProfile.CUSTOM_KEY) },
                enabled = previewEnabled && slots.containsKey(com.eried.eucplanet.audio.CustomSlot.IDLE)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.engine_type_label), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun CustomSlotRow(
    label: String,
    required: Boolean,
    uri: String?,
    status: com.eried.eucplanet.audio.SlotStatus,
    onPick: () -> Unit,
    onClear: () -> Unit,
) {
    val fileName = remember(uri) { uri?.substringAfterLast('/')?.substringBefore('?') }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                    if (required) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.engine_custom_required_tag),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                Text(
                    fileName ?: stringResource(
                        if (required) R.string.engine_custom_no_file else R.string.engine_custom_no_file_optional
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
            if (uri == null) {
                OutlinedButton(onClick = onPick, shape = RoundedCornerShape(50)) {
                    Text(stringResource(R.string.engine_custom_pick))
                }
            } else {
                IconButton(onClick = onClear) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.engine_custom_clear))
                }
            }
        }
        val chip = when (status) {
            com.eried.eucplanet.audio.SlotStatus.OK -> stringResource(R.string.engine_custom_status_ok) to MaterialTheme.colorScheme.primary
            com.eried.eucplanet.audio.SlotStatus.REQUIRED -> stringResource(R.string.engine_custom_status_required) to MaterialTheme.colorScheme.tertiary
            com.eried.eucplanet.audio.SlotStatus.MISSING -> stringResource(R.string.engine_custom_status_missing) to MaterialTheme.colorScheme.error
            com.eried.eucplanet.audio.SlotStatus.EMPTY_OPTIONAL -> null to MaterialTheme.colorScheme.onSurfaceVariant
        }
        chip.first?.let { text ->
            Text(text, style = MaterialTheme.typography.labelSmall, color = chip.second, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
```

Add any missing imports at the top of `SettingsScreen.kt`: `androidx.activity.compose.rememberLauncherForActivityResult`, `androidx.activity.result.contract.ActivityResultContracts`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.background`, `androidx.compose.ui.draw.clip`, `androidx.compose.material3.Checkbox`, `androidx.compose.material3.OutlinedButton`, `androidx.compose.material3.IconButton`, `androidx.compose.material.icons.filled.Close`. (Most are already imported in this large file; add only what the compiler reports missing.)

- [ ] **Step 3: Verify it compiles**

Run: `./gradlew :app:compileDebugKotlin`
Expected: `BUILD SUCCESSFUL`. Fix any missing-import errors reported.

- [ ] **Step 4: Full build**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Manual/emulator verification (real screenshots)**

Install on an emulator/device, open Settings, enable Engine sound, choose Custom, and verify against the approved mockups:
- Empty state shows "Required, pick a sound file" and stays on Custom.
- Picking a main file, checkbox on: Preview plays the looped, pitch-rising clip.
- Uncheck pitch: section rows appear under "Section sounds"; filling rev/startup/decel/shutdown and previewing crossfades and fires the one-shots.
- Point a slot at a file, then revoke it (or pick from a temporary provider) to see "File not found, re-pick".
Capture screenshots for the PR before pushing (per the reviewer's request).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/settings/SettingsScreen.kt
git commit -m "feat(settings): custom engine editor with SAF pickers and status"
```

---

## Self-Review

**Spec coverage:**
- Custom entry in picker -> Task 9. Editor with main file + pitch checkbox + section slots -> Task 10. SAF referenced URIs + persistable grant -> Task 10 step 2. Per-slot status -> Task 2 (`statusFor`) + Task 10. Preview plays real config -> Task 10 (previewEngine CUSTOM). Empty state stays silent, no auto-revert, no preset fallback -> Task 6 step 4 + Task 2 (empty section map). Data model 2 fields + persistence -> Task 3. URI player + whole-file + one-shot completion -> Task 4. Pitch-when-single -> Task 5. Capability flags false -> Task 2 `buildProfile`. Strings -> Task 8. Section keys reuse composition names -> Task 2 `CustomSlot`.
- Slot set note: the spec listed a separate "pops" slot; in the composition model the `decel` one-shot **is** the pop/backfire, so the editor exposes "Decel / pop" and no separate pops slot. This matches the audio engine (no SoundPool needed for custom). Documented here as the single intentional simplification.

**Placeholder scan:** none - every step has concrete code or an explicit read-and-confirm.

**Type consistency:** `CUSTOM_KEY` used consistently (Tasks 1,2,6,9,10). `CustomSlot.*` constants used in Tasks 2,7,10. `SlotStatus` values (OK/REQUIRED/MISSING/EMPTY_OPTIONAL) consistent (Tasks 2,10). `buildProfile(slots, modulatePitch)`, `parseSlots`, `encodeSlots`, `statusFor(slot, uri, canOpen)` signatures identical across producer (Task 2) and consumers (Tasks 6,7,9,10). `updateEngineCustomSlot(slot, uri)` / `updateEngineCustomModulatePitch(v)` consistent (Tasks 7,10).

## Notes for the implementer
- The `SettingsJson` entry-point names in Task 3's test (`toJson`/`fromJson`) are assumed; open `SettingsJson.kt` and match the real function names before running.
- `SettingsScreen.kt` is very large; add new composables next to `EngineTypePicker` and rely on the compiler to flag any missing imports rather than guessing.
- Keep the V10F BLE fix (already committed) in a separate PR from this feature per the reviewer's preference.
