# Changelog — Greenhouse Monitor

All meaningful changes must be recorded here, newest first. Each entry should include date (YYYY-MM-DD), branch/commit where known, Added/Changed/Fixed, validation performed, and limitations. Do not claim untested work is working.

## 2026-11-08 — Reproducible CI verification

- Added a least-permission GitHub Actions workflow for Android unit tests, lint, debug assembly, backend tests, Python compilation, APK checksumming, and short-lived artifact upload.
- No credentials or deployment actions are present in the workflow.
- Local validation remains the latest executed result until GitHub completes the first workflow run.

## 2026-11-08 — Final credential-free checkpoint

- Completed every safe offline component through the cloud activation boundary without changing the approved five-screen design or deploying billable services.
- Final tests: 43 Android JVM tests, Android lint, debug assembly, 12 Python policy tests, Python byte-compilation, and diff checks passed.
- Debug APK SHA-256: `6eabba9fd09d20da0c4389fe1f65329b35cfce82879049137ce7a732e1b05fc1`.
- External gates remain: approved AWS dev deployment, Cognito and Play/FCM configuration, release signing, and Galaxy A71/Pixel 10 network/background/camera validation.

## 2026-11-08 — Correct Kinesis single-master negotiation

- Corrected WebRTC negotiation so the viewer creates the offer and the greenhouse master answers it.
- Preserved Kinesis `senderClientId` and assigned role-specific peer IDs so master SDP/ICE responses target the authorized viewer.
- Tests: 43 Android JVM tests and 12 Python tests passed; Python compilation passed. Live Kinesis interoperability remains a physical/deployed test requirement.

## 2026-11-08 — Foreground server heartbeat runtime

- Added a non-exported, user-visible Android special-use foreground service that reports server/sensor/critical-temperature health approximately every minute only in configured greenhouse-server mode.
- Added bounded retry cadence, encrypted-token expiry shutdown, viewer-mode shutdown, and a local health snapshot updated by Govee refreshes.
- Declared the required foreground-service permissions/subtype without changing the approved interface.
- Tests: Android unit tests, lint, and debug assembly passed with 41 JVM tests. Physical A71 background, battery, reboot, OEM task-killer, notification-permission, and Play policy behavior remain unverified.

## 2026-11-08 — Authenticated Android cloud gateway

- Added an HTTPS-only bearer-authenticated Android gateway for entitlement, pairing, heartbeats, viewer session requests, server polling, and session termination.
- Added Android Keystore-encrypted cloud API, device, access-token, expiry, and optional refresh-token storage; no AWS access keys are stored.
- Added the tenant-derived backend entitlement endpoint and JSON contract tests.
- Tests: Android unit tests, lint, and debug assembly passed with 39 JVM tests; 12 Python tests and Python byte-compilation passed. Cognito token acquisition and live API calls remain unverified.

## 2026-11-08 — Backend-issued Kinesis sessions

- Added deterministic per-greenhouse Kinesis signaling-channel allocation after tenant and Premium authorization.
- Added short-lived SigV4 WebSocket URLs, AWS ICE/TURN configuration, authenticated server polling, and participant-only session termination.
- Added least-privilege-intended Kinesis actions to the undeployed API role and deterministic channel-name tests.
- Tests: 12 Python policy tests and Python byte-compilation passed. Live Kinesis, IAM, TURN, and device interoperability remain unverified without an approved development deployment.

## 2026-11-08 — Account-scoped cloud pairing

- Added five-minute, cryptographically random pairing challenges; only a SHA-256 digest is persisted.
- Added authenticated same-account/same-tenant viewer claims with one-use conditional writes, expiry enforcement, device-ID validation, and safe conflict responses.
- Kept the existing local QR pairing active until Cognito onboarding and a deployed backend are available.
- Tests: 11 Python policy tests and Python byte-compilation passed. DynamoDB integration remains unverified without a deployed development stack.

## 2026-11-08 — Deployment and cost handoff

- Added an approval-gated AWS deployment, rollback, cost, and physical-device validation checklist.
- Made the Cognito Plus cost/security choice explicit and enabled user-pool deletion protection.
- No infrastructure was deployed. SAM validation remains pending because the SAM CLI and AWS configuration are not available in the current environment.
- Final post-rebase verification: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 37 JVM tests; 8 Python tests and Python compilation passed. The debug APK SHA-256 is `acbecc053421b70607946ee206c46e6ec081bd69fe29fbab6c819ef797884ae7`.

## 2026-11-08 — Stage 6 heartbeat and alert foundations

