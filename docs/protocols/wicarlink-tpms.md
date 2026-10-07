# Wicarlink valve-cap TPMS (BLE advertisement)

Protocol reference for the screw-on Bluetooth tyre-pressure caps built on
Shenzhen Wicarlink's platform and sold under a long list of retail names.
Implementation target: `app/src/main/java/com/eried/eucplanet/tpms/LyTpmsDecoder.kt`.

This document describes the wire format only. No code is reproduced from the
vendor application. See "Attribution" at the end of the file.

Status: confirmed. The field layout below is the vendor application's own,
cross-checked against a rider's gauge at several pressures, a flat tyre and two
temperatures.


## 1. Which sensors this covers

One hardware platform, many badges. Every application listed here ships the
same `com.wicarlink.zl` parsing code, so a cap bought under any of these names
decodes identically:

| Retail name | Android package |
| --- | --- |
| LY TPMS | `com.zl.dev.tire.lytpms` |
| ITPMS (K) | `com.zl.dev.tire.itpms` |

Also sold as generic "Bluetooth TPMS", "Smart TPMS" and "external valve cap
TPMS" on the usual marketplaces, typically in packs of two or four with a
plastic cap body and a CR1632 or CR2032 cell. If an application's package name
begins `com.zl.dev.tire.` or its APK contains `com.wicarlink.zl`, it is this
family.

**Not** the same as the other cheap BLE TPMS families:

| Family | Company ID | Payload | Pressure field |
| --- | --- | --- | --- |
| **Wicarlink** (this document) | `0x00AC` | 15 bytes | count x 3.144, split bytes |
| ZEEPIN / "TY" | `0x0001` | 16 bytes | LE u32, thousandths of a kPa |
| "BR" / SYTPMS | service `0x27A5` | 7 bytes | tenths of a psi |


## 2. Identifying the advertisement

| Property | Value |
| --- | --- |
| Manufacturer ID | `0x00AC` |
| Manufacturer payload length | 15 bytes |
| Last 6 payload bytes | the advertiser's own MAC, reversed |

The manufacturer ID is squatted: the Bluetooth SIG assigned `0x00AC` to Green
Throttle Games, an Android games-controller company that shut down in 2014.
Nothing about the vendor can be read from it, and it must not be treated as a
vendor identifier.

All three checks are needed together. Repeating your own MAC inside an
advertisement is common enough that it is worthless alone: a single 30-second
sweep of one room turned up three unrelated devices doing it.

The cap advertises **only when the pressure changes**. A settled tyre produces
nothing for minutes at a time, which is normal and not a fault. Any UI that
shows a last-known reading must say when it went quiet, or a frozen number
reads as a live one.


## 3. Payload layout

Byte offsets are into the manufacturer payload **after** Android strips the
two-byte company ID. The vendor application reads the whole advertisement, so
its own offsets are four higher: two AD header bytes plus the two company-ID
bytes.

| Offset | Field | Encoding |
| --- | --- | --- |
| 0 | Battery | `value x 0.01 + 1.22` volts |
| 1 | Pressure, low byte | see below |
| 2 | Temperature | `value - 55` degrees Celsius |
| 3 | State | enum, see section 5 |
| 4 | Protocol version | constant `0x0A` on observed firmware |
| 5 | Checksum 1 | not validated by this project |
| 6 | Pressure, high byte | see below |
| 7 | Checksum 2 | not validated by this project |
| 8 | BLE version | tenths, `0x28` reads as 4.0 |
| 9..11 | Sensor ID | three bytes, printed in reverse order |
| 12..14 | MAC tail | the advertiser's own address, reversed |

Offsets 9..11 and 12..14 overlap in purpose: the vendor application prints
bytes 14, 13, 12 as the sensor ID shown in its own UI, which is the last three
octets of the MAC in reverse. Matching that string exactly is what lets a rider
recognise a cap they already added elsewhere.


## 4. Pressure

```
counts = payload[1] + 256 * payload[6]
kPa    = counts * 3.144
```

The scale factor is the vendor's, not a rounded convenience: 3.144 is the
constant in the application. A count of 115 gives 361.6 kPa, against a gauge
reading 3.6 bar.

Two traps worth stating, because both survived weeks of packet analysis by
looking correct:

- **The value is a count, not kPa.** Reading `payload[1]` directly as kPa gives
  a number that tracks the tyre perfectly and is wrong by a factor of three:
  3.6 bar displays as 1.15.
- **The high byte is `payload[6]`, not `payload[3]`.** Byte 6 is zero for every
  pressure below 803 kPa, which is every pressure a tyre of this kind ever
  sees, so a decoder that used byte 3 agreed with the correct one on all real
  data.

Zero is a real reading. A cap on a flat tyre reports 0 kPa exactly, and that is
the one moment a pressure sensor exists for; treating zero as "no sensor" hides
a flat tyre.


## 5. State byte

`payload[3]` is an enumeration the vendor application names as follows.

| Value | Meaning |
| --- | --- |
| 0 | Normal |
| 1 | Leakage |
| 2 | Inflation |
| 3 | Start up |
| 4 | Power on |
| 5 | Wake up |

These are the application's own constants, read as static field values rather
than inferred from declaration order. An earlier draft of this document had 3
and 4 the other way round for exactly that reason.

Leakage and inflation are the two worth showing a rider: one is a tyre going
down, the other is the cap noticing a pump. The remaining three are transient
power-up states and say nothing about the tyre.


## 6. A second frame type

The vendor application takes a different path for frames whose hex begins
`55AA0A`, parsing them as a "state" message rather than telemetry. Caps
observed by this project never send it, so it is unimplemented and undocumented
here. It is the first place to look if a cap is identified but never produces a
reading.


## 7. Worked example

Field values only; the byte string is illustrative and the sensor ID and MAC
are placeholders.

```
payload: B3 5B 52 01 0A A2 00 04 28 AA BB CC DD EE FF
         │  │  │  │  │           │  │  └────────────── MAC, reversed
         │  │  │  │  │           │  └───────────────── BLE 4.0
         │  │  │  │  │           └──────────────────── pressure high byte
         │  │  │  │  └──────────────────────────────── protocol version
         │  │  │  └─────────────────────────────────── state: leakage
         │  │  └────────────────────────────────────── 0x52 = 82 - 55 = 27 C
         │  └───────────────────────────────────────── pressure low byte
         └──────────────────────────────────────────── 0xB3 = 179 -> 3.01 V

counts = 0x5B + 256 * 0x00 = 91
kPa    = 91 * 3.144 = 286.1  (2.86 bar, 41.5 psi)
```


## 8. Notes for implementers

- **Temperature is Celsius with a +55 offset, not Fahrenheit.** At room
  temperature the two agree to within a degree, so a Fahrenheit reading of a
  capture looks right until the tyre gets hot. 0x64 is 45 C by the correct rule
  and 37.8 C by the wrong one.
- **Battery is a voltage, not a percentage.** The vendor UI shows a coarse bar;
  the packet carries volts. A CR2032 sits near 3.0 V for most of its life, so a
  percentage derived from a single reading on that plateau means very little.
- **Do not filter the scan by MAC prefix.** These ship with whatever address
  the factory burned in; the rider's own caps have nothing in common with
  anyone else's.
- Advertisements are broadcast. The vendor application only scans, never
  connects or bonds, so several applications can read the same cap at once and
  none of them can claim it.


## Attribution

- **LY TPMS application** (`com.zl.dev.tire.lytpms`), class
  `com.wicarlink.zl.data.bean.TireBean` , field layout, scale factor,
  temperature offset, battery curve and state constants. Read from a copy of
  the APK obtained from a device its owner controls, for the purpose of
  interoperating with hardware they own.
- **Rider gauge readings** , pressures at 0, 1.4, 2.7, 3.2, 3.6 and 4.4 bar with
  matching temperatures, used to confirm the layout independently of the
  application.
- **Bluetooth SIG Assigned Numbers** , company identifier `0x00AC`.
- Other cheap-TPMS families in section 1 are documented by
  [ra6070/BLE-TPMS](https://github.com/ra6070/BLE-TPMS),
  [mtigas/iOS-BLE-Tire-Logger](https://github.com/mtigas/iOS-BLE-Tire-Logger) and
  [andi38/TPMS](https://github.com/andi38/TPMS); neither format matches this one.
