# ExpressCast

[![Android CI](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml/badge.svg)](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/towheduldev/expresscast?label=Release)](https://github.com/towheduldev/expresscast/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**ExpressCast** is a free, open-source, ultra-low-latency **wireless screen mirroring and game casting app** for **Android Phone → Android TV / Google TV** over your local Wi-Fi network.

Designed as a fast, privacy-first, ad-free local-LAN alternative to Miracast, Chromecast mirroring, and ad-supported TV receiver apps—**no internet connection, cloud server, account login, or telemetry required**.

---

## Download

Grab the latest signed APKs from [**GitHub Releases**](https://github.com/towheduldev/expresscast/releases/latest):

| Device | APK | Minimum OS |
|---|---|---|
| **Android Phone (Sender)** | `ExpressCast-v1.1.0-release.apk` | Android 9+ (API 28+, `arm64-v8a` & `armeabi-v7a`; video-only on Android 9) |
| **Android TV / Google TV (Receiver)** | `ExpressCast-TV-v1.1.0-release.apk` | Android TV 8.0+ (API 26+) |

---

## Why ExpressCast?

| Feature | ExpressCast | Typical Cast / Mirror Apps |
|---|---|---|
| **Latency** | Direct hardware H.264 + custom UDP (`0` B-frames, no big jitter buffer) | High buffering or TCP head-of-line stalls |
| **Tall Phone → 16:9 TV** | Real-time OpenGL ES center-crop (`16:9 Fill TV`, `16:10`, `4:3`, `Native`) with auto-rotation | Pillarboxed / letterboxed black bars |
| **Game & Media Audio** | Internal audio capture (`AudioPlaybackCapture`) over 5 ms UDP PCM (`AudioTrack` low-latency) | Often video-only or mic-only |
| **Privacy & Cost** | 100% open source (MIT), offline LAN only, zero ads, zero tracking | Ads, subscriptions, or cloud relays |
| **TV Controls** | D-pad first UI with high-contrast focus borders & optional `FPS - ms` overlay | Touch-first UIs hard to use on a remote |

---

## Key Features

- **Low-Latency Hardware Video Pipeline:** `MediaProjection` → `VirtualDisplay` → hardware `MediaCodec` H.264 encoder (no B-frames, `KEY_LOW_LATENCY`, `KEY_OPERATING_RATE`) → MTU-safe UDP datagrams → hardware `MediaCodec` decoder → direct `SurfaceView` render.
- **Internal Device Audio Streaming:** Captures game and media audio directly from Android (`USAGE_GAME`, `USAGE_MEDIA`, `USAGE_UNKNOWN`) and streams 48 kHz stereo PCM in 5 ms UDP packets to Android TV `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`).
- **Aspect-Ratio Crop for Gaming:** Play on a tall 20:9 phone and crop cleanly to `16:9` on the GPU so games fill your TV screen edge-to-edge—automatically resizing when rotating between portrait and landscape.
- **Configurable Stream Settings:** Adjust resolution (`540p`, `720p`, `1080p`), target FPS (`30` / `60`), bitrate (`4` / `6` / `10` / `15 Mbps`), audio on/off, and TV stats overlay (`FPS - ms`) on/off.
- **Zero-Config Discovery & PIN Pairing:** Automatic LAN discovery via Android NSD/mDNS (`_expresscast._tcp`) and 4-digit PIN verification (TV displays the PIN, phone enters it).
- **Display Lag Reduction:** Uses `window.setPreferMinimalPostProcessing(true)` on Android 11+ TVs to hint Game Mode / minimal display processing.

---

## Use Cases

- Playing Android mobile games and retro emulators on a TV with minimal input-to-display lag
- Mirroring phone apps and video locally over Wi-Fi without an internet connection or Google Cast dependency
- Casting from tall modern phones onto 16:9 TVs without black sidebars

---

## Quick Start

1. Download and install **ExpressCast** on your Android phone and **ExpressCast TV** on your Android TV / Google TV from [**Releases**](https://github.com/towheduldev/expresscast/releases/latest).
2. Connect both devices to the **same Wi-Fi network** (5 GHz Wi-Fi or Ethernet on the TV gives the lowest latency).
3. Open **ExpressCast** on your phone (LAN discovery starts automatically).
4. Open **ExpressCast TV**, select your phone using the TV remote, and note the **4-digit PIN**.
5. Enter the PIN on your phone, choose your **Stream Settings** (defaults to `720p · 60 fps · 6 Mbps · 16:9 Fill TV`), and tap **Start Casting to TV**.

---

## Tech Stack & Architecture

- **Language & UI:** Kotlin, Jetpack Compose, Material 3
- **Video & Graphics:** `MediaProjection`, `VirtualDisplay`, OpenGL ES 2.0 / EGL (`SurfaceTexture` GPU cropper), `MediaCodec` H.264 (`video/avc`), `SurfaceView`
- **Audio:** `AudioPlaybackCaptureConfiguration`, `AudioRecord`, `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`)
- **Networking:** Android `NsdManager` (mDNS `_expresscast._tcp`), length-prefixed TCP control channel (`47800`), custom 22-byte UDP `VideoPacketHeader` (`47801`) with newest-frame-wins `FrameReassembler` & on-demand IDR keyframe recovery, 16-byte UDP `AudioPacketHeader` (`47802`)

```text
ExpressCast/
├── phone/     # dev.towhedul.expresscast        — Capture service, GL cropper, H.264 encoder, audio capture, NSD advertiser
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

## FAQ

- **Why is a protected video app black when cast?**
  Apps or games that mark windows with Android's `FLAG_SECURE` (such as DRM streaming services) block screen capture at the OS level.
- **How do I get the lowest possible latency?**
  Use 5 GHz Wi-Fi (or Ethernet on the TV), enable **Game Mode** in your TV's picture settings if it doesn't switch automatically, and try `720p @ 60 fps` at `6 Mbps`.
- **Does internal audio capture record microphone noise?**
  No—ExpressCast uses Android 10+ `AudioPlaybackCaptureConfiguration` to capture internal game/media audio directly, not the microphone. (Android 9 phones stream video-only.)

---

## Author & License

Created by **Towhedul Islam Ridoy** ([towhedul.dev](https://towhedul.dev) · [@towheduldev](https://github.com/towheduldev)).

Released under the [MIT License](LICENSE).