- Added Android-domain heartbeat reporting and explicit critical/high/normal alert policy without modifying the approved monitoring UI.
- Added paginated, sharded offline detection with atomic incident deduplication; a subsequent authenticated heartbeat clears the incident marker.
- Expanded backend heartbeat policy coverage and corrected the monitor Lambda's DynamoDB write permission.
- Tests: 8 Python unit tests and Python compilation passed. The final combined-history Android checkpoint passed unit tests, lint, and assembly with 37 JVM tests.

## 2026-11-08 — Stage 7 sensor-provider extensibility

- Added a vendor-neutral sensor device/measurement/provider boundary with optional battery, connectivity, identity, and timestamp fields.
- Adapted the existing Govee H5179 implementation, foreground refresh, and WorkManager sync to the provider boundary without changing UI, history, or Govee credentials.
- Added mapping tests that preserve full temperature/humidity precision and missing values.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 30 JVM tests. Live Govee regression remains unverified without hardware/API credentials.

## 2026-11-08 — Stages 3–4 WebRTC media and lifecycle foundations

- Pinned a current precompiled Android `libwebrtc` artifact and added a real peer engine with front-camera master capture, remote-track delivery, continual ICE gathering, and deterministic cleanup.
- Added Kinesis WebRTC signaling SDP/ICE envelope handling over backend-issued secure WebSockets; no AWS keys are embedded.
- Added an on-demand streaming lifecycle that authorizes before connection, handles network loss and bounded reconnection, and ends sessions when the viewer closes, backgrounds, locks, expires, or exhausts retries.
- Preserved the existing encrypted JPEG/local camera implementation; WebRTC is not activated in the approved UI without deployed backend configuration.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 28 JVM tests. Live AWS, TURN, H.264, Galaxy A71, Pixel 10, and network-handoff tests remain unverified.

## 2026-11-08 — Stage 2 security and backend foundations

- Added Android domain contracts for accounts, tenants, devices, one-time pairing, heartbeats, streaming sessions, and backend-authoritative entitlements.
- Added deny-by-default remote camera authorization covering Free, Premium, complimentary backend grants, expiration, revocation, disabled devices, device roles, and tenant isolation.
- Added an isolated AWS SAM template for Cognito, API Gateway, DynamoDB, Lambda, SNS, and scheduled heartbeat checks. Nothing was deployed.
- Added backend authorization, stream-session request, heartbeat foundations, and matching Python policy tests.
- Tests: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 19 JVM tests; 6 Python tests and Python byte-compilation passed. SAM CLI was unavailable, so template lint is pending.

## 2026-11-08 — Stage 1 repository audit

- Read the project memory, camera implementation, sensor/history paths, build configuration, current branch, and worktree state.
- Confirmed the legacy encrypted JPEG/QR/UPnP path and frozen five-screen UI.
- Baseline Android unit tests, lint, and debug assembly passed with 12 JVM tests.

## 2026-11-08 — Premium remote camera implementation scaffold
### Added
- `RemoteCameraAccessPolicy.kt`: fail-closed entitlement policy for remote camera; local camera remains free.
- `RemoteCameraAccessPolicyTest.kt`: premium, complimentary, free, expired and unverified test cases.
- `docs/REMOTE_STREAMING_IMPLEMENTATION.md`: implementation and security acceptance plan for AWS Kinesis Video Streams WebRTC with TURN fallback.
### Changed
- Project memory updated with premium-only, on-demand remote camera streaming decision.
- No existing camera code, five-screen UI, AWS infrastructure, or billing changed.
### Validation
- Source and documentation reviewed; new tests added but not executed in an Android build environment.
### Remaining
- Actual WebRTC transport, Cognito auth, Google Play entitlement verification, backend signaling/TURN authorization, session lifecycle, and AWS deployment remain unimplemented.

## 2026-11-08 — Codex workflow and AWS remote-access upgrade specification
### Added
- Root `AGENTS.md` with mandatory read-before-edit, update-after-task project memory and design-freeze rules.
- AWS authenticated remote camera discovery and encrypted relay fallback requirements for future Codex implementation.
- Persistent changelog policy for all subsequent tasks.
### Changed
- No application source code, UI, camera transport, or AWS resources changed in this documentation update.
### Validation
- Read existing `CameraStreamServer.kt`, `UpnpPortMapper.kt`, `RemoteCameraFeed.kt`, `CameraPairing.kt` and README from `codex/greenhouse-complete-upgrade`.
- No build, emulator, physical-device, or AWS integration tests run.
### Remaining
- AWS discovery, endpoint refresh, and NAT-resistant relay are specifications only; implementation and testing remain outstanding.
