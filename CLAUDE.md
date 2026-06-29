# Project guidance for AI assistants

These rules are binding. The terse list below is the contract; `CONVENTIONS.md`
(repo root) holds the same rules expanded with examples and rationale. Read
`CONVENTIONS.md` before non-trivial UI or settings work.

## Hard rules

1. **Globals live in Advanced settings.** Every global tunable is an
   `AdvancedSettings` field plus an `ADVANCED_SPECS` entry. Per-feature and
   per-alarm values stay local to that feature.
2. **No em-dashes anywhere** (UI strings, code comments, commit and PR text).
   Keep copy brief: say what it affects, then the danger. Use commas, " - ", or
   separate sentences instead.
3. **Modern toast only.** Transients go through `LocalSnackbar` (Compose) or
   `AppNotifier.post()` (background, no Compose scope). Never `Toast.makeText`.
4. **Button and control guidelines.** Numeric input uses `NumberUpDown` (settings
   rows put the control in a `weight(1.4f)` column, description in `weight(1f)`,
   number right-aligned to the unit). Paired choices use half/half segmented
   rows. Always set explicit on-colors.
5. **Drag-to-reorder uses the standard pattern:** `ReorderableColumn` with an
   `Icons.Default.DragHandle` handle (the horizontal-lines glyph, not the 6-dot
   `DragIndicator`) wired through `Modifier.draggableHandle()` and tinted from a
   muted `appColors` token. This matches the alarm-rules, route-stop, and
   data-source lists. Do not invent a new reorder gesture or pick a different
   drag glyph.
6. **Colors come from the theme.** Read via `MaterialTheme.appColors.*`; reuse an
   existing token before adding one; never hardcode `Color(...)` or use
   `MaterialTheme.colorScheme.*` in feature UI. See the theming section below.
7. **Read settings through `SettingsRepository`** (`get()` / `settings` Flow) so
   values pass `sanitized()`. Every numeric global needs a spec range so it is
   clamped. Never touch `SettingsStore` directly.
8. **Keep `AppSettings` under the 255-arg JVM/dex limit.** Nest grouped fields
   (like `AdvancedSettings`) so `copy()` does not exceed the limit and crash with
   an ART VerifyError.
9. **Restore-to-default affordance** for advanced and numeric settings (per row
   plus a section reset), and show the default value.
10. **Preview and test buttons play or show the user's real configuration**, never
    an invented demo. Dynamic output may simulate input but must reproduce the
    configured result.
11. **Editor and studio dialogs set `dismissOnClickOutside = false`** so a stray
    tap cannot drop in-progress edits.
12. **Localize all user-facing text.** Every string lives in `strings.xml` and is
    translated to all supported locales. Keep button and label text short so it
    fits. Use electric-unicycle rider terminology: the device is a wheel (not a
    bike, bicycle, motorbike, or car) and the user is a rider (not a cyclist or
    driver).
13. **Prefer data-driven spec registries** over per-item boilerplate
    (`AdvancedSpec`, `ThemeTokens.specs`). Every registry gets a drift-guard test.
14. **Verify builds** by grepping for `BUILD SUCCESSFUL` / `BUILD FAILED`. Never
    pipe gradle to `tail` and mask the exit code.
15. **Branching.** Rules and repo-wide docs land on every branch (main,
    next-version, next-experimental). New features are built on
    next-experimental.

## Theming / colors (read before adding any UI color)

All UI color comes from the app's semantic theme-token system, never hardcoded
values, so everything stays themeable across the dark, light, and custom themes
and is discoverable by the in-app color identifier.

- **Where the palette is defined:**
  `app/src/main/java/com/eried/eucplanet/ui/theme/AppThemeColors.kt`: the
  `AppThemeColors` token data class, the `ThemeTokens.specs` registry (drives the
  theme-editor list and the color identifier), and `fillDerived()` (token
  fallbacks). The token set evolves, so this doc deliberately does **not** list
  the colors. Read that file for the current palette.
- **In feature code:** read colors via `MaterialTheme.appColors.*`. Do **not**
  hardcode `Color(0x...)`, and do **not** use `MaterialTheme.colorScheme.*`
  directly in feature UI (the theme already maps every Material slot in
  `toColorScheme()`).
- **Controls must stay readable:** always set explicit on-colors (label, icon,
  content) that contrast the fill. Never let a control's text match its
  background. Verify in both the dark and light built-in themes.
- **Need a color the palette doesn't have?** Add a token, don't hardcode one: a
  field on `AppThemeColors` (default `Color.Unspecified`), a `fillDerived()`
  fallback, and a `ThemeTokenSpec` in the registry.
- **Only exceptions:** code under `ui/theme/` (the palette and built-in theme
  definitions) and deliberate fixed colors over an always-dark surface (e.g. an
  icon on a dark scrim).
