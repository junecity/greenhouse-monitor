# Changelog — Greenhouse Monitor

All meaningful changes must be recorded here, newest first. Each entry should include date (YYYY-MM-DD), branch/commit where known, Added/Changed/Fixed, validation performed, and limitations. Do not claim untested work is working.

## 2026-11-08 — Deployment and cost handoff

- Added an approval-gated AWS deployment, rollback, cost, and physical-device validation checklist.
- Made the Cognito Plus cost/security choice explicit and enabled user-pool deletion protection.
- No infrastructure was deployed. SAM validation remains pending because the SAM CLI and AWS configuration are not available in the current environment.
- Final local verification: Android `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed with 33 JVM tests after provisioning temporary build tooling; 8 Python tests and Python compilation passed. The debug APK SHA-256 is `7486024835ad62ee5bbe9b404cbdee9ae9b1edf09a0d3787f4d2858de489828a`.

## 2026-11-08 — Stage 6 heartbeat and alert foundations

- Added Android-domain heartbeat reporting and explicit critical/high/normal alert policy without modifying the approved monitoring UI.
- Added paginated, sharded offline detection with atomic incident deduplication; a subsequent authenticated heartbeat clears the incident marker.
- Expanded backend heartbeat policy coverage and corrected the monitor Lambda's DynamoDB write permission.
- Tests: 8 Python unit tests and Python compilation passed. The subsequent final Android checkpoint passed unit tests, lint, and assembly with 33 JVM tests.

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
