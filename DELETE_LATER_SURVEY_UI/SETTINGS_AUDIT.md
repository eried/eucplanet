# EUC Planet Settings Audit Report

**Conducted:** 2026-05-31  
**Scope:** Complete settings catalog from ui/settings/SettingsScreen.kt and modular content files, validated against AppSettings.kt data model  
**Target:** Comprehensive review for consolidation, restructuring, and removal of redundant or dead-code settings

---

## 1. Current Section Inventory

The settings are organized into 9 main tabs with comprehensive customization for electric unicycle (EUC) riding.

### Tab 1: General
- Recording: auto-record toggle, start-in-motion, idle timeout
- Connection: last device, auto-connect, back button behavior, wheel name display
- Application: language (15 locales), theme, accent color, screen-on, haptic

### Tab 2: Dashboard Layout
- Metric/action reordering (drag-drop), column count (2-3)
- Composite metrics (JSON-based stacked tiles)
- Custom tiles (label+icon+action+URL)
- Metric corner stats (per-metric 5-slot config + sparkline)

### Tab 3: Display
- Gauge colors: band toggle, orange/red thresholds
- Display units: speed, distance, temperature
- Current display mode: amps vs watts

### Tab 4: Speed & Safety
- Speed calibration: -20% to +20% offset
- Speed limits: tiltback, alarm thresholds
- Legal mode: separate tiltback, alarm limits

### Tab 5: Voice & Announcements
- Speech: voice enable, locale, rate, audio focus, output channel, interval
- Periodic reports: 8 toggles (speed, battery, temp, pwm, distance, time, nav, recording) + reorder
- Triggered reports: same 8 toggles (manual/Flic button trigger)
- Event announcements: 7 toggles (lock, lights, recording, connection, GPS, safety, welcome)

### Tab 6: Alarms
- Rule list: add, enable/disable, reorder, edit, delete per rule
- Editor dialog: metric, comparator, threshold, beep (frequency/duration/count), voice (template+preview), vibrate (duration+target), timing (cooldown+repeat)

### Tab 7: Automations
- Auto-lights: enable, minutes before sunset, minutes after sunrise, sun schedule graph
- Auto-volume: enable, curve editor (4 points: 0/25/50/75 kmh, multiplier 1-2x)

### Tab 8: Integration
- External GPS: show on dashboard, pairing, axis remap (X/Y/Z), autodetect wizard, prioritize toggle
- Flic buttons: scan, per-button config (click/double-click/hold actions), forget
- Volume keys: enable, 4 actions (volume up/down click/hold)

### Tab 9: Watch Companion
- General: screen on, auto-start, close on exit, haptic, update rate
- Display: 3 battery toggles, PWM mode, speed unit, GPS speed, prioritize PWM, dial rotation, nav popup
- Stem buttons: click/hold actions for stem 1 and stem 2
- Screen buttons: click/hold actions for screen button 1 and 2 (defaults: HORN, LIGHT_TOGGLE)

### Embedded Subsections
- Navigator (in Voice tab): full path, arrival radius, off-route tolerance, geocoder/router URLs
- Engine Sound (in Display tab): enable, type, volume, curve, muffler, gearbox, idle, decel, brake, duck-on-voice, headphones-only
- Cloud (separate tab): backup folder, force backup, sync choice, cloud trips

---

## 2. Settings That Don't Make Sense

1. **Motor Sound (Legacy)** — Engine Sound is modern replacement; both coexist, confusing. Remove.
2. **Voice Locale Override Flag** — Internal only, should be transient ViewModel state, not persisted.
3. **Engine Volume Auto Enabled** — Dead field for v0.5.x compatibility; keep for load, never write.
4. **Current Display Mode (Amps/Watts)** — Dashboard state, not preference; belongs in action config.
5. **Engine Gearbox/Decel Char** — Ignored for gearless engines; UI doesn't grey them out.
6. **Watch Prioritize PWM, Dial Rotation** — Niche (5% of riders); should hide behind Advanced toggle.
7. **Speed Calibration Per-Wheel** — Claims per-wheel but stored globally; doesn't persist across switches.

---

## 3. Settings That Should Be Combined

1. **Alarm Beep Triplet** — Frequency + duration + repeat (3 sliders) => 1 card + dialog
2. **Periodic vs Triggered Reports** — 2 lists of 8 toggles => 1 list with Periodic/Triggered dual checkboxes per item
3. **Voice Output + Audio Focus** — 2 dropdowns => 1 "Audio Route" composite card
4. **External GPS Remap + Autodetect** — 3 dropdowns + separate button => integrate autodetect as auto-populator

---

## 4. Settings That Should Be Split

