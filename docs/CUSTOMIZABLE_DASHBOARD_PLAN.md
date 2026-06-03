# Customizable Dashboard — Design Plan

Status: **DRAFT — for review, no code yet**
Branch: `customizable-dashboard`

## Goal

Let users tailor the home Dashboard without surfacing a fragmented settings page.
Two things become user-editable:

- **Metrics grid** (currently fixed 2×3: Battery, Temp, Voltage, Current/Watts, Load, Trip)
- **Actions grid** (currently fixed 3×2: Horn, Light, Voice, Safety, Lock, Recorder)

Both editors must feel "compact, looks good, not many pages". The interaction
model below is built around **one settings screen + one bottom-sheet per slot**
so configuration never spawns a tree of sub-pages.

---

## 1. Naming & placement

### Recommended name: **"Dashboard layout"**

Reasoning:
- `Personalization` and `App customization` are vague (would they also cover
  theme, accent, units? those already live in `Display`).
- `Dashboard layout` is the smallest accurate description and parallels the
  industry-standard term ("home screen layout").
- Sister tabs are single nouns: General, Display, Speed, Voice, Cloud, Alarms,
  Automations, Integration, Navigator. `Dashboard` fits that cadence.

### Placement: **new top-level tab between `General` and `Display`** in `SettingsScreen.kt`

- Icon: `Icons.Filled.Dashboard` (or `DashboardCustomize`)
- Tab key: `"dashboard"`
- Tab index: insert at position 1, shift `display`→2, `speed`→3, etc.
  - All callers using `onNavigateToSettings(N)` need re-indexing; check
    `DashboardScreen.kt` long-press menus (lines 949, 974, 1004, 1028, 1052) and
    Flic/Watch deeplinks.
  - Or: drop numeric indices, navigate by tab **key** (small refactor; cleaner).

Rejected alternative: putting this under `Display`. Display is about appearance
chrome (theme, accent, gauge color band). Layout is structurally bigger and
needs its own tab room.

---

## 2. The settings screen layout

One scrollable screen. Three collapsible sections, all expanded by default the
first time the screen is opened.

```
┌─────────────────────────────────────────────────────┐
│ Dashboard layout                                    │
├─────────────────────────────────────────────────────┤
│ ▼ Speed gauge                                       │
│   Tap-to-detail metric  [Speed ▾]                   │
│   (gauge itself is always shown, always speed)      │
├─────────────────────────────────────────────────────┤
│ ▼ Metrics grid                                      │
│   Columns:  ( 2 ) [ 3 ] [ 4 ]                       │
│                                                     │
│   Slot 1            Slot 2            Slot 3        │
│   ┌────────┐        ┌────────┐        ┌────────┐    │
│   │Battery │   ⠿    │  Temp  │   ⠿    │Voltage │    │
│   │  78%   │        │  42°C  │        │ 84.2V  │    │
│   └────────┘        └────────┘        └────────┘    │
│   Slot 4            Slot 5            Slot 6        │
│   ┌────────┐        ┌────────┐        ┌────────┐    │
│   │Current │   ⠿    │  Load  │   ⠿    │ Trip   │    │
│   │ 12.4A  │        │  31%   │        │ 14.2km │    │
│   └────────┘        └────────┘        └────────┘    │
│                                                     │
│   Rolling window for min/max/avg:  [— ●─── +] 5 min │
│                                                     │
│   [ Reset to defaults ]                             │
├─────────────────────────────────────────────────────┤
│ ▼ Action buttons                                    │
│   Columns:  [ 2 ] ( 3 ) [ 4 ]                       │
│                                                     │
│   ┌───────┐  ┌───────┐  ┌───────┐                   │
│   │ Horn  │⠿ │ Light │⠿ │ Voice │                   │
│   └───────┘  └───────┘  └───────┘                   │
│   ┌───────┐  ┌───────┐  ┌───────┐                   │
│   │Legal  │⠿ │ Lock  │⠿ │Record │                   │
│   └───────┘  └───────┘  └───────┘                   │
│                                                     │
│   [ Reset to defaults ]                             │
└─────────────────────────────────────────────────────┘
```

