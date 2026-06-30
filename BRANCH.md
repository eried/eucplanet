# next-version

Staging branch for the next release of EUC Planet. Sits ahead of `main` with fixes and refinements being shaken out before they merge.

## What's new in this commit

> **Convention:** every build states whether the **HUD (MotoEye) APK** changed and its version, so MotoEye riders know when they must re-sideload it. The fix is often HUD-side and a phone-only update would silently miss it.

**HUD APK: UPDATED → `0.1.11`** (was `0.1.8`). **Re-sideload the HUD APK on your MotoEye** — these fixes are HUD-side, so a phone-only update will not pick them up. (Pair it with the latest phone APK for the new advisory, wire protocol 1.10.)

- **fix(hud): the HUD no longer reboots its Wi-Fi in a loop on the first connection.** A regression in 0.1.8/0.1.9 made the HUD treat "the phone isn't answering" (e.g. phone in your pocket, screen off) as "I'm off the air" and reboot its Wi-Fi driver over and over, so the first connection wouldn't settle until you woke the phone. The HUD now judges its own radio state only (am I associated + do I have an IP) and sits quietly waiting for the phone to connect — and never touches the radio during the very first boot association.
- **fix(hud): the HUD recovers on its own when it genuinely falls off your hotspot** (ride out of home-Wi-Fi range, single-radio channel-follow). No more reboot; it reassociates and reports a one-line self-heal summary into your shared diagnostics, e.g. `HUD self-heal: recovered after 9.2s off-air (restart x1 toggle x1) [#1]`. Recovery is now faster (~8–12 s).
- **feat: "Phone Wi-Fi interrupting HUD" advisory.** When the phone detects its OWN Wi-Fi keeps dropping the link (it joins/leaves home Wi-Fi while hotspotting), the HUD shows a small bottom-left badge: *turn off phone Wi-Fi for a stable link.* Needs the latest phone APK.
- **fix(hud): the "PORT" label on the disconnected screen is no longer clipped.**

**Check the versions match:** on the phone, Settings → HUD shows the connected HUD's version; the MotoEye shows its own version on its on-screen banner. The HUD should read `0.1.11`.

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
