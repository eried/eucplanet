# feature/android-auto

EUC Planet on an Android Auto screen. Built on next-experimental, so
everything in that build is here too. Ride it only when you can cut the ride
short.

## Worked on

- Android Auto: the map with your position and route, your stats beside it, and the wheel's buttons.
- Settings > Integration > Android Auto: pick the four stats and three buttons. Navigate is always first.
- Navigate offers Home and Work, the places saved in the route builder.
- Light or lock from the notification, a widget, the car screen or Flic now pauses that automation too.

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
- Does EUC Planet appear on your Android Auto screen and connect by itself?
- Stats update while riding? Speed, battery and the rest read the same as the phone?
- Horn, Light and Record work from the car screen?
- Navigate to Home or Work draws the route and shows the next turn?
- Night: the panel switches to dark colors with the screen?

## Reporting back

Open an issue with your phone, your Android Auto screen model and a photo of
the car screen. If it crashed, Share crash log on the About screen.
