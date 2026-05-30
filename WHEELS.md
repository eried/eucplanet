# Supported wheels

EUC Planet talks to a wheel through a per-family `WheelAdapter`; `CompositeWheelAdapter`
picks the right one from the BLE-advertised name at connect time.

| Tier | Wheels | What it means |
|---|---|---|
| **Verified** | InMotion V14 50GB / 50S, P6 | Author's daily wheel + telemetry/controls confirmed against labelled real-hardware captures |
| **Preliminary** | InMotion V12 HS / HT / Pro | Parser exists, not yet author-tested |
| **Preview** | KingSong S22 / S20 / S19 / S18 / S16 / KS-14/16/18 / F18P / F22P | Telemetry + commands implemented from the public protocol; needs a real-hardware tester |
| **Preview** | Begode/Gotway Master / Master Pro / T3 / T4 / RS / RS-HT / EX / EX.N / EX2 / MSP / MSX / Hero / XWay / Mten4 / Mten5 / MCM5 | same; high-voltage tiltback (>100 km/h) handled since v0.6.2 |
| **Preview** | Veteran Sherman / Sherman S / Sherman Max / Patton / Lynx / Abrams | same |
| **Preview** | InMotion V1 family: V5 / V8 / V8F / V8S / V10 / V10F / V10S / V10T / V10FT / L6 / Lively / Glide 3 | same |
| **Preview** | Ninebot Z6 / Z10, plus legacy One E / E+ / S2 / Mini (read-only) | same; Ninebot Z uses the documented XOR keystream encryption |
| **Experimental** | InMotion V11, V13, V9 | In the model registry; please file a wheel report if you try them |

Preview wheels are implemented from the spec docs in [`docs/protocols/`](docs/protocols/)
(KingSong, Begode, Veteran, InMotion V1, InMotion V2 / V14 / V12 / P6, Ninebot) but
haven't been tested against the actual hardware yet. If your wheel is here and you can
ride it: connect, then file a wheel report from the orange in-app banner. Telemetry
verification is the fastest path to upgrading the tier.

Want a wheel that's not listed? See the [BLE capture guide](docs/BLE_CAPTURE_GUIDE.md).
One labelled riding session and it can usually be mapped in a single pass.

Listed already but a reading looks wrong? See the
[in-app diagnostics guide](docs/DIAGNOSTICS_GUIDE.md).
