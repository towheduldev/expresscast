# ExpressCast

[![Android CI](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml/badge.svg)](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml)

**ExpressCast** is a free, open-source, low-latency wireless screen and game casting app for **Android Phone → Android TV** over your local Wi-Fi network.

No cloud servers, no account login, no ads, and no telemetry—video and audio stream directly over your LAN using hardware-accelerated H.264 and custom UDP transport.

---

## Features

- **Low-Latency Video Pipeline:** Direct `MediaProjection` → `VirtualDisplay` → hardware `MediaCodec` H.264 encoder (zero B-frames, `KEY_LOW_LATENCY`, `KEY_OPERATING_RATE`) → UDP → hardware `MediaCodec` decoder → `SurfaceView`.
- **Low-Latency Internal Audio:** Captures game and media audio on the phone via `AudioPlaybackCaptureConfiguration` and streams 5 ms 48 kHz stereo PCM chunks over UDP to `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`).
- **Stream Settings & GPU Aspect Crop:** Choose resolution (`540p`, `720p`, `1080p`), FPS (`30`/`60`), bitrate (`4`–`15 Mbps`), audio toggle, stats overlay toggle, and real-time OpenGL ES center-cropping (`16:9 (Fill TV)`, `16:10`, `4:3`, or `Native`) with automatic portrait/landscape rotation handling.
- **Newest-Frame-Wins UDP Transport:** Custom 22-byte `VideoPacketHeader` framing (`<= 1400 B` MTU-safe datagrams) with `FrameReassembler` that drops stale incomplete frames and triggers on-demand IDR keyframe recovery over TCP.
- **Zero-Config LAN Discovery & PIN Pairing:** Automatic mDNS/NSD discovery (`_expresscast._tcp`) and a 4-digit PIN displayed on the TV and entered on the phone.
- **TV-First D-Pad UX:** High-contrast white focus borders, minimal `FPS - ms` overlay (optional), and `preferMinimalPostProcessing` support on Android 11+ TVs.

---

## Requirements

- **Phone (Sender):** Android 10+ (`minSdk = 29`)
- **TV (Receiver):** Android TV / Google TV 8.0+ (`minSdk = 26`)
- **Network:** Both devices on the same Wi-Fi LAN (5 GHz Wi-Fi or Ethernet on TV recommended for lowest latency)

---

## Project Structure

```text
ExpressCast/
├── phone/     # dev.towhedul.expresscast        — MediaProjection capture, GL cropper, H.264 encoder, audio capture, NSD advertiser
├── tv/        # dev.towhedul.expresscast.tv     — NSD browser, PIN display, UDP video/audio receiver, H.264 SurfaceView decoder
└── shared/    # dev.towhedul.expresscast.shared — Pure Kotlin/JVM protocol, packet framing, reassembler, unit tests
```

---

## Build & Test

Run from the repository root:

```bash
# Run unit tests
./gradlew :shared:test :phone:testDebugUnitTest

# Build debug APKs and run lint
./gradlew :phone:assembleDebug :tv:assembleDebug :phone:lintDebug :tv:lintDebug

# Build R8-optimized release APKs
./gradlew :phone:assembleRelease :tv:assembleRelease
```

On Windows PowerShell, replace `./gradlew` with `.\gradlew.bat`.

### Signing Release APKs Locally

1. Generate a keystore:
   ```bash
   keytool -genkeypair -v -keystore release-keystore.jks -keyalg RSA -keysize 2048 -validity 10000 -alias expresscast
   ```
2. Copy `keystore.properties.example` to `keystore.properties` (already ignored by `.gitignore`) and fill in your passwords.
3. Run `./gradlew :phone:assembleRelease :tv:assembleRelease` to produce signed `ExpressCast-v<version>-release.apk` and `ExpressCast-TV-v<version>-release.apk`.

---

## Quick Start

1. Install **ExpressCast** on your Android phone and **ExpressCast TV** on your Android TV.
2. Connect both devices to the same Wi-Fi network.
3. Open **ExpressCast** on your phone (discovery starts automatically).
4. Open **ExpressCast TV**, select your phone with the remote, and note the **4-digit PIN**.
5. Enter the PIN on your phone, adjust **Stream Settings** if desired, and tap **Start Casting to TV**.

---

## License

Released under the [MIT License](LICENSE).
