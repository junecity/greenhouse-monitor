# Greenhouse Monitor 2.0

Native Kotlin/Jetpack Compose greenhouse dashboard for Android 10+ (API 29). The interface is landscape-first and scales from the Moto G7 (2270×1080) through the Samsung Galaxy A71 5G (2400×1080) without letterboxing or clipped navigation.

## Included

- Professional charcoal-and-amber dashboard with a circular climate gauge, environmental cards, 24-hour trend, compact sensor list, whole-number display values, and a live front-camera card.
- Govee H5179 discovery and readings through Govee's authenticated cloud API.
- Encrypted API-key storage backed by Android Keystore.
- Multiple greenhouses with editable names and physical dimensions.
- Per-sensor greenhouse/zone assignment and drag-to-position physical coordinates.
- Coordinate-based inverse-distance-weighted temperature, humidity, VPD, and dew-point maps, including center and area-average estimates.
- Local SQLite history and 7-day graphs for all four metrics.
- Live front-camera preview on both Dashboard and Cameras using CameraX, with rear-camera fallback and an in-app permission retry.
- Paired phone-to-phone camera streaming with QR setup, AES-256-GCM encrypted frames, a random viewer token, local Wi-Fi access, and automatic UPnP remote access on compatible routers.
- Selectable camera stream profiles: Data Saver (480×270 at 4 fps), Balanced (640×360 at 7 fps), and High (1280×720 at 10 fps). Frame capture is throttled before JPEG compression to reduce server-phone CPU use and latency.
- ZIP-code weather through Open-Meteo. No GPS or location permission is used.
- Background sensor checks, locally configurable alert categories, quiet defaults, repeat control, and snooze.

The H5179 sensors and phone may be on the same Wi-Fi, but Govee's supported developer API is cloud-based, so internet access is still required.

## Build and install

Requirements: JDK 17 and Android SDK Platform 36.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Greenhouse Monitor** and keep the phone in landscape orientation.

## Connect Govee H5179 sensors

1. Add every H5179 in Govee Home and connect it to Wi-Fi.
2. In Govee Home, request a developer key under **Profile → Settings → Apply for API Key**.
3. In Greenhouse Monitor, open **Settings → Connectivity**, enter the key, select **Save Key**, then **Test Connection**.
4. Open **Settings → Devices** and select Z1–Z4 for each sensor. The selected active greenhouse and zone persist on the phone.
5. Open **Zones** and drag each sensor marker to its measured physical location.

## Weather and notifications

Set a five-digit ZIP code under **Settings → General**. Weather lookup geocodes only that ZIP. Notification defaults are intentionally quiet: pop-ups, sound, vibration, and repeat alerts start disabled. Monitoring and history continue even when notifications are disabled.

## Pair a camera server and viewer

1. On the greenhouse phone, open **Settings → Camera** and select **Greenhouse Server**.
2. Allow camera access and leave the app open. The status reports whether local-only or local-and-remote access is ready.
3. On the other phone, select **Viewer Client**, tap **Scan server QR**, and scan the QR displayed by the greenhouse phone.
4. Open **Dashboard** or **Cameras** on the viewer. It tries the local Wi-Fi address first, then the mapped public address.
5. In the live viewer, pinch to zoom up to 5x, drag to pan, and double-tap to reset the image.
6. Use **Revoke viewers & make new QR** on the server at any time to invalidate the previous token and encryption key.

The video payload is encrypted before being sent. Treat the pairing QR like a camera password and only scan it with trusted phones. Remote access requires UPnP on the router and a publicly reachable IPv4 address. If the ISP changes that public address while both phones are away from the home network, pair again after returning to the local network.

## Deliberate limitations

- The camera server runs while the Greenhouse Monitor app is open. Playback and Timelapse remain unconfigured because the app does not record or upload video.
- Email alerts require an email delivery service and credentials; the app marks them unavailable instead of pretending delivery is configured.
- Background polling uses Android WorkManager's 15-minute platform minimum; one-minute refresh applies while the app is open.

## Rollback and deliverables

- Working Stage 3 rollback tag: `codex-stage3-baseline`
- Upgrade branch: `codex/greenhouse-complete-upgrade`
- Latest APK: `deliverables/geometry-settings-v2.6/Greenhouse Monitor 2.6.1-debug.apk`
- Version 2.6.1 renders the Zones footprint at a uniform feet-to-pixels scale, so equally sized attached greenhouse sections remain visually proportional, and uses a minimalist summary-first Settings layout with advanced editing hidden until requested.
- Version 2.6 makes Settings typography larger, derives the Zones footprint directly from the saved greenhouse plan, calculates section area and bounding dimensions accurately, and safely reprojects sensor positions when geometry changes.
- Version 2.6 Moto G7 screenshots and validation: `deliverables/geometry-settings-v2.6/`
- Previous icon-library APK: `deliverables/icon-library-v2.5/Greenhouse Monitor 2.5-debug.apk`
- Version 2.5 integrates the complete Greenhouse Individual Icon Assets library as sharp native Compose vectors, including accessibility-aware motion timings.
- Version 2.5 screenshots and validation: `deliverables/icon-library-v2.5/`
- Full supplied SVG and PNG preview library: `design-assets/greenhouse-individual-icons/`
- Previous layout APK: `deliverables/greenhouse-layout-v2.4/Greenhouse Monitor 2.4-debug.apk`
- Version 2.4 adds a persistent rectangle/T-shaped greenhouse designer, the 8 × 16 + 8 × 16 T preset, a shape-aware Zones map, and standardized action buttons.
- Version 2.4 screenshots and validation: `deliverables/greenhouse-layout-v2.4/`
- Previous camera streaming APK: `deliverables/camera-streaming-v2.3/Greenhouse Monitor 2.3-debug.apk`
- Version 2.3 dashboard screenshot: `deliverables/camera-streaming-v2.3/dashboard-moto-g7.png`
- Version 2.3 validation: `deliverables/camera-streaming-v2.3/VALIDATION.md`
- Validation report: `deliverables/complete-upgrade/VALIDATION.md`
- A71 screenshots: `deliverables/complete-upgrade/final-a71/`
- Moto G7 screenshots: `deliverables/complete-upgrade/final-moto-g7/`
