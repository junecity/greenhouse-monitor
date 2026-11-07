# Greenhouse Monitor 2.0 validation

## Automated checks

- `testDebugUnitTest`: pass (6 tests)
- `lintDebug`: pass (0 errors; dependency/API advisory warnings only)
- `assembleDebug`: pass
- Emulator launch: pass, activity remained resumed with no fatal exception
- Camera permission and CameraX lifecycle binding: implemented. Final optical preview must be confirmed on the physical A71/Moto because the test AVD exposes an inconsistent virtual-camera profile.

The tests cover exact-point IDW interpolation, symmetric center/area averages, empty interpolation, dew point/VPD derivation, stale-reading rejection, and whole-number display rounding.

## Responsive checks

- Samsung Galaxy A71 5G target: 2400×1080, full-screen landscape
- Moto G7 target: 2270×1080, full-screen landscape

Both sizes retain all five navigation items, the top status area, and the complete dashboard without letterboxing. Screenshots are stored beside this report.

## Data behavior

- Sensor calculations retain `Double` precision; rounding occurs only in display helpers and alert copy.
- The heat map, center, and area average use physical sensor coordinates and inverse-distance weighting.
- Readings older than ten minutes are excluded from live spatial calculations.
- Weather uses a five-digit ZIP and Open-Meteo; location permissions were removed.
- Zone and greenhouse assignments are persisted locally.
- Alert state is persisted per sensor. An unchanged condition does not notify again unless repeated alerts are explicitly enabled and the repeat interval is due.

## Not represented as complete

- Secure remote camera streaming, playback, and timelapse need a WebRTC signaling/TURN backend. Local CameraX preview is implemented.
- Email delivery needs an external email provider.
- A live Govee verification requires the user's API key and physical H5179 devices; no credential is stored in this repository.