Notes:
- ⠿ = drag handle. The slot itself is **tap-to-edit**, the handle is
  **long-press-to-drag** — same `sh.calvin.reorderable` library already used by
  Alarm rules.
- Columns selector is a 3-chip toggle (segmented control). Live previews update
  immediately as the user toggles.
- The mini cards inside the settings screen mirror the real dashboard widget
  rendering (same `StatCard` composable) — keeps the editor compact because
  there's no parallel "settings card" art to maintain.
- Showing live values (78%, 42°C…) keeps the editor "alive" and removes the
  need for a separate preview button. If disconnected, show last-known or a
  static "—".

---

## 3. Per-widget editor (bottom sheet, not a new page)

Tapping a metric slot opens a modal **bottom sheet** with the zoomed widget at
top and the configuration controls below. Closing it commits changes — no
explicit Save button.

```
┌─────────────────────────────────────────────────────┐
│ Slot 4                                       [ × ]  │
│                                                     │
│   ┌─────────────────────────────────────────────┐   │
│   │                                             │   │
│   │   min  →     12.4 A      ← max              │   │
│   │              (current)                      │   │
│   │           ╱╲                                │   │
│   │          ╱  ╲    ╱╲     avg                 │   │
│   │   ─────╱────╲──╱──╲─────                    │   │
│   │                                             │   │
│   └─────────────────────────────────────────────┘   │
│                                                     │
│   Metric:                                           │
│   [Battery] [Temp] [Volt] (Curr) [Load] [Trip]      │
│   [Watts] [Speed] [PWM] [Alt] [Tilt] [Distance]     │
│                                                     │
│   Show:                                             │
│      Center           Top-left      Top-right       │
│      [Current ▾]      [Min ▾]       [Max ▾]         │
│      Bottom-left      Bottom-right                  │
│      [—   ▾]          [Avg ▾]                       │
│                                                     │
│   [x] Show trend sparkline                          │
│   Long-press toggles: ( Amps ↔ Watts )              │
└─────────────────────────────────────────────────────┘
```

Why slots instead of free-drag:
- The user described drag-into-zones ("drag MAX to the right side"). True
  drag-and-drop into pixel zones is fiddly on a phone and breaks A11y. **Five
  fixed slots with dropdowns** gives the same result — every slot can hold
  Current / Min / Max / Avg / Median / P95 / None / Trend — without the user
  hunting for hit-targets. The zoomed preview at the top renders exactly what
  each slot choice would look like, so the user still sees their layout.
- If the slot is set to "None", the corner just disappears in the preview and
  on the real widget.

The **rolling window** lives at the section level (single slider, applies to
all metrics) rather than per-widget, because:
- Per-widget windows make min/max comparisons across cards meaningless.
- Saves space in the bottom sheet.
- If we later need per-widget windows, the section-level value becomes the
  default and we add a "Custom window" override toggle.

### Header copy in the bottom sheet
> "Min, max and average use the last **5 minutes** of data. Change in
> Dashboard layout → Metrics grid → Rolling window."

(The "5 minutes" reflects the section-level slider.)

---

## 4. Action buttons editor

The action grid uses the **same compact pattern**:

- Tap a button slot → bottom sheet with:
  - Action picker grouped by category
  - Optional override: button label, accent color, long-press shortcut

```
┌─────────────────────────────────────────────────────┐
│ Button slot 3                                [ × ]  │
│                                                     │
│   ┌──────────────┐                                  │
│   │   📢 Horn    │   ← live preview                 │
│   └──────────────┘                                  │
│                                                     │
│   Action:                                           │
│   ▾ Wheel control                                   │
│      ○ Horn               ○ Light                   │
│      ○ Lock               ○ Legal mode toggle       │
│      ○ Legal ON           ○ Legal OFF               │
│   ▾ Recording                                       │
│      ○ Record toggle      ○ Record start            │
│      ○ Record stop                                  │
│   ▾ Voice                                           │
│      ○ Voice announce                               │
│   ▾ Media                                           │
│      ○ Play/Pause   ○ Next   ○ Previous             │
│   ▾ None                                            │
│      ● Hide this slot                               │
│                                                     │
│   Long-press shortcut: [ Open speed settings ▾ ]    │
└─────────────────────────────────────────────────────┘
```

