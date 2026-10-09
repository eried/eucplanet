# feature/android-auto

EUC Planet on an Android Auto screen. Built on next-experimental, so
everything in that build is here too. Ride it only when you can cut the ride
short.

## Worked on

- Android Auto is now a weather app, not a navigation or POI app. This is the
  split-screen fix. Android Auto only ever puts two things side by side, a
  navigation app and one card beside it, and a POI app can never be that card.
  A weather app can. The category is honest, the card carries your local
  temperature and wind on its top line.
- The map is gone from the car screen. On a head unit you are already looking
  at Maps, so a second, worse map in our card was noise. The space goes to the
  numbers instead.
- Still no Navigate button and no turn card, same as before.
- Fixed: the app closed when you started a route with EUC Planet's own
  navigation. The car screen was still telling Android Auto "navigation has
  started", which only a navigation app may do, and we stopped being one. The
  call is gone. Stop a route from the phone, where you started it.
- Fixed: the car panel no longer builds more rows than your head unit will
  draw. It asks the head unit how many it takes. The weather line goes last
  and only when there is room, so it never pushes out a metric you chose.
  If your panel is short, set the metric slots you do not need to None in
  Settings, Integration, Android Auto.
- The rider's four metrics moved off our map and into the car's own content
  pane, which the head unit draws large. The map keeps speed alone, at 72sp.
- The map-off view now survives a split. Josh reported that splitting "just
  makes the map smaller, the already too small telemetry window doesn't change";
  at that width the old layout wrapped mph one letter per line and clipped the
  tiles to a single character. Sizes now scale off the width we are given.
- New map on/off button on the car's map controls. Map off gives the whole
  screen to the numbers: speed at 104sp, metrics at 44sp. The old panel was
  12sp labels and 20sp values.
- Settings > Integration > Android Auto still picks the four stats and the
  three buttons. Navigate is no longer one of them, so all three are yours.
  That screen was still printing "Navigate is always the first button", which
  stopped being true when Navigate went. Line removed.
- Light or lock from the notification, a widget, the car screen or Flic pauses
  that automation too.

- Fixed, properly this time: the car screen would not open at all ("EUC Planet
  has encountered an unexpected error"). The real cause was the release build,
  not the head unit. Code shrinking removed a constructor the car library needs
  to hand a screen to the car, so the app died the moment it tried to draw.
  Every tester hit it and no emulator could, because emulator builds are not
  shrunk. The earlier guess about old head units was wrong, and that is why the
  second build failed the same way.

## Please test

**Android Auto**
- **Install from the Play link, not from GitHub.** Android Auto refuses to list a
  car app that was sideloaded, so the APK on this page will never appear in your
  car however you set it up. The earlier note here said to turn on Unknown
  sources: that setting covers media and messaging apps and does nothing for this
  one, so ignore it. Ask for the internal app sharing link, then on your phone open
  Play Store, Settings, tap the Play Store version seven times and turn on
  Internal app sharing, then open the link. Uninstall any sideloaded copy first if
  the install refuses.
- Does the car screen open at all now? The last build failed to open on some
  head units. If it still fails, Share crash log on the About screen.
- Stats update while riding? Speed, battery and the rest read the same as the phone?
- Horn, Light and Record work from the car screen?
- **Can you split with Google Maps or Organic Maps now?** This is the one
  question this build exists to answer. No emulator can tell us: Android Auto's
  split does not exist on any emulator, so you are the only way to find out.
- Does the weather line in the card show your local temperature and wind?
- Night: the panel switches to dark colors with the screen?

**MotoEye owners, three questions about the camera**
- With Android Auto running, can you still see the EUC Planet HUD app at all,
  or does Android Auto take the whole screen?
- With Android Auto running, does the MotoEye rear camera shortcut still work?
- Is your Android Auto wired or wireless?

The rear camera can never be drawn by the Android Auto screen: that code runs on
the phone and the camera is on the HUD, so nothing we ship can reach it. Your
answers decide whether our HUD app can put it on top instead.

## Reporting back

Open an issue with your phone, your Android Auto screen model and a photo of
the car screen. If it crashed, Share crash log on the About screen.
