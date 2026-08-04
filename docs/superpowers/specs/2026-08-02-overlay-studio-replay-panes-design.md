# Overlay Studio: Replay-Mode Pane Faces (Transparent / Video) - Design

**Goal:** Bring panes back to replay mode. Each pane gets an independent **live face**
and **replay face**; the replay face adds two new sources - **Transparent** (default)
and **Video** (a user picked clip seeked in lockstep with the replay cursor) - so a
rider can composite telemetry over their own ride footage, or export the overlay with a
transparent background.

**Status:** Design approved. Ready for an implementation plan (writing-plans).

**Branch:** `feature/overlay-studio-replay` (off `next-experimental`, per the branching
rule). The studio is effectively identical on `next-experimental` and `next-version`, so
these anchors hold on both.

**Tech stack:** Kotlin, Jetpack Compose, the shared `hud-protocol` `OverlayLayout` model,
Camera2 (`StudioCameraHub`), the offscreen export renderer (`StudioOffscreenSession`),
and (new) `MediaMetadataRetriever` for video frame extraction.

## Global constraints

- No em-dashes anywhere (UI strings, comments, commit/PR text).
- All UI color via `MaterialTheme.appColors.*`; never hardcode `Color(...)` in feature UI.
- Every new user-facing string lives in `strings.xml` and is translated to all 18 locales.
- Video files are referenced by a **persistable SAF URI**, never inlined into the preset.
- The video/replay face is **phone-only**; the HUD companion must ignore it and stay
  compatible with any preset. New editor controls that the HUD can't do are badged
  `notOnHud`, matching Panes/Image today.
- Back-compat: any preset saved before this feature must load unchanged and behave exactly
  as today (replay = transparent).

---

## 1. Current state (as-built)

- **Source model.** `enum ViewportSourceType { CAMERA, SOLID, IMAGE, GRADIENT }`
  (`hud-protocol/.../OverlayLayout.kt:44`). Per-pane config `ViewportConfig`
  (`OverlayLayout.kt:52`) holds `source`, `cameraKey`, `fitMode` (CROP/FIT/CENTER),
  colour-grade, `zoom`, `solidColor`, `imageData` (base64), gradient fields. A whole studio
  config is one `OverlayPreset` (`OverlayLayout.kt:280`) = `layout` + `dividers` +
  `viewports: List<ViewportConfig>` + `elements`, serialized by `OverlayPresetJson`
  (write `:31-56`, read `:87-117`).
- **Panes.** A pane = one viewport rect from a fixed `ViewportLayout`
  (SINGLE/ROWS_2/COLUMNS_2/ROWS_3/COLUMNS_3/GRID_4, 1-4 panes, `OverlayLayout.kt:20`).
  `withLayout`/`normalized` pad/trim `viewports` to `paneCount` (`:296-322`). Geometry via
  `paneRects` (`StudioViewports.kt:79`).
- **Render.** `StudioViewportLayer` (`StudioViewports.kt:135`) dispatches on
  `config.source` (`:179-191`); colour-grade is a GPU `ColorMatrix`; camera frames come
  from `StudioCameraHub.frame(cameraKey)` (`:372`).
- **Live vs replay.** `enum StudioMode { LIVE, REPLAY }` (`StudioReplay.kt:8`);
  `replayMode` derived in `OverlayStudioScreen`. In replay the **entire viewport layer is
  skipped** (`StudioViewports.kt:149-153` returns a transparent Box) and the pane/layout
  controls are gated off (`StudioConfigSheets.kt:281-285`, `OverlayStudioScreen.kt` panes
  button). Background renders a transparency checkerboard.
- **Export.** Frame-by-frame over a cursor `pos = replayStartMs + i * stepMs`
  (`OverlayStudioScreen.kt` export `LaunchedEffect`). Offscreen path re-hosts **only** the
  element layer (`StudioOffscreenSession`), viewport layer transparent. Formats carry a
  `hasAlpha` flag; alpha-less formats get a chroma fill + optional force-opaque pass.
- **Video decode: none.** The only `MediaCodec` in the studio is the export encoder. No
  `MediaExtractor`/`MediaPlayer`/`ExoPlayer`/`SurfaceTexture` decode path exists.

---

## 2. The model: per-pane live face + replay face

The **layout is shared** across modes (same pane rects + dividers, so elements keep their
positions). Each pane stores two faces, edited in the mode they apply to:

| | Live face (edited in live) | Replay face (edited in replay) |
|---|---|---|
| Dynamic | **Camera** (default) | **Transparent** (default), **Video** |
| Static | Background (solid/gradient), Image | Background (solid/gradient), Image |

