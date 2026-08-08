# Overlay Studio Replay-Mode Pane Faces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give every studio pane an independent live face and replay face, add Transparent (default) and Video replay sources, enable panes in replay, and composite them under the overlay on export.

**Architecture:** Additive. `ViewportConfig` stays the live face and gains an optional nested `replay: ViewportReplayFace`. The replay renderer stops skipping panes and draws the replay face; a new `MediaMetadataRetriever`-backed `StudioVideoHub` supplies video frames seeked to the replay cursor (real-time + offset). The offscreen export renders the viewport layer (replay faces) under the element layer. HUD ignores the replay face.

**Tech Stack:** Kotlin, Jetpack Compose, `hud-protocol` `OverlayLayout` model, Camera2 `StudioCameraHub` (pattern to mirror), `MediaMetadataRetriever`, `StudioOffscreenSession` export renderer, JUnit (local unit tests).

## Global Constraints

- No em-dashes anywhere (UI strings, comments, commit text).
- UI color via `MaterialTheme.appColors.*`; never hardcode `Color(...)` in feature UI.
- Every new user-facing string lives in `app/src/main/res/values/strings.xml` and is translated to all 18 locales (`values-*`).
- Video files referenced by a persistable SAF URI string, never inlined into the preset.
- Replay/video face is phone-only; HUD must ignore `ViewportConfig.replay`. New editor controls the HUD can't do are badged `notOnHud` (as Panes/Image are).
- Back-compat: a preset with no `replay` field loads as Transparent (today's replay behavior); the live render path is unchanged.
- Verify builds by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED` (never mask exit code). Emulator = `emulator-5556`.
- **Commits are LOCAL only. Do NOT `git push`** - the user tests the build first (see the memory `eucplanet-push-after-user-test`).
- Colour-grade / zoom are NOT applied to replay faces in v1 (fit only).

---

## File map

- `hud-protocol/.../OverlayLayout.kt` - add `ReplaySourceType`, `ViewportReplayFace`, `ViewportConfig.replay`. (model)
- `app/.../data/store/OverlayPresetJson.kt` - serialize/deserialize the replay face. (persistence)
- `app/.../ui/studio/StudioVideoSync.kt` (new) - pure `videoTimeUsFor(...)` sync math. (logic)
- `app/.../ui/studio/camera/StudioVideoHub.kt` (new) - retriever pool, frame cache, invalid flag. (decode)
- `app/.../ui/studio/StudioViewports.kt` - render replay face instead of skipping panes. (render)
- `app/.../ui/studio/StudioConfigSheets.kt` - mode-aware `ViewportConfigSheet` + video sub-panel. (editor)
- `app/.../ui/studio/OverlayStudioScreen.kt` - un-gate panes in replay, bind the video hub, wire export. (integration)
- `app/.../ui/studio/recording/StudioOffscreenSession.kt` - render viewport layer (replay faces) in export. (export)
- `app/src/main/res/values/strings.xml` (+ `values-*`) - new strings. (i18n)
- `app/src/test/java/com/eried/eucplanet/data/OverlayPresetJsonReplayTest.kt` (new) - round-trip. (test)
- `app/src/test/java/com/eried/eucplanet/ui/studio/StudioVideoSyncTest.kt` (new) - sync math. (test)

---

## Task 1: Data model + persistence for the replay face

**Files:**
- Modify: `hud-protocol/src/main/java/com/eried/eucplanet/hud/protocol/OverlayLayout.kt` (after `ViewportConfig`, ends line 88)
- Modify: `app/src/main/java/com/eried/eucplanet/data/store/OverlayPresetJson.kt` (viewport write ~31-56, `viewportFromJson` ~87-117)
- Test: `app/src/test/java/com/eried/eucplanet/data/OverlayPresetJsonReplayTest.kt` (create)

**Interfaces produced:**
- `enum class ReplaySourceType { TRANSPARENT, VIDEO, SOLID, GRADIENT, IMAGE }`
- `data class ViewportReplayFace(source, solidColor, gradientColors, gradientStops, gradientAngle, gradientRadial, imageData, videoUri, videoFit, videoOffsetMs)`
- `ViewportConfig.replay: ViewportReplayFace? = null`

- [ ] **Step 1: Write the failing round-trip test**

Create `app/src/test/java/com/eried/eucplanet/data/OverlayPresetJsonReplayTest.kt`:

```kotlin
package com.eried.eucplanet.data

import com.eried.eucplanet.data.store.OverlayPresetJson
import com.eried.eucplanet.hud.protocol.OverlayPreset
import com.eried.eucplanet.hud.protocol.ReplaySourceType
import com.eried.eucplanet.hud.protocol.ViewportConfig
import com.eried.eucplanet.hud.protocol.ViewportLayout
import com.eried.eucplanet.hud.protocol.ViewportReplayFace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OverlayPresetJsonReplayTest {

    @Test fun videoReplayFace_roundTrips() {
        val preset = OverlayPreset(
            layout = ViewportLayout.COLUMNS_2,
            viewports = listOf(
                ViewportConfig(), // pane 0: camera live, no replay -> transparent
                ViewportConfig(
                    replay = ViewportReplayFace(
                        source = ReplaySourceType.VIDEO,
                        videoUri = "content://x/y/clip.mp4",
                        videoFit = "STRETCH",
                        videoOffsetMs = 4200L,
                    )
                ),
            ),
        )
        val loaded = OverlayPresetJson.fromJson(OverlayPresetJson.toJson(preset))
        assertNull(loaded.viewports[0].replay)
        val r = loaded.viewports[1].replay!!
        assertEquals(ReplaySourceType.VIDEO, r.source)
        assertEquals("content://x/y/clip.mp4", r.videoUri)
        assertEquals("STRETCH", r.videoFit)
        assertEquals(4200L, r.videoOffsetMs)
    }

    @Test fun missingReplay_loadsAsNull_transparentByDefault() {
        // A preset written before this feature has no "replay" key.
        val json = OverlayPresetJson.toJson(OverlayPreset(viewports = listOf(ViewportConfig())))
        (json.getJSONArray("viewports").getJSONObject(0)).remove("replay")
        val loaded = OverlayPresetJson.fromJson(json)
        assertNull(loaded.viewports[0].replay)
    }
}
```

- [ ] **Step 2: Run it, confirm it fails to compile**

Run: `./gradlew :app:testDebugUnitTest --tests "*OverlayPresetJsonReplayTest*"`
Expected: FAILURE - unresolved `ReplaySourceType` / `ViewportReplayFace` / `replay`.

- [ ] **Step 3: Add the model** in `OverlayLayout.kt` right after `ViewportConfig` (line 88):

```kotlin
/** Replay-only source kinds for a pane's replay face. Camera never applies in
 *  replay (no live feed); TRANSPARENT is the default so a camera pane reads as
 *  transparent when replaying, ready to composite or export with alpha. */
enum class ReplaySourceType { TRANSPARENT, VIDEO, SOLID, GRADIENT, IMAGE }

/**
 * A pane's REPLAY face (phone-only; the HUD ignores it). Static kinds reuse the
 * same meaning as [ViewportConfig]. VIDEO plays a user-picked clip seeked to the
 * replay cursor in real time; [videoOffsetMs] is the ride-time at which the
 * clip's first frame shows. Colour-grade/zoom are intentionally not carried here.
 */
data class ViewportReplayFace(
    val source: ReplaySourceType = ReplaySourceType.TRANSPARENT,
    val solidColor: Long = 0xFF101014L,
    val gradientColors: List<Long> = listOf(0xFF1E1E2EL, 0xFF4FC3F7L),
    val gradientStops: List<Float> = listOf(0f, 1f),
    val gradientAngle: Float = 90f,
    val gradientRadial: Boolean = false,
    val imageData: String? = null,
    /** Persistable SAF URI string of the video clip. */
    val videoUri: String? = null,
    /** How the video fills the pane: CROP / FIT / CENTER / STRETCH. */
    val videoFit: String = "CROP",
    /** Ride-time (ms into the replay) at which the clip's first frame shows. */
    val videoOffsetMs: Long = 0L,
)
```

Then add the field to `ViewportConfig` (before the closing `)` at line 87-88, after `gradientRadial`):

```kotlin
    val gradientRadial: Boolean = false,
    /** Optional REPLAY face. null = Transparent (today's replay behavior). */
    val replay: ViewportReplayFace? = null