Action vocabulary is **the same enum already used by Flic and Wear OS**:
`FlicAction` (AppSettings.kt:397-412) gives us 14 actions and the icons come
from `WatchActionMeta.kt:26-37` — re-use both to avoid forking a third
icon/label table.

Add a tiny adapter that maps `FlicAction` → on-screen icon. We already have
that mapping in `DashboardScreen.kt`'s current hard-coded buttons; extract it
into `data/model/DashboardAction.kt` and let Dashboard, Flic settings, and
Wear settings all read from it.

---

## 5. Data model (AppSettings additions)

Add to `data/model/AppSettings.kt`:

```kotlin
data class DashboardLayout(
    // Gauge is always shown and always speed (per Q1/Q2).
    // Only the tap-to-detail target is user-pickable.
    val gaugeTapMetric: MetricType = MetricType.SPEED,

    val metricsColumns: Int = 2,                 // 2, 3, or 4 — for the current form factor.
                                                  // The other form factor auto-bumps as today (Q5).
    val metricsRollingWindowMinutes: Int = 5,    // 1, 2, 5, 10, 15, 30
    val metricsSlots: List<MetricSlot> = defaultMetricsSlots(),

    val actionsColumns: Int = 3,                 // 2, 3, or 4
    val actionsSlots: List<ActionSlot> = defaultActionsSlots()
)

data class MetricSlot(
    val metric: MetricType,                       // existing enum in MetricDetailScreen.kt
    val center: Stat = Stat.CURRENT,
    val topLeft: Stat = Stat.NONE,
    val topRight: Stat = Stat.NONE,
    val bottomLeft: Stat = Stat.NONE,
    val bottomRight: Stat = Stat.NONE,
    val showSparkline: Boolean = true
)

enum class Stat { NONE, CURRENT, MIN, MAX, AVG, MEDIAN, P95 }

data class ActionSlot(
    val action: FlicAction,                       // re-use existing enum
    val longPressAction: FlicAction = FlicAction.NONE
)
```

`AppSettings` already serializes as JSON via DataStore (per the explore report),
so no migration code — old installs deserialize with the defaults above.

---

## 6. Aggregation (min/max/avg)

`WheelRepository.fullHistory` already keeps a 5-minute, 1 Hz, per-metric
`List<MetricSample>`. Add:

```kotlin
fun MetricHistory.aggregate(
    nowMs: Long,
    windowMinutes: Int
): MetricAggregate
```

returning `min/max/avg/median/p95/last`. Compute on demand inside
`DashboardViewModel` whenever `fullHistory` ticks — cheap enough (≤ 900 points
× 6 metrics).

**Edge case**: window > 5 min requires extending the repository's rolling
buffer. Cap the UI slider at 5 min for v1, or extend the buffer to 30 min
(900 → 1800 samples is negligible memory). I'd extend it — gives us room to
expose 15/30-min options without another data-layer change.

---

## 7. Implementation phases

| Phase | What | Risk |
|---|---|---|
| 1 | Extract `DashboardAction` (action+icon+label+handler) from inline `DashboardScreen.kt` so Dashboard, Flic, Wear, and new settings all read from one source | Low — pure refactor |
| 2 | Add `DashboardLayout` to `AppSettings` with defaults; thread through `DashboardViewModel`; **render the dashboard from settings** (still showing the same six metrics and six actions by default) | Medium — touches main UI |
| 3 | Add aggregation helper + extend rolling buffer to 30 min | Low |
| 4 | New "Dashboard layout" settings tab — section A (metrics grid) | Medium |
| 5 | Per-slot bottom sheet for metrics | Medium — new reusable widget |
| 6 | Section B (actions grid) + per-slot bottom sheet for actions | Low (reuses phase 5 patterns) |
| 7 | Polish: live preview animation, A11y labels, drag affordances, localization keys | — |

