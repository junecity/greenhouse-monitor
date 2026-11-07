# Greenhouse Monitor 2.0 — design package verification

## Build

- APK: `app/build/outputs/apk/debug/greenhouse-monitor-2.0-debug.apk`
- SHA-256: `c2b8214403d768d95ee61e4ead7a822e6ccb59d0c786026a67331911e055370e`
- Gradle tasks: `testDebugUnitTest lintDebug assembleDebug`
- Result: successful
- Unit tests: 6 passed, 0 failed
- Android lint: 0 errors

## Visual/device checks

- Galaxy A71 5G landscape profile: 2400 × 1080
- Moto G7 landscape profile: 2270 × 1080
- Android font scale: 100% and 130%
- Edge-to-edge: verified; no white end bands
- Front camera: verified with the Android emulator camera feed on Dashboard and Cameras
- Camera tabs: Live View, Playback, Timelapse, and Settings each verified to open its own state

## Verified in code and build

- Native Compose UI and native Canvas/vector icons
- Approved amber/charcoal design tokens and fixed bottom navigation
- Supplied navigation, card, gauge, sensor, chart, and camera motion timings
- Govee H5179 API discovery, encrypted API-key storage, persisted zone assignment, and sensor positioning
- Local historical storage, retention controls, and real-data-only charts
- ZIP-code outdoor weather through Open-Meteo
- Notification preferences and alert worker
- Front-camera-first selection with rear-camera fallback

## Requires real credentials or infrastructure

- Live Govee readings require the owner's Govee API key and H5179 devices. No user key was available during this build verification.
- Secure remote camera streaming, recordings, and timelapse require an authenticated streaming/signaling/storage backend. The app presents those sections honestly as unconfigured; it does not claim remote access is active.