- Camera is never offered on the replay face (no live feed in replay), so a camera pane is
  transparent in replay until its replay face is given a video. "Transparent in live" never
  exists, because live renders whatever the live face is.
- Static faces (solid/gradient/image) reuse the existing renderers and look identical in
  both modes.

### 2.1 Data model - approach A1 (additive nested replay face)

`ViewportConfig` stays exactly as it is and **becomes the live face**. Add one optional
nested replay face:

```
enum class ReplaySourceType { TRANSPARENT, VIDEO, SOLID, GRADIENT, IMAGE }

data class ViewportReplayFace(
    val source: ReplaySourceType = ReplaySourceType.TRANSPARENT,
    // static fills (reuse the same meaning as ViewportConfig)
    val solidColor: Long = 0x00000000,
    val gradientColors: List<Long> = emptyList(),
    val gradientStops: List<Float> = emptyList(),
    val gradientAngle: Float = 0f,
    val gradientRadial: Boolean = false,
    val imageData: String? = null,        // base64 PNG, same as live IMAGE
    // video
    val videoUri: String? = null,         // persistable SAF URI string
    val videoFit: String = "CROP",        // CROP / FIT / CENTER / STRETCH
    val videoOffsetMs: Long = 0L,         // ride-time at which video frame 0 shows
)
// v1: replay faces get fit only. Colour-grade / zoom are NOT applied to replay
// faces (they stay on the live camera face); this keeps the replay face minimal.

// ViewportConfig gains:
val replay: ViewportReplayFace? = null    // null => transparent (today's behavior)
```

- `null` replay face ⇒ Transparent, which is exactly today's replay render, so **every old
  preset upgrades for free** and the live path is untouched.
- `normalized()`/`withLayout()` seed new panes with `replay = null` (transparent) and the
  existing camera live face.
- Alternatives rejected: A2 (symmetric `{live, replay}` split - churns persistence + every
  renderer for little gain) and A3 (flat `replay*` fields on `ViewportConfig` - bloats the
  type, blurs concerns).

### 2.2 Persistence (`OverlayPresetJson.kt`)

Write/read `viewport.replay` as a nested object when non-null; absent ⇒ `null` ⇒
transparent. The video is stored **by URI reference**, so a preset with a video face is not
fully self-contained (moving it to another device needs the video file too); transparent
and static faces stay self-contained. On load, persist the SAF read permission
(`takePersistableUriPermission`); a URI whose permission is gone renders as **No media**
(section 4).

---

## 3. Rendering (`StudioViewports.kt`)

Replace the early transparent-return in replay (`:149-153`) with a per-pane render of the
**replay face**:

- **Transparent** ⇒ draw nothing (pane stays alpha; the editor checkerboard shows through).
- **Solid / Gradient / Image** ⇒ the exact renderers live uses (`ViewportGradientPane`,
  `ViewportImagePane`, solid `Box`), plus the same colour-grade and fit/zoom transforms.
- **Video** ⇒ the decoded frame for the current cursor (section 4), drawn with `videoFit`.

Live mode continues to render the live face (`config`) exactly as today. The shared layout
means changing panes in either mode updates both; `normalized()` seeds both faces.

---

## 4. The video replay face

### 4.1 Sync (real-time + offset)

For cursor position `cursorMs` (the replay `pos`), the frame shown is:

```
videoTimeUs = (cursorMs - videoOffsetMs) * 1000
```

played 1:1 with real time. The same formula drives both the editor preview and each export
frame (the export loop already owns `pos`). The editor exposes a **"Set offset to current
frame"** action plus a fine nudge: scrub the replay to a landmark, scrub the footage to the
same landmark, lock them.

### 4.2 States

- **Invalid** (file moved, permission lost, unsupported codec, decode failure) ⇒ a
  **"No media" placeholder** (a broken-media icon + short localized label). Never a stale
  frame or a silent blank.
- **Cursor outside the video's covered span** (before `videoOffsetMs`, or past
  `offset + videoDurationMs`) ⇒ render **transparent** (nothing). This is a valid
  "footage does not cover this instant" state, not an error.

### 4.3 Fit

`videoFit`: Stretch / Crop / Fit / Center, mirroring the pane `fitMode` handling.

### 4.4 Decode - approach V1 (`MediaMetadataRetriever.getFrameAtTime`)

For every rendered frame (preview and export), extract that timestamp with
`getFrameAtTime(videoTimeUs, OPTION_CLOSEST)` into a `Bitmap` -> `ImageBitmap`.

- Simple; one code path for preview and export; no GL/Surface/threading machinery; export
  (offline, quality-first) is a natural fit.
- Trade-off: a live scrub of a high-res clip is not buttery (each grab decodes). Mitigate
  with a small last-frame cache keyed by `(uri, roundedTimeMs)` and preview throttling.
  Export is unaffected.