Phase 2 is the keystone — once the dashboard reads from `DashboardLayout`
instead of hard-coded composables, every later phase only changes settings UI,
not runtime UI. So we ship phase 1+2 as a no-op refactor first, verify nothing
regressed, then build the editor on top.

---

## 8. Locked decisions

| # | Decision |
|---|---|
| 1 | **Dial stays locked to Speed.** Only the tap-to-detail metric is configurable. |
| 2 | **Gauge always shown** — no hide toggle. |
| 3 | **Per-section reset** buttons (one inside metrics grid, one inside actions grid). |
| 4 | **Watch stays independently configured** under the Watch tab; phone Dashboard layout does not push to the watch. |
| 5 | User configures for the **current form factor**; the other auto-bumps as today (foldables / tablets keep the existing +1-column behavior). |
| 6 | Trip's Min/Max/Avg/Median/P95 in the per-slot editor are **visible but disabled** with a small "no history" caption — keeps the UI consistent across metrics. |

---

## 9. Build gate (standing requirement)

Every phase ends green or it doesn't land:

- `./gradlew assembleDebug` succeeds on Windows + Linux.
- App installs and launches on the emulator (AVD pinned to a recent Pixel /
  API level we already use in CI) without crashing on the dashboard.
- The dashboard renders the same set of metrics and actions as before for a
  fresh install (default `DashboardLayout` matches today's hard-coded layout
  byte-for-byte visually).
- After phase 4+, a brief smoke test on the emulator: open Settings → Dashboard
  layout, drag one metric, change column count, open a slot editor, change a
  corner stat → back to dashboard, confirm the change persisted across a
  process restart.

I'll run the build + emulator smoke test at the end of each phase and only
move to the next when it's clean. If a phase introduces a hot crash on the
dashboard, it gets reverted, not patched forward.

---

## 10. Expanded metric inventory

Phase 4+ exposes these as choices in the per-slot "Metric:" picker. Items
marked **(history)** already feed the rolling buffer in `WheelRepository`, so
their Min/Max/Avg work for free. Items marked **(current only)** would either
ship without those stats (corners gray out, same as Trip) or get a new history
series added to `FullMetricHistory` — I'd ship current-only first and add
history opportunistically in later phases.

**Already buffered (Min/Max/Avg work out of the box):**
1. Battery (%)
2. Temperature — max sensor (°C/°F)
3. Voltage (V)
4. Current (A) — long-press toggles to Watts as today
5. Load / PWM (%)
6. Speed (km/h or mph) — new for the grid; previously dial-only

**Current-only (no history yet — corners gray for Min/Max/Avg until we extend the buffer):**
7. Trip distance (km/mi)
8. Total distance / odometer (km/mi)
9. Power — battery × current in Watts (V·A; already exists as the Current long-press swap)
10. Motor power (W) — `wheelData.motorPower`
11. Battery power (W) — `wheelData.batteryPower`
12. Battery 1 (%) — split-pack wheels (`battery1Percent`)
13. Battery 2 (%) — split-pack wheels (`battery2Percent`)
14. Pitch angle (°) — tilt forward/back
15. Roll angle (°) — lean left/right
16. G-force combined (g) — phone IMU magnitude
17. Lateral G (g) — `accelX`
18. Forward G (g) — `accelY`
19. Torque (`wheelData.torque`, unit TBD per protocol)
20. Dynamic speed limit (km/h)
21. Dynamic current limit (A)

**Excluded (not useful as a grid card):**
- Latitude / longitude — covered by Navigator, not a numeric readout.
- Per-sensor temperatures (`temperatures: List<Float>`) — could be a Phase 8
  multi-stat card; not v1.
- Park/drive mode (`pcMode`) — boolean state already shown next to the gauge.

Total: 21 metric options in v1, vs. 6 today.

**Default `metricsSlots`** (matches the current dashboard so first launch is
visually identical):

```
slot 1: BATTERY      center=CURRENT, others=NONE
slot 2: TEMPERATURE  center=CURRENT, others=NONE
slot 3: VOLTAGE      center=CURRENT, others=NONE
slot 4: CURRENT      center=CURRENT, others=NONE   (long-press → WATTS)
slot 5: LOAD         center=CURRENT, others=NONE
slot 6: TRIP         center=CURRENT, others=NONE
```

