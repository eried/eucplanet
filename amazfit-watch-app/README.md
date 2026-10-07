# EUC Planet for Amazfit (Zepp OS)

The wrist dial for Amazfit watches, a Zepp OS mini program. Same settings,
same wire vocabulary and same visual language as the Wear OS (`wear/`) and
Garmin (`garmin-watch-app/`) companions. Setup, simulator recipe, wire
contract and limitations: [docs/AMAZFIT_SETUP.md](../docs/AMAZFIT_SETUP.md).

```
npm install          # once
npx zeus dev         # live preview in the Zepp OS Simulator
npx zeus preview     # QR code for a real watch (needs `zeus login`)
npx zeus build       # dist/*.zab
```

Layout:

- `app.json`: targets (T-Rex 3, Balance), pages, Side Service path.
- `page/index.js`: the dial (port of `garmin-watch-app/source/EucPlanetView.mc`).
- `app-side/index.js`: Side Service, relays polls to `http://127.0.0.1:28193`
  on the phone, where EUC Planet answers.
- `utils/protocol.js`: wire keys, mirrors `AmazfitProtocol.kt`.
- `assets/<target>/`: icons (from the Garmin drawables) and button images.