```

- [ ] **Step 4: Serialize** in `OverlayPresetJson.toJson`, inside the per-viewport `JSONObject().apply { ... }` (after `gradientRadial`, line 53):

```kotlin
                    vp.replay?.let { r ->
                        put("replay", JSONObject().apply {
                            put("source", r.source.name)
                            put("solidColor", r.solidColor)
                            put("gradientColors", JSONArray().apply { r.gradientColors.forEach { put(it) } })
                            put("gradientStops", JSONArray().apply { r.gradientStops.forEach { put(it.toDouble()) } })
                            put("gradientAngle", r.gradientAngle.toDouble())
                            put("gradientRadial", r.gradientRadial)
                            if (r.imageData != null) put("imageData", r.imageData)
                            if (r.videoUri != null) put("videoUri", r.videoUri)
                            put("videoFit", r.videoFit)
                            put("videoOffsetMs", r.videoOffsetMs)
                        })
                    }
```

- [ ] **Step 5: Deserialize** in `viewportFromJson`. Add `import com.eried.eucplanet.hud.protocol.ReplaySourceType` and `import com.eried.eucplanet.hud.protocol.ViewportReplayFace` at the top, then add `replay = ` to the returned `ViewportConfig(...)` (after `gradientRadial`, line 115):

```kotlin
            gradientRadial = o.optBoolean("gradientRadial", d.gradientRadial),
            replay = o.optJSONObject("replay")?.let { r ->
                val rd = ViewportReplayFace()
                ViewportReplayFace(
                    source = enumOr(r.optString("source"), rd.source),
                    solidColor = r.optLong("solidColor", rd.solidColor),
                    gradientColors = r.optJSONArray("gradientColors")?.let { arr ->
                        (0 until arr.length()).map { arr.optLong(it) }
                    }?.takeIf { it.isNotEmpty() } ?: rd.gradientColors,
                    gradientStops = r.optJSONArray("gradientStops")?.let { arr ->
                        (0 until arr.length()).map { arr.optDouble(it, 0.0).toFloat() }
                    }?.takeIf { it.isNotEmpty() } ?: rd.gradientStops,
                    gradientAngle = r.optDouble("gradientAngle", rd.gradientAngle.toDouble()).toFloat(),
                    gradientRadial = r.optBoolean("gradientRadial", rd.gradientRadial),
                    imageData = if (r.has("imageData")) r.optString("imageData") else rd.imageData,
                    videoUri = if (r.has("videoUri")) r.optString("videoUri") else rd.videoUri,
                    videoFit = r.optString("videoFit", rd.videoFit),
                    videoOffsetMs = r.optLong("videoOffsetMs", rd.videoOffsetMs),
                )
            }
