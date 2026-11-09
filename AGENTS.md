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

## Historical AWS remote-camera migration plan (implemented in code; not deployed)
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
- `scripts/build-aws-debug-apk.sh` reads only non-secret CloudFormation outputs after deployment and creates a checksum-matched configured debug APK under ignored `build-out/`. It does not deploy infrastructure or access long-lived AWS keys in the APK.
- Encrypted `CloudEnrollment` persists only the stable device ID, cloud greenhouse ID, and backend role. The heartbeat service now obtains automatically refreshed Cognito tokens at report time instead of relying on a copied access token, and reports the backend greenhouse ID.
- A separate portrait-first `CloudOnboardingGate` handles account sign-up, email confirmation, sign-in/session validation, automatic Client registration, client-created Server QR scanning/approval, atomic bootstrap, and encrypted enrollment before rotating into the unchanged landscape five-screen monitor. Viewer enrollments restore through Cognito; newly paired Servers restore through their device-bound credential.
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

1. Install Android 2.7.0 on the intended client and greenhouse phones and complete the physical QR/approval/reboot/replacement/Govee regression matrix.
2. Configure an approved Google Play product/service account in Secrets Manager and validate purchase lifecycle notifications before activating production billing.
3. Validate the physical A71/Pixel camera/network/background/reboot matrix and create a release-signed Play build.

## Automatic client and server enrollment (2026-11-08)

