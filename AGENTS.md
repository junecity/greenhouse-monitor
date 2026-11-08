# Greenhouse Monitor — Codex project memory (AGENTS.md)

## Mandatory workflow for every Codex task
1. Before planning or making ANY edits, read this root AGENTS.md in full, then read CHANGELOG.md and the relevant existing code. Re-read AGENTS.md before each distinct editing task/session. If missing or unreadable, stop and report the blocker.
2. Inspect the current branch, diff, and existing implementation first. Build incrementally; never overwrite unrelated work.
3. Keep all code and AWS resources independent of junecity.studio. Never edit its repository, databases, infrastructure, or notifications.
4. The approved five-screen UI/design is FROZEN. Do not change Dashboard, Zones, History, Cameras, Settings visual layout, styling, typography, navigation, or design assets without explicit new approval. Backend and connection work must preserve the existing user experience.
5. After each completed change/task, BEFORE finishing, update this AGENTS.md with concise durable memory (what changed, decisions, files touched, current limitations, next steps), and append a dated entry to CHANGELOG.md with Added/Changed/Fixed/Testing as appropriate. Include failures and unverified behavior honestly. Commit documentation with the corresponding changes. Never record secrets, credentials, personal addresses, public IPs, tokens, or pairing keys.
6. Run relevant tests and report exact outcomes. Do not claim hardware, network, AWS, or Android behavior verified without testing.
7. On completion, report the branch, commits, files changed, tests, remaining risks, and next steps.

## Product architecture — non-negotiable
- Old Android phone (currently Galaxy A71) is the greenhouse server, local computer, display, front camera, and sensor processor; everyday Android phone (currently Pixel 10) is the client.
- Existing sensor readings, local history, zones, and camera functionality already exist; preserve working features.
- Future customers register, pair devices, configure sensors, and manage subscriptions INSIDE the Android app; no mandatory external website. Multi-customer isolation is server-enforced.
- AWS services are dedicated to this product, even if hosted in the same AWS account as unrelated services.
- Prefer local-first operation: local monitoring should continue during cloud outages when sensor protocols permit it.
- Future custom wireless temperature/humidity sensors integrate via modular adapters; do not replace the old-phone server model.

## Current camera implementation (inspected 2026-11-08)
- `CameraStreamServer.kt`: local HTTP server, TCP 45891, encrypted JPEG frames.
- `UpnpPortMapper.kt`: UPnP port mapping and public IPv4 discovery.
- `RemoteCameraFeed.kt`: attempts saved local host then saved public host.
- `CameraPairing.kt`: QR pairing includes device ID, token, symmetric key, local/public hosts, port.
- Public-IP changes can break remote access; UPnP fails on some routers, CGNAT, and restricted networks. Existing pairing and encryption must be preserved until secure migration is tested.

## Next planned upgrade — AWS remote camera discovery and fallback (NOT YET IMPLEMENTED)
1. Add a dedicated authenticated AWS device-discovery API (e.g. API Gateway/Lambda/DynamoDB) with per-customer/device authorization, least-privilege IAM, short-lived records and heartbeat/last-seen timestamps.
2. Server phone periodically reports current reachable endpoint and mapping status securely, plus reports changes immediately. Never store tokens, encryption keys, or camera frame content in a public registry. Never trust a self-reported address without suitable validation; defend against SSRF and unauthorized endpoint substitution.
3. Client first uses direct LAN streaming; for remote access fetches the latest authorized endpoint, verifies freshness, and attempts a secure direct connection. Handle public IP changes without re-pairing.
4. Provide authenticated encrypted relay fallback for CGNAT/UPnP failures. Evaluate WebRTC STUN/TURN or a suitable outbound-initiated tunnel/relay against the current encrypted-JPEG design, battery, latency, bandwidth, cost, and Android lifecycle constraints. Do not assume an IP registry alone fixes NAT.
5. Prefer removing internet-exposed UPnP HTTP access when a secure remote transport is ready; the current encrypted frame payload alone does not protect all HTTP metadata, pairing tokens in URL, or endpoint exposure. Require explicit review of threat model and transition.
6. Include cloud heartbeat/offline detection and critical-alert architecture separately from ordinary notifications. Test Android restrictions and escalation; no guarantee of persistent alarms without device testing.
7. Keep camera UI unchanged. Implement behind existing interfaces with migrations, feature flags/rollback, automated tests, and real-device LAN/WAN/IP-change/CGNAT/offline tests.
8. Never deploy AWS infrastructure, create billable resources, or change live routing without explicit authorization and cost/security review.

