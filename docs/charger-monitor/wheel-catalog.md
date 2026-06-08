# Supported Wheel Catalog — line-drawing reference

Source of truth: the app's BLE detection code under
`app/src/main/java/com/eried/eucplanet/ble/` (model files, adapters, and
`CompositeWheelAdapter.pickAdapter()`). This list is what the app can actually
*identify*, so per-model line drawings only make sense for the models below.

Purpose: hand this (plus reference photos) to a drawing service to produce
per-wheel **line-art SVGs** for the Charging Monitor's liquid-fill graphic. See
**Asset specification** at the bottom for exactly what to commission.

~70 models / 6 families. `Photo` column is to be filled with reference image
links (see the photo-collection plan).

---

## 1. InMotion V2  (`inmotion_v2`)
Detection: BLE advertised-name prefix → adapter, then modelId from the MainInfo
reply. Model-specific. Source: `InMotionV2Model.kt`, `InMotionV2Adapter.kt`.

| Model | modelId | Class | Detect by | Photo |
|---|---|---|---|---|
| P6 | 21 | 84V | name `P6-*` |  |
| V9 | 121 | 84V | name `v9` |  |
| V11 | 61 | 100V | name `v11` |  |
| V11y | 62 | 100V | name `v11y` |  |
| V12 HS | 71 | 100V | name `v12` + modelId |  |
| V12 HT | 72 | 100V | name `v12` + modelId |  |
| V12 Pro | 73 | 100V | name `v12` + modelId |  |
| V12S | 111 | 100V | name `v12` + modelId |  |
| V13 | 81 | 126V | name `v13` |  |
| V13 Pro | 82 | 126V | name `v13` |  |
| V14 50GB | 91 | 84V (4200 Wh) | name `v14`/`adventure` | **reference (virtual wheel)** |
| V14 50S | 92 | 84V | name `v14` |  |

## 2. InMotion V1  (`inmotion_v1`)
Detection: V1 names routed to V1 adapter; model confirmed from slow-info ASCII
bytes (offset 104/107). Model-specific. Source: `InMotionV1Model.kt`.

| Model | modelId | Class | Detect by | Photo |
|---|---|---|---|---|
| R0 | 30 | legacy | name `r0`/`im30` |  |
| V5 | 50 | 67V | name `v5` |  |
| V5+ | 51 | 67V | name `v5+`/`v5plus` |  |
| V5F | 52 | 67V | name `v5f` |  |
| V5D | 53 | 67V | name `v5d` |  |
| L6 | 60 | low | name `l6` |  |
| Lively | 61 | low | name `lively` |  |
| V8 | 80 | 84V | name `v8` |  |
| Solowheel Glide 3 | 85 | 84V | name `glide`/`solowheel` |  |
| V8F | 86 | 84V | name `v8f` |  |
| V8S | 87 | 84V | name `v8s` |  |
| V10 | 140 | 84V | name `v10` |  |
| V10F | 141 | 84V | name `v10f` |  |
| V10T | 142 | 84V | name `v10t` |  |
| V10FT | 143 | 84V | name `v10ft` |  |
| V10S | 100 | 84V | name `v10s` |  |
| V10SF | 101 | 84V | name `v10sf` |  |

## 3. KingSong  (`kingsong`)
Detection: BLE advertised name is the sole identifier (`ks-*`, `s16`–`s22`,
`f18`, `f22`). Model-specific (voltage drives the % curve). Source:
`KingsongModel.kt`.

| Model | Class | Detect by | Photo |
|---|---|---|---|
| KS-14 | 67V | name `ks-14`/`ks14` |  |
| KS-16 | 67V | name `ks-16`/`ks16` |  |
| KS-16X | 84V | name `ks-16x` |  |
| KS-16S | 84V | name `ks-16s` |  |
| KS-18 | 84V | name `ks-18`/`ks18` |  |
| KS-S16 | 84V | name `s16` |  |
| KS-S18 | 84V | name `s18` |  |
| KS-S19 | 100V | name `s19` |  |
| KS-S20 | 126V | name `s20` |  |
| KS-S22 | 126V | name `s22` |  |
| F18P | 151V | name `f18` |  |
| F22P | 176V | name `f22` |  |

## 4. Begode / Gotway  (`begode`)
Detection: BLE name prefix, model from firmware banner. Model-specific with a
generic 84V fallback for unknown names. Source: `BegodeModel.kt`.

| Model | Class | Detect by | Photo |
|---|---|---|---|
| Mten3 | 84V | name `mten3` |  |
| Mten4 | 84V | name `mten4` |  |
| Mten5 | 84V | name `mten5` |  |
| MCM5 v1 | 67V | name `mcm5` |  |
| MCM5 v2 | 67V | name `mcm5` |  |
| MSX | 100V | name `msx` |  |
| MSP | 100V | name `msp` |  |
| Hero | 100V | name `hero` |  |
| EX | 100V | name `ex` |  |
| EX.N | 100V | name `ex.n`/`ex_n` |  |
| EX2 | 126V | name `ex2` |  |
| EX30 | 151V | name `ex30` |  |
| RS | 126V | name `rs_`/`rs-` |  |
| RS-HT | 134V | name `rs-ht`/`rsht` |  |
| T3 | 84V | name `t3` |  |
| T4 | 134V | name `t4` |  |
| Master | 134V | name `master` | **reference (virtual wheel)** |
| Master Pro | 151V | name `master`+`pro` |  |
| Race | 210V | name `race` |  |