- Android 2.7.0 no longer asks authenticated customers to choose Client or Server. The first successful account sign-in registers the phone as the primary `VIEWER_CLIENT`; additional signed-in phones register as authorized clients and open monitoring after backend validation.
- Fresh installs also offer `Scan QR Code to Pair as Server`. In Android 2.7.1, opening Settings → Connectivity on an authenticated client automatically creates and displays the single-use five-minute invitation; after scanning, the approval control appears in the same Connectivity panel. The scanning phone sends no account password, creates a 256-bit device secret locally, waits for explicit client approval, and persists only the approved role/greenhouse/device credential inside the existing Android Keystore-backed encrypted enrollment.
- Reopening Connectivity restores any still-pending server approval before creating a new QR, preventing an in-progress greenhouse phone from becoming stranded. Expired unused codes refresh automatically, and additional servers can be paired deliberately after approval. Settings → Devices remains the full inventory/replacement/removal workspace.
- Android 2.7.1/code 14 checkpoint: GitHub Actions run `42543388030` passed Android JVM tests/lint/assembly, 57 backend tests, 12 admin-portal tests/lint/build/audit, AWS template lint, and both universal and configured ARM artifact checks. The stable-signed ARM APK is `build-out/Greenhouse Monitor 2.7.1.apk`, 81,348,324 bytes, SHA-256 `f07211a0c2cdc41940682ad70c547f4f8a6c48557f16e51af51186a9c332dbad`, certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`. The existing Drive APK was replaced in place and metadata-readback verified. Physical two-phone scan/approval remains unverified.
- Public server-pairing request/status routes carry no account authority. Invitation creation, pending-request listing, and approval remain Cognito/client-authorized. Approved server service calls use the separate `/v1/device/*` route, a hashed device secret, enabled-device checks, account-state checks, and a server-only route allowlist. Revocation therefore takes effect without relying on local flags.
- Accounts can own multiple greenhouses and servers. Settings → Devices exposes Add Server, Replace Server, Remove Server, client revocation, status, pending approval, and greenhouse selection. Replacement retains the stable greenhouse ID, disables the old server atomically, and preserves geometry/preferences, notification settings, sensor greenhouse/zone assignments, and up to 1,500 recent readings through a server-authenticated backup. Govee credentials are deliberately excluded.
- Legacy authenticated server enrollments without device secrets remain valid for migration; new servers use device credentials and do not need a customer password or Cognito session. The existing local Govee, history, alerts, camera, and five-screen landscape implementation remains in place.
- The development stack is updated and `IN_SYNC`, and the admin stack's current-version marker is 2.7.0. Live disposable-account testing passed automatic client registration, two greenhouses, three server approvals, replacement, device credential validation, and backup restoration, followed by verified cleanup. Local checkpoints: 68 Android JVM tests, lint, configured assembly, emulator onboarding/scanner launch, 57 backend tests, Python compilation, CloudFormation lint, and SAM validate/build all passed.
- Current artifact: `build-out/Greenhouse-Monitor-AWS-debug.apk`, version 2.7.0/code 13, SHA-256 `38c4d7148509acc1626776f8ab5dea74a8ed94decf2c9e522b73f1a5be5b7961`. It is debug-signed. Physical two-phone QR capture/approval, reboot persistence, replacement restore, and Govee regression are not yet claimed.
- `GREENHOUSE_ARM_ONLY=true` produces a single APK containing `armeabi-v7a` and `arm64-v8a` native libraries while preserving the same application code and cloud configuration. The verification workflow exposes this as a manual input and publishes a separately verified configured ARM artifact on upgrade-branch pushes using non-secret repository variables. This keeps a direct-install APK below file-transfer limits without excluding the ARM-based Galaxy A71, Pixel 10, or Moto G7 families. Normal push/PR verification remains a universal build.
- Direct ARM artifact checkpoint: `build-out/Greenhouse Monitor 2.7.0.apk`, 81,299,172 bytes, SHA-256 `76031b14732cd085d3ce9c70375795e0e6b88b16d9367373a082cc0dada56e3d`. GitHub Actions run `42539646383` verified both ARM ABIs and absence of Intel native libraries; the downloaded artifact was then re-signed locally with the same persisted debug keystore used by the prior 2.6.3, 2.6.4, and universal 2.7.0 builds. `apksigner` verified v3 and certificate SHA-256 `b94e4b6a71111e3e0f79c53d2a027baeabcfe148444f752ade85e71afa0f92eb`; package/version/API, both ABIs, and all four deployed non-secret cloud values were rechecked. The existing Google Drive APK and checksum were replaced in place and verified by metadata readback. Physical installation remains unverified.

## Administrator customer registration visibility (2026-11-08)

- The deployed administrator API now treats confirmed identities in the dedicated customer Cognito pool as registered customers immediately, even before the Android app completes `/v1/bootstrap`.
- Registered-but-unbootstrapped identities appear as `PENDING_SETUP` with `setupComplete: false`. They count toward Total customers but intentionally do not count as Active, Free, Premium, devices, or greenhouses until onboarding completes.
- Pending registrations are read-only in the portal: account-state and complimentary-entitlement controls remain hidden until the authoritative account/tenant records exist.
- The administrator Lambda has only `cognito-idp:ListUsers` access to the customer pool for this reconciliation. Existing DynamoDB account records remain authoritative for tenant state, entitlements, devices, and activity.
- Live deployment verification returned registered identities pending setup, so the overview distinguishes Total customers from Active customers. A successful customer sign-in now completes setup by registering that phone automatically as a client; there is no role-selection step.

## Deployment status

- `docs/AWS_DEPLOYMENT.md` is the deployment, cost, rollback, and physical-test checklist.
- The Cognito template explicitly uses Plus because enforced threat protection requires it, and deletion protection is active.
- The dedicated `greenhouse-monitor-dev` application stack and `greenhouse-monitor-deployment-bootstrap` least-privilege role/artifact stack are deployed in `us-east-1`. The application stack is `UPDATE_COMPLETE`; Cognito Plus deletion protection is active.
- Live AWS checks passed for unauthenticated API denial, DynamoDB encryption/PITR/TTL, active Python 3.13 arm64 Lambdas, the enabled one-minute heartbeat schedule, a zero-error heartbeat-monitor invocation, and `IN_SYNC` drift status for both stacks. Kinesis resources remain on-demand and have not yet been exercised by two phones.
- Latest cloud-connected APK: `build-out/Greenhouse-Monitor-AWS-debug.apk`, version 2.7.0/code 13, SHA-256 `38c4d7148509acc1626776f8ab5dea74a8ed94decf2c9e522b73f1a5be5b7961`. It is debug-signed and contains only the real non-secret stack outputs.
- AWS deployment checkpoint: 57 Android JVM tests, lint, assembly, 29 Python tests, Python 3.13 compilation, SAM validation/build, CloudFormation lint, APK verification, live AWS smoke checks, and emulator launch passed. Cognito account confirmation, QR pairing, inventory/revocation, direct/TURN WebRTC, camera handoff, alert delivery, boot recovery, and network transitions remain unverified on the physical A71/Pixel phones.
- `.github/workflows/verify.yml` reproduces Android tests/lint/build plus Python tests/compilation and publishes a checksum-matched debug artifact on pushes and pull requests; it requires no production secrets.
- GitHub Actions runs `42488729170` and `42489260140` passed all Android, backend, AWS template-lint, checksum, and artifact steps. The workflow now uses official Node 24-compatible action majors (including `upload-artifact@v7`) and cancels superseded runs on the same ref to limit CI waste.
- Both the SAM template and deployment bootstrap are linted locally and in CI; the bootstrap suppresses only cfn-lint `W3037` because live API Gateway deployment requires newer `TagResource/UntagResource` actions missing from the linter catalog.
- AWS-only owner activation is deployed through the IAM-restricted entitlement Lambda. Granting complimentary access still waits for the owner to create the Cognito account so its opaque subject exists.
- The operations SNS topic has no subscriber; Google Play verification, release signing, physical-network testing, and an owner-controlled non-root deployer remain external gates.

## Customer-facing account experience (2026-11-08)

- Customer-facing brand copy is `junecity.studio`; do not expose AWS, Cognito, Kinesis, Lambda, or other infrastructure-provider names in Android interface copy. Technical code, logs, deployment docs, and internal identifiers may retain accurate provider names.
- Account onboarding is portrait-first and scrollable on narrow phones. Successful/restored enrollment switches the activity to sensor landscape for the frozen Dashboard, Zones, History, Cameras, and Settings screens; unconfigured local builds also open directly in landscape.
- New-account passwords are validated before submission against the deployed policy: at least 12 characters with uppercase, lowercase, a number, and a symbol. Raw authentication failures are converted to customer-facing guidance, and existing customers can request and confirm an emailed password-reset code inside the portrait onboarding flow.
- Android version 2.7.0 / code 13 is the latest build. Automatic Client registration and approved Server QR pairing passed 68 JVM tests, lint, configured assembly, APK signature/configuration verification, emulator onboarding/scanner launch, and a disposable live backend flow; physical two-phone pairing and Govee regression still require device confirmation.

## Private admin portal (2026-11-08)

- `admin-portal/` is a separate Next.js 16/React 19 static web application. It does not modify the frozen Android monitoring interface and has no public registration route or customer camera-view capability.
- `infrastructure/admin-portal.yaml` defines the deployed private S3/CloudFront site, dedicated Cognito Plus administrator pool with mandatory TOTP MFA, Owner/Support/ReadOnly groups, JWT-authorized HTTP API, admin Lambda, login audit trigger, throttling, security headers, retention controls, and alarms.
- `backend/src/admin_api.py` enforces roles server-side and exposes real-data dashboard, customer, entitlement, greenhouse/device, streaming-metadata, cost, alarm, audit, and support APIs. Destructive actions are confirmed in the portal and audited; secrets, passwords, purchase tokens, camera keys, and video are never returned.
- Customer account/entitlement/device/greenhouse/session records now maintain an `adminEntity`/`adminSort` GSI projection. `scripts/backfill-admin-index.py` is dry-run by default and excludes pairing secrets and alert content.
- Google Play purchase verification is backend-only through an optional Secrets Manager service-account credential. The Android API can submit a purchase token for verification; only the backend writes authoritative Premium state. Production billing and credentials remain approval-gated and unconfigured.
- The `greenhouse-monitor-admin-dev` stack is live in `us-east-1`; CloudFront serves the portal, the admin API denies unauthenticated requests, and the initial enabled Owner is in only the Owner group with `FORCE_CHANGE_PASSWORD` status. The temporary password was delivered by Cognito and is not stored in the repository.
- Portal authentication uses authorization-code + PKCE with explicit stack-provided Cognito endpoints because the issuer discovery response lacks browser CORS headers. It falls back to in-memory storage when session storage is unavailable and times out safely instead of hanging.
- The callback consumes each one-time authorization code exactly once. A context initialization rerender previously caused duplicate callback processing after Cognito had already accepted a valid login; the corrected dependency is regression-tested and the static portal fix is live.
- Live verification passed for HTTPS/security headers, AES256 S3 storage, CloudFront delivery, sign-in redirect, Lambda dashboard execution, tag-filtered Cost Explorer access, API denial, and the existing application Lambda import/runtime path. The admin GSI is active, the dry-run backfill found zero eligible existing records, and the bootstrap/application/admin stacks all report `IN_SYNC` drift status.
- Current test checkpoint: 45 Python tests, Python 3.13 compilation, 11 portal tests, ESLint, static production build, zero npm audit findings, SAM validation/build, CloudFormation lint, and prior unchanged Android regression with 60 JVM tests/lint/debug assembly. Authenticated portal/API mutations, TOTP, Google Play, and physical mobile-browser checks still require the Owner's first sign-in and/or external credentials/devices.
- Deployment, migration, rollback, costs, live endpoints, and owner-first-sign-in steps are in `docs/ADMIN_PORTAL.md`. Future deployments must continue using reviewed change sets and the restricted CloudFormation service role.

## Live account/API repair checkpoint (2026-11-08)

- HTTP API events include the named deployment stage in `rawPath`; both customer and administrator handlers now remove that exact leading stage before route matching. Tests cover `/dev/v1/bootstrap` and `/dev/admin/v1/session` so deployed routes cannot silently fall through again.
- DynamoDB resource clients accept native Python values for `TransactWriteItems`. The bootstrap, device, pairing, heartbeat, administrator-revocation, and offline-monitor transactions no longer pre-serialize values, which previously double-encoded keys as maps. The application and heartbeat roles now explicitly include the required `dynamodb:TransactWriteItems` permission.
- The admin API accepts Chromium's `cache-control` and `pragma` preflight headers. An explicit unauthenticated `OPTIONS /admin/v1/{proxy+}` route returns 204 before administrator claim parsing; all data methods remain protected by the JWT authorizer.
- The repaired application and admin stacks are deployed and `UPDATE_COMPLETE`. A disposable real Cognito SRP customer received HTTP 201 from the live staged bootstrap, and its Cognito/DynamoDB test data was removed. The live admin preflight returns 204 with the configured origin/method/header policy, and the deployed staged admin session route returned 200 in a role-claim Lambda smoke test.
- Current automated checkpoint: 48 Python tests passed, both CloudFormation templates passed `cfn-lint`, and the admin template passed account-aware SAM validation/build. The existing confirmed customer identity remains intentionally unmapped until its next authenticated app bootstrap; no customer password, token, or account record was fabricated by administration.

## Android customer reauthentication checkpoint (2026-11-08)

- A successful identity sign-in followed by the earlier failed bootstrap could leave Amplify with a persisted signed-in session while onboarding remained visible. Explicit credential sign-in now performs a local sign-out first, then starts a fresh SRP sign-in for the entered customer. This also makes switching customer identities deterministic.
- Account error translation now inspects the bounded nested cause chain plus Amplify recovery suggestions, recognizes stale-session, unconfirmed-account, rate-limit, nested credential, and connection failures, and continues to keep infrastructure-provider language out of customer UI.
- Android 2.6.4 / version code 12 is the current cloud-connected build. A clean emulator installed the configured APK, signed in through the live customer pool with a disposable confirmed identity, completed live account bootstrap, and reached phone-role selection. The disposable Cognito identity and all bootstrap records were deleted afterward.
- Current Android checkpoint: 63 JVM tests passed with zero failures/errors, lint passed, configured debug assembly passed, and APK package/version inspection passed. The non-fatal pre-existing D8 Kotlin metadata warning remains. APK SHA-256: `9c67c24daf8041d480877758f0d036738617bc336d0ad41fd0641f7f6afee0ae`.
- The admin stack's current Android version marker is deployed as 2.6.4 and the stack is `UPDATE_COMPLETE`. The existing real customer's password reset is confirmed by the identity provider, but its private-credential sign-in on a physical phone still must be performed by the owner using the 2.6.4 APK.

## Project memory / handoff log
### 2026-11-08 — Server lifecycle controls and backend-enforced video access
- Android 2.7.4/code 17 separates camera entitlement from greenhouse-server authorization. Client Connectivity and Manage Devices expose Disconnect Server, Reconnect Server, Revoke Server, Pair Again, and Replace Server according to the authoritative device state; a stale locally selected greenhouse no longer hides its registered server.
- Disconnect temporarily suspends readings, heartbeats, backups, and video sessions without deleting the server credential. Resume restores the same authorized device. Revoke invalidates the device immediately and cannot be undone by a saved local role; Pair Again issues a fresh single-use two-minute QR request and new device credential while preserving the greenhouse, settings, sensor assignments, and history.
- Stream creation, server session polling, lease renewal, heartbeat, and missing-heartbeat policy all honor enabled/disconnected/revoked state. Customer-level Video Streaming remains a separate backend entitlement with Subscription Controlled, Complimentary Access, and Disabled states; it is evaluated when opening and renewing an actual stream.
- The administrator customer detail now nests greenhouse servers beneath their owning customer and exposes an explicit Revoke Server action separately from the Video Streaming entitlement controls. Expanded server health includes battery, charging, network, sensors/provider, camera availability, and last sensor/data synchronization when reported.
- Each greenhouse now stores its own 1/5/10/15-minute dashboard refresh interval. Server sensor collection, safety heartbeats, history, and alerts remain on their independent one-minute path. SQLite history uses a unique sensor/timestamp key so queued/imported records are deduplicated.
- Local validation passed 66 backend tests, 12 portal tests plus lint/production build, 74 Android JVM tests plus lint/debug assembly, CloudFormation lint, Python compilation, and `git diff --check`. Same-Wi-Fi direct ICE and off-Wi-Fi AWS-managed TURN/reconnect paths are implemented and regression-tested at the authorization/lifecycle layer; physical Galaxy A71/second-phone carrier-network interoperability remains a required device test and is not claimed here.

### 2026-11-08 — Server reconnect and adjustable data synchronization
- Android 2.7.3/code 16 adds the requested Client-side Connectivity actions for Disconnect Server, Reconnect Server, and Replace Server. Disconnect revokes the Server device immediately but retains the stable greenhouse backup and identity; reconnect/replacement requires a fresh five-minute QR scan plus explicit Client approval.
- The greenhouse Server is authoritative for a selectable 1/2/5/10/30-minute sensor and backup interval. The Server reads Govee and publishes its backup on that cadence; safety heartbeats remain one minute; Clients check for published changes every 30 seconds and display the Server-managed interval read-only.
- The administrator customer detail groups greenhouse Servers beneath one customer account and separately lists only authorized Client phones. Server connectivity is derived from the authoritative device record, so a preserved greenhouse no longer makes a revoked Server appear active.
- The existing Owner-only complimentary-entitlement control is the temporary per-customer camera test switch. It remains enforced by the backend and renewable session lease; no insecure local payment bypass was added. Viewer copy no longer advertises Premium when access is disabled.
- The updated admin Lambda/version marker and live-configured portal are deployed to the existing development stack; CloudFront invalidation completed and the `/customers/` route returned 200 with the configured security policy. Local validation passed 72 Android JVM tests, lint/assembly, 61 backend tests/compilation, and 12 portal tests/lint/build/audit. GitHub Actions run `42552326235` passed both jobs for implementation commit `7042978`. The ARM APK is `build-out/Greenhouse Monitor 2.7.3.apk`, 81,333,355 bytes, SHA-256 `7b21107844e09e595035659031be1947b36263f6a6d03c256775b51aaefb9b8a`, with the persisted debug certificate; Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` was replaced in place and metadata-readback verified. Physical two-phone/Govee/WebRTC/timing validation remains required.

### 2026-11-08 — Server-authoritative client synchronization and camera test access
- Android 2.7.2/code 15 makes the paired greenhouse Server the source of truth. Schema-2 greenhouse backups include current sensor identity/readings/status in addition to preferences, geometry, zones, notifications, and bounded history. Viewer Clients poll the authenticated backend, persist/display that snapshot, and never run the Govee provider or edit sensor assignments.
- A newly enrolled Client is deliberately empty: its Govee credential, sensors, history, assignments, and preferences are cleared before waiting for the paired Server. The Govee API key remains encrypted and local to the Server phone. The Connectivity/Devices copy identifies server synchronization without changing the approved five-screen design.
- Backup writes remain restricted to the currently authorized greenhouse Server. Same-account authorized Clients may read; an authorized replacement Server may also read the existing backup so the stable greenhouse configuration survives replacement. Import replaces local history and assignments to avoid accumulation on repeated sync.
- The Owner-only admin customer detail uses the existing backend entitlement endpoint as a clear remote-camera test switch. Turning it on grants expiring complimentary `REMOTE_LIVE_VIDEO`; turning it off revokes that grant. This is not a client-side Premium bypass, and a verified paid entitlement remains backend-authoritative.
- The application backend and configured admin portal are deployed in the existing development AWS stacks. Local checkpoint: 70 Android JVM tests + lint/configured ARM assembly, 60 Python tests + compilation, 12 portal tests + lint/build/audit, CloudFormation lint, SAM validate/build, APK signature/package/ABI checks. Target-phone client/server/Govee/WebRTC validation is still required.
- GitHub Actions run `42548402194` passed for implementation commit `b7a71f1`, including both full regression and configured ARM jobs. Drive file `19NBhnl5JRIscX6MaR5q7nFPM_a2plNXR` was replaced in place with `Greenhouse Monitor 2.7.2.apk`; metadata readback matched 81,300,591 local bytes. SHA-256: `bd87738960b92457a804c158848fa63e3e9eb34ca5ca77dd1b1b463454e85150`.

### 2026-11-08 — Initial memory
- Located source on `codex/greenhouse-complete-upgrade` (main contains only initial commit).
- Added remote-connectivity upgrade requirements and required AGENTS.md + CHANGELOG.md workflow.
- No runtime camera or AWS code was changed by the initial documentation task.