## Premium camera product decision (2026-11-08)
- User selected maximum reliability: AWS Kinesis Video Streams WebRTC signaling and managed TURN fallback, not a public-IP registry as the primary remote transport.
- Remote live camera is Premium-only for customers, with explicit backend-granted complimentary developer access. Local monitoring and local camera remain free.
- Stream starts only when a client explicitly opens live view and ends when viewer exits/locks; preserve approved UI.
- Implement server-side subscription authorization before issuing signaling/TURN access. Never treat client entitlement as authoritative.
- Phase 1 created `RemoteCameraAccessPolicy.kt`, unit tests, and `docs/REMOTE_STREAMING_IMPLEMENTATION.md`. These are scaffolding only, NOT deployed WebRTC.
- Next steps: backend customer identity, Google Play entitlement verification, signaling/TURN authorization, Android WebRTC media transport, network and hardware tests. No AWS deployment without separate cost/security review and deployment approval.
- An IAM-only `EntitlementAdminFunction` now supports complimentary owner grants, revocation, and Free reset by opaque Cognito subject or account ID. It has no HTTP event/function URL, writes audit request IDs, and is never callable by the Android app. This activates the required owner access after deployment without hardcoding a personal email.

## Current Android architecture

- Kotlin/Jetpack Compose, Android API 29+, landscape-only monitoring UI.
- `GreenhouseViewModel` owns Govee refresh, weather, local history, alerts, greenhouse layout, and UI state.
- Govee H5179 uses the supported cloud API; the API key is encrypted with Android Keystore.
- Local history uses SQLite. Background refresh uses WorkManager's 15-minute minimum.
- Server camera capture uses CameraX and `CameraFrameHub`. Existing viewer transport polls AES-256-GCM encrypted JPEG frames from `CameraStreamServer` using a QR-distributed token/key.
- `CameraStreamServer` currently attempts UPnP mapping. This is migration-only and must not be treated as the commercial remote architecture.
- Customer identity/tenant/entitlement contracts, heartbeat backend, and WebRTC channel/session code exist, but no AWS stack, Cognito application, Play product, or live signaling channel has been deployed.

## Validated external constraints

- AWS Kinesis Video Streams WebRTC Android SDK v1.3.0 is an AWS sample/source integration, not a complete Maven media dependency. It requires a compatible `libwebrtc` AAR and AWS signaling dependencies.
- Never place long-lived AWS keys in the APK. Authenticated users obtain short-lived authorization through the Greenhouse backend.
- Google Play subscription benefits require backend verification and lifecycle processing; local flags are not authoritative.

## Rollback and verification

- Rollback tag: `codex-stage3-baseline`.
- Current upgrade branch: `codex/greenhouse-complete-upgrade`.
- Baseline on 2026-11-08: `testDebugUnitTest`, `lintDebug`, and `assembleDebug` passed; 12 JVM tests passed.

## Implemented cloud foundation

