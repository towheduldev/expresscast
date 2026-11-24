# ExpressCast Project Guidelines (`AGENTS.md`)

ExpressCast is a low-latency wireless game streaming system split across two Android applications (`phone` as sender, `tv` as receiver) and a pure Kotlin/JVM `shared` protocol module.

## Module Responsibilities

- **`shared/`** (`com.expresscast.shared`, pure Kotlin/JVM):
  - Protocol versioning, network defaults, packet header framing, connection state models, stream configurations, and shared error codes.
  - **Strict rule:** Never put Android framework imports, Context references, or UI code in `shared/`.
- **`phone/`** (`com.expresscast.phone`, `minSdk = 29`):
  - Runs the game on the phone and acts as the streaming server.
  - Captures the screen via `MediaProjection` + `VirtualDisplay` inside a foreground service, encodes hardware H.264 via `MediaCodec`, advertises via NSD/mDNS, handles pairing/control, and streams UDP video to the TV.
- **`tv/`** (`com.expresscast.tv`, `minSdk = 26`):
  - Android TV receiver (`LEANBACK_LAUNCHER` + `LAUNCHER`, landscape).
  - Discovers phone servers via NSD (or connects by manual IP), reconstructs incoming H.264 frames, decodes via hardware `MediaCodec`, and renders directly onto a `SurfaceView` with minimal buffering.

## Architectural Boundaries

- Keep **networking**, **encoding**, **decoding**, and **UI** strictly separated. Neither the encoder nor the decoder may depend directly on socket/NSD details; communicate across clean interfaces and shared models.
- Never use TCP for continuous video frame delivery unless a specific, documented fallback requires it.

## Engineering Rules

1. Work incrementally by phase; never implement everything in one monolithic change.
2. Build and test after meaningful changes; fix compilation and lint errors before proceeding.
3. Do not use deprecated APIs when a modern alternative exists.
4. Never assume device capabilities—query `MediaCodecList` and codec capabilities at runtime and provide fallbacks.
5. Never hardcode device IP addresses.
6. Never silently swallow exceptions.
7. Use structured logging with clear component tags.
8. Avoid unnecessary abstractions, overengineering, and heavy dependencies unless they deliver clear value.
9. Document non-obvious technical decisions in `docs/`.
10. Never claim runtime or hardware behavior works without testing it on real devices; document hardware-only verification gaps explicitly.
11. Preserve a working, compiling state at every milestone.
12. Building APKs via Gradle is authorized for verification; only install to physical devices or emulators when explicitly requested by the user.

## Build and Verification Commands

Run from the repository root (`C:\Users\towhe\AndroidStudioProjects\ExpressCast`):

```powershell
# Run shared JVM unit tests
.\gradlew.bat :shared:test --console=plain

# Build debug APKs and run lint for both apps
.\gradlew.bat :phone:assembleDebug :tv:assembleDebug :phone:lintDebug :tv:lintDebug --console=plain

# Build release APKs (verify R8 optimization)
.\gradlew.bat :phone:assembleRelease :tv:assembleRelease --console=plain
```
