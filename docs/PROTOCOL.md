# Protocol notes

Everything I found out about how the JBSmartBulb speaker bulbs talk, while rewriting the JBSmartBulb
2.9 app (2016, targetSdk 23) whose source was lost. The protocol was recovered from the original APK
and then checked against a real bulb.

Code layout:

```
shared/       protocol, connection controller, platform transports (KMP: Android, desktop JVM, iOS core)
composeApp/   Material 3 UI in Compose Multiplatform (Android app + desktop app)
```

## The bulb protocol

The old app used Actions Semiconductor's "iBluz" SDK, which carries the same `01 FE` frames over
either Bluetooth Classic **SPP** or **BLE GATT**. The bulb this was tested with (`smart bulb`,
Realtek `00:E0:4C:…` address, model string `Model-BL-05`) is dual-mode. With nothing connected it
takes BLE; its Classic side (audio + SPP) only accepted connections after a power cycle. The app
tries BLE first and uses SPP when the bulb is connected as a speaker or is Classic-only.

### BLE (tested on hardware)

| Service | Characteristic | Use |
|---|---|---|
| `6666` | `8888` (indicate) | bulb → phone frames |
| `7777` | `8877` (write) | phone → bulb frames, **with response** |

1. Connect over LE, discover services, enable indications on `8888`, request MTU 247 (bulb gives 200).
2. Write the raw ASCII bytes `01234567`. **Required**: without it the bulb drops the link on the
   first command. With it, commands run and every query is answered.
3. Write raw frames to `8877`, one write per frame.
4. The bulb truncates indications to MTU − 3 bytes rather than splitting them, so the default MTU
   cuts its 28- and 40-byte answers; the MTU request fixes that.

While a BLE link is up, `createBond()` on a dual-mode device bonds over LE rather than Classic, so
the app drops BLE before pairing for audio. (An older SDK BLE layout with extra length/control
characteristics `9998`/`9988` is also supported; it was not available to test.)

The BL08A speaker bulb documented at <https://github.com/mertemr/chsmartbulb> uses the same GATT
layout and frames and independently confirms the light-command layout below (`GG BB RR` byte order).

### SPP (Classic), as the old app ran it

1. Open an RFCOMM socket to the SPP UUID `00001101-0000-1000-8000-00805F9B34FB` (insecure first).
2. Write the raw ASCII bytes `01234567`.

### Session (both transports)

1. Send *query features* (`0x5102`), wait up to 2 s for `0x4102`.
2. Send *clock sync*, then *lamp query* (`0x5182`); the reply (`0x4182`) carries the lamp state.
3. Poll status (`0x5100`) every ~400 ms; drop the link after 25 unanswered polls.

### Frame (16-byte header, little-endian except the key)

| offset | size | field |
|---|---|---|
| 0 | 2 | `01 FE` sync |
| 2 | 2 | segment type / seq (0 / 0) |
| 4 | 2 | key, **big-endian** |
| 6 | 2 | total length incl. header, LE |
| 8 | 4 | arg1, LE |
| 12 | 4 | arg2, LE |
| 16 | n | payload, zero-padded to a multiple of 4 |

Unused args are `0x80000000` (Integer.MIN_VALUE). Bulb replies use keys `0x41xx`.

### Commands

| key | arg1 | arg2 | payload | meaning |
|---|---|---|---|---|
| `5383` | `R<<16 \| B<<8 \| G` (on the wire: `GG BB RR 00`) | `white<<8 \| mode` (`EF WW 00 00`) | – | lamp: effect `0x50` = fixed colour; all zero + `0x50` = off. RGB and white can be lit together |
| `5182` | 0 | 0 | – | query lamp state → reply `4182` with the same arg layout |
| `5381` | seconds | seconds | – | sleep timer |
| `5384` | 0 | 0 | – | cancel sleep timer |
| `5380` | 0 | 0 | 57 B: `03` + UTF-8 name (≤56 B, space-padded to ≥7) | rename |
| `5300` | 1 | volume 0–31 | – | speaker volume |
| `5300` | 2 | preset | – | EQ preset: Jazz 1, Pop 2, Classic 3, Soft 4, DBB 5, Rock 6 |
| `5300` | 3 | – | 7 × int8 (−12…12 dB) | EQ bands 80 Hz…16 kHz |
| `5300` | 0 | – | `yearLo yearHi month day h m s 00` | clock sync |
| `5100` | – | – | – | status poll → `4100`, 24-byte status (byte 3 volume, 4 EQ, 5 mute) |
| `5102` | – | – | – | feature query → `4102` |
| `5301` | 0 | – | – | release (sent on disconnect) |

The last byte of arg2 (`FD`) is a ~1 s hardware glide on the BL08A, but the tested BL-05 ignores
it (colours switch instantly), so fades are streamed: the app sends interpolated colours. At the
start of every session it measures how fast the bulb takes colour commands (a burst of 20 commands
repeating the current colour, then a lamp query; the answer arrives after all are handled) and fades
at 70% of that rate. Measured on the BL-05 over Classic SPP: 21 commands in 414 ms ≈ 50/s → a new
colour every 28 ms. On BLE the app also requests a short connection interval.

Each transition follows a Gaussian timing curve (`FadeCurve`: normal CDF, σ = 0.15 of the fade
time, rescaled to start/end exactly on the colours): it dwells on the start colour, speeds through
the middle and settles on the next one. For 1000 ms: < 0.4 % of the way after 100 ms, < 5 % at
250 ms, 50 % at 500 ms, symmetric towards the end.

The bulb has no colour-temperature channel. The app's White mode drives the white LED at the chosen
brightness plus an RGB tint of the chosen temperature (6500 K → 2700 K), see `LampColors`.

`shared/src/commonTest/.../GoldenVectorsTest.kt` checks every command against bytes produced by
running the original app's encoder (decompiled) with the original app's argument expressions.

## Speaker (audio)

The bulb takes one Bluetooth bearer at a time: BLE for light only, or Classic for audio, with light
control then over SPP. **Sound → Connect speaker** drops the BLE link, Classic-pairs the bulb (so
Android bonds over BR/EDR, not LE) and waits for Android to connect audio; control then reconnects
over SPP and keeps working alongside the music. If pairing times out (`HCI_ERR_PAGE_TIMEOUT`), switch
the bulb off and on and try again right away: on the tested bulb the Classic side only answered
after a power cycle.

## Changes from the old app

- Modern runtime permissions (Android 12+ `BLUETOOTH_CONNECT` / `BLUETOOTH_SCAN`).
- The old SDK forced the A2DP audio link through hidden `BluetoothA2dp.connect()`, which modern
  Android blocks. The app pairs with the public `createBond()` and lets Android connect the audio.
- The built-in MP3 player is gone: any music app plays through the bulb once audio is connected.
- No analytics / update SDKs (Umeng).

## Platforms

- **Android**: full support.
- **Desktop (macOS/Linux)**: UI runs. The transport opens the OS serial device for a paired SPP
  bulb (`/dev/cu.*`, `/dev/rfcomm*`). Not yet tested with a real bulb.
- **iOS**: the shared protocol core compiles for iOS. The tested bulb is controlled over BLE, which
  iOS allows without MFi, so an iOS app needs only a CoreBluetooth transport (Classic SPP would not
  be possible there).
