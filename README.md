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

## Deliberate limitations

- The Cameras page provides a clipped live front-camera preview. Its tabs are selectable; Playback and Timelapse show their current unconfigured states because stored/remote media still requires a streaming and storage backend.
- Email alerts require an email delivery service and credentials; the app marks them unavailable instead of pretending delivery is configured.
- Background polling uses Android WorkManager's 15-minute platform minimum; one-minute refresh applies while the app is open.

## Rollback and deliverables

- Working Stage 3 rollback tag: `codex-stage3-baseline`
- Upgrade branch: `codex/greenhouse-complete-upgrade`
- Final APK: `deliverables/greenhouse-monitor-2.0-debug.apk`
- Validation report: `deliverables/complete-upgrade/VALIDATION.md`
- A71 screenshots: `deliverables/complete-upgrade/final-a71/`
- Moto G7 screenshots: `deliverables/complete-upgrade/final-moto-g7/`
