# Custom Theme System — Design Spec

Branch: `feat/custom-theme` (off `next-version`). Date: 2026-06-02.

Replaces the old `accentColor` + 4-way `themeMode` settings with a full custom
theme system: every color the app draws is a named **semantic token**, and a
**theme** is one self-contained set of those tokens. Users pick from 3 immutable
built-ins or fork-and-edit them with a floating editor widget.

## Core model

- A **theme is single-flavor**: one flat map of ~30–35 semantic tokens → colors.
  No light/dark pairing inside a theme; Light, Dark, Pure Black are *sibling*
  themes.
- **3 built-in themes are immutable**: `Light`, `Dark`, `Pure Black`. They cannot
  be edited in place or deleted. Editing forks them (see Working draft).
- **One active theme** at a time. No "follow the system" — the OS does not drive
  the theme after install.
- **Install-time pick** (first launch, and on upgrade for old `system` users):
  `isSystemInDarkTheme()` → **OS-light = Light, OS-dark = Pure Black**. Gray
  `Dark` is one tap away in the combo. (OLED is not detectable via a public
  Android API, so OS-dark defaults to Pure Black.)

## Dropping accents (invisible to users)

- Remove the accent picker UI. **Keep** `accentColor` and `themeMode` fields in
  `AppSettings` for backup compat + one-time migration.
- Migration maps legacy state to the new active theme:
  - `themeMode="light"` → Light · `"dark"` → Dark · `"black"` → Pure Black ·
    `"system"` → Pure Black (matches old system=black-on-dark) or Light per the
    install rule at first read.
  - `accentColor=X` → the active theme's `primary` token = that accent color.

## Token taxonomy (~30–35 tokens, 8 groups)

| Group | Tokens |
|---|---|
| Surfaces | appBackground, surface, surfaceVariant, dialog, outline, scrim |
| Text & icons | textPrimary, textSecondary, textDisabled, iconTint |
| Accent | primary, onPrimary, selection |
| Status | statusGood, statusWarn, statusDanger |
| Metric palette | metricVoltage, metricBattery, metricTemp, metricPosition, metricAccel |
| Gauge | gaugeTrack, gaugeFill, gaugeDanger |
| Nav / overlay | navRouteLine, navPopupPanel, navPopupInk |
| Indicators | connectionActive, connectionIdle |

- The ~448 existing `MaterialTheme.colorScheme.*` reads stay unchanged — we build
  the Material `colorScheme` from the active theme, so they reskin for free.
- The ~333 direct palette refs + chrome `Color(0x…)` literals get routed through
  the tokens (via `LocalAppColors`).

### Out of scope (stays fixed)

- **Overlay Studio** canvas chrome, editing handles, and user element fills — it
  is an intentionally fixed dark editing workspace + user content. (Its Material
  config dialogs still follow the theme for free.)
- **Service-mode** debug sheet already renders inside `MaterialTheme`, so it
  reskins incidentally — nothing to do.
- **Map controls are IN**: extend the existing Leaflet WebView color bridge
  (`nativeSetAccent(hex)` + map-type/bg injection in `ui/navigator/MapHtml.kt`)
  to push token colors for on-map controls + route line + markers.

## Architecture & performance (two regimes)

- **Editor OFF (default, 99.9%)**: colors come from a single **immutable**
  `AppColors` object via `LocalAppColors` (same mechanism as MaterialTheme). A
  stable object ⇒ zero extra recomposition; reads cost the same as today. None of
  the inspection machinery is in the tree. Performance == today's app.
- **Editor ON**: an `inspectMode` flag flips; themed accessors/modifiers then
  attach `onGloballyPositioned` bounds tagging, the hit-test registry, the target
  crosshair, pulse animation, drag-to-recolor, live preview. Entering/leaving the
  tool is one deliberate recomposition.

## Persistence (mirrors OverlayPresetStore)

- **Active theme** = one JSON field in DataStore (`activeThemeJson` +
  `activeThemeName`). Works with no backup folder; rides along with the existing
  settings backup automatically.
- **Working draft**: first edit forks the active theme into a single persistent
  "Working theme (unsaved changes)" (app-private). Built-ins never mutated. Combo
  shows e.g. "Dark — modified".
- **Saved themes** = `.json` files in the backup folder under `themes/`
  (`ThemeStore`, modeled on `OverlayPresetStore.kt`). Visible in the combo once a
  backup folder is configured.
- **Built-ins** = code constants, read-only.

## UI

- **Theme combo** (Settings → Appearance): always present. Lists built-ins + saved
  customs (+ "— modified" working draft). Selecting one makes it active.
- **"Theme editor" master toggle** (Settings → Appearance, off by default): when
  on, the floating widget appears and Save/edit become possible. When off, no
  widget, no save options — just the combo.
- **Floating widget**: draggable, collapsible (compact pill ↔ expanded panel),
  realtime. Token list grouped; tap a token → color editor. Theme-flip switcher.
  Target tool (tagged hit-test + pixel fallback; chooser when multiple tokens
  overlap). Save / Save-as. Discard prompt when switching themes with unsaved
  edits. Toggling the editor off hides the widget but keeps the draft.

## Behavioral defaults

- One working-draft slot. Switching the active theme with unsaved edits prompts
  "Discard unsaved changes?" before replacing the draft.
- Turning the editor toggle off hides the widget; it does not discard the draft.
