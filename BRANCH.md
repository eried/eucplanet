# fix/inmotion-v8s

0.22.0 with fixes for the InMotion V8S (and the other InMotion V1 wheels:
V5, V8, V8F, V10, L6, Glide 3). Everything else is the same as the release.

## Worked on

- V8S: commands go to the wheel in small pieces like EUC World does, so the link should stop dropping.
- V8S: the light button and auto-lights follow the wheel's real light state.
- V8S, V8F, V10: shows Charging as soon as the charger is plugged in.
- Lights: a reconnect while walking the wheel no longer turns the light on and off again.

## Please test

**InMotion V8S (or any V1 wheel)**
- Ride as long as you usually do: how many times does it disconnect, compared with EUC World?
- Turn the light on and off with the wheel's own button: does the app's Light button follow within a few seconds?
- After dark with auto-lights on, walk the wheel slowly: the light stays off, also after a disconnect?
- Plug in the charger: does Charging show within seconds?

## Reporting back

Say which wheel and firmware. For disconnects, a Service Mode log covering a
few drops is worth more than a description.

---

Each branch keeps its own BRANCH.md, and this one changes as the work does.
