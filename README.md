# ExpressCast

**ExpressCast** is a free, open-source, low-latency wireless screen and game casting app for **Android Phone → Android TV** over your local Wi-Fi network.

No cloud servers, no account login, no ads, and no telemetry—video streams directly over your LAN using hardware-accelerated H.264 and custom UDP transport.

---

## Features

- **Low-Latency Pipeline:** Direct `MediaProjection` → `VirtualDisplay` → hardware `MediaCodec` H.264 encoder (zero B-frames, `KEY_LOW_LATENCY`, `KEY_OPERATING_RATE`) → UDP → hardware `MediaCodec` decoder → `SurfaceView`.
- **Newest-Frame-Wins UDP Transport:** Custom 22-byte `VideoPacketHeader` framing (`<= 1400 B` MTU-safe datagrams) with `FrameReassembler` that drops stale incomplete frames and triggers on-demand IDR keyframe recovery over TCP.
- **Zero-Config LAN Discovery:** Automatic mDNS/NSD discovery (`_expresscast._tcp`) on the local Wi-Fi network.
- **4-Digit PIN Pairing:** TV displays a one-time 4-digit PIN; enter it on your phone to authorize the session before screen capture begins.
- **TV-First D-Pad UX:** Clean Android TV interface with high-contrast white focus borders and `preferMinimalPostProcessing` support (Android 11+ TVs) to minimize display lag.

---

## Requirements

- **Phone (Sender):** Android 10+ (`minSdk = 29`)
- **TV (Receiver):** Android TV / Google TV 8.0+ (`minSdk = 26`)
- **Network:** Both devices connected to the same Wi-Fi LAN (5 GHz Wi-Fi or Ethernet on TV recommended for lowest latency)

---

## Project Structure

```text
ExpressCast/
├── phone/     # dev.towhedul.expresscast        — MediaProjection capture, H.264 encoder, audio capture, NSD advertiser, UDP sender
├── tv/        # dev.towhedul.expresscast.tv     — NSD browser, PIN display, UDP video/audio receiver, H.264 SurfaceView decoder
└── shared/    # dev.towhedul.expresscast.shared — Pure Kotlin/JVM protocol, packet framing, reassembler, unit tests
```

---

## Build & Test

Run from the repository root:

```bash
# Run shared protocol & packet-reassembly unit tests
./gradlew :shared:test

# Build debug APKs and run lint
./gradlew :phone:assembleDebug :tv:assembleDebug :phone:lintDebug :tv:lintDebug

# Build R8-optimized release APKs
./gradlew :phone:assembleRelease :tv:assembleRelease
```

On Windows PowerShell, replace `./gradlew` with `.\gradlew.bat`.

---

## Quick Start

1. Install **ExpressCast** on your Android phone and **ExpressCast TV** on your Android TV.
2. Connect both devices to the same Wi-Fi network.
3. Open **ExpressCast** on your phone (discovery starts automatically).
4. Open **ExpressCast TV**, select your phone with the remote, and note the **4-digit PIN**.
5. Enter the PIN on your phone, then tap **Start Casting to TV**.

---

## License

Released under the [MIT License](LICENSE).