- A `StudioVideoHub` (analogous to `StudioCameraHub`) owns one retriever per distinct
  `videoUri` in the preset, exposes `frameAt(uri, timeUs): ImageBitmap?`, caches the last
  frame, and reports an `invalid` flag per URI. Bound from the preset's video faces the way
  the camera hub is bound from camera panes.
- Audio is ignored (visual background only).
- Alternative rejected for v1: V2 (`MediaCodec` + `SurfaceTexture` player) - smoother
  preview + faster seek, but a lot of machinery to feed a GL texture into both the Compose
  viewport and the offscreen export bitmap. Revisit only if preview scrubbing is too slow.

---

## 5. Editor UI (`StudioConfigSheets.kt`, `OverlayStudioScreen.kt`)

- Un-gate the pane/layout controls in replay (`StudioConfigSheets.kt:281-285`, the panes
  button in `OverlayStudioScreen.kt`) and make the per-pane wrench + `ViewportConfigSheet`
  reachable in replay (today it is live-only).
- `ViewportConfigSheet` (`StudioConfigSheets.kt:995`) becomes **mode-aware**:
  - Live: chips Camera / Background / Image (unchanged), editing the live face.
  - Replay: chips **Transparent** (default) / **Video** / Background / Image, editing the
    replay face. No Camera chip in replay; no Transparent/Video in live.
  - Video sub-panel: a "Pick video" button (SAF `OpenDocument`, take persistable
    permission), the fit selector, the offset "set to current frame" + nudge, and the
    inline "No media" state when invalid.
- "Video" (and the replay panel) badged `notOnHud`.
- New strings: `studio_source_transparent`, `studio_source_video`, `studio_video_pick`,
  `studio_video_fit`, `studio_video_offset`, `studio_video_set_offset`,
  `studio_video_no_media` (all localized).

---

## 6. Export (`StudioOffscreenSession.kt`, `OverlayStudioScreen.kt`)

Today replay export renders only the element layer over transparency. Extend the offscreen
session to also render the **viewport layer with replay faces underneath** the elements, so
a video/image/solid replay face composites under the telemetry. Each export frame pulls its
video frame via the sync formula at that frame's `pos`. The existing alpha handling is
preserved: transparent faces -> alpha formats (PNG/GIF/APNG) with the current
chroma/force-opaque logic; a video/opaque face -> MP4 works directly.

---

## 7. HUD compatibility

`OverlayLayout` lives in the shared `hud-protocol` module, so the new `ViewportReplayFace`
type + `ViewportConfig.replay` ship to the HUD too. The HUD renders live only and must
simply ignore `replay`. Serialization tolerates the new field in both directions; a preset
authored with a replay/video face stays valid on the HUD (it uses the live face).

---

## 8. Data flow (summary)

- **Live render:** pane -> `config` (live face) -> existing renderer (unchanged).
- **Replay render:** pane -> `config.replay ?: Transparent` -> transparent / static /
  video(frame at `cursor - offset`).
- **Export frame i:** cursor `pos = start + i*step` -> viewport layer (replay faces, video
  via `getFrameAtTime(pos - offset)`) under element layer -> bitmap -> encoder.

---

## 9. Testing

- **Unit:**
  - Cursor -> video-time+offset math, including before-start and past-end (out-of-range).
  - `OverlayPresetJson` round-trip for a viewport with each replay face kind (incl. a video
    URI + fit + offset); and an old preset (no `replay`) reading as Transparent.
  - `normalized()`/`withLayout()` seeds both faces on pane-count change.
- **Manual (emulator, emulator-5556 - `MediaMetadataRetriever` decodes in software there):**
  - Panes appear in replay; layout picker + per-pane wrench work in replay.
  - Transparent replay face shows the checkerboard; static/image faces render.
  - Video face seeks with the cursor; offset alignment works; invalid URI -> "No media";
    out-of-range -> transparent.
  - Export composites video + elements into MP4; transparent -> alpha PNG/GIF/APNG correct.
  - Old preset still behaves as before (replay transparent). HUD unaffected.

---

## 10. Risks / open items

- **Preview scrub performance** on high-res video with V1 (mitigated by cache/throttle;
  escalate to V2 only if needed).
- **SAF permission longevity** - persistable permission can still be revoked by the OS or a
  provider; the "No media" state is the honest fallback.
- **Export memory** - compositing a decoded video frame per export frame adds bitmap
  allocations; reuse buffers where the export loop already does.

## 11. Out of scope (v1)

- Video audio, trimming, or multiple videos time-sequenced in one pane.
- A `MediaCodec`/GL real-time player (V2).
- Making video presets self-contained (bundling the clip).
