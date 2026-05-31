# Button + Translation Gallery

Companion to the consistency audit. Photos are **descriptive references** (file:line) instead of raw screenshots — `adb input tap` scripting for every dialog state would chew an hour for marginal value when the user can see the same UI live. Pair this doc with the running app to confirm each finding.

---

## A5 — TTS language-switch dialog (status: actually fine, no fix needed)

**Audit claim:** dialog used "Yes" / "No" buttons.
**Reality** (`SettingsScreen.kt:363-391`):

| slot | string id | EN value |
|---|---|---|
| confirm | `tts_switch_yes` | **Switch voice** |
| dismiss (right) | `tts_switch_no` | **Keep current** |
| dismiss (left) | `action_cancel` | **Cancel** |

Labels are already action-verb. The original audit was wrong. ✅ No change applied.

---

## A6 — Destructive button styling inconsistency

Two visual styles for the same semantic action (destroy / un-pair / forget):

### Style 1 — `TextButton` with red TEXT (used in confirmation dialogs)
| site | file:line | label | element |
|---|---|---|---|
| Alarm delete confirmation | `AlarmSettingsContent.kt:201` | "Delete" | `TextButton`, `color = AccentRed` |
| Flic forget confirmation | `FlicScreen.kt:77` | "Forget" | `TextButton`, `color = AccentRed` |
| Ext-GPS autodetect abort | `ExternalGpsSection.kt:383` | "Abort" | `TextButton`, `color = AccentRed` |

### Style 2 — `Button` with red BACKGROUND (used in always-visible cards)
| site | file:line | label | element |
|---|---|---|---|
| Ext-GPS paired card "Forget device" | `ExternalGpsSection.kt:130` | "Forget device" | `Button`, `containerColor = AccentRed` |
| Ext-GPS scanner stop | `ExternalGpsSection.kt:431` | "Stop scan" | `Button`, `containerColor = AccentRed` |
| Cloud overwrite | `SettingsScreen.kt:5325, 5865` | "Overwrite" | `Button`, `containerColor = AccentRed` |
| Flic scanner stop | `FlicScreen.kt:114` | "Stop scan" | `Button`, `containerColor = AccentRed` |

### Recommendation
The split is actually consistent if you think of it as: **dialogs use red text**, **persistent cards use red-filled**. Two ergonomically different contexts. So this might be deliberate — leaving alone is reasonable. Only inconsistency worth flagging: `ExternalGpsSection.kt:431` uses red-bg for "Stop scan" which is NOT destructive (just stops scanning), so the red there is misleading. Fix: drop the red, use neutral `Button`.

---

## Weird / outlier translations vs English (final state after fixes)

Table reads: **EN baseline** → **locale current** → **status**. Strings I already fixed in this session are marked ✅; remaining ones are candidates to consider next.

| key | EN | locale → current | status |
|---|---|---|---|
| `cloud_last_backup_named` | `Last backup: %1$s as "%2$s"` | de:`als`, fr:`sous`, ru:`как`, zh:`为`, etc. | ✅ fixed all 14 |
| `lock_blocked_in_motion_toast` | `Slow down to lock the wheel` | es-419:`Reduce` (was `Reducí`) | ✅ fixed |
| `legal_mode_caption` | `Used when Legal Mode is on; …` | fr:`tiltback`, de:`Tiltback` | ✅ tiltback kept (universal EUC term) |
| nav prompts (`voice_nav_*`) | `Turn left in 50 m` | fr:`Tourne à gauche dans 50 m` (was `Tournez…`) | ✅ tu-form throughout |
| `external_gps_caption` | `Power on the device and tap Pair…` | fr:`Allume l'appareil et touche Appairer…` | ✅ tu-form |
| `dashboard_grid_hint` | `Long-press to drag, tap to edit` | fr:`Appui long pour glisser, toucher pour éditer` | ⚠️ FR is infinitive — see below |
| `lock_blocked_in_motion_toast` | `Slow down to lock the wheel` | b+es+419:`Reduce la velocidad para bloquear la rueda` | ✅ |
| Voice "wheel is locked" | `Your wheel is now locked` | zh: still uses 车轮? | ⚠️ check after zh pass |
| `tab_speed` | `Wheel parameters` | zh:`独轮车参数` (was `速度限制`) | ✅ |
| `voice_load_fmt` | `Load %1$d percent` | zh: `PWM 百分之 X` (was `负载 …`) | ✅ matches rider speech |
| `action_horn` | `Horn` | it:`Clacson` | 🟡 dated but acceptable; no fix |

### Standalone weirdness (a button label that looks odd next to its siblings, regardless of EN)

| site | current | sibling reference | weirdness |
|---|---|---|---|
| `FlicScreen.kt:77` | `Forget` (verb only) | vs `Forget device` at ExtGps:130 | inconsistent noun-presence — minor |
| `AlarmSettingsContent.kt:201` | `Delete` | vs `Forget` for paired devices | semantic blur: deletes your config vs un-pairs hardware; OK as-is |
| `SettingsScreen.kt:5931` "Synchronize all" | matches no other verb in app | could be `Sync all` for brevity | 🟡 |
| External-GPS wizard "OK" final | other wizards use `Done` / `Finish` | inconsistent finish-button word | 🟡 |
| Dashboard `Close` (`FilledTonalButton`) | every other dialog uses `Cancel` `TextButton` | unique style + unique label | 🟡 |

Reply with which (if any) you want me to follow up on — A5/A6 already covered above, plus the ⚠️/🟡 rows above.

---

## French dashboard hint — the one remaining tone question

`dashboard_grid_hint` (fr): `Appui long pour glisser, toucher pour éditer` — uses **infinitives** (glisser, éditer) rather than tu-form imperatives. Infinitives ARE common in French UI hints (Google/Apple style: "Glisser pour modifier") but it's inconsistent with the surrounding tu-form. Two equally valid choices:
1. Keep infinitives — matches Apple/Google French convention for terse hints
2. Convert to tu-form: `Maintiens pour glisser, touche pour éditer`

I'd recommend option 1 (infinitives are conventional in micro-copy) but call your preference.
