# NOSFET Aeon alarm-speed mapping repair

This changes only the existing alarm-speed command for identified Aeon wheels. No headlight changes, new controls, capability changes, telemetry decoding, UI, firmware operations or legal-mode flow changes. Unknown models and other Veteran models retain their existing alarm packet. The existing 1..99 km/h application clamp is preserved; no off sentinel is introduced.

## Verified from Aeon capture

Wheel firmware503002 (503.0.02), model key44. The generic 17-byte LkAp alarm packet did not change either alarm-setting readback in isolated PC tests. A 19-byte LdAp settings-bank2 command, value at zero-based wire14, did:

```text
2026-09-11 21:40:19.772 +03:00, 34 km/h:
4c 64 41 70 13 01 02 80 80 80 80 80 80 80 22 e9 29 46 92
2026-09-11 21:40:36.113 +03:00, restore 35 km/h:
4c 64 41 70 13 01 02 80 80 80 80 80 80 80 23 9e 2e 76 04
```

Common-header alarm and page8 byte54 both changed35→34→35; tiltback stayed42. Restoration was confirmed21:40:52.474. A fresh BLE-session repeat at21:42 reproduced both changes. Original vectors, including CRC32-BE, are used in JVM tests. Research sources: owner's aeon-controlled-20260911-213954.jsonl (TX lines574/962) and aeon-investigation-backlog-20260911.md.

This verifies setting acceptance/readback, not physical alarm enforcement while riding. No wheel operation is performed by the tests.

## Confirmed in official Aero firmware code

Aero edition502.0.06, 125236 bytes, downloaded2026-09-12 from the official-app-derived endpoint `http://www.baat22.com:800/nfapp/firm/1/_main5020xx.bin`. Identical to EUC World's Aero image45. SHA-256: `b2f213e4be112b343690a1676ab4a1ffed05b3fe6194d3e7dbd400a26176f7c6`. HTTP provenance and mirror match are not cryptographic manufacturer authentication.

Dispatcher at file offsets0x5e86..0x5f02 loads bank2 wire14 and calls setter0x380c, which writes valid speed into configuration RAM0x20008fa4+0x35. The page8 serializer reads the same byte. Code locations here are file offsets, not assumed runtime addresses.

Thus the replacement field mapping is confirmed in official Aero firmware code and independently verified from Aeon capture. It was not merely copied from another app's proposed write. This is evidence of shared NOSFET semantics, but rollout is Aeon-only: other model/version compatibility has not been established. No Aero image was flashed to Aeon.
