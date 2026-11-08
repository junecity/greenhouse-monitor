# Remote camera streaming — implementation contract

Status: **Implementation connected and build-tested; AWS deployment and physical interoperability are still unverified.**

## Product rules
- Galaxy A71 or customer's old Android phone remains the server, sensor processor, display and camera.
- Pixel 10 or customer's everyday Android phone is the viewer.
- The existing five-screen visual design is frozen.
- Remote live camera is **Premium**; owner/developer complimentary access is an explicit server-side grant.
- Local greenhouse monitoring, sensor history, and local camera access must remain available without Premium.
- Stream starts only after an intentional viewer action and stops on leaving the view, closing it or locking the client (subject to verified Android lifecycle handling).
- No continuous cloud video recording or uploading. One active viewing session should not create continuous relay usage.
- No required external registration website. All customer onboarding is in-app.
- Dedicated AWS resources and identity boundaries, separate from junecity.studio.

## Implemented sequence
1. Add Cognito-based customer authentication and backend device identity. Pair server and client with expiring one-use codes; never expose credentials or camera keys in logs.
2. Add server-verified entitlement checks before issuing AWS Kinesis Video Streams WebRTC signaling permissions and TURN/ICE credentials. Complimentary access is administered through an IAM-only AWS Lambda; commercial Google Play verification remains deployment-config gated and must never rely on a Kotlin flag.
3. Implement WebRTC sender on greenhouse phone and receiver on client using tested Android SDK/library versions. Keep existing local stream working until migration is verified. Use short-lived credentials, encrypted media, and authorization-scoped signaling channels.
4. Use ICE/STUN to connect peer-to-peer when possible, and AWS-managed TURN relay when necessary. Detect Wi-Fi/cellular transitions and ICE disconnects, reconnect with bounded exponential backoff, and expose clear failure states.
5. Test camera lifecycle: only capture/transmit while viewing, terminate sessions on viewer departure/lock, handle app process death, Android camera/foreground-service restrictions, server restart, and multiple client requests.
6. Remove public UPnP exposure after remote WebRTC is fully tested. Do not expose an HTTP endpoint containing a pairing token over the public internet.
7. Add independent AWS heartbeat monitoring and offline alerting; keep this distinct from paid remote camera access and ordinary website notifications.
8. Add operational alarms, API throttling, bounded retries, log retention, and a heartbeat dead-letter queue. Account budgets, measured TURN/egress limits, and production quotas remain deployment-time gates.

## Security and commercial acceptance
- Backend denies free, expired, revoked and cross-customer access to signaling/TURN credentials.
- No private IP/public IP registry is treated as a NAT traversal solution.
- Customer cannot impersonate another greenhouse device or view another customer's stream.
- Temporary cloud outage does not stop available local sensor monitoring.
- Validate direct LAN, cellular, CGNAT, changing public IP, relay-only, network drop/reconnect, revoked premium, subscription expiry, and owner complimentary access.
- Do not deploy billable AWS services or alter live routing until cost/security review and deployment approval.

## Current source facts
- `CameraStreamServer.kt` serves encrypted JPEG frames on TCP 45891.
- `RemoteCameraFeed.kt` tries saved local then public address.
- `UpnpPortMapper.kt` configures router UPnP mapping.
- `CameraPairing.kt` holds local/public hosts and pairing credentials.

This implementation is retained as a migration/local fallback. It is not commercially scalable because pairing embeds network addresses, public IP changes break remote access, UPnP exposes a device port, and there is no customer identity or server-side entitlement check.

## Target architecture

1. Cognito user-pool identity authenticates customers in the Android application.
2. A Greenhouse API authorizer derives the tenant from validated JWT claims. Clients never submit an authoritative tenant ID.
3. The backend stores accounts, devices, greenhouse membership, pairing challenges, entitlements, heartbeats, and short-lived streaming sessions in dedicated DynamoDB tables.
4. An authorized Premium client requests a viewing session. The backend verifies tenant/device access and entitlement before returning short-lived Kinesis signaling configuration.
5. The greenhouse server receives an on-demand request, starts CameraX/WebRTC capture, and joins as master. The viewer joins with a unique client ID.
6. ICE prefers direct connectivity; AWS-managed STUN/TURN data is returned by Kinesis. No router forwarding is required.
7. Closing/backgrounding the viewer ends the session. The server stops capture when no authorized viewer remains or the lease expires.
8. Local same-network viewing remains available independently of AWS and does not require Premium.

