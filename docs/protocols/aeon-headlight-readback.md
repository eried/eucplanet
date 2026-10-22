# NOSFET Aeon headlight level readback

The Aeon reports the headlight level selected on its physical panel. Decode it
only for the identified Aeon, from a complete, CRC-valid 87-byte Veteran frame
with page ID 1 at byte 46. Byte 49 is an unsigned level:

| Raw byte | Level |
| --- | --- |
| 0 | Off |
| 1 | Low |
| 2 | Medium |
| 3 | High |
| Other, including 128 | Unknown |

The existing light tile displays the measured level. Missing, unknown,
disconnected and expired samples show `Light: ?`. Samples expire after eight
seconds by default; the timeout is in Advanced settings under Controls.
Unrelated pages do not refresh the sample timestamp. Expiry also runs when
notifications stop entirely. Disconnect and replacement by a different model
clear the cached level.

The Boolean light state used by the existing toggle follows known readback.
Unknown bytes retain the last known Boolean without inventing an OFF transition,
while the level label explicitly becomes unknown. Sending a command does not
create a measured level. Models without this readback retain their existing
command-tracked behavior.

## Polling and freshness

This follows the existing push-telemetry path, without a headlight polling loop.
`WheelRepository.runPollingLoop` uses the configurable `wheelPollIntervalMs`
(250 ms by default) for request/response wheels. Settings and extended-stat
queries share that loop at cycle-count intervals. Chart sampling and HUD/watch
reporting are separately paced and do not determine when BLE measurements arrive.

`VeteranAdapter`, including Aeon, returns empty realtime/settings poll commands;
`BleConnectionManager` ignores those commands. The wheel chooses when to send its
rotating telemetry pages. A headlight sample updates only on a valid page-1 frame.

`headlightReadbackMaxAgeMs` controls sample validity, not acquisition frequency.
It follows the existing `AdvancedSettings` and `ADVANCED_SPECS` convention, as do
other source-specific freshness limits such as `gpsFixMaxAgeSec`. The dashboard
schedules a single expiry per sample so silence can make the label unknown.
This sends no BLE traffic and does not periodically poll the UI or the wheel.
Coupling expiry to `wheelPollIntervalMs` would be misleading because that setting
does not control Aeon's push cadence. The BLE initial-data watchdog is also a
different concern: it checks for any data after connection, not the age of page 1.

## Captured evidence

Source: owner passive BLE capture `aeon-pc-capture-20260907-123125.txt`,
7 September 2026, local time UTC+03:00.

SHA-256: `8d1efd6345e5e7250b08eb5a1df336806d2198dff783582f9cc3552335100509`.

Reassembly of the original notifications yields 177 CRC-valid page-1 frames.
Byte 49 changes in this sequence during the owner's documented physical-panel
OFF / LOW / MEDIUM / HIGH / OFF cycle:

| Last fragment received | Byte 49 |
| --- | --- |
| 12:31:31.077 | 0 |
| 12:32:35.737 | 1 |
| 12:33:33.498 | 2 |
| 12:34:38.140 | 3 |
| 12:35:59.759 | 0 |

`AeonHeadlightFixture.kt` contains those five reassembled packets with their
original CRCs. The test splits them into 20-byte notifications before feeding
the production adapter. Additional synthetic cases are explicitly identified
and recompute CRCs; they are robustness tests, not physical observations.

Bytes 28..29 in these packets contain 44250 (`0xacda`), which identifies model
44 through the existing parser. This is a raw protocol identifier, not a claim
that the wheel's displayed firmware version is "44250". Earlier owner reports
also confirmed all four labels in the fork's Alpha 0.19.0 build. Neither the
installed build nor displayed firmware for those reports was independently
verified during this extraction.

The earlier fork ledger reports corroborating page-8 byte-47 observations.
This implementation deliberately uses page 1 only: independently timed pages
must not overwrite a more recent authoritative sample.

## Scope and validation limits

The existing Aeon ASCII `SetLightON` / `SetLightOFF` mapping from upstream PR #20
is retained. This adds no level-selection command, settings write, clock sync,
SND policy or capability change. Reading medium/high does not establish a way
to select them remotely. It does not establish equivalent readback on Aero,
Apex or other Veteran models.

This source is a passive PC capture, not a manufacturer-app btsnoop. The
contribution guide requests a manufacturer-app btsnoop for protocol changes;
that evidence remains to be supplied or its substitution agreed with the
maintainer before submission. The raw capture stays outside the repository;
only the five relevant packets and provenance are included here.

Unit tests cover captured levels, fragmentation, CRC rejection, page/length and
model isolation, unknown bytes, disconnects, unchanged light commands and label
freshness. They do not establish physical operation of this newly extracted
build. A stationary panel-cycle check, disconnect/reconnect check and UI review
in light/dark themes remain the hardware/UI acceptance checks.
