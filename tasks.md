# EUC Planet — pending tasks

Work through these **one at a time**. Complete a task fully, verify it builds,
check it off (`- [ ]` → `- [x]`), then stop. Tasks are roughly ordered; the
recording bug (0) is independent, the rest are navigation + assorted fixes.

## Recording

- [x] **0. Recording is broken** — the resulting video is multi-hour long and
  stuck on a few frames. Recording quality/output got worse than before.
  Investigate the recording pipeline and fix so output is correct duration and
  smooth.
  *Fixed: the MediaCodec Surface-input path produced nanosecond timestamps read
  as microseconds (multi-hour clips). Reverted to the buffer path with real
  wall-clock timestamps and multi-threaded the ARGB->YUV conversion so it stays
  fast. Verified on the emulator: a ~72 s recording produces a 71.6 s clip.*

## Navigation

- [x] **1. "Wrong way" timing** — say "Wrooooong waaay" much later / less
  eagerly than it does now.

- [x] **2. Recalculation logic** — confirm the app recalculates the route when
  the user goes far from the original path or repeatedly travels the wrong way.
  Right now it seems not to: routing makes no sense — it announces the next
  goal, then "goal reached", then "wrong way", as if it is not travelling with
  the user or recalculating. Make guidance much less active/chatty and make
  recalculation actually track the user.

- [ ] **3. Arrival radius + on-map circles + per-point override** — make the
  default arrival radius (target-to-reach) bigger. Show it on the map as a
  faint dotted circle around each marker. Investigate saving the radius into
  the GPX so each point can have its own custom radius; the value in Settings
  then becomes only the default.

- [ ] **4. Units for tolerances** — make sure the arrival radius, off-route
  tolerance, and similar distance values display in the app's configured units
  (feet/meters, etc.), not a hardcoded unit.

- [x] **5. Rename "Goal" in voice** — when there is more than one stop, call it
  "Next stop"; call the final one "Destination" in the voice announcements.

- [x] **6. Start point is not a goal** — the first point (where the user
  starts) should not be announced/treated as a goal.

- [x] **7. Announce recalculating** — say "Recalculating" when the route is
  recalculating.

- [ ] **8. Home / Work presets** — add Home and Work presets, settable from the
  map or the list of goals. When searching, suggest these presets.

## Trip recorder / details

- [x] **9. "View online" opens external browser** — in the Trip recorder, when
  the user taps "View online", open it in the system browser, not an in-app
  WebView.

- [x] **10. Share dialog title** — in Trip Details, the share dialog should not
  show the "Trip" title.

- [ ] **11. Map layer selector in Trip Details** — the map in Trip Details
  should have a layer selector (satellite, etc.), like the one in the
  Navigation editor.

## Settings

- [x] **12. Settings cleanup** — remove "Default travel mode". Move the "Voice
  guidance" option into the "Voice & announcements" section.

## Navigation UI

- [ ] **13. Mini-map on navigation popups** — while navigation is running, the
  arrow popups should have a small map near the minimize button that reopens
  the map screen. Because navigation is running, search and the stop editor are
  disabled on that map; the user can still tap "Stop navigation" or change the
  mapping mode.

- [x] **14. Current-location marker z-order** — in the stop's navigation
  editor, draw the current-location marker on top of the navigation lines and
  make it bigger.

- [x] **15. Section-source button hidden by rounded corners** — on the Pixel
  Pro the rounded screen corners hide the "Section N source" button. Move it
  down and restyle it as a label with the top-left and bottom-left corners very
  rounded and the right corners square.
