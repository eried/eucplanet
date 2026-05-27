# next-version

Staging branch for the next release of EUC Planet. Sits ahead of `main` with fixes and refinements being shaken out before they merge.

## Who should test this

- **Owners of wheels affected by an active fix** flagged in the release notes section below ("What's new in this commit"). If your wheel isn't listed, the fix probably doesn't apply to you, but a quick smoke test (connect, dashboard, light/horn/alarm controls, a short ride with telemetry visible) is always welcome.
- **Anyone willing to validate that an in-progress change hasn't broken their setup.** Connect, ride, report.

## How to install

This is a CI-built debug APK. It is signed with the debug key, so it **cannot update an existing Play Store install in place**. To install:

1. Uninstall the Play Store version (note: this wipes local app data).
2. Sideload the phone APK from this release.
3. If you use a Wear OS watch, sideload the wear APK too.

## Reporting back

Open an issue at https://github.com/eried/eucplanet/issues or message in the project's testing channel. Include:

- Wheel model + firmware version (visible on the dashboard once connected).
- Phone model + Android version.
- For BLE issues: what state the connection got stuck in, and if possible the diagnostics log (Settings -> Diagnostics -> Share).