## 5. Veteran  (`veteran`)
Detection: BLE name + authoritative `mVer` byte (frame offset 28) +
magic-byte auto-rescue. Model-specific. Source: `VeteranModel.kt`.

| Model | mVer | Class | Detect by | Photo |
|---|---|---|---|---|
| Sherman | 0/1 | 100V | name `sherman` / mVer |  |
| Abrams | 2 | 100V | name `abrams` / mVer |  |
| Sherman S | 3 | 134V | name `sherman s` / mVer |  |
| Sherman Max | (alias→S) | 134V | back-compat alias |  |
| Patton | 4 | 134V | name `patton` / mVer |  |
| Patton S | 7 | 134V | name `patton s` / mVer |  |
| Nosfet Aero | 43 | 134V | name / mVer |  |
| Lynx | 5 | 151V | name `lynx` / mVer |  |
| Lynx S | 9 | 151V | name `lynx s` / mVer |  |
| Sherman L | 6 | 151V | name `sherman l` / mVer |  |
| Nosfet Apex | 42 | 151V | name / mVer |  |
| Nosfet Aeon | 44 | 151V | name / mVer |  |
| Oryx | 8 | 218V | name `oryx` / mVer |  |

## 6. Ninebot / Segway  (`ninebot`)
Detection: BLE name → protocol (Z vs Legacy) + model. Source: `NinebotModel.kt`.

| Model | Class | Protocol | Detect by | Photo |
|---|---|---|---|---|
| Z6 | 63V | Z | name `z6`/`zn*` |  |
| Z10 | 84V | Z | name `z10` |  |
| One E | 63V | Legacy | name `one e` |  |
| One E+ | 63V | Legacy | name `one e+` |  |
| One S2 | 63V | Legacy | name `s2` |  |
| Mini | 36V | Legacy | name `mini` |  |
| Mini Pro | 36V | Legacy | name `mini pro` |  |
| Mini Plus | 63V | Z | name `miniplus` |  |

---

## Reference / virtual-wheel models (highest priority)
The app ships built-in simulators for these — they're the developer's primary
test wheels, so draw these first:

- **InMotion V14 50GB** (`V14VirtualWheel.kt`)
- **InMotion P6** (`P6VirtualWheel.kt`)
- **Begode Master** (`BegodeMasterVirtualWheel.kt`)

## Suggested drawing tiers (to keep the art commission sane)
- **Tier 0 — Generic EUC silhouette.** One fallback drawing for any
  unidentified / not-yet-drawn wheel. *Ship this first; everything works without
  per-model art.*
- **Tier 1 — Reference + popular current-gen.** V14, P6, Begode Master +
  Master Pro/RS, KS-S22/S20, Veteran Sherman/Patton/Lynx, InMotion V11/V12/V13.
- **Tier 2 — Remaining current-gen.**
- **Tier 3 — Legacy** (InMotion V5/V8/V10, KS-14/16, Ninebot Mini/One, older
  Begode/Gotway).

> **Body-shape grouping cuts the count.** Many models within a brand share one
> body shape (e.g. most Veterans, the V12/V13 pair, the KS-S line). One silhouette
> can cover several models with just a name label, so ~15–20 distinct drawings
> likely cover all ~70 listed models.

---

## Asset specification (what to commission)

**Format:** author in **SVG** (master), which converts cleanly to Android
`VectorDrawable` XML (the app's runtime format). Keep it convertible:

- **Paths only.** No `<text>`, no embedded raster (`<image>`), no filters/blur,
  no CSS classes — flatten to plain `<path>` elements.
- **Monochrome, no baked fill colour.** Use a single stroke/fill (e.g. black on
  transparent). The app tints it via the theme; the liquid colours are supplied
  at runtime.
- **Uniform viewBox across all wheels** — e.g. portrait `0 0 120 160`,
  wheel centered, same scale, so the fill-level maths is identical for every
  drawing.
- **Two layers/groups per wheel:**
  1. `outline` — the line-art contour + details (spokes, pedals, hub) as the
     visible drawing.
  2. `fill` — ONE closed path describing the interior region the "liquid" fills
     (the tyre/body silhouette). The app clips a rising gradient into this path,
     so its vertical bounding box = 0 %…100 %.
- **Optimized:** run through SVGO; keep total node count modest for Android
  vector perf.

Deliver per model: `wheel-<brand>-<model>.svg` (e.g. `wheel-inmotion-v14.svg`),
plus the generic `wheel-generic.svg`.
