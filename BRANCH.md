# next-version

Integration branch for the next release. Bundles three feature branches.

## What's in it

**Size optimization**
- R8 code shrinking + resource shrinking on release builds; engine-sound
  clips re-encoded. Release APK drops from ~86 MB to ~18 MB with no
  behaviour change.

**Overlay Studio**
- A camera video / photo recorder with fully customisable live-data
  overlays: data values, dial and bar gauges, rolling graphs, free text
  with `{speed}`-style variables, images, the app badge and floating
  cameras.
- Multi-camera viewports, gradient / colour / image backgrounds, layout
  presets saved as `.json`, and dialog-free self-render H.264 + AAC
  recording (no MediaProjection consent prompt).

**Navigator**
- In-app route builder on a map, live turn-by-turn guidance with voice
  cues, a Treasure Hunt proximity mode, and an optional Wear OS popup
  mirror.

## Who should test this

- Everyone: install, open, connect to a wheel, confirm the dashboard
  updates normally.
- Overlay Studio: open it from the dashboard dial, add overlay elements,
  record a clip and take a photo.
- Navigator: build a route, run guidance, check the watch mirror if you
  have a paired watch.

## How to install

Debug-signed CI build. Uninstall any Play Store install first, then
install this APK. Reinstalling from Play later overwrites this build.

## Feedback

Open an issue at https://github.com/eried/eucplanet/issues tagged
`branch:next-version`.
