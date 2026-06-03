# Project guidance for AI assistants

## Theming / colors (read before adding any UI color)

All UI color comes from the app's semantic theme-token system — never hardcoded
values — so everything stays themeable across the dark / light / custom themes
and is discoverable by the in-app color identifier.

- **Where the palette is defined:**
  `app/src/main/java/com/eried/eucplanet/ui/theme/AppThemeColors.kt` — the
  `AppThemeColors` token data class, the `ThemeTokens.specs` registry (drives the
  theme-editor list + color identifier), and `fillDerived()` (token fallbacks).
  The token set evolves, so this doc deliberately does **not** list the colors —
  read that file for the current palette.
- **In feature code:** read colors via `MaterialTheme.appColors.*`. Do **not**
  hardcode `Color(0x…)`, and do **not** use `MaterialTheme.colorScheme.*`
  directly in feature UI (the theme already maps every Material slot in
  `toColorScheme()`).
- **Controls must stay readable:** always set explicit on-colors (label / icon /
  content) that contrast the fill — never let a control's text match its
  background. Verify in both the dark and light built-in themes.
- **Need a color the palette doesn't have?** Add a token, don't hardcode one: a
  field on `AppThemeColors` (default `Color.Unspecified`), a `fillDerived()`
  fallback, and a `ThemeTokenSpec` in the registry.
- **Only exceptions:** code under `ui/theme/` (the palette + built-in theme
  definitions) and deliberate fixed colors over an always-dark surface (e.g. an
  icon on a dark scrim).
