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

## Current Android architecture

- Kotlin/Jetpack Compose, Android API 29+, landscape-only monitoring UI.
- `GreenhouseViewModel` owns Govee refresh, weather, local history, alerts, greenhouse layout, and UI state.
- Govee H5179 uses the supported cloud API; the API key is encrypted with Android Keystore.
- Local history uses SQLite. Background refresh uses WorkManager's 15-minute minimum.
- Server camera capture uses CameraX and `CameraFrameHub`. Existing viewer transport polls AES-256-GCM encrypted JPEG frames from `CameraStreamServer` using a QR-distributed token/key.
- `CameraStreamServer` currently attempts UPnP mapping. This is migration-only and must not be treated as the commercial remote architecture.
- No customer identity, backend tenant isolation, verified subscription entitlement, heartbeat backend, or deployed AWS WebRTC channel exists yet.

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

## WebRTC migration implementation

- `streaming/StreamingLifecycle.kt` enforces authorization-before-connect, bounded exponential reconnect, network-loss waiting, and session termination when the viewer hides, the client locks, the lease expires, or retries are exhausted.
- `LibWebRtcPeer` uses pinned `io.github.webrtc-sdk:android:150.7871.04`, front-camera capture for the master only, unified-plan peer connections, continual ICE gathering, backend-provided ICE servers, and deterministic resource release.
- `KinesisWebSocketSignaling` implements the Kinesis SDP/ICE message envelope over a backend-issued WSS URL. It contains no AWS access key.
- These components compile but are not connected to the approved camera UI because the backend does not yet issue signed Kinesis URLs and no AWS stack is deployed. Existing encrypted JPEG viewing remains active.
- Stage 3/4 verification: Android unit/lint/build passed with 28 JVM tests. Codec and lifecycle behavior are unit-tested; physical A71/Pixel, H.264, direct ICE, TURN, handoff, and AWS signaling remain unverified.

## Next implementation steps

1. Complete one-time pairing persistence and signed Kinesis WSS/ICE issuance in the backend.
2. Wire the WebRTC coordinator behind the existing camera view after a deployed test stack is available.
3. Add heartbeat/emergency Android worker and provider-neutral sensor interfaces.

## Project memory / handoff log
### 2026-11-08 — Initial memory
- Located source on `codex/greenhouse-complete-upgrade` (main contains only initial commit).
- Added remote-connectivity upgrade requirements and required AGENTS.md + CHANGELOG.md workflow.
- No runtime camera or AWS code has been changed by this documentation task.
- Next: Codex read these files, audit existing camera implementation, propose scoped architecture and cost/security plan, then implement only with authorization.
