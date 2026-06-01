# CUSTOM BLE action — design

Status: **approved (design), pre-implementation**
Date: 2026-06-01
Branch: `fix/LK19486-horn` (will likely move to its own branch for implementation)

## 1. Motivation

Advanced users want to send wheel commands EUC Planet doesn't expose yet. The
trigger case: the in-app light toggle now drives the Veteran **high** beam
(`LkAp`/`LdAp` pair), so a rider who wants the **low** beam (`SetLightON`) needs
a way to send arbitrary bytes themselves. Generalize that into a draggable
**CUSTOM BLE** action: a dashboard button that writes user-defined bytes to the
connected wheel.

This must expand functionality for SOME wheels without affecting others — a raw
write goes to whatever is connected, so each custom command is scoped to a wheel
family and only fires on a match.

## 2. Decisions (locked with the user)

| # | Decision |
|---|----------|
| 1 | **Write-only** v1 — tap fires bytes, no response parsing, no state (like HORN). Schema leaves room for a future read. |
| 2 | **Whole raw frame(s)** — the user pastes complete bytes (e.g. from a btsnoop); sent verbatim. Captured frames already carry a valid CRC, so no CRC code is needed and it works for every family. Optional Veteran "payload + auto-CRC" mode is a future nicety, not v1. |
| 3 | **Family-scoped** — each command is tagged with a target `familyId` and only appears/fires on a matching wheel. Useless on other wheels is expected and acceptable. |
| 4 | **Multi-frame** — a command is an ordered list of frames, sent as separate writes (covers Veteran `LkAp`+`LdAp` pairs). |
| 5 | **Dashboard tiles only** for v1 (the "Actions buttons" section). Flic/volume/watch deferred. |

## 3. Approach

Mirror the existing synthetic-ID tile pattern already used by the customizable
dashboard, rather than invent a new command library or a dynamic `ActionCatalog`
layer:

- `dashboardActionOrder` is a CSV of action keys; group tiles already use a
  synthetic id `G:<uuid>` whose definition lives in `dashboardActionGroups`.
- Metric tiles use `M:<uuid>` (`dashboardCompositeMetrics`) and `C:<uuid>`
  (`dashboardCustomTiles`, the `{text,icon,action,url}` custom tiles).

CUSTOM BLE is the action-grid twin of `dashboardCustomTiles`: a new synthetic id
`B:<uuid>` in `dashboardActionOrder`, with its definition in a sibling JSON
field. Built-in actions, the dispatch path, and other wheel families stay
untouched; the feature is purely additive.

## 4. Data model

New field in `data/model/AppSettings.kt`, exact sibling of `dashboardCustomTiles`:

```kotlin
/**
 * Custom BLE action definitions as a JSON object keyed by "B:<uuid>". Value:
 * { "label":"Low beam", "icon":"FlashlightOn", "family":"veteran",
 *   "frames":["53 65 74 4c 69 67 68 74 4f 4e"] }
 * - frames: 1+ hex strings, each sent as one BLE write in array order.
 * - family: target adapter familyId; the command only fires on a match.
 * B:<uuid> ids appear in [dashboardActionOrder] like G:/group ids.
 */
val dashboardCustomBle: String = "{}",
```

Parsed model (new `data/model/CustomBleCommand.kt`):

```kotlin
data class CustomBleCommand(
    val id: String,            // "B:<uuid>"
    val label: String,
    val iconKey: String,       // reuse the existing icon-key table (custom tiles / WatchActionMeta)
    val family: String,        // adapter familyId: "veteran", "kingsong", ...
    val frames: List<ByteArray> // already-parsed bytes, sent in order
)
```

- Serialization lives with the other dashboard JSON in `data/store/SettingsJson.kt`
  (`put("dashboardCustomBle", ...)` / `optString(...)`), following the existing
  `dashboardCustomTiles` round-trip exactly.
- Stored hex is canonical; the editor's ASCII helper converts to hex on save.
- Read/merge rule matches the others: a `B:` token in the order CSV with no
  matching JSON entry is dropped; deleting a command removes both the token and
  the JSON entry.

## 5. Palette + drag

Add a **CUSTOM BLE** template to the actions palette next to the existing
**+ Group** template (the dashboard layout editor in `ui/settings/SettingsScreen.kt`).
Dragging it:

1. Generates `B:<uuid>`, defaulting `family` to the connected wheel's `familyId`
   (fallback: first family / "veteran").
2. Inserts the id into `dashboardActionOrder` at the drop position.
3. Opens the per-slot editor for immediate configuration.

## 6. Editor (per-slot bottom sheet)