```

Note: `enumOr` in this file is generic over enums; confirm it resolves `ReplaySourceType`. If it is `inline fun <reified T : Enum<T>> enumOr(...)`, it works unchanged.

- [ ] **Step 6: Run the test, confirm PASS**

Run: `./gradlew :app:testDebugUnitTest --tests "*OverlayPresetJsonReplayTest*"`
Expected: `BUILD SUCCESSFUL`, both tests pass.

- [ ] **Step 7: Full build (model shared with hud-protocol + HUD)**

Run: `./gradlew :app:assembleDebug`
Expected: `BUILD SUCCESSFUL` (HUD compiles though it ignores `replay`).

- [ ] **Step 8: Commit (local only, no push)**

```bash
git add hud-protocol/src/main/java/com/eried/eucplanet/hud/protocol/OverlayLayout.kt \
        app/src/main/java/com/eried/eucplanet/data/store/OverlayPresetJson.kt \
        app/src/test/java/com/eried/eucplanet/data/OverlayPresetJsonReplayTest.kt
git commit -m "feat(studio): add per-pane replay face model + persistence"
```

---

## Task 2: Video sync math (pure function)

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/studio/StudioVideoSync.kt`
- Test: `app/src/test/java/com/eried/eucplanet/ui/studio/StudioVideoSyncTest.kt`

