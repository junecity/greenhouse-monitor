# junecity.studio Greenhouse Monitor — Stages 1–3

A native Kotlin/Jetpack Compose landscape dashboard for Android 10+ (API 29). The layout fills common Android landscape aspect ratios without stretching, clipping, or fixed-device letterboxing. It is verified at 1280×720, 1600×900, 1920×1080, the Moto G7's 2270×1080 resolution, and the Samsung Galaxy A71 5G's 2400×1080 resolution.

## Build

Requirements: JDK 17 and Android SDK Platform 36.

```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
./gradlew assembleDebug
```

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Install

Enable Developer options and USB debugging on the A71, connect it by USB, accept the device authorization prompt, then run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Launch **Greenhouse Monitor**. Android may show its standard one-time immersive-mode education message; dismiss it once. The dashboard locks to landscape, hides system bars, keeps the screen awake, and restores immersive mode when resumed.

## Connect Govee H5179 sensors

1. Add each H5179 to the Govee Home app and connect it to Wi-Fi.
2. In Govee Home, open **Profile → Settings → Apply for API Key**.
3. In Greenhouse Monitor, open **Settings**, paste the key, and select **Save & discover**.
4. Open **Zones**, select a sensor, and assign it to Zone 1, 2, 3, or 4.

The API key is encrypted with a non-exportable AES key stored in Android Keystore. It is not compiled into the app or written to logs. Govee's supported integration is cloud-based: being on the same Wi-Fi helps each H5179 stay connected, but the app still needs internet access to query Govee's authenticated API.

## Weather and remote monitoring

Grant location permission when prompted. Only the device coordinates are sent to Open-Meteo to retrieve current local conditions and today's forecast; the app does not request background location.

For remote monitoring, install the APK on the other Android phone and connect the same Govee API key. Foreground readings refresh every two minutes. Android WorkManager performs a network-constrained background check every 15 minutes. Online readings are retained locally for 30 days and temperature/humidity threshold violations generate Android notifications.

## Deliverables

- `deliverables/greenhouse-monitor-stage3-debug.apk`
- `deliverables/stage3-dashboard-a71.png`
- `deliverables/stage3-zones-a71.png`
- `deliverables/stage3-settings-a71.png`
- `deliverables/responsive-moto-g7-2270x1080.png`
- `deliverables/reference-a71-letterboxed.png`
- `deliverables/comparison-overlay.png`
- `deliverables/difference.png`
- `DESIGN_SPEC.md`

The camera remains a demonstration preview; camera streaming is outside the current sensor/weather/remote-monitoring integration.
