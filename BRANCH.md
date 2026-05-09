# more-wheels-support

## What this branch adds

Five new BLE protocol families, taking the app from "InMotion + P6" to a
multi-brand companion. Implementations are clean-room from public spec
docs that live under `docs/protocols/` (one per family). Telemetry and
controls compile and pass static audits; none of these have been ridden
on real hardware yet — riders willing to test and file a wheel report
are how these graduate from **Preview** to **Verified**.

This branch is built on top of `main` 0.3.1, which already ships the
V14, V12, P6 and **Galaxy Watch Ultra / Wear OS 5+ companion**. All of
that is included in this APK pair (`-phone.apk` + `-wear_os.apk`); the
watch dial, three-battery row, accent + imperial follow-the-phone, and
horn / light remote controls all keep working. Existing wheels are
unchanged — this branch only adds new families.

Concretely shipped here:

- **KingSong** (HM-10 0xFFE0/0xFFE1, 20-byte AA 55 fixed frames). Covers
  S22, S20, S19, S18, S16, KS-14/16/18, F18P, F22P. Live telemetry
  (speed, voltage, current, battery, temperatures, trip, total odometer,
  PWM, ride mode), settings reads, horn / light / lock commands, and
  BMS detail packets.
- **Begode / Gotway** (HM-10, 24-byte BIG-ENDIAN 55 AA frames with
  multi-tag dispatch and a 5A re-sync injection). Per-model voltage tier
  scaler covers seven battery sizes (84 V → 134 V). Master, Master Pro,
  T3, T4, RS, RS-HT, EX, EX.N, EX2, MSP, MSX, Hero, Mten4, Mten5, MCM5.
- **Veteran** (HM-10, DC 5A 5C magic header, word-swapped u32 distance
  quirk). Sherman / Sherman S / Sherman Max / Patton / Lynx / Abrams.
  Optional CRC32-BE on long BMS frames.
- **InMotion V1 family** (proprietary 0xFFE4/0xFFE9 with 0xA5 byte
  stuffing and 32-bit CAN-IDs). V5, V8, V8F, V8S, V10, V10F, V10S, V10T,
  V10FT, L6, Lively, Glide 3.
- **Ninebot** — both the modern Z6 / Z10 (Nordic UART, 5A A5 framing,
  16-byte XOR keystream `gamma[(i-3) % 16]` with a `GetKey 0x5B`
  handshake) and the legacy One E / E+ / S2 / Mini family (HM-10, 55 AA
  framing, plaintext, read-only).

## Architecture

- One `WheelAdapter` per family (`KingSongAdapter`, `BegodeAdapter`,
  `VeteranAdapter`, `InMotionV1Adapter`, `NinebotAdapter`) plus the
  existing `InMotionAdapter` for V14 / V12 / P6 — six total.
- `CompositeWheelAdapter` routes connect-time by the BLE-advertised
  name. The legacy InMotion V2 path stays the default; family-specific
  prefixes (KS-, Master, Sherman, IM-, Z6, etc.) are matched first.
- `BleScanner.isLikelyWheel` recognises advertising names from all six
  families so the scan list shows a wheel instead of a generic device.
- Each parser publishes through the same `WheelTelemetry` flow — the
  rest of the app (dashboard, alarms, voice, recording, watch) is
  unchanged.

## Credits & attribution

Public protocol research used to write the KingSong, Begode and Veteran
adapters comes from **Ilya Shkolnik and the WheelLog community**. Their
spec work made this possible; the code here is independently written
against `docs/protocols/`. The Credits tab in the in-app About dialog
attributes them, plus Gio (Wheel In Motion) for P6 and Wear OS testing,
and InMotion for the V14.

## Who should test this

- **KingSong, Begode, Veteran, InMotion V1, Ninebot owners.** Connect,
  ride a known route, and file a wheel report through the orange
  in-app banner (or open an issue with the same data). Verifying
  battery %, speed, voltage and temperature against the wheel's own
  display is enough for an initial pass.
- **V14 / V12 / P6 owners**: a regression check is welcome too — the
  composite-routing change and shared `BleScanner` code touch your
  path. Existing telemetry should match `main` exactly.

## Known limits

- **All five new families are in Preview.** Parser + commands compile
  and pass static audits, but none have been validated against the
  actual wheels.
- **No real-hardware capture for Ninebot Z encryption yet.** The
  keystream and `GetKey 0x5B` handshake are implemented from the spec
  doc; the first paired Z6 / Z10 will tell us whether the handshake
  needs adjustment.
- **Legacy Ninebot is read-only on purpose.** Plaintext frames don't
  carry write commands the modern app exposes.

## Feedback

File issues at https://github.com/eried/eucplanet/issues. Please tag
with brand and model — that's the fastest way to upgrade your wheel's
tier.