**Interfaces produced:**
- `fun videoTimeUsFor(cursorMs: Long, offsetMs: Long, videoDurationMs: Long): Long?` - returns the video timestamp in microseconds, or `null` when the cursor is outside the clip's covered span (before the offset or past its end).

- [ ] **Step 1: Failing test**

```kotlin
package com.eried.eucplanet.ui.studio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StudioVideoSyncTest {
    @Test fun atOffset_isFrameZero() {
        assertEquals(0L, videoTimeUsFor(cursorMs = 5000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun realTime_oneToOne() {
        assertEquals(2_000_000L, videoTimeUsFor(cursorMs = 7000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun beforeOffset_isNull() {
        assertNull(videoTimeUsFor(cursorMs = 4000, offsetMs = 5000, videoDurationMs = 10_000))
    }
    @Test fun pastEnd_isNull() {
        assertNull(videoTimeUsFor(cursorMs = 16_000, offsetMs = 5000, videoDurationMs = 10_000))
    }
}
```

- [ ] **Step 2: Run, confirm fail (unresolved `videoTimeUsFor`)**

Run: `./gradlew :app:testDebugUnitTest --tests "*StudioVideoSyncTest*"` -> FAILURE.

- [ ] **Step 3: Implement**

```kotlin
package com.eried.eucplanet.ui.studio

/**
 * Maps a replay cursor position to the video timestamp to show, real-time and
 * offset-aligned: [offsetMs] is the ride-time at which the clip's first frame
 * shows. Returns null when the cursor is outside the clip's covered span (before
 * the offset, or past offset + duration) - the caller renders that as nothing.
 */
fun videoTimeUsFor(cursorMs: Long, offsetMs: Long, videoDurationMs: Long): Long? {
    val into = cursorMs - offsetMs
    if (into < 0L || into > videoDurationMs) return null
    return into * 1000L
}
```

- [ ] **Step 4: Run, confirm PASS.** `./gradlew :app:testDebugUnitTest --tests "*StudioVideoSyncTest*"` -> `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit (local)**

```bash
git add app/src/main/java/com/eried/eucplanet/ui/studio/StudioVideoSync.kt \
        app/src/test/java/com/eried/eucplanet/ui/studio/StudioVideoSyncTest.kt