Reuse the existing action-slot bottom sheet. When the slot id starts with `B:`,
render a CUSTOM BLE form:

- **Label** (text) — button caption.
- **Icon** (picker) — from the existing icon-key set.
- **Target family** (dropdown) — known adapter families; default = connected wheel.
- **Frames** (multiline) — one frame per line, hex (spaces / `0x` / bare all
  accepted). Live validation: even hex-digit count, per-line byte count, total
  frame count. A per-editor **ASCII** toggle converts typed text → hex on commit.
- Closing the sheet commits (matches the no-Save-button pattern).

Validation feedback:
- Invalid hex → inline error, command saved but flagged; dispatch no-ops a
  command with zero valid frames.
- A frame > 20 bytes shows a soft warning ("may be truncated on this wheel's
  MTU; split into multiple frames"). The wheel reassembles multi-frame writes by
  magic, same as the official app's 20+8 split.

## 7. Dispatch (single path)

All action dispatch already funnels through `FlicManager.executeAction(key)`
(the dashboard delegates via `dispatchActionByName`). Add one branch:

```kotlin
if (key.startsWith("B:")) {
    val cmd = customBleCommands(settings)[key] ?: return
    if (cmd.family != wheelRepository.connectedFamilyId) {
        // wrong wheel — ignore + brief toast
        return
    }
    wheelRepository.sendCustomBle(cmd.frames)
    return
}
```

- `wheelRepository.connectedFamilyId`: new accessor exposing the active adapter's
  `familyId` (CompositeWheelAdapter already tracks `active.familyId`); null when
  disconnected.

## 8. Repository

```kotlin
fun sendCustomBle(frames: List<ByteArray>) {
    frames.forEach { if (it.isNotEmpty()) bleManager.writeCommand(it) }
}
```

Each frame is one queued write, processed in order by the existing serial write
queue — the same mechanism the Veteran horn pair uses.

## 9. Gating ("don't mess other wheels")

- A command only fires when `cmd.family == connectedFamilyId`.
- In the editor / dashboard, a custom command whose family ≠ the connected wheel
  renders dimmed with a "for <family>" caption; tapping it on the wrong wheel is
  a no-op with a toast.
- Raw bytes never reach a wheel they were not authored for. Built-in actions are
  unaffected.

## 10. Surfaces

v1: dashboard action tiles only. Because dispatch is the shared `FlicManager`
path, adding Flic/volume/watch later is just exposing `B:` ids to those pools
behind a future `eyesFreeSafe` flag on the command (custom bytes are not assumed
eyes-free-safe by default).

## 11. Low-beam example (the concrete deliverable)

Ship a built-in template the user can drop and have working immediately:

- **Veteran low beam (on)** — `family:"veteran"`, `frames:["53 65 74 4c 69 67 68 74 4f 4e"]` (`SetLightON`).
- **Veteran low beam (off)** — `frames:["53 65 74 4c 69 67 68 74 4f 46 46"]` (`SetLightOFF`).

(Write-only means one button = one fixed command, so low beam ships as an
on/off pair, matching how the rider thinks about a separate low-beam switch.)

## 12. Testing

JVM unit tests (no Android runtime), matching the existing `ble` test style:
- `CustomBleCommand` JSON round-trip (parse/serialize, including multi-frame and
  malformed-hex rejection).
- Hex parser: spaces / `0x` / bare, odd-digit rejection, ASCII→hex conversion.
- Dispatch gating: family match fires, mismatch no-ops (logic extracted to a
  pure function so it's testable without Android).
- Low-beam preset bytes equal `SetLightON` / `SetLightOFF`.

UI (palette, drag, editor) verified by `assembleDebug` + emulator smoke, per the
project's standing build gate.

## 13. Out of scope for v1 (reserved, additive later)

- Reading wheel state (would need per-family response parsing/subscription).
- Veteran "payload + auto-CRC" authoring mode (we already have `buildVendorFrame`).
- Per-model gating (family is enough for now).
- Eyes-free surfaces (Flic / volume / watch).

## 14. Affected files

- `data/model/AppSettings.kt` — new `dashboardCustomBle` field.
- `data/store/SettingsJson.kt` — serialize/deserialize it.
- `data/model/CustomBleCommand.kt` — new parsed model + JSON helpers + hex parser.
- `flic/FlicManager.kt` — `B:` dispatch branch.
- `data/repository/WheelRepository.kt` — `sendCustomBle`, `connectedFamilyId`.
- `ui/settings/SettingsScreen.kt` — palette template + per-slot editor form.
- (strings.xml) — labels for the template, editor fields, toasts.
- tests under `app/src/test/.../ble` (or `.../data`).
