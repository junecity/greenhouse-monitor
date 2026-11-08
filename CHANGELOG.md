# Changelog — Greenhouse Monitor

All meaningful changes must be recorded here, newest first. Each entry should include date (YYYY-MM-DD), branch/commit where known, Added/Changed/Fixed, validation performed, and limitations. Do not claim untested work is working.

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