## Verified SDK facts

- AWS's Android repository release v1.3.0 targets API 29+ and demonstrates Kinesis signaling with AWS Android SDK 2.81.1.
- The AWS repository does not publish a complete drop-in media artifact. Its documented setup requires importing a compatible `libwebrtc` AAR.
- Physical Android devices must verify H.264 encode/decode support; emulator success is insufficient.
- AWS temporary credentials or backend-minted signed signaling data replace embedded long-lived keys.

## Migration gates

Do not retire the existing remote path until all of these pass on the Galaxy A71 and Pixel 10: authenticated session creation, direct ICE, TURN fallback, network handoff, reconnect, viewer close/background termination, server restart, expired/revoked entitlement denial, cross-tenant denial, and AWS outage fallback to local monitoring.

UPnP must be disabled by default after WebRTC verification and removed only after a rollback release is available.

## Current status

- Existing local/encrypted-JPEG path: implemented; previously device-rendered, not revalidated in this stage.
- Identity, tenant/device authorization, backend entitlement, pairing, heartbeat, and WebRTC session contracts: implemented as credential-free foundations.
- Backend authorization denies Free, expired, revoked, disabled-device, wrong-role, unverified-source, and cross-tenant requests. Live AWS integration is not yet verified.
- AWS Cognito/API Gateway/DynamoDB/Lambda/SNS/scheduled-monitor resources: defined in SAM but not created or deployed.
- Kinesis-compatible WebRTC media peer, SDP/ICE signaling codec, secure WebSocket transport, and on-demand lifecycle: implemented and build-tested behind interfaces.
- AWS WebRTC activation is now connected for enrolled devices: the backend issues signed WSS/ICE payloads, the Server foreground service accepts only pending authorized sessions, and enrolled Viewers use the AWS feed in the existing camera panels. No AWS or physical-device validation has occurred.

## Streaming lifecycle invariants

- No camera peer is created until the backend grants a streaming session.
- Only the master creates a local camera track; viewers receive remote video only.
- Reconnect uses bounded exponential backoff and waits for network availability after handoff.
- Viewer close/background/lock, lease expiry, and retry exhaustion close signaling, peer connection, camera capturer, video source, texture thread, factory, and EGL resources, then end the backend session.
- The server sensor-monitoring path does not depend on a viewing session.

## Delivered
- `RemoteCameraAccessPolicy.kt`: pure entitlement decision logic for future UI/service integration.
- `RemoteCameraAccessPolicyTest.kt`: local/free/premium/complimentary/expired/unverified cases.
- Later phases added the authenticated backend contracts, signed signaling/TURN issuance, WebRTC sender/receiver, cloud gateway, Cognito onboarding/refresh, approved-screen activation, heartbeat runtime, AWS alert inbox/polling/acknowledgement, boot recovery, account device revocation, and IAM-only complimentary entitlement administration. Still not delivered or verified: Play Billing lifecycle, deployed infrastructure, release signing, and physical-device interoperability.

The sensor path now uses a provider-neutral boundary. This is intentionally independent of remote streaming, so future BLE or local-Wi-Fi Junecity providers can continue local monitoring even when AWS is unavailable.

Heartbeat monitoring is also independent of video. The scheduled backend uses a configurable offline timeout (initially 180 seconds), atomically deduplicates an incident until the server reports again, stores an account-scoped alert, and emits an internal tenant-tagged SNS event. Enrolled Android devices poll the authenticated alert inbox approximately once per minute, create local high-priority notifications, and acknowledge them back to AWS. Physical-device background and Do Not Disturb behavior remain unverified.

