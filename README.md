# Greenhouse Monitor 2.0

Native Kotlin/Jetpack Compose greenhouse dashboard for Android 10+ (API 29). The interface is landscape-first and scales from the Moto G7 (2270×1080) through the Samsung Galaxy A71 5G (2400×1080) without letterboxing or clipped navigation.

## Included

- Professional charcoal-and-amber dashboard with a circular climate gauge, large environmental readings, a compact sensor list, and a live front-camera card.
- Govee H5179 discovery and readings through Govee's authenticated cloud API.
- Encrypted API-key storage backed by Android Keystore.
- Multiple greenhouses with editable names and physical dimensions.
- Per-sensor greenhouse/zone assignment and drag-to-position physical coordinates.
- Coordinate-based inverse-distance-weighted temperature, humidity, VPD, and dew-point maps, including center and area-average estimates.
- Local SQLite history and 7-day graphs for all four metrics.
- Live front-camera preview on both Dashboard and Cameras using CameraX, with rear-camera fallback and an in-app permission retry.
- AWS Cognito in-app account setup and expiring, one-use QR pairing for greenhouse Server and Viewer phones when AWS deployment outputs are configured.
- On-demand AWS Kinesis Video Streams WebRTC remote camera sessions with encrypted media, direct ICE where possible, managed TURN fallback, renewable Premium authorization, bounded reconnects, and no router port forwarding.
- Legacy AES-256-GCM JPEG streaming remains available on the local network as the rollback path. AWS-configured builds disable its public UPnP mapping.
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

An all-blank AWS configuration produces the local-only rollback build. After an approved AWS deployment, build the cloud-enabled APK with all four non-secret stack outputs together:

```bash
./gradlew assembleDebug \
  -PGREENHOUSE_AWS_REGION=<region> \
  -PGREENHOUSE_COGNITO_USER_POOL_ID=<user-pool-id> \
  -PGREENHOUSE_COGNITO_APP_CLIENT_ID=<android-client-id> \
  -PGREENHOUSE_AWS_API_BASE_URL=<https-api-url>
```

Partial AWS configuration fails the app closed. Never put AWS access keys, IAM credentials, or entitlement-administration permission in the APK.

After the CloudFormation stack is deployed, `scripts/build-aws-debug-apk.sh <stack-name> <aws-region> [aws-profile]` reads its non-secret outputs and writes the configured debug APK and checksum to `build-out/`. The deployed development build is `build-out/Greenhouse-Monitor-AWS-debug.apk`.

## Connect Govee H5179 sensors

1. Add every H5179 in Govee Home and connect it to Wi-Fi.
2. In Govee Home, request a developer key under **Profile → Settings → Apply for API Key**.
3. In Greenhouse Monitor, open **Settings → Connectivity**, enter the key, select **Save Key**, then **Test Connection**.
4. Open **Settings → Devices** and select Z1–Z4 for each sensor. The selected active greenhouse and zone persist on the phone.
5. Open **Zones** and drag each sensor marker to its measured physical location.

## Weather and notifications

Set a five-digit ZIP code under **Settings → General**. Weather lookup geocodes only that ZIP. Notification defaults are intentionally quiet: pop-ups, sound, vibration, and repeat alerts start disabled. Monitoring and history continue even when notifications are disabled.

## Pair an AWS camera server and viewer

1. Install an APK built with the four AWS stack outputs on both phones.
2. Create and confirm the Cognito account in the app, then sign in on both phones.
3. Choose **Server** on the greenhouse phone. It registers the greenhouse and displays a five-minute, one-use pairing QR.
4. Choose **Client** on the other phone and scan the Server QR or enter its code. The backend binds it to the same account and greenhouse.
5. Grant the account verified Premium or IAM-administered complimentary access. Free accounts retain local monitoring but cannot obtain remote signaling/TURN access.
6. Open **Dashboard** or **Cameras** on the Viewer. Video starts only while the live view is open and stops when the view closes or backgrounds.
7. In the live viewer, pinch to zoom up to 5x, drag to pan, and double-tap to reset the image.

AWS remote video uses authenticated Kinesis signaling and encrypted WebRTC media. AWS-managed TURN is used only when a direct connection cannot be established. Public-IP changes do not require re-pairing, and router port forwarding is not required. The legacy local QR contains a camera secret and must still be treated like a password when using the rollback path.

## Deliberate limitations

- Remote camera capture is on demand; playback and Timelapse remain unconfigured because the app does not continuously record or upload video.
- AWS heartbeat and alert services use foreground-service notifications. Android/OEM background limits and Do Not Disturb can still affect delivery; physical A71/Pixel testing is required before making alarm guarantees.
- Customer SMS/email/voice escalation is not configured. The Android app polls its Cognito-authorized AWS alert inbox about once per minute and uses local high-priority notifications without Firebase.
- The AWS development stack is deployed in `us-east-1`. Release signing, Google Play products, complimentary owner activation, and the physical cross-network camera matrix remain unconfigured or unverified.

## Private administration portal

The separate `admin-portal/` Next.js application and `infrastructure/admin-portal.yaml` stack provide an approval-gated private administration surface. They do not change the Android interface or permit administrators to view customer camera feeds. See `docs/ADMIN_PORTAL.md` for security boundaries, local verification, operating-cost assumptions, and the deployment checklist. The portal infrastructure is prepared but has not been deployed.

## Rollback and deliverables

- Working Stage 3 rollback tag: `codex-stage3-baseline`
- Upgrade branch: `codex/greenhouse-complete-upgrade`
- Latest AWS-configured APK: `build-out/Greenhouse-Monitor-AWS-debug.apk` (debug-signed; real development-stack outputs, no AWS credentials).
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