---

## 11. Expanded action inventory

The picker in the action-slot bottom sheet is grouped by category. Categories
keep a long list browsable on a phone-sized screen.

**Wheel control** (from existing `FlicAction` enum + dashboard handlers):
- Horn
- Light toggle
- Lock toggle
- Legal mode toggle / Legal ON / Legal OFF (3 separate actions)

**Recording** (`FlicAction` + `onNavigateToRecording`):
- Record toggle
- Record start
- Record stop
- Open recording screen

**Voice** (existing handlers in `DashboardViewModel`):
- Voice announce (one-shot)
- Toggle periodic voice (existing long-press in menu)

**Media** (existing `FlicAction`):
- Play / Pause
- Next track
- Previous track

**Shortcuts / Open…** (new "navigation" action category, mapping to existing
`onNavigateTo*` callbacks):
- Open Studio (overlay camera) — `onNavigateToStudio`
- Open Navigator (map) — `onNavigateToNavigator`
- Open Flic settings — `onNavigateToFlic`
- Open Scan / Connect — `onNavigateToScan`
- Open Trip detail (latest trip) — `onNavigateToTripDetail`
- Open Settings → General (tab 0)
- Open Settings → Display (tab 1 after reindex)
- Open Settings → Speed (tab 2)
- Open Settings → Voice (tab 3)
- Open Settings → Alarms (tab 5)
- Open Settings → Automations (tab 6)
- Open Settings → Integration / Flic (tab 7)
- Open Settings → Navigator (tab 8)
- Open Metric history (per metric, parameterized) — `onNavigateToMetric("BATTERY")` etc.

**None**:
- Hide this slot (the button doesn't render at all, freeing visual space).

Total: ~30 action options in v1, vs. 6 today.

### Where the new actions live in code

Add to `data/model/DashboardAction.kt` (created in Phase 1):

```kotlin
sealed interface DashboardAction {
    val iconKey: String        // looked up by WatchActionMeta-style icon table
    val labelRes: Int

    // Wheel-control / Recording / Voice / Media — these map 1:1 to FlicAction.
    data class Wheel(val flic: FlicAction): DashboardAction

    // Navigation actions — open a screen, may carry a settings tab key.
    data class OpenScreen(
        val target: ScreenTarget,
        val settingsTab: String? = null,
        val metricKey: String? = null
    ): DashboardAction

    enum class ScreenTarget {
        RECORDING, NAVIGATOR, STUDIO, SCAN, FLIC_SETTINGS,
        TRIP_DETAIL_LATEST, SETTINGS_TAB, METRIC_DETAIL
    }

    data object None: DashboardAction
}
```

This shape keeps Flic / Watch / Dashboard buttons reading from one vocabulary
without coupling navigation callbacks into the Flic enum (Flic and Wear OS
can't navigate the phone UI, so they only see `Wheel` variants — `OpenScreen`
is dashboard-only).

### Icon strategy

- `Wheel` actions: re-use the icons from `WatchActionMeta.kt:26-37` exactly
  (Horn = Campaign, Light = FlashlightOn, Lock = Lock, etc.).
- `OpenScreen` actions: re-use the icon already used by the matching dashboard
  affordance today — Studio = PhotoCamera, Navigator = Navigation, Scan =
  Bluetooth, Flic settings = Extension, Settings tabs = the icon defined on
  that tab in `SettingsScreen.kt:460-497`, Metric detail = the metric's
  existing accent color + first-letter chip.

No new icon assets needed — every action already has one somewhere in the
codebase.

---

## 12. First-PR scope

Phase 1 (the `DashboardAction` extraction) is the right first PR:

- Pure refactor — no behavior change, no settings UI yet.
- Touches DashboardScreen, FlicScreen, the watch's `WatchActionMeta`.
- Easy to verify: `assembleDebug` + emulator smoke (boot, see same buttons /
  metrics, tap each).

After it merges, Phase 2 (read dashboard from `DashboardLayout` in
`AppSettings`, still with defaults that reproduce today's layout) is the next
PR. Everything user-visible starts in Phase 4.