- `cloud/CloudContracts.kt` defines authenticated principals, tenant-bound devices and greenhouses, backend entitlement states/sources, pairing, heartbeat, and remote-session contracts.
- `RemoteAccessPolicy` denies unauthenticated, cross-tenant, disabled, wrong-role, free, expired, and revoked access. Complimentary access is accepted only as a backend grant.
- `backend/src/domain.py` mirrors the authoritative backend policy. `backend/src/app.py` contains JWT-derived tenant lookup, tenant-owned resource lookup, heartbeat, and authorized stream-session request foundations.
- `infrastructure/template.yaml` defines isolated Cognito, HTTP API, DynamoDB, Lambda, SNS, and scheduled heartbeat-monitor resources. It has not been deployed.
- Stage 2 verification: Android unit/lint/build passed with 19 JVM tests; 6 Python authorization tests and Python byte-compilation passed. SAM CLI was unavailable, so SAM template validation is pending.
- Backend pairing now issues 256-bit, five-minute challenges, stores only their SHA-256 digest, restricts claims to the authenticated account/tenant and requested viewer device, and atomically rejects reuse/expiry. It does not replace the legacy local QR until account onboarding is connected.
- `AuthenticatedCloudGateway` now covers entitlement, cloud pairing, heartbeat, viewer-session creation, server polling, and session termination over HTTPS bearer authentication. Cloud API/device/token state is encrypted with Android Keystore.
- `CognitoAuthManager` configures Amplify Auth programmatically from AWS deployment outputs, supports SRP sign-up/confirmation/sign-in/sign-out, obtains automatically refreshed Cognito access tokens, and creates the authenticated gateway without embedding AWS credentials. The onboarding gate calls it for new and restored sessions.
- `POST /v1/bootstrap` now idempotently and atomically creates an isolated account, tenant, and active Free entitlement from the validated Cognito subject. `POST /v1/devices` atomically registers the authenticated phone as a greenhouse server plus its initial greenhouse. Viewer devices are still created only by the same-account one-use pairing claim.
- AWS deployment outputs can now be injected at build time through four `GREENHOUSE_*` Gradle properties/environment variables. An all-blank local build preserves existing local operation; partial configuration fails closed.
- Encrypted `CloudEnrollment` persists only the stable device ID, cloud greenhouse ID, and backend role. The heartbeat service now obtains automatically refreshed Cognito tokens at report time instead of relying on a copied access token, and reports the backend greenhouse ID.
- A separate landscape `CloudOnboardingGate` now handles Cognito sign-up, email confirmation, sign-in/session validation, Server/Client role selection, atomic bootstrap, QR generation/scanning, one-use pairing, and encrypted enrollment before entering the unchanged five-screen monitor. Existing enrollments still require a valid Cognito session after process start.
- Account device inventory records are maintained transactionally beside primary device records. Cognito-authenticated, enabled devices can list their account's devices and enable/revoke them through tenant/account-conditioned APIs; revocation is audit-stamped and is enforced by alert access, pairing, and renewable remote-stream authorization. Android gateway methods are present, but no new controls were added to the frozen five monitoring screens.

## WebRTC migration implementation

- `streaming/StreamingLifecycle.kt` enforces authorization-before-connect, bounded exponential reconnect, network-loss waiting, and session termination when the viewer hides, the client locks, the lease expires, or retries are exhausted.
- `LibWebRtcPeer` uses pinned `io.github.webrtc-sdk:android:150.7871.04`, front-camera capture for the master only, unified-plan peer connections, continual ICE gathering, backend-provided ICE servers, and deterministic resource release.
- `KinesisWebSocketSignaling` implements the Kinesis SDP/ICE message envelope over a backend-issued WSS URL. It contains no AWS access key.
- The approved camera panels now select AWS WebRTC for an enrolled Viewer and retain encrypted JPEG viewing as the unconfigured/local rollback. `CloudCameraServerService` polls for authorized pending sessions without opening the camera, activates the front camera only during a session, respects Data Saver/Balanced/High capture profiles, and releases it afterward.
- Stage 3/4 verification: Android unit/lint/build passed with 28 JVM tests. Codec and lifecycle behavior are unit-tested; physical A71/Pixel, H.264, direct ICE, TURN, handoff, and AWS signaling remain unverified.
- The backend now creates/reuses deterministic single-master Kinesis channels only after entitlement/tenant authorization, returns 299-second SigV4 WSS URLs and AWS ICE/TURN configuration, lets the authenticated server poll pending sessions, and lets either participating device terminate a session. No live AWS call has been made.
- Kinesis single-master negotiation is viewer-initiated. Incoming sender IDs are retained so master answers/ICE target only the requesting viewer; backend session payloads now assign peer IDs by role.
- Viewer rendering uses `SurfaceViewRenderer` with the existing pinch-zoom/pan interaction, is scoped to the visible lifecycle, retries peer/network failures up to five times, and ends the backend session in a non-cancellable cleanup. A process-wide camera lease prevents CameraX and libwebrtc from opening the front camera simultaneously.
- Configured AWS builds keep the legacy encrypted JPEG server available only on the LAN and disable UPnP/public router mapping. Unconfigured local-only builds retain the previous migration behavior.
- Streaming sessions now have a four-hour absolute cap plus a two-minute renewable authorization lease. The Viewer revalidates its backend Premium entitlement every minute; the Server independently checks that lease, so abandoned/modified/revoked viewers stop receiving video. Both roles request fresh SigV4 connection/ICE data before reconnecting after the original 299-second URL ages.

## Sensor provider boundary

- `sensors/SensorProvider.kt` defines vendor-neutral devices, measurements, optional battery, connectivity, timestamps, and credential types.
- `GoveeSensorProvider` adapts the existing H5179 API to this boundary. The ViewModel and background worker use the provider while mapping back to the unchanged UI/history model.
- No Junecity hardware protocol is assumed or implemented.
- Stage 7 verification: Android unit/lint/build passed with 30 JVM tests; live Govee API regression requires a real API key and H5179 devices.

