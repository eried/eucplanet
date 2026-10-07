# Aeon mapping repairs

Based on upstream main fac53425 (release line v0.19.0). This branch fixes existing command mappings only: no new controls, telemetry extensions, UI, capability declarations, firmware operations or experimental logging. Other Veteran models retain existing mappings.

## Headlight

Select existing ASCII SetLightON/OFF for Aeon, with no binary companion. This preserves the Boolean toggle, not remote selection of medium/high levels. Select by existing model identification (name or telemetry mVer44); unknown models keep the upstream mapping. No SND-based policy is introduced.

Owner PC capture, Aeon 503002, 2026-09-11: ASCII ON/OFF at20:55:48/20:56:04 with SND10 toggled the light without beeps; binary pair at20:56:21/20:56:37–38 beeped. Binary pair also beeped with SND0 at20:54:48/20:55:04–05. Physical light observations and captured readbacks agree. Earlier app tests reporting ASCII-associated beeps remain historical evidence; universal silence and proportional beep amplitude are not claimed. SND is panel key-tone volume, not a basis for selecting Bluetooth light packets.

Research ledger: aeon-pc-investigation-20260911.md in the owner's analysis workspace. Tests retain the upstream Lynx vectors and exercise name identification, captured Aeon telemetry identification and reset.

## Firmware corroboration and scope

The official Aero image downloaded on 2026-09-12 from `http://www.baat22.com:800/nfapp/firm/1/_main5020xx.bin` is byte-identical to EUC World's Aero image45. Edition502.0.06, 125236 bytes, SHA-256 `b2f213e4be112b343690a1676ab4a1ffed05b3fe6194d3e7dbd400a26176f7c6`. This is provenance from an official-app-derived endpoint plus an independent mirror match, not cryptographic manufacturer authentication.

In that image, ASCII SetLightON/OFF handlers at file offsets0xe7b4/0xe7ca store1/0 to light state RAM0x20000259 without calling the binary acknowledgement-parameter routine. Binary light routes use setter0x374c, which accepts only0/1. These are **confirmed in official Aero firmware code**, independently corroborating the command-path difference observed on Aeon. Neither proves universal silence or physical operation on an Aero we have not tested.

Thus these findings describe shared NOSFET protocol behavior with stronger cross-model evidence, rather than an arbitrary Aeon workaround. Runtime changes remain limited to Aeon: compatibility on other models/releases requires its own evidence. No Aero firmware was flashed to Aeon.