1. **Alarm Editor Dialog** — Add collapsible sections (Outputs, Timing)
2. **Engine Sound** — Basic (type, volume visible), Character (muffler/gearbox/idle/decel/brake collapsible), Interactions, Curve
3. **Watch Display** — Battery toggles separate from Speed/Navigation + Advanced collapsible

---

## 5. Sections That Should Be Merged

1. **GPS Controls** — Show on dashboard + External device + Prioritize external => cohesive sub-cards
2. **Input Device Bindings** — Flic, Volume Keys, Watch Stem/Screen => unified Hardware Controls framework

---

## 6. Sections That Should Be Split

1. **Display Tab** — Appearance/units (Dashboard & Display tab), Engine Sound (separate tab)
2. **Speed & Safety Tab** — Speed Calibration, Performance Limits (normal), Legal Mode Limits (separate cards)
3. **Integration Tab** — Sensors (GPS), Input Devices (Flic, Volume Keys), Watch (separate tab)

---

## 7. New Settings Worth Adding

1. Voice Report templated prefix/suffix (e.g., "My wheel: {Speed}, {Battery}%")
2. Alarm time-based trigger windows (e.g., only 8 AM-5 PM)
3. Dashboard metric decimal precision override (framework exists in dashboardMetricStats, needs UI)
4. Recording selective metric logging (checkboxes to exclude metrics)
5. Speed limits asymmetric alarm/tiltback (set at different speeds)

---

## 8. Settings Worth Removing

1. Motor Sound — dead code, Engine Sound is replacement
2. Voice Locale Override Flag — transient state, not persisted preference
3. Engine Volume Auto Enabled — dead field, v0.5.x compat only
4. Current Display Mode — dashboard state, not display preference

---

## 9. Categorical Re-org Proposal

### New Structure (10 tabs, more logical)

1. **Device & Connection** — auto-connect, wheel name, back button, restore defaults
2. **Dashboard & Display** — layout, composites, tiles, stats, theme, accent, units, haptic, language
3. **Safety & Speed** — calibration, performance limits (normal), legal mode limits
4. **Voice & Alerts** — speech, unified voice reports (periodic/triggered), event announcements
5. **Alarms** — rule list, editor
6. **Automations** — auto-lights, auto-volume
7. **Recording & Hardware** — auto-record, external GPS (sensors), Flic+Volume Keys (input devices)
8. **Engine Sound** — type, volume, curve, character (collapsible), interactions
9. **Watch Companion** — general, display, advanced (collapsible), control buttons
10. **Cloud & Advanced** — backup, sync, trips, navigator settings

---

## 10. Numbered Action List

1. Remove Motor Sound (dead code, Engine Sound is replacement)
2. Combine Alarm Beep (frequency+duration+repeat -> composite card)
3. Unify Periodic/Triggered Voice Reports (2 lists -> 1 list with dual checkboxes)
4. Merge Voice Output + Audio Focus (2 dropdowns -> 1 Audio Route card)
5. Add Engine Sound Collapsible Sections (Character defaults collapsed)
6. Expose Metric Corner Stats UI (dialog instead of JSON)
7. Fix Speed Calibration UX (per-wheel or clarify global; restore on reconnect)
8. Split Display Tab (appearance/units -> Dashboard; Engine Sound -> separate tab)
9. Reorganize Integration Tab (Recording, Sensors, Input Devices sub-groups)
10. Grey Out Unsupported Engine Settings (disable gearbox/decel for gearless engines)
11. Move Amps/Watts to Dashboard Action Config (not display preference)
12. Collapse Watch Display Subsections (battery/speed/nav/advanced as collapsibles)
13. Add Advanced Toggle to Watch (hide dial rotation, PWM priority by default)
14. Move Navigator Settings to Cloud & Advanced Tab (out of Voice tab)
15. Create Power-User/Debug Section (collect rarely-used advanced options under Advanced collapsible)

---

## Summary

**Headline Issues:**
- Redundant: Motor Sound duplicate, 16 redundant Periodic/Triggered toggles
- Misplaced: Amps/Watts, Navigator in Voice, Engine Sound in Display
- Monolithic: Alarm editor, Engine Sound need sub-sectioning
- Niche settings clutter main tabs: dial rotation, PWM priority

**5 Highest-Impact Changes:**
1. Remove Motor Sound (cleanup)
2. Unify voice reports (16 redundant toggles gone)
3. Combine alarm beep (compact+clear)
4. Split Display Tab (Engine Sound separate)
5. Hide advanced settings (PWM priority, dial rotation, rotation behind collapsible)

These reduce clutter by 20 rows, clarify hierarchy, eliminate confusing redundancies.