Cloud pairing now uses an authenticated, single-use, five-minute challenge. The backend stores only a SHA-256 digest and binds a claimed viewer to the same account and tenant as the greenhouse server. The high-entropy code is sent in the HTTPS request body, not a URL path. The separate onboarding gate activates this flow; the legacy QR remains only for local rollback.

After successful authorization, the backend now creates or reuses a deterministic single-master Kinesis signaling channel and returns a 299-second SigV4-signed WSS endpoint plus AWS-managed ICE/TURN servers. The greenhouse server polls its own pending-session partition and receives a freshly signed master endpoint; either participating device may terminate the session. These code paths are build-time foundations only until the SAM stack is validated/deployed and exercised against live Kinesis.

Kinesis negotiation follows the single-master contract: the viewer initiates the SDP offer, the master answers, and master SDP/ICE messages target the viewer's authenticated client ID. The signaling decoder retains AWS `senderClientId` for this routing.

The Android cloud gateway now consumes those contracts over HTTPS with a bearer token and device identifier. Stable device enrollment is encrypted with Android Keystore, while Amplify owns Cognito token refresh. The gateway does not manufacture entitlements or AWS credentials. `CognitoAuthManager` configures Amplify Auth from deployment outputs, supports Cognito SRP sign-up/confirmation/sign-in/sign-out, and obtains refreshed access tokens for the gateway.

The backend account bootstrap is implemented. A validated Cognito subject can idempotently create exactly one isolated account/tenant and active Free entitlement in an atomic DynamoDB transaction. An authenticated phone can atomically register itself as the greenhouse server with its initial greenhouse; a viewer is registered only by claiming a same-account, one-use pairing challenge. The Android onboarding flow invokes these APIs.

Android builds now accept the deployed AWS region, Cognito user-pool ID, Android app-client ID, and API URL as build properties/environment variables. Configuration is accepted only when all four values are present. Encrypted enrollment state contains stable resource identifiers and role, not copied AWS credentials. The server heartbeat obtains refreshed Cognito tokens from Amplify for each request path and uses the backend greenhouse identifier.

The separate landscape onboarding flow is now connected without changing the approved monitoring screens. It supports Cognito account creation, email confirmation, sign-in and restored-session validation; atomic account bootstrap; Server/Client choice; server registration; five-minute one-use QR generation; client QR scanning/manual entry; and encrypted role/resource enrollment. An enrolled app validates the Cognito session before opening the monitor. Live Cognito delivery and camera-based QR scanning still require the deployed development stack and physical phones.

In greenhouse-server mode, a non-exported special-use foreground service starts only when an encrypted cloud enrollment exists. It obtains refreshed Cognito tokens, posts cached sensor-connection and critical-temperature state every minute, and retries transient failures. Opt-in boot recovery restarts heartbeat and alert polling but never camera capture. Long-running OEM/Play behavior is deliberately not claimed until physical and policy testing is complete.

The on-demand camera runtime is now wired behind the existing panels. The server polls the authenticated AWS API every five seconds while ready but does not open or transmit from the camera until an authorized session appears. A shared camera lease releases CameraX before libwebrtc captures the front camera, then restores local preview after cleanup. Capture uses the selected 480×270, 640×360, or 1280×720 profile. The viewer uses the same approved panel with WebRTC rendering, pinch zoom/pan, lifecycle cancellation, five bounded reconnect attempts, and non-cancellable backend termination. AWS-configured builds disable public UPnP mapping; the prior encrypted JPEG service remains LAN-only for rollback. Live interoperability is still unverified.

Each backend stream has a four-hour absolute limit and a two-minute authorization lease. The Viewer renews the lease every minute, and every renewal reruns tenant/device/greenhouse/Premium authorization. The Server checks—but cannot extend—the same lease every minute, limiting abandoned or modified-client exposure. If either peer reconnects, it requests a fresh role-scoped SigV4 WebSocket URL and AWS ICE/TURN configuration instead of reusing an expired 299-second URL. Closing the view still ends the session immediately; absolute expiry requires a new authorized session. These timers are unit/build verified only, not live-AWS verified.
