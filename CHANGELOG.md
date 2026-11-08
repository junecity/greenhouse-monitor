# Changelog — Greenhouse Monitor

All meaningful changes must be recorded here, newest first. Each entry should include date (YYYY-MM-DD), branch/commit where known, Added/Changed/Fixed, validation performed, and limitations. Do not claim untested work is working.

## 2026-11-08 — AWS-only complimentary Premium administration

- Added an IAM-invoked entitlement administration Lambda with no HTTP route or function URL.
- Added fail-closed account selection by opaque Cognito subject or account ID and actions to grant complimentary remote video, revoke access, or reset an account to Free.
- Added entitlement revisioning, verification timestamps, Lambda request-ID audit stamps, 90-day logs, least-privilege DynamoDB permissions, and a CloudWatch error alarm.
- Added deployment instructions that do not hardcode or use a personal email as an authorization bypass.
- Tests: 29 Python tests, Python byte-compilation, and `cfn-lint` 1.46.0 passed. Live IAM invocation, DynamoDB updates, CloudTrail audit, and renewable-lease revocation remain unverified until an approved AWS development deployment.

## 2026-11-08 — AWS account device inventory and revocation

- Added transactionally maintained account-device index records for Server and paired Viewer phones.
- Added Cognito-authenticated, tenant/account-conditioned device listing and enable/revoke endpoints with revocation time and requesting-device audit fields.
- Added Android gateway contracts for device inventory and state changes without modifying the approved monitoring screens.
- Revoked devices are denied alert access and pairing, and remote streaming loses authorization when the current renewable lease expires.
- Tests: 57 Android JVM tests, Android lint, debug assembly, 26 Python tests, and Python byte-compilation passed. Live DynamoDB transactions, Cognito authorization, revocation propagation timing, and account-administration UI remain unverified or intentionally outside the frozen five-screen interface.

## 2026-11-08 — AWS operational safeguards and template validation

- Added HTTP API throttling, explicit 30-day Lambda log retention, an encrypted operations SNS topic, and CloudWatch alarms for API errors/throttling/5xx responses and heartbeat-monitor errors.
- Added bounded EventBridge retries, an encrypted heartbeat dead-letter queue, and an alarm for exhausted scheduled checks.
- Added `cfn-lint` to backend development dependencies and CI so the transformed SAM template is checked without AWS credentials.
- Updated deployment guidance to describe the implemented Cognito onboarding, WebRTC, AWS alert inbox/acknowledgement, AWS-only Android polling, operations-topic subscription, and remaining deployment gates accurately.
- Tests: `cfn-lint` 1.46.0 passed for `infrastructure/template.yaml`; 25 Python tests and Python byte-compilation passed. AWS SAM CLI/account-aware validation and live alarm delivery remain unverified because no AWS stack is deployed.

## 2026-11-08 — AWS-only alert delivery and reboot recovery

- Added account/tenant-isolated DynamoDB alerts for server-offline, sensor-communication, and critical-temperature incidents with 90-day TTL retention.
- Added Cognito-authorized alert retrieval and acknowledgement APIs; disabled or cross-account devices cannot retrieve or acknowledge another account's alerts.
- Added Android AWS alert polling, high-priority local notifications, and an authenticated Acknowledge action without Firebase/FCM or another cloud platform.
- Added opt-in boot recovery for cloud heartbeat and alert services. Camera capture intentionally waits for an app launch because Android restricts starting camera foreground services from boot receivers.
- Extended the heartbeat GSI projection with account identity and kept SNS as an internal event destination for future AWS-native escalation.
- Tests: 56 Android JVM tests, Android lint, debug assembly, 25 Python tests, and Python byte-compilation passed. Live notification timing, reboot/OEM restrictions, Do Not Disturb behavior, Cognito refresh after long idle, and SNS escalation remain unverified until AWS deployment and physical-device testing.

## 2026-11-08 — Renewable, revocation-aware streaming sessions

- Extended authorized viewing sessions to a four-hour absolute cap while adding a two-minute backend authorization lease.
- Viewer phones revalidate tenant/device/Premium authorization every minute; Server phones independently enforce but cannot extend the lease, limiting abandoned or modified-client streaming.
- Added participant-only connection refresh endpoints that issue fresh role-scoped SigV4 WebSocket and AWS ICE/TURN data before bounded reconnect attempts.
- Kept immediate non-cancellable session termination on viewer exit/background and preserved the on-demand camera lifecycle.
- Tests: 54 Android JVM tests, Android lint, debug assembly, 20 Python tests, and Python byte-compilation passed. Live lease timing, revocation, long-duration streaming, and reconnects remain unverified until deployment and device testing.

