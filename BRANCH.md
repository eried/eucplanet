# next-version

Staging branch for the next release of EUC Planet. Sits ahead of `main` with fixes and refinements being shaken out before they merge.

## What's new in this commit

> **Convention:** every build states whether the **HUD (MotoEye) APK** changed and its version, so MotoEye riders know when they must re-sideload it. The fix is often HUD-side and a phone-only update would silently miss it.

**HUD APK: UPDATED → `0.1.8`** (was `0.1.7`). **Re-sideload the HUD APK on your MotoEye** — the fix in this build is HUD-side, so a phone-only update will not pick it up.

- **fix(hud): the HUD now recovers on its own when it falls off your hotspot.** If you ride out of home-WiFi range (or the radio otherwise drops the hotspot), the HUD used to go dark until you rebooted it. It now detects the off-air state and reassociates itself. After a drop it reports a one-line self-heal summary into your shared diagnostics, e.g. `HUD self-heal: recovered after 9.2s off-air (reassoc x2 toggle x1) [#1]`.
- **Phone app also updated (wire protocol 1.9).** It logs the HUD's self-heal summary, and it no longer tears down the hotspot link when your *home* WiFi leaves range.

**Check the versions match:** on the phone, Settings → HUD shows the connected HUD's version; the MotoEye shows its own version on its on-screen banner. Both should read `0.1.8`.

## Who should test this

- **Owners of wheels affected by an active fix** flagged in the release notes section below ("What's new in this commit"). If your wheel isn't listed, the fix probably doesn't apply to you, but a quick smoke test (connect, dashboard, light/horn/alarm controls, a short ride with telemetry visible) is always welcome.
- **Anyone willing to validate that an in-progress change hasn't broken their setup.** Connect, ride, report.

## How to install

This is a CI-built debug APK. It is signed with the debug key, so it **cannot update an existing Play Store install in place**. To install:

1. Uninstall the Play Store version (note: this wipes local app data).
2. Sideload the phone APK from this release.
3. If you use a MotoEye / Android HUD, sideload the HUD APK too. **This build requires it** — see "What's new in this commit" above for whether the HUD changed and which version to expect.
4. If you use a Wear OS watch, sideload the wear APK too.

## Reporting back

Open an issue at https://github.com/eried/eucplanet/issues or message in the project's testing channel. Include:

- Wheel model + firmware version (visible on the dashboard once connected).
- Phone model + Android version.
- For BLE issues: what state the connection got stuck in, and if possible the diagnostics log (Settings -> Diagnostics -> Share).
