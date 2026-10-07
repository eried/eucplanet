# feature/android-auto

EUC Planet on an Android Auto screen. Built on next-experimental, so
everything in that build is here too. Ride it only when you can cut the ride
short.

## Worked on

- Android Auto is no longer a navigation app. It is a POI app, so it no longer
  fights Google Maps for the one navigation slot. The cost is real: no Navigate
  button and no turn card on the car screen.
- The rider's four metrics moved off our map and into the car's own content
  pane, which the head unit draws large. The map keeps speed alone, at 72sp.
- New map on/off button on the car's map controls. Map off gives the whole
  screen to the numbers: speed at 104sp, metrics at 44sp. The old panel was
  12sp labels and 20sp values.
- Settings > Integration > Android Auto still picks the four stats and the
  three buttons. Navigate is no longer one of them, so all three are yours.
- Light or lock from the notification, a widget, the car screen or Flic pauses
  that automation too.

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