git commit -m "feat(studio): video-cursor sync math (real-time + offset)"
```

---

## Task 3: StudioVideoHub (decode + cache + invalid)

**Files:**
- Create: `app/src/main/java/com/eried/eucplanet/ui/studio/camera/StudioVideoHub.kt`

Read first for the pattern: `app/src/main/java/com/eried/eucplanet/ui/studio/camera/StudioCameraHub.kt` (frames map, `rememberStudioCameraHub`, keying).

**Interfaces produced:**
- `class StudioVideoHub(context)` with:
  - `fun frameAt(uri: String, timeUs: Long): androidx.compose.ui.graphics.ImageBitmap?` - the video frame; null when out of range or undecodable.
  - `fun durationMs(uri: String): Long` - clip length; `0L` if invalid.
  - `fun isInvalid(uri: String): Boolean` - true once a URI fails to open/decode.
  - `fun bind(uris: Set<String>)` / `fun release()` - lifecycle (open a `MediaMetadataRetriever` per distinct URI, close on release).
- `@Composable fun rememberStudioVideoHub(uris: Set<String>, enabled: Boolean): StudioVideoHub` - mirrors `rememberStudioCameraHub`.

**Implementation notes (concrete):**
- One `MediaMetadataRetriever` per distinct `uri`, opened via `setDataSource(context, Uri.parse(uri))` inside try/catch; failure -> mark invalid, `durationMs = 0`.
- `durationMs(uri)` from `extractMetadata(METADATA_KEY_DURATION)`.
- `frameAt`: compute nothing here about range (caller uses `videoTimeUsFor`); just `getFrameAtTime(timeUs, OPTION_CLOSEST)?.asImageBitmap()`, cache the last `(uri, timeUs rounded to ~66ms)` -> ImageBitmap in a small `LruCache`/single-slot map to avoid re-decoding the same frame during a paused scrub. Any exception -> mark invalid, return null.
- Do decode work off the main thread is NOT required for `getFrameAtTime` correctness, but for preview smoothness wrap calls so a slow grab doesn't jank compose: cache aggressively and only re-grab when the rounded time changes.
- `rememberStudioVideoHub`: `remember { StudioVideoHub(context) }`, `LaunchedEffect(uris, enabled) { if (enabled) hub.bind(uris) else hub.release() }`, `DisposableEffect { onDispose { hub.release() } }`.

- [ ] **Step 1:** Read `StudioCameraHub.kt` fully to mirror structure/lifecycle.
- [ ] **Step 2:** Create `StudioVideoHub.kt` with the interface + notes above.
- [ ] **Step 3:** Build. Run: `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Commit (local): `git add app/src/main/java/com/eried/eucplanet/ui/studio/camera/StudioVideoHub.kt && git commit -m "feat(studio): StudioVideoHub - retriever-backed frame source"`

Manual verification happens end-to-end in Task 8 (needs the UI + a picked video).

---

## Task 4: Render replay faces (StudioViewports)

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/StudioViewports.kt` (the replay early-return ~149-153; the `when (config?.source)` ~179-191; reuse `ViewportImagePane` ~451-479, `ViewportGradientPane` ~441-448)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/OverlayStudioScreen.kt` (the `StudioViewportLayer(...)` call ~1039 - pass the video hub + cursor)

**Interfaces consumed:** `ViewportConfig.replay`, `ReplaySourceType`, `videoTimeUsFor(...)`, `StudioVideoHub.frameAt/durationMs/isInvalid`.

**Implementation notes (concrete):**
- Add params to `StudioViewportLayer`: `videoHub: StudioVideoHub?` and `replayCursorMs: Long` (the `replayPosMs` the screen already has), plus keep `replayMode`.
- Replace the `if (replayMode) return transparent Box` early-out. In replay, for each pane render `config.replay` (or Transparent when null):
  - `TRANSPARENT` -> `Box(Modifier.fillMaxSize())` (nothing; checkerboard shows in editor).
  - `SOLID` -> colored `Box` (reuse the SOLID branch, use `replay.solidColor`).
  - `GRADIENT` -> `ViewportGradientPane` fed from the replay gradient fields.
  - `IMAGE` -> `ViewportImagePane` fed from `replay.imageData`.
  - `VIDEO` -> compute `t = videoTimeUsFor(replayCursorMs, replay.videoOffsetMs, videoHub.durationMs(uri))`; if `videoHub.isInvalid(uri)` draw the No-media placeholder (a centered broken-media `Icon` + `stringResource(R.string.studio_video_no_media)`, colored via `MaterialTheme.appColors`); else if `t == null` draw nothing (transparent); else draw `videoHub.frameAt(uri, t)` as an `Image` with the `ContentScale` chosen by `replay.videoFit` (STRETCH -> `FillBounds`, CROP -> `Crop`, FIT -> `Fit`, CENTER -> `None`).
- Extract the static SOLID/GRADIENT/IMAGE rendering the live `when` already has so both live and replay call the same small composables (DRY) - do not duplicate.
- Live path unchanged.

