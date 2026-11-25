# ExpressCast

[![Android CI](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml/badge.svg)](https://github.com/towheduldev/expresscast/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/towheduldev/expresscast?label=Release)](https://github.com/towheduldev/expresscast/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

**ExpressCast** is a free, open-source, ultra-low-latency **wireless screen mirroring and game casting app** for **Android Phone → Android TV / Google TV** over your local Wi-Fi network.

Unlike general-purpose casting apps that buffer heavily over TCP/HTTP or rely on cloud relays, ExpressCast streams **hardware-encoded H.264 directly over custom UDP datagrams** with zero B-frames, live adaptive bitrate (ABR), GPU aspect-ratio cropping, internal game/media audio, and mDNS fail-prevention—**no internet connection, Google Cast dependency, account login, ads, or telemetry required**.

---

## Download

Grab the latest signed APKs from [**GitHub Releases**](https://github.com/towheduldev/expresscast/releases/latest):

| Device | APK | Minimum OS |
|---|---|---|
| **Android Phone (Sender)** | `ExpressCast-v1.2.0-release.apk` | Android 9+ (API 28+, `arm64-v8a` & `armeabi-v7a`; video-only on Android 9) |
| **Android TV / Google TV (Receiver)** | `ExpressCast-TV-v1.2.0-release.apk` | Android TV 8.0+ (API 26+) |

---

## Capabilities & Feature Support

Every feature below is implemented and verifiable directly in the repository (`phone/`, `tv/`, `shared/`):

| Capability | Status in ExpressCast v1.2.0 | Implementation Notes |
|---|---|---|
| **Full-Screen Mirroring** | Supported | `MediaProjection` + `VirtualDisplay` foreground service |
| **Hardware H.264 Encoding & Decoding** | Supported | `MediaCodec` (`video/avc`), `0` B-frames, `KEY_OPERATING_RATE`, Surface-to-Surface |
| **Live Adaptive Bitrate (ABR)** | Supported (On by default) | TV sends 1s `StreamStatsReport` (completed/lost frames, FPS, RTT); phone adjusts `PARAMETER_KEY_VIDEO_BITRATE` dynamically without restarting |
| **Frame Rate Options (`15 / 30 / 60 / 90 / 120` fps)** | Supported | Coalesced deferred GL pacing for steady `15`/`30` fps; `90`/`120` fps gated by phone encoder + TV `PeerCapabilities` |
| **Custom Bitrate (`1–30 Mbps`) & Presets** | Supported | Continuous slider (`1–30 Mbps`), quick presets (`2/4/6/10/15/25 Mbps`), and low-FPS CBR frame-size capping |
| **Resolutions & GPU Aspect Crop** | Supported | `540p`, `720p`, `1080p` + OpenGL ES crop (`16:9 Fill TV`, `16:10`, `4:3`, `Native`) with auto-rotation |
| **mDNS Fail-Prevention (Dual Discovery)** | Supported | Runs NSD/mDNS (`_expresscast._tcp`) + UDP LAN Beacon (`:47803`) simultaneously |
| **Manual IP & Port Connect (LAN / VPN)** | Supported | One-tap **Copy IP** on phone + always-visible **Connect by IP** card on TV (also works over Tailscale / WireGuard) |
| **Packet-Loss & Latency Protection** | Supported | Newest-frame-wins `FrameReassembler`, stale decoded frame draining on TV, on-demand IDR keyframe recovery, and ABR step-down |
| **Internal Game & Media Audio** | Supported (Android 10+) | `AudioPlaybackCapture` (`USAGE_GAME`, `USAGE_MEDIA`, `USAGE_UNKNOWN`) over 5 ms 48 kHz stereo UDP PCM (`AudioTrack` low-latency) |
| **TV Remote / D-Pad Navigation** | Supported | 10-foot Compose UI with high-contrast emerald focus rings and live `FPS · RTT` overlay |
| **Security & Privacy** | Supported | Per-session 4-digit PIN pairing, 100% offline LAN, zero cloud/ads/tracking |
| **DRM-Protected Apps (`FLAG_SECURE`)** | Not supported (OS limit) | Blocked at the Android OS/SurfaceFlinger level for third-party screen capture |
| **iOS / Windows / Linux / Chromecast URL Cast** | Out of scope | Dedicated Android Phone → Android TV low-latency mirroring pipeline |

---

## Why ExpressCast?

| Area | ExpressCast | Typical Mirror / HTTP Cast Apps |
|---|---|---|
| **Latency** | Direct hardware H.264 + custom UDP + stale decoded frame draining | High jitter buffers or TCP head-of-line stalls |
| **Congested Wi-Fi** | Dynamic `PARAMETER_KEY_VIDEO_BITRATE` ABR + on-demand IDR recovery | Fixed bitrate stutters or multi-second freezes |
| **Mesh / Isolated Routers** | NSD/mDNS + UDP LAN Beacon (`:47803`) + Manual IP fallback | Fails silently when mDNS multicast is blocked |
| **Tall Phone → 16:9 TV** | Real-time OpenGL ES center-crop (`16:9 Fill TV`, `16:10`, `4:3`, `Native`) | Pillarboxed/letterboxed black bars |
| **Game & Media Audio** | Internal audio capture (`AudioPlaybackCapture`) in 5 ms UDP PCM packets | Often video-only or noisy microphone capture |
| **Privacy & Cost** | 100% open source (MIT), offline LAN only, zero ads, zero tracking | Ads, paywalls, or cloud relays |

---

## Key Features

- **Ultra-Low-Latency Hardware Video Pipeline:** `MediaProjection` → `VirtualDisplay` → OpenGL ES 2.0 cropper → hardware `MediaCodec` H.264 encoder (zero B-frames, `KEY_OPERATING_RATE`) → MTU-safe UDP datagrams (`<= 1400 B`) → newest-frame-wins `FrameReassembler` → hardware `MediaCodec` decoder (draining older queued outputs so latency never drifts) → direct `SurfaceView` render.
- **Live Adaptive Bitrate (ABR):** The TV receiver reports frame completion, packet loss, and control RTT every second (`StreamStatsReport`). On congestion, ExpressCast's `AdaptiveBitrateController` steps down `MediaCodec.PARAMETER_KEY_VIDEO_BITRATE` dynamically without interrupting playback and climbs back smoothly once the link stabilizes.
- **Reliable 3-Layer Discovery (Zero-Config + Fail-Safe):** Combines Android NSD/mDNS (`_expresscast._tcp`), an active UDP LAN discovery beacon (`:47803`), and an always-visible **Connect by IP** panel on the TV paired with a one-tap **Copy** Local IP card on the phone.
- **Flexible FPS & Bitrate Controls:** Choose `15`, `30`, `60`, `90`, or `120` fps (`90`/`120` fps automatically filtered by both phone encoder and TV `PeerCapabilities`). Steady `15`/`30` fps mode uses coalesced deferred GL drawing so UI overlays never get dropped or out of sync, alongside automatic low-FPS CBR frame-size capping.
- **Internal Device Audio Streaming:** Captures game and media audio directly on Android 10+ (`AudioPlaybackCaptureConfiguration`) and streams 48 kHz stereo PCM in 5 ms UDP packets to Android TV `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`).
- **Aspect-Ratio Crop for Gaming:** Crop a tall 20:9 phone cleanly to `16:9 Fill TV`, `16:10`, `4:3`, or `Native` on the GPU—automatically resizing when rotating between portrait and landscape.
- **Display Lag Reduction:** Uses `window.setPreferMinimalPostProcessing(true)` on Android 11+ TVs to hint Game Mode / minimal display processing.

---

## Quick Start

1. Download and install **ExpressCast** on your Android phone and **ExpressCast TV** on your Android TV / Google TV from [**Releases**](https://github.com/towheduldev/expresscast/releases/latest).
2. Connect both devices to the **same Wi-Fi network** (5 GHz Wi-Fi or Ethernet on the TV gives the lowest latency).
3. Open **ExpressCast** on your phone—mDNS and the UDP LAN beacon start automatically, and your **Local IP Address** is shown on the home card.
4. Open **ExpressCast TV**:
   - Select your phone from **Nearby Phones** using the TV remote, **or**
   - If your router blocks discovery, type the phone's **Local IP Address** into **Connect by IP** on the right.
5. Enter the **4-digit PIN** shown on the TV into your phone.
6. Adjust **Stream Settings** if needed (defaults to `720p · 60 fps · 6 Mbps Auto · 16:9 Fill TV`) and tap **Start Casting to TV**.

---

## Tech Stack & Architecture

- **Language & UI:** Kotlin, Jetpack Compose, Material 3
- **Video & Graphics:** `MediaProjection`, `VirtualDisplay`, OpenGL ES 2.0 / EGL (`SurfaceTexture` GPU cropper with coalesced deferred pacing), `MediaCodec` H.264 (`video/avc`), `SurfaceView`
- **Audio:** `AudioPlaybackCaptureConfiguration`, `AudioRecord`, `AudioTrack` (`PERFORMANCE_MODE_LOW_LATENCY`)
- **Networking & Protocol (v2, backwards-compatible with v1):**
  - Discovery: Android `NsdManager` (`_expresscast._tcp`) + UDP LAN Beacon (`47803`, `LanDiscoveryProtocol`)
  - Control: Length-prefixed TCP (`47800`) for handshake, 4-digit PIN pairing, `PeerCapabilities`, `StreamStatsReport`, heartbeats, and `RequestKeyframe`
  - Media: Custom 22-byte UDP `VideoPacketHeader` (`47801`) + 16-byte UDP `AudioPacketHeader` (`47802`)

```text
ExpressCast/
├── phone/     # dev.towhedul.expresscast        — Capture service, GL cropper, H.264 encoder, audio capture, NSD + LAN beacon, ABR
├── tv/        # dev.towhedul.expresscast.tv     — NSD + LAN beacon browser, Manual IP, PIN display, UDP video/audio receiver, H.264 decoder
└── shared/    # dev.towhedul.expresscast.shared — Pure Kotlin/JVM protocol, packet framing, reassembler, ABR controller, unit tests
```

---

## Build & Test

Run from the repository root:

```bash
# Run JVM unit tests
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

- **What if my phone doesn't appear on the TV automatically?**
  ExpressCast already pairs mDNS/NSD with a secondary UDP LAN beacon (`:47803`) to bypass common router multicast issues. If your network isolates discovery completely, enter the **Local IP Address** shown on your phone directly into the **Connect by IP** card on the TV.
- **Can I cast across different networks using a VPN?**
  While ExpressCast has no cloud relay (to preserve sub-frame latency and privacy), power users can connect over a mesh VPN like Tailscale or WireGuard by entering the phone's VPN IP into **Connect by IP**.
- **What happens if Wi-Fi gets congested mid-game?**
  With **Max Bitrate (Auto)** enabled, ExpressCast monitors TV frame loss and RTT every second and dynamically lowers encoder bitrate via `PARAMETER_KEY_VIDEO_BITRATE` without stopping the stream, then recovers bitrate once Wi-Fi clears.
- **Why are 90 fps / 120 fps options disabled for some resolutions or TVs?**
  ExpressCast queries hardware `MediaCodecList` capabilities on both your phone's encoder and the connected TV's decoder (`PeerCapabilities`) and only enables `90`/`120` fps when both endpoints support that resolution and rate.
- **Why is a protected video app black when cast?**
  Apps that mark windows with Android's `FLAG_SECURE` (such as DRM streaming services) block screen capture at the OS level.
- **Does internal audio capture record microphone noise?**
  No—ExpressCast uses Android 10+ `AudioPlaybackCaptureConfiguration` to capture internal game/media audio directly, not the microphone. (Android 9 phones stream video-only.)

---

## Author & License

Created by **Towhedul Islam Ridoy** ([towhedul.dev](https://towhedul.dev) · [@towheduldev](https://github.com/towheduldev)).

Released under the [MIT License](LICENSE).
