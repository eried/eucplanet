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

### Terminal punctuation and paths

A descriptive string that is a sentence explaining something ends with a full
stop. Japanese and Chinese take their own full-width stop, not a Latin dot.
These are exempt, and the exemption is by name in
`HelperCopyPunctuationTest.exempt` so the reasoning stays readable:

- text-field placeholders and terse field hints ("4 to 8 digits")
- lists of values ("Garmin: Start / Amazfit: Select")
- anything ending in a URL, a format argument or markup, where a stop would
  read as part of the address or the value
- widget-surface strings, where the character is not worth the space

The rule follows the string's job, not its key, but the key is how the guard
finds it: `_desc`, `_description`, `_help`, `_hint`, `_subtitle`, `_body`,
`_explain`, `_note` and `_caption` are helper copy. A `_title` is a heading and
a `_toast` / `_failed` / `_error` is a transient message; neither is a sentence
of explanation, so neither takes a stop.

A navigation path uses an arrow, not an angle quote: "Settings -> Voice and
announcements", "Apps -> EUC Planet". Display copy names a site bare
(`eucstats.ried.no`); a full `https://` URL belongs only in a string that is
handed to an intent, and that string is `translatable="false"`.

`HelperCopyPunctuationTest` enforces the stop across all 23 languages.

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

Reorderable lists use `sh.calvin.reorderable.ReorderableColumn` with an
`Icons.Default.DragHandle` handle (the horizontal-lines glyph, not the 6-dot
`DragIndicator`) wired through `Modifier.draggableHandle()` and tinted from a
muted `appColors` token such as `textSecondary`. This is the list-reorder
affordance the alarm-rules, route-stop, and data-source lists use. The studio
canvas element manager still uses `DragIndicator` for historical reasons; new
lists should use `DragHandle`. Do not write a new custom drag gesture.

## 6. Colors come from the theme

Read every color via `MaterialTheme.appColors.*`. Reuse an existing token before
adding a new one. Never hardcode `Color(...)` and never use
`MaterialTheme.colorScheme.*` in feature UI (the theme already maps Material slots
in `toColorScheme()`). When the palette genuinely lacks a color, add a token: a
field on `AppThemeColors`, a `fillDerived()` fallback, and a `ThemeTokenSpec`.
Full detail lives in the theming section of `CLAUDE.md`.

## 7. Read settings through SettingsRepository

Read settings only through `SettingsRepository`. Every path passes through
`sanitized()`, which clamps every Advanced knob to its spec range, so a 0,
negative, or absurd value (including one from an imported or synced file) can
never busy-loop a `delay()`, divide by zero, or starve a loop. Every numeric
global must therefore have a spec range. Do not read `SettingsStore` directly.

Pick the read by how often it runs:

| Where | Use | Why |
| --- | --- | --- |
| UI, one-shot actions, anything that just wrote | `get()` | Reads the store itself, so it always sees a write that has returned. |
| Per frame or per tick (telemetry collector, alarm evaluators, watch, HUD and Garmin publish loops) | `currentOrLoad()` | A field read after the first load. `get()` re-parses the whole JSON blob, which those loops did about 35 times a second between them. |
| Reacting to changes | `settings` Flow, or `current` (a `StateFlow`) | Emits after every write. |

`current` mirrors the `settings` Flow, which re-emits after every write, so it
is never invalidated by hand and cannot go stale while the app runs. The one
difference from `get()` is timing: the new value arrives a moment after
`update()` returns, so code that writes and immediately reads back uses `get()`.
Before the store has been read once, `current` holds the defaults, which is why
the loops call `currentOrLoad()`: it waits for that first read, then never
suspends again.

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

## 16. Merging a PR: account for every deleted line, then run every surface

The watch-map PR (#25) replaced the line `flicManager.initialize()` in
`EucPlanetApp.onCreate` with `MapTileCache.start(...)`. It compiled, every test
passed, and Flic buttons were dead: no scan, no forget, no presses, and not one
log line, because every Flic call sits behind a `?: return` on a manager that
was never created. The review read the diff as an addition.

Before merging anything from outside:

- Take the diff and list every non-comment line it deletes, per file. For each
  one write down where it moved or why it should go. A refactor moves hundreds
  of lines and is fine; one lost line in a file the feature does not own is
  the thing to find. `git diff base..head | grep '^-'` is the starting point.
- Run every surface the PR could touch, live, not just the compile: phone on
  the emulator, watch on the paired Wear emulator, HUD on its emulator. Open
  the screens the PR changed and look at them.
- The suite guards the wiring: `AppStartupTest` pins the start list in
  `EucPlanetApp.onCreate`, and `LifecycleWiringTest` fails when any
  `@Singleton` with a `start()` or `initialize()` is called by nobody. A new
  process-wide subsystem gets its call in `onCreate` and its line in the first
  test. Both tests strip comments first, so commenting a call out is the same
  as deleting it.