- [ ] **Step 1:** Read the full current `StudioViewportLayer` + the SOLID/GRADIENT/IMAGE branches + `paneRects`.
- [ ] **Step 2:** Add the params + replay-face render, and **land the video-hub binding here** in `OverlayStudioScreen.kt`: compute the set of replay video URIs from `preset.viewports.mapNotNull { it.replay?.takeIf { r -> r.source == ReplaySourceType.VIDEO }?.videoUri }`, call `val videoHub = rememberStudioVideoHub(videoUris, enabled = replayMode)` (mirror how `rememberStudioCameraHub` is bound ~362-376), and pass `videoHub` + `replayPosMs` into `StudioViewportLayer(...)`. The VIDEO branch still null-guards `videoHub` defensively.
- [ ] **Step 3:** Build: `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Emulator smoke (`emulator-5556`): install, open studio, switch to replay - a SINGLE-pane preset with no replay face still shows the checkerboard (transparent). No crash.
- [ ] **Step 5:** Commit (local): `git commit -am "feat(studio): render replay faces (transparent/static/video) in replay"`

---

## Task 5: Editor - mode-aware pane config + video sub-panel + un-gate panes

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/StudioConfigSheets.kt` (`ViewportConfigSheet` ~995-1153; the source chips ~1018-1056; panes-gate ~281-285)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/OverlayStudioScreen.kt` (`canChangePanes` gate ~1447; make the pane wrench + `StudioSheet.ViewportConfig` reachable in replay)
- Modify: `app/src/main/res/values/strings.xml`

**Implementation notes (concrete):**
- `ViewportConfigSheet` takes the current `StudioMode` (or a `replayMode: Boolean`) and edits the **live face** in live and the **replay face** in replay. In replay it reads/writes `config.replay ?: ViewportReplayFace()` and writes back via `onChange { config.copy(replay = newFace) }`.
- Source chips:
  - Live (unchanged): Camera / Background(SOLID+GRADIENT) / Image.
  - Replay: Transparent / Video / Background / Image. Map Background to SOLID/GRADIENT sub-editor (reuse `BackgroundEditor`), Image to the existing pick/replace/clear rows (write to `replay.imageData`).
- Video sub-panel (replay source == VIDEO):
  - "Pick video" via `rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument())` with mime `arrayOf("video/*")`; on result call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` and store `uri.toString()` in `replay.videoUri`.
  - Fit selector: chips Stretch/Crop/Fit/Center -> `replay.videoFit`.
  - Offset: a "Set offset to current frame" button that writes `replay.videoOffsetMs = currentReplayPosMs` (thread the current cursor into the sheet), plus +/- nudge buttons (e.g. +/-100ms).
  - If `videoHub.isInvalid(uri)` show the inline "No media" row.
- Un-gate: panes/layout picker `enabled = true` in replay; `canChangePanes = true`; open the wrench + `ViewportConfig` sheet in replay.
- Badge Video (and the replay video controls) `notOnHud`.
- New strings (add to `values/strings.xml`, English; localized in Task 7):
  - `studio_source_transparent` = "Transparent"
  - `studio_source_video` = "Video"
  - `studio_video_pick` = "Pick video"
  - `studio_video_replace` = "Replace video"
  - `studio_video_fit` = "Fit"
  - `studio_video_offset` = "Offset"
  - `studio_video_set_offset` = "Set to current frame"
  - `studio_video_no_media` = "No media"

- [ ] **Step 1:** Read the full `ViewportConfigSheet` + `BackgroundEditor` + the image pick rows + `LayoutPickerSheet` gate.
- [ ] **Step 2:** Add the mode-aware source chips + replay bodies + video sub-panel; un-gate panes in replay; add strings.
- [ ] **Step 3:** Build -> `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Emulator: in replay, open a pane's wrench, set replay source Transparent/Solid/Image and see it render; pick a video, set fit, set offset. Invalid (revoke by picking then it works; test No-media by pointing at a deleted file if feasible, else defer to Task 8).
- [ ] **Step 5:** Commit (local): `git commit -am "feat(studio): replay pane editor (transparent/video/background/image) + un-gate panes"`

---

## Task 6: Export composites replay faces under the overlay

**Files:**
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/recording/StudioOffscreenSession.kt` (`OverlayFrameSpec` ~38-41; the re-hosted layer ~53-86 renders element layer only)
- Modify: `app/src/main/java/com/eried/eucplanet/ui/studio/OverlayStudioScreen.kt` (export `LaunchedEffect`; per-frame `pos`; `OverlayFrameSpec` construction ~718-735)