## Heartbeat and emergency monitoring

- `cloud/MonitoringAlerts.kt` keeps critical-temperature and server-offline events separate from ordinary informational notification preferences and defines an authenticated heartbeat reporter boundary.
- The scheduled backend scans sharded heartbeat records, paginates results, atomically deduplicates an offline incident, and publishes one internal SNS event. A fresh authenticated server heartbeat clears the incident marker.
- `CloudHeartbeatService` is a non-exported, user-visible special-use foreground service. In server mode with a valid encrypted cloud session it reports cached sensor/critical health every minute and applies bounded retries; it stops when the token expires or the phone changes to viewer mode.
- Account-scoped alert records now live in DynamoDB for server-offline, sensor-communication, and critical-temperature incidents. Heartbeat state changes and their alert records are written atomically; the scheduled offline monitor atomically claims the incident and creates its alert before publishing the internal SNS event.
- Enrolled Android devices poll the Cognito-authorized alert API approximately once per minute, create high-priority local notifications, and acknowledge alerts back to the account/tenant-conditioned API. This intentionally uses AWS plus Android notifications only; Firebase/FCM is not used.
- When Auto Start is enabled, `CloudBootReceiver` restores AWS heartbeat reporting on Server devices and alert polling on enrolled devices after boot. It never starts camera capture from the boot broadcast because modern Android prohibits background camera foreground-service startup.
- This is not an end-user alarm guarantee. Polling can add roughly one minute of latency, Android/OEM background limits and Do Not Disturb can suppress presentation, and SNS has no customer SMS/email subscription configured. Play special-use review and physical Android background/reboot testing remain incomplete.
- Stage 6 verification: 8 Python policy tests and Python compilation passed. After rebasing the pre-existing premium-access scaffold, Android unit/lint/build passed with 37 JVM tests.

## Next implementation steps

1. Add backend-verified subscription lifecycle handling after Google Play product/service-account configuration is approved.
2. Validate the deployed stack and physical A71/Pixel camera/network/background/reboot matrix.
3. Decide where account/device administration is exposed without redesigning the approved monitoring screens.

## Deployment status

- `docs/AWS_DEPLOYMENT.md` is the approval-gated deployment, cost, rollback, and physical-test checklist.
- The Cognito template explicitly uses Plus because enforced threat protection requires it, and deletion protection is active.
- Nothing has been deployed. SAM CLI validation, live Kinesis allocation, Play configuration, and all physical-network tests remain pending.
- Latest local APK: `app/build/outputs/apk/debug/app-debug.apk` (debug-signed, approximately 100 MB because it contains universal WebRTC native libraries). Final credential-free checkpoint SHA-256: `6eabba9fd09d20da0c4389fe1f65329b35cfce82879049137ce7a732e1b05fc1`.
- AWS device-security checkpoint: 57 Android JVM tests, lint, debug assembly, 26 Python tests, and Python compilation passed. Cognito, QR pairing, inventory/revocation, Kinesis signaling, TURN/direct ICE, camera handoff, alert delivery, boot recovery, and network transitions remain unverified on physical devices because no stack is deployed and ADB is unavailable.
- `.github/workflows/verify.yml` reproduces Android tests/lint/build plus Python tests/compilation and publishes a checksum-matched debug artifact on pushes and pull requests; it requires no production secrets.
- `cfn-lint` 1.46.0 validates the SAM template locally and in CI. The undeployed template now includes bounded HTTP API throttling, explicit 30-day API/Lambda log retention, encrypted operations SNS and heartbeat DLQ resources, scheduled retry limits, and alarms for API/Lambda/heartbeat failures. SAM CLI/account-aware validation is still pending.
- AWS-only owner activation is prepared through the undeployed IAM-restricted entitlement Lambda. Its complimentary grant/revoke/reset state machine has backend tests, but IAM invocation, CloudTrail audit, and lease revocation require a deployed development stack.

## Project memory / handoff log
### 2026-11-08 — Initial memory
- Located source on `codex/greenhouse-complete-upgrade` (main contains only initial commit).
- Added remote-connectivity upgrade requirements and required AGENTS.md + CHANGELOG.md workflow.
- No runtime camera or AWS code was changed by the initial documentation task.
