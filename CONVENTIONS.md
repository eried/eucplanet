# EUC Planet conventions

The binding summary is in `CLAUDE.md`. This file expands each rule with the why
and a concrete pointer. If the two ever disagree, fix them so they match; the
intent here is the source of truth.

No em-dashes appear in this file, on purpose (rule 2).

---

## 1. Globals live in Advanced settings

Any value that tunes global app behavior (a poll rate, a timeout, a threshold, an
estimator parameter) is declared once as a field on `AdvancedSettings` and once
as an entry in `ADVANCED_SPECS`
(`data/model/AdvancedSpec.kt`). The spec carries id, group, label, description,
range, step, unit, get/set, and optional format/parse. The UI, JSON
serialization, clamping, restore, and reset all iterate the registry, so adding a
knob is: one field, one spec, two strings.

Per-feature and per-alarm values (a single alarm's threshold, one screen's toggle)
stay local to that feature. Advanced is for app-wide tunables only.

## 2. No em-dashes anywhere, keep copy brief

Do not use the em-dash character in UI strings, code comments, or commit and PR
text. Use a comma, a parenthetical, " - ", or a second sentence. Keep
user-facing copy short: state what the setting affects, then the danger of a bad
value, in as few words as read cleanly.

## 3. Modern toast only

Every transient, user-facing message is a Material 3 Snackbar. From Compose use
`LocalSnackbar`. From background code with no Compose scope (services, singleton
repositories, intent handlers) call `AppNotifier.post(...)`; a single root host in
`MainActivity` shows it over whatever screen is on top. Never call
`Toast.makeText`; native toasts sit behind the keyboard and do not match the UI.

## 4. Button and control guidelines

- Numeric input uses the shared `NumberUpDown` (in `AlarmSettingsContent.kt`).
- In a settings row the control sits in a `weight(1.4f)` column and the
  description in a `weight(1f)` column, so the stepper is about half width.
- The number is right-aligned next to its unit (`numberAlign = TextAlign.End`)
  so there is no gap between value and unit.
- Paired or mutually exclusive choices use half/half segmented rows, not full
  width buttons stacked.
- Always set explicit on-colors (label, icon, content) that contrast the fill.

## 5. Drag-to-reorder uses the standard pattern

Reorderable lists use `sh.calvin.reorderable.ReorderableColumn` with a
`Icons.Default.DragIndicator` handle wired through `Modifier.draggableHandle()`.
See `ManageElementRow` in `StudioConfigSheets.kt` for the reference. Do not write
a new custom drag gesture.

## 6. Colors come from the theme

Read every color via `MaterialTheme.appColors.*`. Reuse an existing token before
adding a new one. Never hardcode `Color(...)` and never use
`MaterialTheme.colorScheme.*` in feature UI (the theme already maps Material slots
in `toColorScheme()`). When the palette genuinely lacks a color, add a token: a
field on `AppThemeColors`, a `fillDerived()` fallback, and a `ThemeTokenSpec`.
Full detail lives in the theming section of `CLAUDE.md`.

## 7. Read settings through SettingsRepository

Read settings only via `SettingsRepository.get()` or its `settings` Flow. Both
pass through `sanitized()`, which clamps every Advanced knob to its spec range, so
a 0, negative, or absurd value (including one from an imported or synced file)
can never busy-loop a `delay()`, divide by zero, or starve a loop. Every numeric
global must therefore have a spec range. Do not read `SettingsStore` directly.

## 8. Keep AppSettings under the 255-arg limit

The JVM and dex cap a method at 255 argument registers. A data class `copy()` (and
the synthetic `copy$default`) takes one argument per field, so a flat
`AppSettings` with too many fields crashes at runtime with an ART VerifyError on
the first `.copy()`. Group related fields into a nested data class (the pattern
`AppSettings.advanced: AdvancedSettings` uses) and expose typed getters if needed.

## 9. Restore-to-default affordance

Advanced and numeric settings show their default and offer a way back to it: a
per-row restore control (greyed when already at default) and a section-level reset
that confirms with a list of what will change. Defaults come from one source (the
data class constructor / `ADVANCED_DEFAULTS`).

## 10. Previews play the real configuration

A preview or test button reproduces exactly what the feature does in use. It must
not play or show an invented demo. If the real output is dynamic (it changes with
live input), the preview may simulate the input, but the output must be the
configured one. Example of the anti-pattern that was removed: a beep test that
played a rising scale instead of the configured beep.

## 11. Editor dialogs do not dismiss on outside tap

Editor and studio dialogs set `properties = DialogProperties(... dismissOnClickOutside = false)`
so a stray touch in the dialog margins cannot silently drop in-progress edits.
They close only through their explicit buttons or back.

## 12. Localize everything, EUC terminology, short labels

All user-facing text lives in `res/values/strings.xml` and is translated to every
supported locale. Keep button and label text short so it fits the control across
languages. Use electric-unicycle rider terminology in copy and translations: the
device is a wheel, not a bike, bicycle, motorbike, or car; the person is a rider,
not a cyclist or driver. Frame topics for EUC riding.

## 13. Prefer data-driven spec registries

When a screen would otherwise repeat the same boilerplate per item (read, write,
clamp, serialize, render), model the items as a registry of specs and iterate it.
`AdvancedSpec`/`ADVANCED_SPECS` and `ThemeTokens.specs` are the references. Every
registry gets a drift-guard unit test (round-trip, isolation, unique ids,
in-range defaults), like `AdvancedSpecTest`.

## 14. Verify builds by exit status, not by tail

Check a gradle build by grepping its output for `BUILD SUCCESSFUL` or
`BUILD FAILED`. Do not pipe gradle to `tail`; that masks the real exit code and a
failed build can look like it passed.

## 15. Branching

Rules and repo-wide docs (`CLAUDE.md`, this file) land on every long-lived branch:
`main`, `next-version`, and `next-experimental`. New features are developed on
`next-experimental` first.