## 2026-11-08 — On-demand AWS WebRTC camera activation

- Connected enrolled Viewer camera panels to backend-authorized Kinesis WebRTC while retaining the existing local encrypted-JPEG fallback.
- Added a non-exported Server foreground service that waits without opening the camera, captures only for an authorized live session, applies the selected stream resolution/rate, performs bounded reconnects, and releases camera/network resources afterward.
- Added lifecycle-scoped WebRTC rendering with the existing pinch-zoom/pan interaction and non-cancellable backend session termination when the viewer leaves or locks the phone.
- Added a camera lease so CameraX and libwebrtc cannot contend for the front camera.
- Disabled UPnP/public router mapping in AWS-configured builds; the legacy server remains LAN-only for rollback.
- Tests: 54 Android JVM tests, Android lint, and debug assembly passed. Live Cognito/Kinesis signaling, direct ICE, TURN fallback, camera handoff, Wi-Fi/cellular transitions, and physical video remain unverified without AWS deployment and devices. Initial backend session lifetime is still finite and renewable connection leases are the next reliability task.

## 2026-11-08 — In-app AWS onboarding and QR device pairing

- Added a separate responsive landscape onboarding flow for Cognito sign-up, email confirmation, sign-in, restored-session validation, and Server/Client selection without modifying the approved five monitoring screens.
- Added atomic account bootstrap and Server registration calls, five-minute one-use QR generation, client QR scanning/manual entry, same-account claim, and encrypted role/resource enrollment.
- Existing enrollments now require a valid automatically refreshed Cognito session before the monitoring interface opens.
- Standardized onboarding controls on the existing charcoal/amber/white/green palette and retained large landscape typography.
- Tests: 53 Android JVM tests, Android lint, and debug assembly passed; configured BuildConfig generation was checked with placeholder AWS outputs and then restored to a blank local build. Live Cognito, QR camera scanning, backend pairing, and physical layout remain unverified until AWS deployment and device access.

## 2026-11-08 — AWS runtime configuration and durable device enrollment

- Added all-or-nothing build-time injection for the AWS region, Cognito user pool/client, and API Gateway URL while keeping unconfigured local builds operational.
- Added encrypted stable device/greenhouse enrollment identifiers and backend role; no AWS credentials are stored.
- Added bootstrap/server-registration gateway calls and greenhouse identity propagation through server and one-use viewer pairing responses.
- Changed cloud heartbeat reporting to obtain automatically refreshed Cognito tokens at runtime and report the backend greenhouse ID instead of a copied token/local UI ID.
- Tests: 49 Android JVM tests, Android lint, debug assembly, 18 Python tests, and Python byte-compilation passed. Live Cognito/API behavior remains unverified without deployment; the onboarding UI is the next task.

## 2026-11-08 — Atomic AWS account and server-device bootstrap

- Added an idempotent Cognito-subject bootstrap endpoint that atomically creates an isolated account, tenant, and active Free entitlement without storing email or trusting client tenant identifiers.
- Added atomic authenticated greenhouse-server and initial-greenhouse registration; viewer creation remains restricted to the same-account single-use pairing claim.
- Added API/domain tests for first-run bootstrap, retry idempotency, tenant binding, device mismatch rejection, resource validation, and Free defaults.
- Added the AWS SDK as a CI-only backend test dependency; Lambda continues to use its managed runtime SDK.
- Tests: 18 Python tests and Python byte-compilation passed. DynamoDB transaction behavior and JWT authorizer integration remain unverified until deployment.

## 2026-11-08 — AWS Cognito Android authentication foundation

- Added programmatic AWS Amplify/Cognito configuration with SRP sign-up, confirmation, sign-in, sign-out, automatically refreshed access-token retrieval, and authenticated cloud-gateway creation.
- Added strict AWS region, user-pool, app-client, and HTTPS API configuration validation; no AWS credentials or Firebase services are embedded.
- Enabled Android core-library desugaring required by the pinned Amplify Auth dependency.
- Tests: 46 Android JVM tests, Android lint, and debug assembly passed. Live Cognito registration and token refresh remain unverified until a development stack is deployed; the onboarding UI and backend account bootstrap are separate next tasks.

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