**Implementation notes (concrete):**
- The offscreen session must render the viewport layer (replay faces) UNDER the element layer for each frame. Extend `OverlayFrameSpec` to carry what the viewport layer needs per frame: the `OverlayPreset` (for `viewports`/`layout`/`dividers`) and the per-pane video `ImageBitmap?` already resolved for that frame's `pos` (resolve via `videoHub.frameAt(uri, videoTimeUsFor(pos, offset, dur))` on the producing side so the offscreen ComposeView draws a ready bitmap - avoids a retriever call inside the frame clock).
- In the offscreen `setContent`, stack `StudioViewportLayer(replayMode = true, ... prebuiltVideoFrames ...)` under `StudioElementLayer(...)`.
- Alpha unchanged: transparent faces -> alpha formats keep working; a video/opaque face fills the frame so MP4 is correct.
- Keep the on-screen fallback path consistent (it reads back the live graphics layer, which now includes the rendered replay faces).

- [ ] **Step 1:** Read `StudioOffscreenSession.kt` fully + the export `LaunchedEffect` + `captureReplayFrame`.
- [ ] **Step 2:** Thread the preset + per-frame video bitmaps into the offscreen render; stack viewport under elements.
- [ ] **Step 3:** Build -> `BUILD SUCCESSFUL`.
- [ ] **Step 4:** Emulator: export a short replay with (a) a video pane -> MP4 shows footage under the overlay; (b) a transparent pane -> PNG/GIF has alpha. Confirm frame timing matches offset.
- [ ] **Step 5:** Commit (local): `git commit -am "feat(studio): composite replay faces under overlay on export"`

---

## Task 7: Localize new strings

**Files:**
- Modify: all `app/src/main/res/values-*/strings.xml` (18 locales)

- [ ] **Step 1:** Translate the 8 `studio_*` strings from Task 5 into every locale (rider terminology, no em-dashes, curly apostrophes so no XML escaping is needed). Reuse the project's inject-JSON approach.
- [ ] **Step 2:** Verify every base key is present in every locale (Python key-diff) and no em-dashes; then `./gradlew :app:assembleDebug` -> `BUILD SUCCESSFUL` (validates all locale XML).
- [ ] **Step 3:** Commit (local): `git commit -am "i18n(studio): localize replay pane + video strings"`

---

## Task 8: Integration + emulator verification + HUD check

**Files:** none new; end-to-end verification and any fix-up.

- [ ] **Step 1:** Run all unit tests: `./gradlew :app:testDebugUnitTest` -> `BUILD SUCCESSFUL` (incl. the two new tests + the settings drift guard).
- [ ] **Step 2:** Emulator run-through (`emulator-5556`): live mode unchanged (camera/bg/image); replay panes editable; Transparent default; Solid/Gradient/Image replay faces render; Video seeks with the cursor and offset; invalid video -> "No media"; out-of-range -> transparent; export MP4 (video) + alpha (transparent) correct; an OLD preset (no replay) still replays transparent.
- [ ] **Step 3:** HUD sanity: build `:hud:assembleDebug` (or the HUD variant) -> `BUILD SUCCESSFUL`; confirm a preset with a replay/video face still loads for the HUD (it uses the live face, ignores `replay`).
- [ ] **Step 4:** Commit any fix-ups (local). **Do NOT push.** Report the build to the user for device testing.

---

## Notes for the executor

- Every commit is LOCAL. The user tests before any `git push` (branch `feature/overlay-studio-replay`).
- Tasks 1-2 are strict TDD (unit-testable). Tasks 3-6 are Compose/rendering/video, verified by build + emulator (this codebase does not unit-test Compose UI); still write each as a focused, independently-committable change.
- Reuse, don't duplicate: the static SOLID/GRADIENT/IMAGE renderers and `BackgroundEditor` serve both faces.
